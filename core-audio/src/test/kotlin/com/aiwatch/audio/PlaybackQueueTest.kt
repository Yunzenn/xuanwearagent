package com.aiwatch.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.test.*

class PlaybackQueueTest {
    private class Sink(var maxWrite: Int = Int.MAX_VALUE) : PcmPlaybackSink {
        val written = mutableListOf<Short>()
        var flushes = 0
        override fun write(pcm: ShortArray, offset: Int, length: Int): Int {
            val count = minOf(length, maxWrite)
            written.addAll(pcm.slice(offset until offset + count)); return count
        }
        override fun pauseAndFlush() { flushes++ }
        override fun close() {}
    }

    @Test fun partialWritesAndTtsStopDrainWithoutDroppingSamples() {
        val sink = Sink(2)
        PlaybackQueue({ shortArrayOf(1, 2, 3, 4, 5) }, sink).use { queue ->
            queue.begin(1); assertTrue(queue.offer(byteArrayOf(1), 1)); queue.end(1)
            assertFalse(queue.offer(byteArrayOf(2), 1))
            repeat(3) { queue.pump() }
            assertEquals(listOf<Short>(1, 2, 3, 4, 5), sink.written)
            assertTrue(queue.idle)
        }
    }

    @Test fun flushClearsEncodedAndPartiallyWrittenPcmAndClosesGate() {
        val sink = Sink(1)
        PlaybackQueue({ shortArrayOf(1, 2, 3) }, sink).use { queue ->
            queue.begin(1); queue.offer(byteArrayOf(1), 1); queue.pump()
            queue.offer(byteArrayOf(2), 1)
            queue.flush()
            assertEquals(0, queue.encodedDepth); assertEquals(0, queue.pcmDepth)
            assertFalse(queue.offer(byteArrayOf(3), 1))
            repeat(5) { queue.pump() }
            assertEquals(listOf<Short>(1), sink.written)
            assertEquals(2, sink.flushes)
        }
    }

    @Test fun inFlightDecodeCannotRepopulateQueueAfterFlush() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val sink = Sink()
        try {
            PlaybackQueue({ entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); shortArrayOf(9) }, sink).use { queue ->
                queue.begin(1); queue.offer(byteArrayOf(1), 1)
                val future = executor.submit { queue.pump() }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                queue.flush(); queue.begin(2)
                assertFalse(queue.offer(byteArrayOf(1), 1))
                release.countDown(); future.get(3, TimeUnit.SECONDS)
                assertTrue(sink.written.isEmpty()); assertTrue(queue.idle)
            }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun queueAndPcmRemainBoundedUnderBackpressure() {
        val sink = Sink(0)
        PlaybackQueue({ shortArrayOf(1) }, sink, 2, 1).use { queue ->
            queue.begin(1)
            assertTrue(queue.offer(byteArrayOf(1), 1)); queue.pump()
            assertTrue(queue.offer(byteArrayOf(1), 1)); assertTrue(queue.offer(byteArrayOf(1), 1))
            repeat(100) { queue.pump() }
            assertFalse(queue.offer(byteArrayOf(1), 1))
            assertEquals(2, queue.encodedDepth); assertEquals(1, queue.pcmDepth)
        }
    }
}
