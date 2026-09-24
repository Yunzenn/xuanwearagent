package com.aiwatch.audio

/**
 * Converts arbitrarily sized PCM16 chunks into exact frames without padding or dropping samples.
 * This class is deliberately codec-independent so it can be tested before Concentus is introduced.
 */
class PcmFrameAccumulator(private val frameSamples: Int) {
    init {
        require(frameSamples > 0) { "frameSamples must be positive" }
    }

    private var pending = ShortArray(frameSamples * 2)
    private var pendingSize = 0

    val bufferedSamples: Int get() = pendingSize

    fun append(chunk: ShortArray, offset: Int = 0, length: Int = chunk.size - offset): List<ShortArray> {
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

    fun reset() {
        pendingSize = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= pending.size) return
        var capacity = pending.size
        while (capacity < required) capacity *= 2
        pending = pending.copyOf(capacity)
    }
}
