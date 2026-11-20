package zsh.android.client.env

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/**
 * tar.gz 解压 + .deb（ar 归档）提取。
 * rootfs 与 Termux proot 的分发格式都靠这两个函数吃下来。
 *
 * 链接处理：tar 里硬链接可能先于目标出现，第一遍先落盘普通文件，
 * 链接失败则延后到主循环结束统一重试（Ubuntu 的 perl 等大量使用硬链接）。
 */
object Tar {

    private class DeferredLink(val target: File, val linkName: String)

    fun extractTarGz(tarGz: InputStream, destDir: File, stripComponents: Int = 0) {
        destDir.mkdirs()
        val buffered = BufferedInputStream(tarGz, 64 * 1024)
        GzipCompressorInputStream(buffered, true).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                drain(tar, destDir, stripComponents)
            }
        }
    }

    /**
     * 解包 .deb（ar 归档），把 data.tar.* 里的内容提取到 destDir。
     * 支持 data.tar / data.tar.gz / data.tar.xz（Termux deb 目前是 xz）。
     */
    fun extractDeb(debFile: File, destDir: File) {
        ArArchiveInputStream(BufferedInputStream(debFile.inputStream(), 64 * 1024)).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                val name = entry.name
                if (!name.startsWith("data.tar")) continue
                when (name) {
                    "data.tar" -> extractPlain(ar, destDir)
                    "data.tar.gz" -> extractTarGz(ar, destDir)
                    "data.tar.xz" -> extractXz(ar, destDir)
                    "data.tar.zst" -> throw UnsupportedOperationException(
                        "Termux deb 使用了 zstd 压缩（$name），请反馈以便支持",
                    )
                }
                return // 只取第一个 data 归档
            }
        }
        throw IllegalStateException("deb 中未找到 data.tar.*")
    }

    private fun extractXz(input: InputStream, destDir: File) {
        XZCompressorInputStream(BufferedInputStream(input, 64 * 1024)).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                drain(tar, destDir, 0)
            }
        }
    }

    private fun extractPlain(input: InputStream, destDir: File) {
        TarArchiveInputStream(BufferedInputStream(input, 64 * 1024)).use { tar ->
            drain(tar, destDir, 0)
        }
    }

    private fun drain(tar: TarArchiveInputStream, destDir: File, stripComponents: Int) {
        val deferred = mutableListOf<DeferredLink>()
        while (true) {
            val e: TarArchiveEntry = tar.nextTarEntry ?: break
            writeEntry(e, tar, destDir, stripComponents, deferred)
        }
        // 第二遍：重试之前目标尚未落盘的硬链接。
        // 注意：SELinux 禁止应用创建硬链接（lnk_file create 不放行），一律物化为内容拷贝。
        for (link in deferred) {
            materializeLink(link.target, sanitize(destDir, link.linkName))
        }
    }

    private fun materializeLink(target: File, source: File) {
        if (!source.exists()) {
            throw IllegalStateException("硬链接目标不存在: $target -> $source")
        }
        Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        if (source.canExecute()) target.setExecutable(true, false)
    }

    private fun writeEntry(
        e: TarArchiveEntry,
        input: InputStream,
        destDir: File,
        stripComponents: Int,
        deferred: MutableList<DeferredLink>,
    ) {
        var name = e.name.removePrefix("./")
        if (stripComponents > 0) {
            val parts = name.split('/').filter { it.isNotEmpty() }
            if (parts.size <= stripComponents) return
            name = parts.drop(stripComponents).joinToString("/")
        }
        val target = sanitize(destDir, name)
        if (e.isDirectory) {
            target.mkdirs()
            return
        }
        target.parentFile?.mkdirs()
        runCatching { target.delete() }
        if (e.isSymbolicLink) {
            // Android 应用私有目录允许创建符号链接（rootfs 内大量 .so 链接依赖它）
            Files.createSymbolicLink(
                target.toPath(),
                java.nio.file.FileSystems.getDefault().getPath(e.linkName ?: ""),
            )
            return
        }
        if (e.isLink) {
            // 硬链接：目标已落盘就立即拷贝，否则延后重试
            val source = sanitize(destDir, e.linkName ?: "")
            if (source.exists()) materializeLink(target, source)
            else deferred.add(DeferredLink(target, e.linkName ?: ""))
            return
        }
        FileOutputStream(target).use { input.copyTo(it) }
        if (e.mode and 0b001000000 != 0) { // owner 执行位
            target.setExecutable(true, false)
        }
    }

    /** 防止归档越界路径（../ 穿越），对齐 DSHA 的"拒绝归档越界"。 */
    fun sanitize(destDir: File, name: String): File {
        val destCanonical = destDir.canonicalFile
        val canonical = File(destDir, name).canonicalFile
        if (canonical != destCanonical && !canonical.path.startsWith(destCanonical.path + File.separator)) {
            throw SecurityException("归档越界路径: $name")
        }
        return canonical
    }
}
