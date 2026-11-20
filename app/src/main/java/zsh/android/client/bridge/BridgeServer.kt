package zsh.android.client.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import zsh.android.client.R
import zsh.android.client.device.ZshAccessibilityService
import zsh.android.client.env.EnvManager
import zsh.android.client.env.EnvPhase
import zsh.android.client.runtime.AnsiStrip
import zsh.android.client.runtime.Job
import zsh.android.client.runtime.RuntimeManager
import zsh.android.client.store.Settings
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * App 桥（对齐 DSHA 的 App 桥）。
 * 默认只绑 127.0.0.1；开启局域网访问后绑 0.0.0.0，非回环请求一律要求 token。
 */
class BridgeServer(
    private val context: Context,
    private val settings: Settings,
    private val runtime: RuntimeManager,
    private val env: EnvManager,
) : NanoHTTPD(if (settings.lanEnabled) null else "127.0.0.1", settings.bridgePort) {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false

    /** start() 之后的真实监听端口（用于状态回显）。 */
    val actualPort: Int
        get() = if (wasStarted()) getListeningPort() else settings.bridgePort

    fun launch() {
        stopped = false
        start(SOCKET_READ_TIMEOUT, true)
    }

    @Synchronized
    fun shutdown() {
        stopped = true
        runCatching { super.stop() }
    }

    /** 自检：是否仍在服务（用于看门狗）。 */
    fun isHealthy(): Boolean = !stopped && wasStarted()

    override fun serve(session: IHTTPSession): Response = try {
        gate(session) ?: (route(session) ?: notFound())
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "internal error"))
    }

    /**
     * 访问门禁：回环放行；非回环要求「设置里已开启局域网」+ token
     * （Authorization: Bearer <t> / X-Token: <t> / ?token=<t> 任一）。
     */
    private fun gate(session: IHTTPSession): Response? {
        val remote = runCatching { session.remoteIpAddress }.getOrDefault("") ?: ""
        val loopback = remote == "::1" || remote.startsWith("127.")
        if (loopback) return null
        if (!settings.lanEnabled) {
            return json(
                Response.Status.FORBIDDEN,
                JSONObject().put("error", "局域网访问未开启（应用内主页 → 局域网访问）"),
            )
        }
        val header = session.headers["authorization"] ?: ""
        val bearer = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ").trim() else ""
        val supplied = bearer
            .ifEmpty { session.headers["x-token"] ?: "" }
            .ifEmpty { param(session, "token") ?: "" }
        if (supplied.isEmpty() || supplied != settings.lanToken) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "token 缺失或不匹配"))
        }
        return null
    }

    // ------------------------------------------------------------- routing

    private fun route(session: IHTTPSession): Response? {
        val uri = session.uri
        val method = session.method
        return when {
            uri == "/" && method == Method.GET -> htmlAsset()
            uri == "/app/help" -> jsonOk(help())
            uri == "/app/status" -> jsonOk(status())
            uri == "/app/device" -> jsonOk(deviceInfo())
            uri == "/app/env/install" && method == Method.POST -> envInstall()
            uri == "/app/env/log" && method == Method.GET -> jsonOk(
                JSONObject().put("lines", JSONArray(env.log.value)),
            )
            uri == "/app/env/exec" && method == Method.POST -> envExec(session)

            uri == "/app/chat" && method == Method.POST -> postChat(session)
            uri == "/app/chat/stream" && method == Method.GET -> chatStream(session)
            uri == "/app/chat/result" && method == Method.GET -> chatResult(session)

            uri == "/app/agent/send" && method == Method.POST -> agentSend(session)
            uri == "/app/agent/events" && method == Method.GET -> agentEvents(session)
            uri == "/app/agent/messages" && method == Method.GET -> agentMessages()
            uri == "/app/agent/stop" && method == Method.POST -> agentStop()
            uri == "/app/agent/new" && method == Method.POST -> agentNew()
            uri == "/app/agent/status" && method == Method.GET -> agentStatus()

            uri == "/app/login" && method == Method.POST -> postLogin(session)
            uri == "/app/job" && method == Method.GET -> jobStatus(session)

            uri == "/app/terminal/start" && method == Method.POST -> jsonOk(
                JSONObject().put("started", runtime.startTerminal()),
            )
            uri == "/app/terminal/stop" && method == Method.POST -> {
                runtime.stopTerminal(); jsonOk(JSONObject().put("stopped", true))
            }
            uri == "/app/terminal/input" && method == Method.POST -> {
                val body = readBody(session)
                runtime.terminalInput(body.optString("text", ""))
                jsonOk(JSONObject().put("ok", true))
            }
            uri == "/app/terminal/buffer" && method == Method.GET -> jsonOk(
                JSONObject().put("lines", JSONArray(runtime.termBuffer().map { AnsiStrip.strip(it) })),
            )

            uri == "/app/screen" && method == Method.GET -> screen()
            uri == "/app/tap" && method == Method.POST -> tap(session)
            uri == "/app/input" && method == Method.POST -> typeText(session)
            uri == "/app/key" && method == Method.POST -> pressKey(session)
            uri == "/app/scroll" && method == Method.POST -> scroll(session)

            uri == "/app/clipboard" && method == Method.GET -> getClipboard()
            uri == "/app/clipboard" && method == Method.POST -> setClipboard(session)
            uri == "/app/notify" && method == Method.POST -> notify_(session)
            else -> null
        }
    }

    private fun notFound(): Response =
        json(Response.Status.NOT_FOUND, JSONObject().put("error", "no such route; see /app/help"))

    // ------------------------------------------------------------- payloads

    private fun status(): JSONObject {
        val phase = when (val p = env.phase.value) {
            is EnvPhase.Ready -> "ready"
            is EnvPhase.Installing -> "installing:${p.step.key}"
            is EnvPhase.Failed -> "failed:${p.step?.key}"
            is EnvPhase.AbiUnsupported -> "abi_unsupported:${p.abi}"
            EnvPhase.Idle -> "idle"
        }
        val receipt = zsh.android.client.env.Receipt.load(env.paths.receiptFile)
        val obj = JSONObject()
            .put("phase", phase)
            .put("bridge", "127.0.0.1:$actualPort")
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("chatBusy", runtime.isChatBusy())
            .put("terminalRunning", runtime.terminalRunning)
            .put("workspace", "/root/zsha")
            .put("agentMode", settings.agentSessionMode)
            .put("agentServer", runtime.agentServerStatus())
            .put("agentSessionId", runtime.agentSessionId ?: JSONObject.NULL)
            .put("agentBusy", runtime.agentBusy.value)
            .put("lan", settings.lanEnabled)
        if (receipt != null) obj.put("versions", JSONObject(receipt.versions.toMap()))
        return obj
    }

    private fun deviceInfo(): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("abIs", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("batteryPct", runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1))
            .put("accessibilityEnabled", ZshAccessibilityService.instance != null)
    }

    private fun help(): JSONObject {
        val obj = JSONObject().put("bridge", "ZCodeA 0.2").put("routes", helpRoutes())
        return obj
    }

    private fun helpRoutes(): JSONArray {
        val routes = arrayOf(
            "GET  /                移动 Web UI（聊天）",
            "GET  /app/help        本清单",
            "GET  /app/status      环境 / 运行时状态",
            "GET  /app/device      设备信息",
            "POST /app/env/install 触发装机流水线（幂等，202 返回）",
            "GET  /app/env/log     装机日志",
            "POST /app/chat       {message} → {job}（-p 单发，同一时刻一条）",
            "GET  /app/chat/stream?job&after  SSE 行流（event: line / done）",
            "GET  /app/chat/result?job&after   轮询兜底",
            "POST /app/agent/send  {message} → 202（app-server 会话模式，流式）",
            "GET  /app/agent/events    SSE 原始事件流（ZCode Protocol）",
            "GET  /app/agent/messages  会话历史",
            "POST /app/agent/stop|new  停止 / 新建会话",
            "GET  /app/agent/status    会话状态",
            "POST /app/login       {provider: zai|bigmodel} → {job}（输出含 OAuth URL）",
            "GET  /app/job?id      任务状态与输出",
            "POST /app/terminal/start|stop|input  简易终端",
            "GET  /app/terminal/buffer",
            "GET  /app/screen      无障碍读屏（需授权）",
            "POST /app/tap         {text} 按文本点按",
            "POST /app/input       {text} 对焦点输入框写入文本",
            "POST /app/key         {key: back|home}",
            "POST /app/scroll      {dir: forward|backward}",
            "GET|POST /app/clipboard  读/写剪贴板",
            "POST /app/notify      {title, body} 系统通知",
            "（局域网）非回环请求需 token：Authorization: Bearer / X-Token / ?token=",
        )
        return JSONArray(routes.toList())
    }

    // ------------------------------------------------------------- chat / jobs

    private fun readBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val raw = map["postData"] ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun postChat(session: IHTTPSession): Response {
        if (!envReady()) return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "环境未就绪"))
        val message = readBody(session).optString("message").trim()
        if (message.isEmpty()) return badRequest("message 不能为空")
        val job = runtime.startChat(message) ?: return json(
            Response.Status.CONFLICT,
            JSONObject().put("error", "已有任务在运行，请等待完成"),
        )
        return jsonOk(JSONObject().put("job", job.id))
    }

    private fun postLogin(session: IHTTPSession): Response {
        val provider = readBody(session).optString("provider", "zai")
        if (provider != "zai" && provider != "bigmodel") return badRequest("provider 必须是 zai 或 bigmodel")
        val job = runtime.startLogin(provider) ?: return json(
            Response.Status.CONFLICT,
            JSONObject().put("error", "已有登录任务"),
        )
        return jsonOk(JSONObject().put("job", job.id))
    }

    private fun jobStatus(session: IHTTPSession): Response {
        val job = runtime.getJob(param(session, "id") ?: "") ?: return badRequest("job 不存在")
        return jsonOk(jobSummary(job))
    }

    private fun jobSummary(job: Job): JSONObject {
        val obj = JSONObject()
            .put("id", job.id)
            .put("kind", job.kind.name.lowercase())
            .put("done", job.done)
            .put("exit", job.exitCode ?: JSONObject.NULL)
            .put("lines", JSONArray(job.output.map { AnsiStrip.strip(it) }))
        if (job.kind == Job.Kind.LOGIN) {
            loginUrl(job)?.let { obj.put("loginUrl", it) }
        }
        return obj
    }

    private fun loginUrl(job: Job): String? =
        job.output.map { AnsiStrip.strip(it) }.firstOrNull {
            it.contains("http://") || it.contains("https://")
        }?.let { Regex("https?://\\S+").find(it)?.value }

    /** SSE：event: line（data=行文本）/ event: done（data={"exit":n}），发完即关。 */
    private fun chatStream(session: IHTTPSession): Response {
        val job = runtime.getJob(param(session, "job") ?: "") ?: return badRequest("job 不存在")
        val after = param(session, "after")?.toIntOrNull() ?: 0
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        Thread {
            var idx = after
            try {
                while (true) {
                    val (chunk, newIdx) = job.pollAfter(idx, 8000)
                    idx = newIdx
                    chunk.forEach { line ->
                        pipeOut.write("event: line\ndata: ${jsonEscape(AnsiStrip.strip(line))}\n\n".toByteArray(Charsets.UTF_8))
                    }
                    pipeOut.flush()
                    if (job.done && idx >= job.output.size) {
                        pipeOut.write("event: done\ndata: {\"exit\":${job.exitCode ?: -1}}\n\n".toByteArray(Charsets.UTF_8))
                        pipeOut.flush()
                        break
                    }
                    if (stopped) break
                }
            } catch (_: Exception) {
                // 客户端断开：任务继续在后台跑
            } finally {
                runCatching { pipeOut.close() }
            }
        }.apply { isDaemon = true; start() }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
    }

    private fun chatResult(session: IHTTPSession): Response {
        val job = runtime.getJob(param(session, "job") ?: "") ?: return badRequest("job 不存在")
        val after = param(session, "after")?.toIntOrNull() ?: 0
        val (chunk, newIdx) = job.pollAfter(after, 3000)
        return jsonOk(
            JSONObject()
                .put("id", job.id)
                .put("done", job.done)
                .put("exit", job.exitCode ?: JSONObject.NULL)
                .put("after", newIdx)
                .put("lines", JSONArray(chunk.map { AnsiStrip.strip(it) })),
        )
    }

    private fun envReady(): Boolean = env.phase.value is EnvPhase.Ready

    // ------------------------------------------------------------- agent（v0.2 会话模式）

    private fun agentStatus(): Response {
        val s = runtime.appServer.state.value
        return jsonOk(
            JSONObject()
                .put("server", runtime.agentServerStatus())
                .put("sessionId", runtime.agentSessionId ?: JSONObject.NULL)
                .put("busy", runtime.agentBusy.value)
                .put("mode", if (settings.agentSessionMode) "session" else "prompt")
        )
    }

    private fun agentSend(session: IHTTPSession): Response {
        if (!envReady()) return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "环境未就绪"))
        if (!settings.agentSessionMode) return badRequest("会话模式未启用（设置 → agentSessionMode）")
        val message = readBody(session).optString("message").trim()
        if (message.isEmpty()) return badRequest("message 不能为空")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { runtime.agentSend(message) }
                .onFailure { android.util.Log.w("BridgeServer", "agentSend: ${it.message}") }
        }
        return json(Response.Status.ACCEPTED, JSONObject().put("accepted", true))
    }

    /** SSE：app-server 的全部通知/反向请求原样转发（data: <json>）。客户端断开时写入端会收到 IOException 自行收尾。 */
    private fun agentEvents(session: IHTTPSession): Response {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runtime.appServer.events.collect { msg ->
                    pipeOut.write("data: ${msg.toString().replace("\n", " ")}\n\n".toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { pipeOut.close() }
            }
        }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
    }

    private fun agentMessages(): Response = try {
        val result = kotlinx.coroutines.runBlocking { runtime.agentMessages() }
        jsonOk(result)
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "failed"))
    }

    private fun agentStop(): Response {
        CoroutineScope(Dispatchers.IO).launch { runCatching { runtime.agentStop() } }
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun agentNew(): Response {
        CoroutineScope(Dispatchers.IO).launch { runCatching { runtime.agentNewSession() } }
        return jsonOk(JSONObject().put("ok", true))
    }

    /** 触发装机流水线（幂等：已就绪/进行中直接返回当前状态）。 */
    private fun envInstall(): Response {
        val phase = env.phase.value
        if (phase is EnvPhase.Ready || phase is EnvPhase.Installing) {
            return json(Response.Status.ACCEPTED, JSONObject().put("phase", status().optString("phase")))
        }
        CoroutineScope(Dispatchers.IO).launch {
            env.install()
        }
        return json(Response.Status.ACCEPTED, JSONObject().put("phase", "installing:started"))
    }

    /** 在容器里直接执行一条命令（调试与 agent 复用）。args 为空格分隔；"!raw " 前缀 = 裸 proot 不走 env 模板；"!host " 前缀 = 宿主侧命令（仅 debug 构建）。 */
    private fun envExec(session: IHTTPSession): Response {
        val body = runCatching { readBody(session) }.getOrDefault(JSONObject())
        var raw = false
        var host = false
        var spec = body.optString("args").trim()
        if (spec.startsWith("!raw ")) {
            raw = true
            spec = spec.removePrefix("!raw ").trim()
        }
        if (spec.startsWith("!host ")) {
            host = true
            spec = spec.removePrefix("!host ").trim()
        }
        val args = spec.split(' ').filter { it.isNotBlank() }
        if (args.isEmpty()) return badRequest("args 不能为空")
        return try {
            val out = if (host) {
                if (!zsh.android.client.BuildConfig.DEBUG) return badRequest("仅 debug 构建可用")
                val pb = ProcessBuilder(args)
                pb.redirectErrorStream(true)
                pb.start().inputStream.bufferedReader().use { it.readText() }
            } else {
                env.execProotCapture(args, raw = raw)
            }
            jsonOk(JSONObject().put("output", out))
        } catch (e: Exception) {
            jsonOk(JSONObject().put("error", e.message ?: "exec failed"))
        }
    }

    private fun jsonEscape(s: String): String =
        JSONObject.quote(s).removePrefix("\"").removeSuffix("\"")

    // ------------------------------------------------------------- device

    private fun screen(): Response {
        val svc = ZshAccessibilityService.instance
            ?: return json(Response.Status.FORBIDDEN, JSONObject().put("error", "无障碍服务未开启（设置 → 无障碍 → ZCodeA）"))
        val tree = svc.dumpActiveWindow()
        return if (tree == null) json(Response.Status.FORBIDDEN, JSONObject().put("error", "无法读取当前窗口"))
        else jsonOk(tree)
    }

    private fun tap(session: IHTTPSession): Response {
        val svc = requireA11y() ?: return a11yForbidden()
        val text = readBody(session).optString("text").trim()
        if (text.isEmpty()) return badRequest("text 不能为空")
        return jsonOk(JSONObject().put("clicked", svc.tapByText(text)))
    }

    private fun typeText(session: IHTTPSession): Response {
        val svc = requireA11y() ?: return a11yForbidden()
        val text = readBody(session).optString("text")
        return jsonOk(JSONObject().put("typed", svc.typeIntoFocused(text)))
    }

    private fun pressKey(session: IHTTPSession): Response {
        val svc = requireA11y() ?: return a11yForbidden()
        when (readBody(session).optString("key", "back")) {
            "back" -> svc.performGlobal(ZshAccessibilityService.GLOBAL_BACK)
            "home" -> svc.performGlobal(ZshAccessibilityService.GLOBAL_HOME)
            else -> return badRequest("key 支持 back|home")
        }
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun scroll(session: IHTTPSession): Response {
        val svc = requireA11y() ?: return a11yForbidden()
        val dir = readBody(session).optString("dir", "forward")
        return jsonOk(JSONObject().put("scrolled", svc.scroll(dir == "forward")))
    }

    private fun requireA11y(): ZshAccessibilityService? = ZshAccessibilityService.instance

    private fun a11yForbidden(): Response = json(
        Response.Status.FORBIDDEN,
        JSONObject().put("error", "无障碍服务未开启（设置 → 无障碍 → ZCodeA）"),
    )

    // ------------------------------------------------------------- clipboard / notify

    private fun getClipboard(): Response {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        return jsonOk(JSONObject().put("text", text ?: JSONObject.NULL))
    }

    private fun setClipboard(session: IHTTPSession): Response {
        val text = readBody(session).optString("text")
        mainHandler.post {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("zcodea", text))
        }
        return jsonOk(JSONObject().put("ok", true))
    }

    private fun notify_(session: IHTTPSession): Response {
        val body = readBody(session)
        val title = body.optString("title", "ZCodeA")
        val text = body.optString("body", "")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("zcodea_agent") == null) {
            nm.createNotificationChannel(
                NotificationChannel("zcodea_agent", "ZCode 通知", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val n = Notification.Builder(context, "zcodea_agent")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        nm.notify(System.nanoTime().toInt(), n.build())
        return jsonOk(JSONObject().put("ok", true))
    }

    // ------------------------------------------------------------- plumbing

    private fun param(session: IHTTPSession, name: String): String? =
        session.parameters[name]?.firstOrNull()

    private fun jsonOk(obj: JSONObject): Response = json(Response.Status.OK, obj)

    private fun badRequest(msg: String): Response =
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", msg))

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())

    private fun htmlAsset(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            context.resources.openRawResource(R.raw.index).bufferedReader().use { it.readText() },
        )
}
