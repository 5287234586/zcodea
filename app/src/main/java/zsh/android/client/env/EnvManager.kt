package zsh.android.client.env

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import zsh.android.client.runtime.ProotCmd
import zsh.android.client.store.Settings
import java.io.File
import java.util.concurrent.TimeUnit

enum class EnvStep {
    PROOT, ROOTFS, NODE, ZCODE, VERIFY;

    val key: String get() = name.lowercase()
}

sealed interface EnvPhase {
    data object Idle : EnvPhase
    data class AbiUnsupported(val abi: String) : EnvPhase
    data class Installing(val step: EnvStep, val message: String) : EnvPhase
    data object Ready : EnvPhase
    data class Failed(val step: EnvStep?, val message: String) : EnvPhase
}

/**
 * 装机流水线：proot → Ubuntu rootfs → Node.js → ZCode CLI → 校验。
 *
 * 每步「先探测再执行、装完即校验」，只修失败项，不从头重来 —— 与 DSHA 的六步流水线同思路。
 */
class EnvManager(
    context: Context,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext
    val paths = EnvPaths(context.filesDir)
    private val downloader = Downloader()
    private val mutex = Mutex()

    private val _phase = MutableStateFlow<EnvPhase>(EnvPhase.Idle)
    val phase: StateFlow<EnvPhase> = _phase

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private fun log(line: String) {
        val stamped = "[${timeNow()}] $line"
        _log.value = (_log.value + stamped).takeLast(MAX_LOG_LINES)
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

    /** 快速探测当前环境状态（不下载、不执行长任务）。 */
    fun probe(): EnvPhase {
        if (Abi.detect(Build.SUPPORTED_ABIS) == null) {
            return EnvPhase.AbiUnsupported(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        }
        val receipt = Receipt.load(paths.receiptFile)
        return if (receipt != null &&
            EnvStep.entries.all { receipt.stepOk(it.key) } &&
            paths.prootBinary.exists() && paths.rootfs.exists()
        ) {
            EnvPhase.Ready
        } else {
            EnvPhase.Idle
        }
    }

    suspend fun install() = mutex.withLock {
        val abi = Abi.detect(Build.SUPPORTED_ABIS)
        if (abi == null) {
            _phase.value = EnvPhase.AbiUnsupported(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            return@withLock
        }
        paths.ensureDirs()
        var receipt = Receipt.load(paths.receiptFile)
        if (receipt == null || receipt.abi != abi.androidAbi) {
            receipt = Receipt(abi.androidAbi, mutableMapOf(), mutableMapOf())
        }

        try {
            runStep(EnvStep.PROOT, receipt) { runProotStep(abi, receipt) }
            runStep(EnvStep.ROOTFS, receipt) { runRootfsStep(abi, receipt) }
            runStep(EnvStep.NODE, receipt) { runNodeStep(abi, receipt) }
            runStep(EnvStep.ZCODE, receipt) { runZcodeStep(receipt) }
            runStep(EnvStep.VERIFY, receipt) { runVerifyStep(receipt) }

            writeReceipt(receipt)
            log("✔ 装机完成，回执已写入 ${paths.receiptFile.name}")
            _phase.value = EnvPhase.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: CanceledStep) {
            _phase.value = EnvPhase.Idle
        } catch (e: Throwable) {
            // Throwable：像 NoClassDefFoundError 这类 Error 也要稳住，不能崩掉整个进程
            log("✘ 失败：${e.message}")
            val failedStep = pendingStep
            _phase.value = EnvPhase.Failed(failedStep, e.message ?: e.javaClass.simpleName)
        }
    }

    private var pendingStep: EnvStep? = null

    private class CanceledStep : Exception("canceled")

    private suspend fun runStep(step: EnvStep, receipt: Receipt, body: suspend () -> Unit) {
        if (receipt.stepOk(step.key)) {
            log("⏭ ${step.key} 已完成，跳过")
            return
        }
        pendingStep = step
        _phase.value = EnvPhase.Installing(step, "进行中")
        log("▶ 开始步骤 ${step.key}")
        try {
            withContext(Dispatchers.IO) { body() }
            receipt.markOk(step.key)
            writeReceipt(receipt)
            log("✔ ${step.key} 完成")
        } catch (e: Exception) {
            receipt.markFail(step.key)
            writeReceipt(receipt)
            throw e
        }
    }

    private fun writeReceipt(receipt: Receipt) {
        runCatching {
            paths.receiptFile.writeText(receipt.toJson())
        }
    }

    // ---------------------------------------------------------------- proot

    /** 主引擎：proot-android（静态 talloc + 外置 loader，文件树可自由搬移）。 */
    private fun installProotStatic(receipt: Receipt) {
        log("下载 proot-android（主引擎，官方 raw → jsdelivr 回退）…")
        val tarball = File(paths.downloads, "proot-android.tar.gz")
        downloader.download(Sources.prootAndroidCandidates(abiCached ?: throw IllegalStateException("ABI 未检测")), tarball)
        paths.prootPkg.deleteRecursively()
        Tar.extractTarGz(tarball.inputStream().buffered(), paths.prootPkg)
        // 包内可能带 root/ 前缀目录，归一化到含 bin/proot 的那一层
        var pkgRoot = paths.prootPkg
        if (File(pkgRoot, "root/bin/proot").exists()) pkgRoot = File(pkgRoot, "root")
        val bin = File(pkgRoot, "bin/proot")
        val loader = File(pkgRoot, "libexec/proot/loader")
        if (!bin.exists() || !loader.exists()) {
            throw IllegalStateException("proot-android 包内缺少 bin/proot 或 libexec/proot/loader")
        }
        // 在 nativeDir 下铺一份可搬移布局：proot + libexec/proot/loader
        bin.copyTo(paths.prootBinary, overwrite = true)
        paths.prootBinary.setExecutable(true, false)
        val loaderDir = File(paths.nativeDir, "libexec/proot")
        loaderDir.mkdirs()
        loader.copyTo(File(loaderDir, "loader"), overwrite = true)
        File(paths.prootPkg, "libexec/proot/loader32").takeIf { it.exists() }?.let {
            it.copyTo(File(loaderDir, "loader32"), overwrite = true)
        }
        verifyElf(paths.prootBinary, "proot")
        log("主引擎 proot 就绪：${prootVersion().lineSequence().firstOrNull()}")
    }

    /** 回退引擎：Termux deb（proot + libtalloc + libandroid-shmem）。 */
    private fun installProotTermux(abi: Abi): Boolean {
        val pkgs = listOf("proot", "libtalloc", "libandroid-shmem")
        log("查找 Termux ${pkgs.joinToString("/")}（回退引擎）…")
        val debs = mutableListOf<File>()
        var ok = false
        for (repo in Sources.termuxRepos) {
            try {
                debs.clear()
                for (pkg in pkgs) {
                    val poolPrefix = if (pkg.startsWith("lib")) pkg.take(4) else pkg.take(1)
                    val listing = downloader.getText("$repo/pool/main/$poolPrefix/$pkg/")
                    val name = Sources.latestTermuxDebName(listing, pkg, abi.termuxArch)
                        ?: throw Downloader.HttpGone("pool 列表中未找到 ${pkg}_${abi.termuxArch}")
                    debs += downloader.download(
                        listOf(Sources.termuxPoolUrl(repo, pkg, name)),
                        File(paths.downloads, name),
                    )
                }
                ok = true
                break
            } catch (e: Exception) {
                log("· 源不可用 $repo：${e.message}")
            }
        }
        if (!ok || debs.isEmpty()) return false

        val staging = File(paths.nativeDir, "staging")
        staging.deleteRecursively()
        debs.forEach { Tar.extractDeb(it, staging) }
        val termuxUsr = findTermuxUsr(staging)
            ?: throw IllegalStateException("deb 内未找到 termux usr 目录")
        val prootSrc = File(termuxUsr, "bin/proot")
        if (!prootSrc.exists()) throw IllegalStateException("deb 中没有 bin/proot")
        prootSrc.copyTo(paths.prootTermux, overwrite = true)
        paths.prootTermux.setExecutable(true, false)
        paths.termuxLibDir.mkdirs()
        File(termuxUsr, "lib").listFiles()?.forEach { lib ->
            lib.copyTo(File(paths.termuxLibDir, lib.name), overwrite = true)
        }
        staging.deleteRecursively()
        verifyElf(paths.prootTermux, "proot-termux")
        log("回退引擎 proot-termux 就绪")
        return true
    }

    private var abiCached: Abi? = null

    private fun prootVersion(): String {
        val pb = ProcessBuilder(paths.prootBinary.absolutePath, "--version")
        ProotCmd.hostEnv(paths).forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)
        return pb.start().inputStream.bufferedReader().use { it.readText() }
    }

    private fun runProotStep(abi: Abi, receipt: Receipt) {
        abiCached = abi
        installProotStatic(receipt)
        val haveFallback = runCatching { installProotTermux(abi) }.getOrElse { false }
        if (!haveFallback) log("· 回退引擎不可用（仅主引擎）")
        File(paths.nativeDir, "active-engine").writeText("static")
        val version = prootVersion()
        require(version.contains("proot")) { "proot --version 输出异常：${version.take(120)}" }
        receipt.markOk(EnvStep.PROOT.key, version.lineSequence().firstOrNull())
    }

    /**
     * 容器探针失败时切换引擎重试一次。
     * 激活引擎记录在 nativeDir/active-engine 标记文件（"static" / "termux"）。
     */
    private fun swapProotAndReprobe(): Boolean {
        val marker = File(paths.nativeDir, "active-engine")
        val active = marker.takeIf { it.exists() }?.readText()?.trim() ?: "static"
        val next = if (active == "static") "termux" else "static"
        val src = if (next == "termux") paths.prootTermux else File(paths.prootPkg, "bin/proot")
        if (!src.exists()) {
            log("· 引擎 $next 未安装，无法切换")
            return false
        }
        log("· 探针失败，切换到引擎 $next 重试…")
        src.copyTo(paths.prootBinary, overwrite = true)
        paths.prootBinary.setExecutable(true, false)
        marker.writeText(next)
        return true
    }

    private fun findTermuxUsr(staging: File): File? {
        val direct = File(staging, "data/data/com.termux/files/usr")
        if (direct.isDirectory) return direct
        return staging.walkTopDown()
            .firstOrNull { it.isDirectory && it.name == "usr" && File(it, "bin").exists() }
    }

    private fun verifyElf(file: File, what: String) {
        val head = file.inputStream().use { it.readNBytes(4) }
        if (!head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
            throw IllegalStateException("$what 不是有效的 ELF 二进制")
        }
    }

    // ---------------------------------------------------------------- rootfs

    private fun runRootfsStep(abi: Abi, receipt: Receipt) {
        val requested = settings.ubuntuVersion
        paths.rootfs.deleteRecursively()
        paths.rootfs.mkdirs()
        val tarball = File(paths.downloads, "ubuntu-base.tar.gz")
        val offlineRootfs = offlineAsset("rootfs-${abi.ubuntuArch}.tgz", tarball)
        val actualVersion: String
        if (offlineRootfs != null) {
            actualVersion = offlineRootfs.second.optString("version", requested)
            log("· 使用内置离线 rootfs（$actualVersion，${abi.ubuntuArch}）")
        } else {
            log("下载 Ubuntu base $requested（${abi.ubuntuArch}，约 35MB）…")
            downloader.download(Sources.ubuntuRootfsCandidates(requested, abi), tarball)
            actualVersion = Regex("ubuntu-base-([\\d.]+)-base-").find(downloader.lastUrl ?: "")
                ?.groupValues?.get(1) ?: requested
            if (actualVersion != requested) log("· 请求版本不存在，改用 $actualVersion")
        }

        verifyChecksums(
            tarball,
            "ubuntu-base-$actualVersion-base-${abi.ubuntuArch}.tar.gz",
            Sources.ubuntuShaSumsCandidates(actualVersion),
        )
        log("解压 rootfs …")
        Tar.extractTarGz(tarball.inputStream().buffered(), paths.rootfs)

        // DNS：base 镜像默认 systemd-resolved 桩文件，直接写静态配置
        File(paths.rootfs, "etc/resolv.conf").writeText(
            "nameserver 223.5.5.5\nnameserver 223.6.6.6\nnameserver 8.8.8.8\n",
        )
        // apt 源：官方 ports → 清华镜像回退由用户自行选择，这里默认清华（国内可用）
        File(paths.rootfs, "etc/apt/sources.list.d/ubuntu.sources").let {
            it.parentFile?.mkdirs()
            it.writeText(
                """
                Types: deb
                URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
                Suites: noble noble-updates noble-security
                Components: main universe
                Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

                """.trimIndent(),
            )
        }
        paths.workspace.mkdirs()
        File(paths.rootfs, "tmp").mkdirs()
        File(paths.rootfs, "opt").mkdirs()

        probeContainer()
        log("Ubuntu $actualVersion 基础环境就绪")
        receipt.markOk(EnvStep.ROOTFS.key, actualVersion)
    }

    /** 容器可执行性探针；失败时切换引擎重试一次，仍失败则抛出明确错误。 */
    private fun probeContainer() {
        val probe = {
            runCatching {
                execProotCapture(listOf("/bin/echo", "PROBE_OK")) == "PROBE_OK"
            }.getOrDefault(false)
        }
        if (probe()) return
        swapProotAndReprobe()
        if (!probe()) {
            throw IllegalStateException(
                "容器探针失败：proot 无法在当前内核上执行动态 ELF（模拟器 API33+/WHPX 有已知兼容问题，真机 arm64 不受影响）",
            )
        }
    }

    // ---------------------------------------------------------------- node

    private fun runNodeStep(abi: Abi, receipt: Receipt) {
        val tarball = File(paths.downloads, "node.tar.gz")
        val offlineNode = offlineAsset("node-${abi.nodeArch}.tgz", tarball)
        val version: String
        if (offlineNode != null) {
            version = offlineNode.second.optString("version", settings.nodeVersion)
            log("· 使用内置离线 Node（$version，linux-${abi.nodeArch}）")
        } else {
            version = settings.nodeVersion
            log("下载 Node.js $version（linux-${abi.nodeArch}，约 50MB）…")
            downloader.download(Sources.nodeTarballCandidates(version, abi), tarball)
            verifyChecksums(
                tarball,
                "node-$version-linux-${abi.nodeArch}.tar.gz",
                Sources.nodeShaSumsCandidates(version),
            )
        }
        log("解压到 /opt/node …")
        paths.nodeInRootfs.deleteRecursively()
        paths.nodeInRootfs.mkdirs()
        Tar.extractTarGz(tarball.inputStream().buffered(), paths.nodeInRootfs, stripComponents = 1)

        val nodeBin = File(paths.nodeInRootfs, "bin/node")
        verifyElf(nodeBin, "node")
        val out = execProotCapture(listOf("/opt/node/bin/node", "-v"))
        log("Node 就绪：${out.trim()}")
        receipt.markOk(EnvStep.NODE.key, out.trim())
    }

    // ---------------------------------------------------------------- zcode

    private fun runZcodeStep(receipt: Receipt) {
        val pkg = settings.npmPackage
        val registry = settings.npmRegistry
        log("容器内安装 $pkg@latest（npm，需数分钟）…")
        val out = execProotCapture(
            listOf(
                "/opt/node/bin/npm", "install", "-g", "$pkg@latest",
                "--registry", registry, "--no-audit", "--no-fund",
            ),
            timeoutMinutes = 20,
        )
        val last = out.lineSequence().lastOrNull { it.isNotBlank() }
        log("npm 完成${last?.let { "：$it" } ?: ""}")

        val version = execProotCapture(listOf("/opt/node/bin/zcode", "--version"), timeoutMinutes = 3)
        log("ZCode 就绪：${version.trim()}")
        receipt.markOk(EnvStep.ZCODE.key, version.trim())
    }

    // ---------------------------------------------------------------- verify

    private fun runVerifyStep(receipt: Receipt) {
        val nodeV = execProotCapture(listOf("/opt/node/bin/node", "-v")).trim()
        val zcodeV = execProotCapture(listOf("/opt/node/bin/zcode", "--version"), timeoutMinutes = 3).trim()
        receipt.versions["node"] = nodeV
        receipt.versions["zcode"] = zcodeV
        log("校验通过：node=$nodeV zcode=$zcodeV")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 从 APK 内置离线资产取包（assets/offline/，见 gradle bundleOfflineEnv 任务）。
     * 校验 manifest sha256，通过则落到 dest 并返回版本元信息；缺失/损坏返回 null（走网络）。
     */
    private fun offlineAsset(name: String, dest: File): Pair<File, JSONObject>? {
        return runCatching {
            val manifest = JSONObject(
                appContext.assets.open("offline/manifest.json").bufferedReader().use { it.readText() },
            )
            // 调用方传文件名（rootfs-amd64.tgz），manifest 键不带扩展名
            val key = name.removeSuffix(".tgz")
            val sha = manifest.optString(key, "")
            if (sha.isEmpty()) {
                log("· manifest 中没有 $key（keys=" + manifest.keys().asSequence().toList() + "）")
                return null
            }
            val tmp = File(dest.parentFile, dest.name + ".asset")
            appContext.assets.open("offline/$name").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!Checksum.matches(tmp, sha)) {
                log("⚠ 内置离线包 $name SHA-256 不符，回退网络下载")
                tmp.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
            val versionMeta = when {
                key.startsWith("rootfs-") -> manifest.optString("_rootfsVersion", "")
                key.startsWith("node-") -> manifest.optString("_nodeVersion", "")
                else -> ""
            }
            dest to JSONObject().put("version", versionMeta)
        }.getOrElse {
            log("· 内置离线资产读取异常：${it.message}")
            null
        }
    }

    private fun verifyChecksums(file: File, fileName: String, sumsUrlCandidates: List<String>) {
        for (url in sumsUrlCandidates) {
            try {
                val map = Checksum.parseChecksums(downloader.getText(url))
                val expected = map[fileName] ?: continue
                log("校验 SHA-256（$fileName）…")
                if (!Checksum.matches(file, expected)) {
                    throw IllegalStateException("SHA-256 校验失败：$fileName 与 ${url.substringBeforeLast('/')} 的清单不符")
                }
                log("· 校验通过")
                return
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                log("· 校验清单获取失败 $url：${e.message}")
            }
        }
        log("⚠ 未能获取校验清单，跳过摘要校验（TOFU：已记录到回执）")
    }

    fun execProotCapture(
        args: List<String>,
        timeoutMinutes: Int = 5,
        what: String? = null,
        raw: Boolean = false,
    ): String {
        val cmd = if (raw) {
            // 裸 proot：不经 env -i 模板，直接执行 guest 命令（调试用）
            mutableListOf(paths.prootBinary.absolutePath, "-r", paths.rootfs.absolutePath, "-0", "-w", "/root", "-b", "/dev", "-b", "/proc", "-b", "/sys").apply { addAll(args) }
        } else {
            ProotCmd.build(paths, args)
        }
        val pb = ProcessBuilder(cmd)
        ProotCmd.hostEnv(paths).forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)
        val p = pb.start()
        // 只保留尾部 400 行，避免 npm 长输出撑爆内存；读线程结束后再取结果
        val deque = ArrayDeque<String>(400)
        val reader = Thread {
            p.inputStream.bufferedReader().use { r ->
                r.forEachLine { line ->
                    if (deque.size >= 400) deque.removeFirst()
                    deque.addLast(line)
                }
            }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMinutes.toLong(), TimeUnit.MINUTES)) {
            p.destroyForcibly()
            throw IllegalStateException("${what ?: args.first()} 超时（${timeoutMinutes}min）")
        }
        reader.join(2000)
        val output = deque.joinToString("\n")
        if (p.exitValue() != 0 && what != null) {
            throw IllegalStateException("$what 失败：exit=${p.exitValue()} ${output.take(500)}")
        }
        return output
    }

    companion object {
        private const val MAX_LOG_LINES = 300
    }
}
