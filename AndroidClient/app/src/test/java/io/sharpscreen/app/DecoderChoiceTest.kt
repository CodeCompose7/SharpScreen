package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderChoiceTest {
    private fun c(
        name: String,
        hw: Boolean = true,
        size: Boolean = true,
        rate: Boolean = true,
        lowLatency: Boolean = false,
        secure: Boolean = false,
    ) = DecoderChoice.Candidate(name, hw, size, rate, lowLatency, secure)

    // The Tab S11 Ultra's HEVC decoders in MediaCodecList order.
    private val tab =
        listOf(
            c("c2.mtk.hevc.decoder"),
            c("c2.mtk.hevc.decoder.secure", secure = true, lowLatency = true),
            c("c2.mtk.hevc.decoder.lowlatency", lowLatency = true),
            c("c2.android.hevc.decoder", hw = false, rate = false),
        )

    @Test
    fun prefersTheLowLatencyHardwareVariant() {
        assertEquals("c2.mtk.hevc.decoder.lowlatency", DecoderChoice.choose(tab)?.name)
    }

    @Test
    fun neverPicksASecureOnlyComponent() {
        val onlySecure = listOf(c("x.secure", secure = true, lowLatency = true), c("x"))
        assertEquals("x", DecoderChoice.choose(onlySecure)?.name)
    }

    @Test
    fun rateSupportOutranksLowLatency() {
        val list = listOf(c("ll", rate = false, lowLatency = true), c("plain"))
        assertEquals("plain", DecoderChoice.choose(list)?.name)
    }

    @Test
    fun hardwareOutranksSoftwareAndKeepsPlatformOrder() {
        val list = listOf(c("sw", hw = false), c("hw1", rate = false), c("hw2", rate = false))
        assertEquals("hw1", DecoderChoice.choose(list)?.name)
        assertNull(DecoderChoice.choose(listOf(c("none", size = false))))
    }
}
