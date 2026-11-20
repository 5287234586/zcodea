package zsh.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zsh.android.client.env.Abi
import zsh.android.client.env.Sources

class SourcesTest {

    @Test
    fun `pick latest proot deb from pool listing`() {
        val html = """
            <a href="../">../</a>
            <a href="proot_5.4.0_aarch64.deb">proot_5.4.0_aarch64.deb</a>
            <a href="proot_5.3.10_aarch64.deb">proot_5.3.10_aarch64.deb</a>
            <a href="proot_5.4.1-1_aarch64.deb">proot_5.4.1-1_aarch64.deb</a>
            <a href="proot_5.4.1-1_x86_64.deb">proot_5.4.1-1_x86_64.deb</a>
        """.trimIndent()
        assertEquals("proot_5.4.1-1_aarch64.deb", Sources.latestTermuxDebName(html, "proot", "aarch64"))
        assertEquals("proot_5.4.1-1_x86_64.deb", Sources.latestTermuxDebName(html, "proot", "x86_64"))
        assertNull(Sources.latestTermuxDebName(html, "proot", "arm"))
    }

    @Test
    fun `version key sorts numerically`() {
        val versions = listOf("5.9", "5.10", "5.4.0", "5.4.1-1", "5.4.1")
        val sorted = versions.sortedBy { Sources.versionKey(it).joinToString("") { n -> "%04d".format(n) } }
        assertEquals(listOf("5.4.0", "5.4.1", "5.4.1-1", "5.9", "5.10"), sorted)
    }

    @Test
    fun `pool url for libtalloc uses libt prefix`() {
        val url = Sources.termuxPoolUrl(
            "https://packages.termux.dev/apt/termux-main",
            "libtalloc",
            "libtalloc_2.4.2_aarch64.deb",
        )
        assertEquals("https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.2_aarch64.deb", url)
        val url2 = Sources.termuxPoolUrl(
            "https://packages.termux.dev/apt/termux-main",
            "proot",
            "proot_5.4.0_aarch64.deb",
        )
        assertEquals("https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0_aarch64.deb", url2)
    }

    @Test
    fun `ubuntu rootfs candidates include mirrors and fallback versions`() {
        val urls = Sources.ubuntuRootfsCandidates("24.04.3", Abi.ARM64)
        assertTrue(urls.any { it.contains("cdimage.ubuntu.com") && it.endsWith("ubuntu-base-24.04.3-base-arm64.tar.gz") })
        assertTrue(urls.any { it.contains("tuna") && it.endsWith("ubuntu-base-24.04.3-base-arm64.tar.gz") })
        assertTrue(urls.any { it.endsWith("ubuntu-base-24.04.2-base-arm64.tar.gz") })
        // amd64 for x86_64 devices
        val urlsX64 = Sources.ubuntuRootfsCandidates("24.04.3", Abi.X64)
        assertTrue(urlsX64.first().endsWith("ubuntu-base-24.04.3-base-amd64.tar.gz"))
    }

    @Test
    fun `node candidates cover three mirrors`() {
        val urls = Sources.nodeTarballCandidates("v22.20.0", Abi.X64)
        assertEquals(listOf(
            "https://nodejs.org/dist/v22.20.0/node-v22.20.0-linux-x64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/v22.20.0/node-v22.20.0-linux-x64.tar.gz",
            "https://cdn.npmmirror.com/binaries/node/v22.20.0/node-v22.20.0-linux-x64.tar.gz",
        ), urls)
    }
}
