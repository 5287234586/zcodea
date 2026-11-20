package zsh.android.client.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import zsh.android.client.env.EnvPaths
import zsh.android.client.store.Settings
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * ZCode Protocol（app-server --stdio）客户端。
 *
 * 协议（逆向自 zcode CLI，换行分隔 JSON）：
 * - 客户端→服务端请求：{method, params, id} → 响应 {id, result} 或 {id, error}
 * - 服务端→客户端通知：{method, params}
 * - 服务端→客户端请求：{method, params, id} → 客户端须回 {id, result}（如 session/requestRuntimePreferences）
 */
class AppServerClient(
    private val paths: EnvPaths,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nextId = AtomicLong(1)
    private val pendingResponses = ConcurrentHashMap<Long, kotlinx.coroutines.CompletableDeferred<JSONObject>>()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data object Ready : State
        data class Down(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    /** 服务端通知 + 服务端反向请求（原始 JSON 行）。 */
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 512, replay = 0)
    val events: SharedFlow<JSONObject> = _events

    private class PendingCall(val method: String)

    val isRunning: Boolean get() = process?.isAlive == true

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun ensureStarted(): Boolean {
        if (isRunning) return true
        _state.value = State.Starting
        return try {
            val cmd = ProotCmd.containerCommand(
                paths,
                listOf("/opt/node/bin/zcode", "app-server", "--stdio"),
            )
            val pb = ProcessBuilder(cmd)
            ProotCmd.hostEnv(paths).forEach { (k, v) -> pb.environment()[k] = v }
            pb.redirectErrorStream(false)
            val p = pb.start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))

            Thread {
                val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
                reader.forEachLine { line ->
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    handleMessage(msg)
                }
                // stdout 关闭 = 进程结束
                val wasRunning = _state.value is State.Ready || _state.value is State.Starting
                _state.value = State.Down("app-server 已退出 (exit=${runCatching { p.waitFor() }.getOrDefault(-1)})")
                if (wasRunning) restartIfNeeded()
            }.apply { isDaemon = true; start() }

            Thread {
                p.errorStream.bufferedReader().forEachLine { line ->
                    android.util.Log.w(TAG, "app-server stderr: $line")
                }
            }.apply { isDaemon = true; start() }

            _state.value = State.Ready
            true
        } catch (e: Exception) {
            _state.value = State.Down("无法启动 app-server: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
        writer = null
        pendingResponses.clear()
        _state.value = State.Stopped
    }

    private fun restartIfNeeded() {
        if (settings.agentSessionMode) {
            scope.launch {
                kotlinx.coroutines.delay(2000)
                ensureStarted()
            }
        }
    }

    // ---------------------------------------------------------------- messaging

    private fun handleMessage(msg: JSONObject) {
        val id = msg.opt("id")
        if (msg.has("result") || msg.has("error")) {
            // 对我方请求的响应
            val key = when (id) {
                is Number -> id.toLong()
                is String -> id.toLongOrNull() ?: return broadcast(msg)
                else -> return broadcast(msg)
            }
            pendingResponses.remove(key)?.complete(msg)
            return
        }
        if (msg.has("method") && msg.has("id")) {
            // 服务端反向请求 —— 必须回应，否则会话会挂起
            handleServerRequest(msg)
            return
        }
        broadcast(msg)
    }

    private fun broadcast(msg: JSONObject) {
        _events.tryEmit(msg)
    }

    private fun handleServerRequest(msg: JSONObject) {
        val reqId = msg.opt("id")
        val method = msg.optString("method")
        val result: JSONObject = when (method) {
            "session/requestRuntimePreferences" -> JSONObject()
                .put("nativeSearchEnhancementsEnabled", false)
                .put("toolSearchMode", "off")
                .put("memoryEnabled", false)
                .put("askUserQuestionAutoResolutionEnabled", true)
                .put("modelContextBudgetStrategy", "preflight-v1")
                .put("subagentRuntimeConfigEnabled", false)
            else -> JSONObject() // 未知反向请求：先空结果应答，原始消息同时广播给 UI（可后续实现授权卡片）
        }
        android.util.Log.i(TAG, "server request $method -> auto result")
        // 仍然广播出去（UI 可以展示权限请求等）
        broadcast(msg)
        val response = JSONObject().put("id", reqId).put("result", result)
        writeToStdin(response)
    }

    /** 发送请求并等待响应（挂起）。 */
    suspend fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long = 30_000,
    ): JSONObject {
        if (!ensureStarted()) throw IllegalStateException("app-server 未运行")
        val id = nextId.getAndIncrement()
        val deferred = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        pendingResponses[id] = deferred
        val req = JSONObject().put("method", method).put("params", params).put("id", id)
        writeToStdin(req)
        return try {
            val resp = withTimeout(timeoutMs) { deferred.await() }
            if (resp.has("error")) {
                throw AgentProtocolException(
                    resp.optJSONObject("error")?.optString("message") ?: "protocol error",
                )
            }
            resp.optJSONObject("result") ?: JSONObject()
        } catch (e: Exception) {
            pendingResponses.remove(id)
            throw e
        }
    }

    class AgentProtocolException(message: String) : Exception(message)

    private fun writeToStdin(obj: JSONObject) {
        synchronized(writeLock) {
            val w = writer ?: throw IllegalStateException("app-server stdin 未就绪")
            w.write(obj.toString())
            w.write("\n")
            w.flush()
        }
    }

    companion object {
        private const val TAG = "AppServerClient"
    }
}
