package zsh.android.client.runtime

import zsh.android.client.env.EnvPaths
import java.io.File

/**
 * 构造 proot 命令行。
 *
 * 关键点：
 * - `-0` 模拟 root（免 ROOT 的核心）；
 * - `/dev` `/proc` `/sys` 绑定进容器，Node 与 npm 依赖它们；
 * - 容器内 PATH 前置 /opt/node/bin，zcode 的 `#!/usr/bin/env node` 才能找到 node；
 * - proot 自身需要的 termux 库通过 LD_LIBRARY_PATH 在宿主侧设置（由 ProcessBuilder 注入）。
 */
object ProotCmd {

    const val CONTAINER_PATH =
        "/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun build(paths: EnvPaths, command: List<String>): List<String> {
        require(command.isNotEmpty()) { "command 不能为空" }
        val binds = mutableListOf(
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom",
        )
        return mutableListOf(
            paths.prootBinary.absolutePath,
            "--kill-on-exit",
            "-r", paths.rootfs.absolutePath,
            "-0",
            "-w", "/root",
        ).apply { addAll(binds) }.apply {
            addAll(
                listOf(
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "USER=root",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "TMPDIR=/tmp",
                    "PROOT_TMP_DIR=" + paths.nativeDir.absolutePath,
                    "npm_config_prefix=/opt/node",
                    "PATH=$CONTAINER_PATH",
                ),
            )
            addAll(command)
        }
    }

    /** 宿主侧进程环境：proot 二进制依赖随 deb 提取出来的 termux 库。 */
    fun hostEnv(paths: EnvPaths): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to paths.termuxLibDir.absolutePath,
        "PROOT_TMP_DIR" to paths.nativeDir.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "HOME" to (paths.root.parentFile?.absolutePath ?: "/data"),
    )

    /** 在容器里跑一条命令（npm 安装、zcode 调用等都走这里）。 */
    fun containerCommand(paths: EnvPaths, cmd: List<String>): List<String> = build(paths, cmd)

    fun ensureProotBinary(paths: EnvPaths): Boolean =
        File(paths.prootBinary.absolutePath).let { it.exists() && it.canExecute() }
}
