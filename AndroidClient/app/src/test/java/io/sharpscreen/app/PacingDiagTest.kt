package io.sharpscreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingDiagTest {
    @Test
    fun evenStreamReportsNoDoublesOrHolds() {
        val line = PacingDiag.summarize(List(100) { 1_000_000L * 50 }, List(119) { 8_333_333L })
        assertTrue(line, line.contains("p50=8.3"))
        assertTrue(line, line.contains("<4ms=0% >12.5ms=0%"))
        assertTrue(line, line.contains("transit jitter ms p50=0.0"))
    }

    @Test
    fun countsFramesSharingAVsyncAndHeldVsyncs() {
        val intervals = List(8) { 8_333_333L } + listOf(1_000_000L, 16_000_000L)
        val line = PacingDiag.summarize(emptyList(), intervals)
        assertTrue(line, line.contains("<4ms=10% >12.5ms=10%"))
        assertTrue(line, line.contains("no host timestamps"))
    }

    @Test
    fun transitIsRelativeToTheFastestFrame() {
        val line = PacingDiag.summarize(listOf(5_000_000L, 6_000_000L, 15_000_000L), listOf(8_333_333L))
        assertTrue(line, line.contains("max=10.0"))
    }

    @Test
    fun percentileUsesNearestRank() {
        val sorted = (1L..10L).toList()
        assertEquals(1L, PacingDiag.percentile(sorted, 0))
        assertEquals(6L, PacingDiag.percentile(sorted, 50))
        assertEquals(10L, PacingDiag.percentile(sorted, 100))
    }
}
