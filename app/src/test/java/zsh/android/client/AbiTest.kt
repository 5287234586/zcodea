package zsh.android.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import zsh.android.client.env.Abi

class AbiTest {

    @Test
    fun `arm64 mapping`() {
        val abi = Abi.fromAndroid("arm64-v8a")!!
        assertEquals("aarch64", abi.termuxArch)
        assertEquals("arm64", abi.ubuntuArch)
        assertEquals("arm64", abi.nodeArch)
    }

    @Test
    fun `x86_64 mapping`() {
        val abi = Abi.fromAndroid("x86_64")!!
        assertEquals("x86_64", abi.termuxArch)
        assertEquals("amd64", abi.ubuntuArch)
        assertEquals("x64", abi.nodeArch)
    }

    @Test
    fun `unsupported abi rejected`() {
        assertNull(Abi.fromAndroid("armeabi-v7a"))
        assertNull(Abi.fromAndroid("x86"))
        assertNull(Abi.detect(arrayOf("armeabi")))
        assertEquals(Abi.ARM64, Abi.detect(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(Abi.X64, Abi.detect(arrayOf("x86_64")))
    }
}
