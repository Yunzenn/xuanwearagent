package com.aiwatch.audio

/**
 * Converts PCM16 chunks into exact frames without padding or dropping during append.
 * Explicit normal utterance completion may pad one final frame; cancellation uses reset.
 * This class is deliberately codec-independent so it can be tested before Concentus is introduced.
 */
class PcmFrameAccumulator(private val frameSamples: Int) {
    init {
        require(frameSamples > 0) { "frameSamples must be positive" }
    }

    private var pending = ShortArray(frameSamples * 2)
    private var pendingSize = 0
    private var finalized = false

    val bufferedSamples: Int get() = pendingSize

    fun append(chunk: ShortArray, offset: Int = 0, length: Int = chunk.size - offset): List<ShortArray> {
        check(!finalized) { "Reset before starting another utterance" }
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size)
        ensureCapacity(pendingSize + length)
        chunk.copyInto(pending, pendingSize, offset, offset + length)
        pendingSize += length

        val frames = ArrayList<ShortArray>(pendingSize / frameSamples)
        var consumed = 0
        while (pendingSize - consumed >= frameSamples) {
            frames += pending.copyOfRange(consumed, consumed + frameSamples)
            consumed += frameSamples
        }
        if (consumed > 0) {
            pending.copyInto(pending, 0, consumed, pendingSize)
            pendingSize -= consumed
        }
        return frames
    }

    /** Explicit normal end only. Ordinary append never pads; this emits at most one final frame. */
    fun finishUtterance(): ShortArray? {
        if (finalized) return null
        finalized = true
        if (pendingSize == 0) return null
        val finalFrame = ShortArray(frameSamples)
        pending.copyInto(finalFrame, 0, 0, pendingSize)
        pendingSize = 0
        return finalFrame
    }

    fun reset() {
        pendingSize = 0
        finalized = false
    }

    private fun ensureCapacity(required: Int) {
        if (required <= pending.size) return
        var capacity = pending.size
        while (capacity < required) capacity *= 2
        pending = pending.copyOf(capacity)
    }
}
