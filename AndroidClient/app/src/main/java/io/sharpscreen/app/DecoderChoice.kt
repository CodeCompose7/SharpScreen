package io.sharpscreen.app

/**
 * Picks the decoder component. Vendors often list a plain and a
 * ".lowlatency" variant of the same hardware decoder; taking the first match
 * picked the plain one on the Tab S11 Ultra (c2.mtk.hevc.decoder, ~9 ms per
 * frame) over c2.mtk.hevc.decoder.lowlatency.
 */
object DecoderChoice {
    data class Candidate(
        val name: String,
        val hardware: Boolean,
        val sizeSupported: Boolean,
        val rateSupported: Boolean,
        val lowLatency: Boolean,
        val secureOnly: Boolean,
    )

    /**
     * Hardware before software; within each, the target frame rate before
     * size-only, and a low-latency component before a plain one. Components
     * that require secure playback cannot render to our surface and are skipped.
     */
    fun choose(candidates: List<Candidate>): Candidate? =
        candidates
            .filter { it.sizeSupported && !it.secureOnly }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<Candidate>>(
                    { if (it.value.hardware) 0 else 1 },
                    { if (it.value.rateSupported) 0 else 1 },
                    { if (it.value.lowLatency) 0 else 1 },
                    { it.index }, // otherwise keep the platform's order
                ),
            ).firstOrNull()
            ?.value
}
