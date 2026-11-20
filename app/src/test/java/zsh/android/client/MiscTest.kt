package zsh.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import zsh.android.client.env.Receipt
import zsh.android.client.env.Tar
import zsh.android.client.runtime.AnsiStrip
import zsh.android.client.runtime.Job
import zsh.android.client.env.EnvStep
import java.io.File

class MiscTest {

    @Test
    fun `ansi strip removes colors and titles`() {
        assertEquals(
            "hello world",
            AnsiStrip.strip("\u001B[31mhello\u001B[0m \u001B]0;title\u0007world"),
        )
    }

    @Test
    fun `job append and poll`() {
        val job = Job("t", Job.Kind.CHAT)
        job.append("a")
        job.append("b")
        val (chunk, idx) = job.pollAfter(0, 50)
        assertEquals(listOf("a", "b"), chunk)
        assertEquals(2, idx)
        job.finish(0)
        assertTrue(job.done)
    }

    @Test
    fun `receipt roundtrip`() {
        val f = File.createTempFile("receipt", ".json")
        try {
            val r = Receipt("arm64-v8a", mutableMapOf(), mutableMapOf())
            r.markOk(EnvStep.PROOT.key, "proot 5.4.0")
            r.markFail(EnvStep.ROOTFS.key)
            f.writeText(r.toJson())
            val loaded = Receipt.load(f)!!
            assertEquals("arm64-v8a", loaded.abi)
            assertTrue(loaded.stepOk(EnvStep.PROOT.key))
            assertEquals(false, loaded.steps[EnvStep.ROOTFS.key])
            assertEquals("proot 5.4.0", loaded.versions[EnvStep.PROOT.key])
        } finally {
            f.delete()
        }
    }

    @Test
    fun `tar sanitize blocks path traversal`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "zsha-sanitize-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        assertEquals(
            File(dir, "root/zsha").canonicalPath,
            Tar.sanitize(dir, "root/zsha").canonicalPath,
        )
        assertThrows(SecurityException::class.java) { Tar.sanitize(dir, "../evil") }
        assertThrows(SecurityException::class.java) { Tar.sanitize(dir, "a/../../evil") }
    }
}
