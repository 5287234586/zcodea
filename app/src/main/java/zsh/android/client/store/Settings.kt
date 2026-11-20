package zsh.android.client.store

import android.content.Context
import android.content.SharedPreferences

/** 应用设置。装机参数变更后需要重装对应步骤才生效。 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zsha_settings", Context.MODE_PRIVATE)

    var npmPackage: String
        get() = prefs.getString(KEY_NPM_PKG, "zcode-app-cli")!!
        set(value) = prefs.edit().putString(KEY_NPM_PKG, value).apply()

    var nodeVersion: String
        get() = prefs.getString(KEY_NODE_VER, "v22.20.0")!!
        set(value) = prefs.edit().putString(KEY_NODE_VER, value).apply()

    var ubuntuVersion: String
        get() = prefs.getString(KEY_UBUNTU_VER, "24.04.3")!!
        set(value) = prefs.edit().putString(KEY_UBUNTU_VER, value).apply()

    var bridgePort: Int
        get() = prefs.getInt(KEY_PORT, 3090)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var npmRegistry: String
        get() = prefs.getString(KEY_REGISTRY, "https://registry.npmmirror.com")!!
        set(value) = prefs.edit().putString(KEY_REGISTRY, value).apply()

    /** zcode 的权限档位：build / edit / plan / yolo / guarded */
    var permMode: String
        get() = prefs.getString(KEY_PERM_MODE, "yolo")!!
        set(value) = prefs.edit().putString(KEY_PERM_MODE, value).apply()

    /** v0.2 会话模式：常驻 app-server（流式 + 会话连续）；关闭则回退 -p 单发 */
    var agentSessionMode: Boolean
        get() = prefs.getBoolean(KEY_AGENT_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_MODE, value).apply()

    /** 持久化的 agent 会话 id（重启后续聊） */
    var agentSessionId: String
        get() = prefs.getString(KEY_AGENT_SESSION, "")!!
        set(value) = prefs.edit().putString(KEY_AGENT_SESSION, value).apply()

    /** v0.3 局域网访问：开启后桥绑定 0.0.0.0，非回环请求必须带 token */
    var lanEnabled: Boolean
        get() = prefs.getBoolean(KEY_LAN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAN_ENABLED, value).apply()

    var lanToken: String
        get() = prefs.getString(KEY_LAN_TOKEN, "")!!
        set(value) = prefs.edit().putString(KEY_LAN_TOKEN, value).apply()

    /** 首次访问时生成 24 位随机 token */
    fun ensureLanToken(): String {
        if (lanToken.isBlank()) {
            val bytes = ByteArray(12)
            java.security.SecureRandom().nextBytes(bytes)
            lanToken = bytes.joinToString("") { "%02x".format(it) }
        }
        return lanToken
    }

    fun regenerateLanToken(): String {
        lanToken = ""
        return ensureLanToken()
    }

    companion object {
        private const val KEY_NPM_PKG = "npm_pkg"
        private const val KEY_NODE_VER = "node_ver"
        private const val KEY_UBUNTU_VER = "ubuntu_ver"
        private const val KEY_PORT = "bridge_port"
        private const val KEY_REGISTRY = "npm_registry"
        private const val KEY_PERM_MODE = "perm_mode"
        private const val KEY_AGENT_MODE = "agent_mode"
        private const val KEY_AGENT_SESSION = "agent_session"
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_LAN_TOKEN = "lan_token"
    }
}
