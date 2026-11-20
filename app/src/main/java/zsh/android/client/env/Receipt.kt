package zsh.android.client.env

import org.json.JSONObject
import java.io.File

/** 安装回执：记录每步状态与版本，作为"探测再执行、只修失败项"的依据。 */
data class Receipt(
    val abi: String,
    val steps: MutableMap<String, Boolean>,
    val versions: MutableMap<String, String>,
    val installedAt: Long = System.currentTimeMillis(),
) {
    fun stepOk(step: String): Boolean = steps[step] == true

    fun markOk(step: String, version: String? = null) {
        steps[step] = true
        if (version != null) versions[step] = version
    }

    fun markFail(step: String) {
        steps[step] = false
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("abi", abi)
        obj.put("installedAt", installedAt)
        obj.put("steps", JSONObject(steps.toMap()))
        obj.put("versions", JSONObject(versions.toMap()))
        return obj.toString(2)
    }

    companion object {
        fun load(file: File): Receipt? = runCatching {
            val obj = JSONObject(file.readText())
            val steps = obj.optJSONObject("steps")
                ?.let { jo -> jo.keys().asSequence().associateWith { jo.optBoolean(it, false) } }
                ?.toMutableMap() ?: mutableMapOf()
            val versions = obj.optJSONObject("versions")
                ?.let { jo -> jo.keys().asSequence().associateWith { jo.optString(it) } }
                ?.toMutableMap() ?: mutableMapOf()
            Receipt(
                abi = obj.optString("abi", ""),
                steps = steps,
                versions = versions,
                installedAt = obj.optLong("installedAt", 0L),
            )
        }.getOrNull()
    }
}
