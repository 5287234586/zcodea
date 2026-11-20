package zsh.android.client.env

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Checksum {

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().buffered().use { sha256Hex(it) }

    fun matches(file: File, expectedHex: String): Boolean =
        sha256Hex(file).equals(expectedHex.trim().lowercase(), ignoreCase = false)

    /**
     * 解析 SHA256SUMS / SHASUMS256.txt 格式：
     * `<hex>  <filename>`（两个空格，文件名可能带 `*` 前缀表示二进制模式）
     */
    fun parseChecksums(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val idx = line.indexOfFirst { it.isWhitespace() }
                if (idx <= 0) return@mapNotNull null
                val hex = line.substring(0, idx)
                val name = line.substring(idx).trim().trimStart('*', ' ').trim()
                if (!hex.matches(Regex("^[0-9a-fA-F]{64}$")) || name.isEmpty()) null
                else name to hex.lowercase()
            }
            .toMap()
}
