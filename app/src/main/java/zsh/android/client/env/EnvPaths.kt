package zsh.android.client.env

import java.io.File

/** 环境目录布局。全部位于应用私有目录（filesDir），卸载即清。 */
class EnvPaths(root: File) {

    val root: File = File(root, "zsha")
    val downloads: File = File(this.root, "downloads")
    val nativeDir: File = File(this.root, "runtime") // proot 引擎 + loader + termux libs
    val rootfs: File = File(this.root, "rootfs")
    val nodeInRootfs: File = File(rootfs, "opt/node")
    val workspace: File = File(rootfs, "root/zsha")
    val receiptFile: File = File(this.root, "receipt.json")

    /** 当前激活的 proot 可执行文件（真实文件，可被引擎切换覆盖）。 */
    val prootBinary: File get() = File(nativeDir, "proot")
    /** 主引擎文件树（g-g-a proot-android，bin/proot + libexec/proot/loader）。 */
    val prootPkg: File get() = File(nativeDir, "proot-pkg")
    /** 回退引擎（Termux 构建，依赖 lib/ 下的共享库）。 */
    val prootTermux: File get() = File(nativeDir, "proot-termux")
    val termuxLibDir: File get() = File(nativeDir, "lib")

    fun ensureDirs() {
        root.mkdirs(); downloads.mkdirs(); nativeDir.mkdirs(); workspace.mkdirs()
    }
}
