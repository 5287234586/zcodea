package zsh.android.client.env

/**
 * 下载源。全部走「官方优先、国内镜像回退」的多候选策略，
 * 与 DSHA 的镜像自动回退思路一致。
 */
object Sources {

    // ---- Ubuntu base rootfs ----

    val ubuntuVersions = listOf("24.04.3", "24.04.2", "24.04.1", "24.04")

    private fun ubuntuBaseUrls(version: String, abi: Abi): List<String> = listOf(
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$version/release/ubuntu-base-$version-base-${abi.ubuntuArch}.tar.gz",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$version/release/ubuntu-base-$version-base-${abi.ubuntuArch}.tar.gz",
    )

    fun ubuntuRootfsCandidates(requestedVersion: String, abi: Abi): List<String> {
        val urls = mutableListOf<String>()
        urls += ubuntuBaseUrls(requestedVersion, abi)
        ubuntuVersions.filter { it != requestedVersion }.forEach { v -> urls += ubuntuBaseUrls(v, abi) }
        return urls.distinct()
    }

    fun ubuntuShaSumsCandidates(requestedVersion: String): List<String> {
        val urls = mutableListOf<String>()
        ubuntuVersions.forEach { v ->
            urls += listOf(
                "https://cdimage.ubuntu.com/ubuntu-base/releases/$v/release/SHA256SUMS",
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$v/release/SHA256SUMS",
            )
        }
        return urls.distinct()
    }

    // ---- Node.js ----

    fun nodeDistBaseUrls(version: String): List<String> = listOf(
        "https://nodejs.org/dist/$version",
        "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/$version",
        "https://cdn.npmmirror.com/binaries/node/$version",
    )

    fun nodeTarballCandidates(version: String, abi: Abi): List<String> =
        nodeDistBaseUrls(version).map { "$it/node-$version-linux-${abi.nodeArch}.tar.gz" }

    fun nodeShaSumsCandidates(version: String): List<String> =
        nodeDistBaseUrls(version).map { "$it/SHASUMS256.txt" }

    // ---- proot 运行时 ----

    /**
     * 主引擎：green-green-avk/build-proot-android —— NDK 构建、静态链接 talloc、
     * 外置 loader（root/bin/proot + root/libexec/proot/loader），整个文件树可自由搬移。
     */
    fun prootAndroidCandidates(abi: Abi): List<String> {
        val arch = when (abi) {
            Abi.ARM64 -> "aarch64"
            Abi.X64 -> "x86_64"
        }
        return listOf(
            "https://raw.githubusercontent.com/green-green-avk/build-proot-android/master/packages/proot-android-$arch.tar.gz",
            "https://cdn.jsdelivr.net/gh/green-green-avk/build-proot-android@master/packages/proot-android-$arch.tar.gz",
        )
    }

    /** 回退引擎：Termux proot / libtalloc / libandroid-shmem（deb 提取）。 */
    val termuxRepos = listOf(
        "https://packages.termux.dev/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
    )

    /**
     * 从 Termux 仓库 pool 目录列表里挑出指定架构的最新版本 deb 文件名。
     * 目录形如: <a href="proot_5.4.0_aarch64.deb">proot_5.4.0_aarch64.deb</a>
     */
    fun latestTermuxDebName(listingHtml: String, pkg: String, termuxArch: String): String? {
        val regex = Regex("""${Regex.escape(pkg)}_([0-9][0-9a-zA-Z.\-+~:]*)_$termuxArch\.deb""")
        return regex.findAll(listingHtml)
            .map { it.groupValues[1] }
            .maxWithOrNull(compareBy { versionKey(it).joinToString("") { n -> "%04d".format(n) } })
            ?.let { "${pkg}_$it" + "_$termuxArch.deb" }
    }

    /** 版本号排序 key：按数字段比较，避免字典序把 5.10 排到 5.9 前面。 */
    fun versionKey(version: String): List<Long> =
        Regex("\\d+").findAll(version).map { it.value.toLongOrNull() ?: 0L }.toList()

    fun termuxPoolUrl(repo: String, pkg: String, fileName: String): String {
        val poolPrefix = if (pkg.startsWith("lib")) pkg.take(4) else pkg.take(1)
        return "$repo/pool/main/$poolPrefix/$pkg/$fileName"
    }
}
