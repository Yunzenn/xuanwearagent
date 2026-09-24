package com.aiwatch.audio

import com.aiwatch.protocol.PlaybackAudioConfig
import kotlin.math.sin
import kotlin.test.*

class ConcentusOpusCodecTest {
    private fun codec(rate: Int = 24000, channels: Int = 1) =
        ConcentusOpusCodec(PlaybackAudioConfig("opus", rate, channels, 60))
    private fun tone() = ShortArray(960) { (sin(it * 2.0 * Math.PI * 440 / 16000) * 12000).toInt().toShort() }

    @Test fun actualOpusRoundTripUsesHelloRateAndChannels() {
        for (rate in listOf(8000, 12000, 16000, 24000, 48000)) for (channels in 1..2) {
            val codec = codec(rate, channels)
            val packet = codec.encodeUplink(tone())
            val pcm = codec.decodeDownlink(packet)
            assertEquals(rate * 60 / 1000 * channels, pcm.size)
            assertTrue(pcm.any { it.toInt() != 0 })
        }
    }

    @Test fun refusesPartialFramesInsteadOfPadding() {
        val codec = codec()
        for (count in listOf(0, 959, 961)) assertFailsWith<IllegalArgumentException> {
            codec.encodeUplink(ShortArray(count))
        }
    }

    @Test fun arbitraryChunksProduceOnlyWholeFramesAndKeepRemainder() {
        val accumulator = PcmFrameAccumulator(960)
        val codec = codec(16000)
        var total = 0
        var packets = 0
        for (size in listOf(1, 319, 777, 2048, 11, 160, 3000)) {
            total += size
            accumulator.append(ShortArray(size) { 500 }).forEach { frame ->
                assertEquals(960, codec.decodeDownlink(codec.encodeUplink(frame)).size)
                packets++
            }
            assertEquals(total / 960, packets)
            assertEquals(total % 960, accumulator.bufferedSamples)
        }
    }

    @Test fun resetRestoresCodecStateWithoutSyntheticAudio() {
        val codec = codec()
        val first = codec.encodeUplink(tone())
        val firstPcm = codec.decodeDownlink(first)
        codec.encodeUplink(tone())
        codec.reset()
        val repeated = codec.encodeUplink(tone())
        assertContentEquals(first, repeated)
        assertContentEquals(firstPcm, codec.decodeDownlink(repeated))
    }

    @Test fun rejectsMissingPacketAndUnsupportedPlaybackConfiguration() {
        assertFailsWith<IllegalArgumentException> { codec().decodeDownlink(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { codec(32000) }
        assertFailsWith<IllegalArgumentException> { codec(24000, 3) }
    }
}
