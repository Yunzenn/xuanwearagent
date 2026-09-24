package com.aiwatch.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class PcmFrameAccumulatorTest {
    private fun checkFinalTail(size: Int) {
        val accumulator = PcmFrameAccumulator(960)
        val input = ShortArray(size) { (it + 1).toShort() }
        assertEquals(0, accumulator.append(input).size)
        assertEquals(size, accumulator.bufferedSamples)
        val final = accumulator.finishUtterance()
        if (size == 0) assertNull(final) else {
            val expected = ShortArray(960)
            input.copyInto(expected)
            assertContentEquals(expected, final)
        }
        assertEquals(0, accumulator.bufferedSamples)
        assertNull(accumulator.finishUtterance())
        assertFailsWith<IllegalStateException> { accumulator.append(shortArrayOf(1)) }
    }

    @Test fun zeroTailDoesNotEmitFinalSilence() = checkFinalTail(0)
    @Test fun oneSampleTailIsPreservedAndPaddedOnce() = checkFinalTail(1)
    @Test fun hundredSampleTailIsPreservedAndPaddedOnce() = checkFinalTail(100)
    @Test fun almostFullTailAddsOnlyOneSilenceSample() = checkFinalTail(959)

    @Test fun resetStartsNewUtteranceWithoutReusingFinalFrame() {
        val accumulator = PcmFrameAccumulator(960)
        accumulator.append(shortArrayOf(7))
        accumulator.finishUtterance()
        accumulator.reset()
        assertContentEquals(ShortArray(960) { 8 }, accumulator.append(ShortArray(960) { 8 }).single())
        assertNull(accumulator.finishUtterance())
    }
    @Test
    fun arbitraryChunksProduceExactFramesWithoutLossOrPadding() {
        val source = ShortArray(2_017) { it.toShort() }
        val accumulator = PcmFrameAccumulator(960)
        val frames = buildList {
            addAll(accumulator.append(source, 0, 137))
            addAll(accumulator.append(source, 137, 1_000))
            addAll(accumulator.append(source, 1_137, 880))
        }

        assertEquals(2, frames.size)
        assertContentEquals(source.copyOfRange(0, 960), frames[0])
        assertContentEquals(source.copyOfRange(960, 1_920), frames[1])
        assertEquals(97, accumulator.bufferedSamples)
    }

    @Test
    fun doesNotEmitShortFrame() {
        val accumulator = PcmFrameAccumulator(960)
        assertEquals(0, accumulator.append(ShortArray(959)).size)
        assertEquals(959, accumulator.bufferedSamples)
        assertEquals(1, accumulator.append(shortArrayOf(7)).size)
        assertEquals(0, accumulator.bufferedSamples)
    }

    @Test
    fun oneLargeChunkEmitsEveryCompleteFrameAndKeepsOnlyRemainder() {
        val source = ShortArray(3 * 960 + 11) { (it - 1_000).toShort() }
        val accumulator = PcmFrameAccumulator(960)

        val frames = accumulator.append(source)

        assertEquals(3, frames.size)
        assertContentEquals(source.copyOfRange(0, 960), frames[0])
        assertContentEquals(source.copyOfRange(960, 1_920), frames[1])
        assertContentEquals(source.copyOfRange(1_920, 2_880), frames[2])
        assertEquals(11, accumulator.bufferedSamples)
    }

    @Test
    fun resetDropsOnlyExplicitlyDiscardedPendingSamples() {
        val accumulator = PcmFrameAccumulator(960)
        accumulator.append(ShortArray(400) { 1 })
        assertEquals(400, accumulator.bufferedSamples)

        accumulator.reset()

        assertEquals(0, accumulator.bufferedSamples)
        val frame = accumulator.append(ShortArray(960) { 2 }).single()
        assertContentEquals(ShortArray(960) { 2 }, frame)
    }
}
