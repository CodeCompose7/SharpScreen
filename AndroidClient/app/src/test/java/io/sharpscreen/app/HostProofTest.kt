package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostProofTest {
    private val token = ByteArray(32) { it.toByte() }

    /** Same vector as the Mac's HostAdvertiserTests. */
    @Test
    fun proofMatchesTheMac() {
        assertEquals("UWJFtLLQBlzfU1zu", HostProof.proof(token, "192.168.0.12", 54321))
    }

    @Test
    fun proofDependsOnAddressPortAndToken() {
        val base = HostProof.proof(token, "192.168.0.12", 54321)
        assertNotEquals(base, HostProof.proof(token, "192.168.0.13", 54321))
        assertNotEquals(base, HostProof.proof(token, "192.168.0.12", 54322))
        assertNotEquals(base, HostProof.proof(ByteArray(32) { 7 }, "192.168.0.12", 54321))
    }

    @Test
    fun onlySignedAddressesCount() {
        val good = HostProof.proof(token, "192.168.0.12", 54321)
        val other = HostProof.proof(ByteArray(32) { 7 }, "10.0.0.5", 54321)
        val txt = "192.168.0.12=$good,10.0.0.5=$other,garbage,=x,1.2.3.4="
        assertEquals(listOf("192.168.0.12"), HostProof.verifiedAddresses(token, txt, 54321))
        assertEquals(emptyList<String>(), HostProof.verifiedAddresses(token, txt, 54322))
    }
}
