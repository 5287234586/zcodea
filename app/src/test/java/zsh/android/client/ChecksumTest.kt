package zsh.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zsh.android.client.env.Checksum
import java.io.ByteArrayInputStream
import java.io.File

class ChecksumTest {

    @Test
    fun `sha256 known vector abc`() {
        val hex = Checksum.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun `parse SHA256SUMS format`() {
        val text = """
            0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd  ubuntu-base-24.04.2-base-arm64.tar.gz
            ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000 *node-v22.20.0-linux-arm64.tar.gz
            # comment line
            garbage
        """.trimIndent()
        val map = Checksum.parseChecksums(text)
        assertEquals(2, map.size)
        assertEquals(
            "0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd0123abcd",
            map["ubuntu-base-24.04.2-base-arm64.tar.gz"],
        )
        assertEquals(
            "ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000",
            map["node-v22.20.0-linux-arm64.tar.gz"],
        )
    }

    @Test
    fun `matches uses lowercase hex`() {
        val f = File.createTempFile("sum", ".txt")
        f.writeText("abc")
        try {
            assertTrue(
                Checksum.matches(
                    f,
                    "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                ),
            )
            assertFalse(
                Checksum.matches(
                    f,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ae",
                ),
            )
        } finally {
            f.delete()
        }
    }
}
