package zsh.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import zsh.android.client.env.EnvPaths
import zsh.android.client.runtime.ProotCmd
import java.io.File

class ProotCmdTest {

    private fun paths(tmp: File): EnvPaths {
        val root = File(tmp, "appfiles")
        root.mkdirs()
        return EnvPaths(root)
    }

    @Test
    fun `proot command structure`() {
        val p = paths(createTempDir())
        val cmd = ProotCmd.build(p, listOf("/opt/node/bin/node", "-v"))

        assertTrue(cmd.contains("--kill-on-exit"))
        val rIdx = cmd.indexOf("-r")
        assertTrue(rIdx >= 0)
        assertEquals(p.rootfs.absolutePath, cmd[rIdx + 1])
        assertTrue(cmd.contains("-0"))
        assertTrue(cmd.contains("-w"))
        // /dev /proc /sys 必须绑定
        listOf("/dev", "/proc", "/sys").forEach { dir -> assertTrue(cmd.contains(dir)) }
        // env -i 带 PATH 且 /opt/node/bin 在最前
        val envIdx = cmd.indexOf("/usr/bin/env")
        assertTrue(envIdx >= 0)
        assertEquals("-i", cmd[envIdx + 1])
        val path = cmd.first { it.startsWith("PATH=") }.removePrefix("PATH=")
        assertTrue(path.startsWith("/opt/node/bin:"))
        // 命令本体在最后
        assertEquals("/opt/node/bin/node", cmd[cmd.size - 2])
        assertEquals("-v", cmd.last())
    }

    @Test
    fun `host env points proot to termux libs`() {
        val p = paths(createTempDir())
        val env = ProotCmd.hostEnv(p)
        val expectedSuffix = "runtime" + File.separator + "lib"
        assertTrue(env["LD_LIBRARY_PATH"]!!.endsWith(expectedSuffix))
        assertTrue(env.containsKey("PROOT_NO_SECCOMP"))
    }

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "zsha-test-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }
}
