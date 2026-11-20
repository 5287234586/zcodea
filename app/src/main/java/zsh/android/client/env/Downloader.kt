package zsh.android.client.env

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 带多镜像回退与进度回调的下载器。 */
class Downloader(private val onProgress: (bytes: Long, total: Long?) -> Unit = { _, _ -> }) {

    /** 最近一次成功下载的 URL（用于从文件名反推实际版本）。 */
    @Volatile var lastUrl: String? = null
        private set

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 依次尝试 candidates，下载到 destFile（先写 .part，成功后原子改名）。
     * 4xx 视为"源没有这个文件"继续换源；网络错误同样换源。
     */
    fun download(candidates: List<String>, destFile: File): File {
        val tmp = File(destFile.parentFile, destFile.name + ".part")
        var lastError: Exception? = null
        for (url in candidates) {
            try {
                onProgress(0, null)
                downloadOne(url, tmp)
                if (tmp.length() == 0L) throw IOException("下载内容为空")
                destFile.parentFile?.mkdirs()
                if (destFile.exists()) destFile.delete()
                if (!tmp.renameTo(destFile)) {
                    tmp.copyTo(destFile, overwrite = true)
                    tmp.delete()
                }
                lastUrl = url
                return destFile
            } catch (e: HttpGone) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
            } finally {
                tmp.delete()
            }
        }
        throw IOException("所有下载源均失败（共 ${candidates.size} 个候选）。最后错误：${lastError?.message}")
    }

    class HttpGone(message: String) : IOException(message)

    private fun downloadOne(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpGone("HTTP ${response.code} $url")
            val body = response.body ?: throw IOException("空响应体 $url")
            val total = body.contentLength().takeIf { it > 0 }
            var written = 0L
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
        }
    }

    fun getText(url: String, timeoutMs: Int = 15_000): String {
        val shortClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(url).build()
        shortClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpGone("HTTP ${response.code} $url")
            return response.body?.string() ?: throw IOException("空响应体 $url")
        }
    }
}
