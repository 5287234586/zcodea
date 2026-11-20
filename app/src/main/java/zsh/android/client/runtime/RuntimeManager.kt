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
 * - chat：每次消息 = 一次 `zcode -p`（无 TUI，输出经 SSE 实时上屏）；同一时刻一条在跑；
 * - terminal：容器内常驻 bash（经 script 挂 PTY），输出进环形缓冲；
 * - login：`zcode login <provider> --no-browser`，把 OAuth URL 吐给手机浏览器。
 */
class RuntimeManager(
    private val paths: EnvPaths,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobId = AtomicLong(0)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val chatLock = Object()
    @Volatile private var chatBusy = false

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
            job.append("[zsha] 进程异常退出：${e.message}")
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
                appendTerm("[zsha] bash 已启动（容器 /root）")
                scope.launch {
                    p.inputStream.bufferedReader().forEachLine { appendTerm(it) }
                    appendTerm("[zsha] bash 已退出")
                    synchronized(termLock) {
                        terminalRunning = false
                        terminalProcess = null
                    }
                }
                true
            } catch (e: Exception) {
                appendTerm("[zsha] 无法启动终端：${e.message}")
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
        jobs.values.filter { !it.done }.forEach { it.finish(-255) }
        setState(RuntimeState.Stopped)
    }
}

/** ANSI 转义序列清洗（简易终端 / 聊天展示用；不处理光标控制类 TUI 完整语义）。 */
object AnsiStrip {
    private val pattern = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")

    fun strip(text: String): String = text.replace(pattern, "")
}
