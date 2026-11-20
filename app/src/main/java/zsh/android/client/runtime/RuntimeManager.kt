package zsh.android.client.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import zsh.android.client.env.EnvPaths
import zsh.android.client.store.Settings
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一次 zcode 调用（聊天 / 登录）。输出环形缓冲 + 退出码，供 SSE 与轮询读取。 */
class Job(val id: String, val kind: Kind) {
    enum class Kind { CHAT, LOGIN }

    private val lock = Object()
    private val linesBuf = ArrayDeque<String>(1024)
    @Volatile var exitCode: Int? = null
        private set
    @Volatile var done: Boolean = false
        private set

    val output: List<String> get() = synchronized(lock) { linesBuf.toList() }

    fun append(line: String) {
        synchronized(lock) {
            if (linesBuf.size >= 2048) linesBuf.removeFirst()
            linesBuf.addLast(line)
            lock.notifyAll()
        }
    }

    /** 等待新行；无新行最多等 timeoutMs 后返回（可能为空）。返回 chunk 与新下标。 */
    fun pollAfter(index: Int, timeoutMs: Long): Pair<List<String>, Int> {
        synchronized(lock) {
            val waitStart = System.currentTimeMillis()
            while (index >= linesBuf.size && !done && System.currentTimeMillis() - waitStart < timeoutMs) {
                lock.wait(timeoutMs)
            }
            val end = minOf(linesBuf.size, index + 2000)
            val chunk = linesBuf.toList().subList(index.coerceAtLeast(0), end)
            return chunk to end
        }
    }

    fun finish(code: Int) {
        synchronized(lock) {
            exitCode = code
            done = true
            lock.notifyAll()
        }
    }
}

sealed interface RuntimeState {
    data object Stopped : RuntimeState
    data object Starting : RuntimeState
    data class Running(val port: Int) : RuntimeState
    data object Crashed : RuntimeState
}

/**
 * 运行时管理：
 * - agent：常驻 `zcode app-server --stdio` 的会话模式（v0.2 主路径，流式 + 会话连续）；
 * - chat：`zcode -p` 单发模式（兼容回退）；同一时刻一条在跑；
 * - terminal：容器内常驻 bash（经 script 挂 PTY），输出进环形缓冲；
 * - login：`zcode login <provider> --no-browser`，把 OAuth URL 吐给手机浏览器。
 */
class RuntimeManager(
    private val paths: EnvPaths,
    private val settings: Settings,
    val appServer: AppServerClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobId = AtomicLong(0)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val chatLock = Object()
    @Volatile private var chatBusy = false

    private val agentLock = Object()

    @Volatile var agentSessionId: String? = null
        private set

    private val _agentBusy = MutableStateFlow(false)
    val agentBusy: StateFlow<Boolean> = _agentBusy

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    val state: StateFlow<RuntimeState> = _state

    fun setState(s: RuntimeState) {
        _state.value = s
    }

    @Volatile var terminalProcess: Process? = null
        private set
    private val termLock = Object()
    private val termLines = ArrayDeque<String>(4096)
    @Volatile var terminalRunning: Boolean = false
        private set

    // ---------------------------------------------------------------- chat

    /** 串行执行一次聊天。已在跑时返回 null（HTTP 层转 409）。 */
    fun startChat(message: String): Job? {
        synchronized(chatLock) {
            if (chatBusy) return null
            chatBusy = true
        }
        val job = Job("job-${jobId.incrementAndGet()}", Job.Kind.CHAT)
        jobs[job.id] = job
        scope.launch {
            runProcess(
                ProotCmd.containerCommand(
                    paths,
                    listOf(
                        "/opt/node/bin/zcode", "-p", message,
                        "--no-color", "--mode", settings.permMode,
                        "--cwd", "/root/zsha",
                    ),
                ),
                job,
            )
        }
        return job
    }

    fun getJob(id: String): Job? = jobs[id]

    fun isChatBusy(): Boolean = chatBusy

    private fun runProcess(cmd: List<String>, job: Job) {
        val pb = ProcessBuilder(cmd)
        ProotCmd.hostEnv(paths).forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)
        try {
            val p = pb.start()
            p.inputStream.bufferedReader().forEachLine { line -> job.append(line) }
            job.finish(p.waitFor())
        } catch (e: Exception) {
            job.append("[zcodea] 进程异常退出：${e.message}")
            job.finish(-1)
        } finally {
            if (job.kind == Job.Kind.CHAT) {
                synchronized(chatLock) { chatBusy = false }
            }
            // 只保留最近 50 个 job 的缓冲
            while (jobs.size > 50) {
                jobs.keys.firstOrNull { it != job.id }?.let { jobs.remove(it) } ?: break
            }
        }
    }

    // ---------------------------------------------------------------- login

    fun startLogin(provider: String): Job? {
        if (jobs.values.any { it.kind == Job.Kind.LOGIN && !it.done }) return null
        val job = Job("login-${jobId.incrementAndGet()}", Job.Kind.LOGIN)
        jobs[job.id] = job
        scope.launch {
            runProcess(
                ProotCmd.containerCommand(
                    paths,
                    listOf("/opt/node/bin/zcode", "login", provider, "--no-browser"),
                ),
                job,
            )
        }
        return job
    }

    // ---------------------------------------------------------------- terminal

    fun startTerminal(): Boolean {
        synchronized(termLock) {
            if (terminalRunning) return true
            val cmd = ProotCmd.containerCommand(
                paths,
                listOf("/usr/bin/script", "-qfc", "/bin/bash", "/dev/null"),
            )
            val pb = ProcessBuilder(cmd)
            ProotCmd.hostEnv(paths).forEach { (k, v) -> pb.environment()[k] = v }
            return try {
                val p = pb.start()
                terminalProcess = p
                terminalRunning = true
                appendTerm("[zcodea] bash 已启动（容器 /root）")
                scope.launch {
                    p.inputStream.bufferedReader().forEachLine { appendTerm(it) }
                    appendTerm("[zcodea] bash 已退出")
                    synchronized(termLock) {
                        terminalRunning = false
                        terminalProcess = null
                    }
                }
                true
            } catch (e: Exception) {
                appendTerm("[zcodea] 无法启动终端：${e.message}")
                terminalRunning = false
                false
            }
        }
    }

    fun stopTerminal() {
        synchronized(termLock) {
            terminalProcess?.destroy()
            terminalProcess = null
            terminalRunning = false
        }
    }

    fun terminalInput(text: String) {
        val out: OutputStream = terminalProcess?.outputStream ?: return
        try {
            out.write((text + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
    }

    fun termBuffer(): List<String> = synchronized(termLock) { termLines.toList() }

    private fun appendTerm(line: String) {
        synchronized(termLock) {
            if (termLines.size >= 4096) termLines.removeFirst()
            termLines.addLast(line)
        }
    }

    fun stopAll() {
        stopTerminal()
        appServer.stop()
        jobs.values.filter { !it.done }.forEach { it.finish(-255) }
        setState(RuntimeState.Stopped)
    }

    // ---------------------------------------------------------------- agent（v0.2 会话模式）

    sealed class AgentException(message: String) : Exception(message) {
        class ServerDown(message: String) : AgentException(message)
        class Protocol(message: String) : AgentException(message)
    }

    /**
     * 发送一条消息到当前会话（无会话则先创建）。
     * 返回 sessionId。流式输出经 appServer.events 广播到 /app/agent/events。
     */
    suspend fun agentSend(content: String): String {
        synchronized(agentLock) {
            if (_agentBusy.value) throw AgentException.ServerDown("已有消息在处理中")
            _agentBusy.value = true
        }
        try {
            if (!appServer.ensureStarted()) {
                throw AgentException.ServerDown(
                    (appServer.state.value as? AppServerClient.State.Down)?.reason ?: "app-server 启动失败",
                )
            }
            var session: String? = agentSessionId ?: settings.agentSessionId.takeIf { it.isNotBlank() }
            if (session != null) {
                val ok = runCatching { sendToSession(session, content) }
                if (ok.isSuccess) return session
                log2("会话 $session 发送失败，重建会话重试")
                session = null
            }
            session = createSession()
            agentSessionId = session
            settings.agentSessionId = session
            sendToSession(session, content)
            return session
        } finally {
            _agentBusy.value = false
        }
    }

    private suspend fun sendToSession(sessionId: String, content: String) {
        appServer.request(
            "session/send",
            org.json.JSONObject().put("sessionId", sessionId).put("content", content),
            timeoutMs = 20_000,
        )
    }

    private suspend fun createSession(): String {
        // create 的响应可能迟到；同时监听 lifecycle 事件兜底取 sessionId
        val eventSession = kotlinx.coroutines.CompletableDeferred<String>()
        val collector = scope.launch {
            appServer.events.collect { msg ->
                if (msg.optString("method") == "session/lifecycle/changed") {
                    val state = msg.optJSONObject("params")?.optString("state")
                    if (state == "starting" || state == "ready" || state == "running") {
                        val sid = msg.optJSONObject("params")?.optString("sessionId")
                        if (sid != null) eventSession.complete(sid)
                    }
                }
            }
        }
        try {
            val result = appServer.request(
                "session/create",
                org.json.JSONObject().put(
                    "workspace",
                    org.json.JSONObject()
                        .put("workspacePath", "/root/zsha")
                        .put("workspaceKey", "/root/zsha"),
                ),
                timeoutMs = 60_000,
            )
            val sid = result.optString("sessionId", "")
            if (sid.isNotEmpty()) return sid
            return kotlinx.coroutines.withTimeout(30_000) { eventSession.await() }
        } finally {
            collector.cancel()
        }
    }

    suspend fun agentStop() {
        val session = agentSessionId ?: return
        runCatching {
            appServer.request("session/stop", org.json.JSONObject().put("sessionId", session), timeoutMs = 10_000)
        }
    }

    suspend fun agentNewSession() {
        agentStop()
        agentSessionId = null
        settings.agentSessionId = ""
    }

    suspend fun agentMessages(): org.json.JSONObject {
        val session = agentSessionId ?: settings.agentSessionId.takeIf { it?.isNotBlank() == true }
            ?: throw AgentException.Protocol("还没有会话")
        return appServer.request("session/messages", org.json.JSONObject().put("sessionId", session), timeoutMs = 20_000)
    }

    fun agentServerStatus(): String = when (val s = appServer.state.value) {
        AppServerClient.State.Stopped -> "stopped"
        AppServerClient.State.Starting -> "starting"
        AppServerClient.State.Ready -> "ready"
        is AppServerClient.State.Down -> "down:${s.reason}"
    }

    private fun log2(line: String) {
        android.util.Log.w("RuntimeManager", line)
    }
}

/** ANSI 转义序列清洗（简易终端 / 聊天展示用；不处理光标控制类 TUI 完整语义）。 */
object AnsiStrip {
    private val pattern = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")

    fun strip(text: String): String = text.replace(pattern, "")
}
