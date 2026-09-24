package com.aiwatch.probe

import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.audio.*
import com.aiwatch.protocol.PlaybackAudioConfig
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Emulator proves Android API behavior only, not microphone quality or reference-phone performance. */
class AudioDeviceTest {
    @Test fun audioTrackPauseFlushStopsQueuedPlayback() {
        val config = PlaybackAudioConfig("opus", 24000, 1, 60)
        val codec = ConcentusOpusCodec(config)
        val sink = AndroidPcmPlaybackSink(config)
        PlaybackQueue(codec::decodeDownlink, sink).use { queue ->
            queue.begin(1)
            repeat(4) { assertTrue(queue.offer(codec.encodeUplink(ShortArray(960) { 1000 }), 1)) }
            queue.pump()
            assertTrue(sink.isPlaying)
            queue.flush()
            assertFalse(sink.isPlaying)
            assertEquals(0, queue.encodedDepth)
            assertEquals(0, queue.pcmDepth)
            repeat(4) { queue.pump() }
            assertFalse(sink.isPlaying)
            assertFalse(queue.offer(byteArrayOf(1), 1))
        }
    }

    @Test fun audioRecordReads16kPcmAndReleases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = AndroidAudioCaptureSource(context)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Int> { source.start(); source.read(ShortArray(1024)) }
            assertTrue(future.get(5, TimeUnit.SECONDS) > 0)
        } finally {
            source.stop()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            source.close()
        }
    }
}
