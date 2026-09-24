package com.aiwatch.audio

import com.aiwatch.protocol.PlaybackAudioConfig
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder

/** One serialized audio worker owns each codec. This API accepts raw Opus, not Ogg containers. */
interface OpusCodec {
    fun encodeUplink(frame: ShortArray): ByteArray
    fun decodeDownlink(packet: ByteArray): ShortArray
    fun reset()
}

/** Uplink is fixed by protocol. Playback configuration must be supplied from a validated Hello. */
class ConcentusOpusCodec(val playback: PlaybackAudioConfig) : OpusCodec {
    init {
        require(playback.format == "opus")
        require(playback.sampleRate in setOf(8000, 12000, 16000, 24000, 48000))
        require(playback.channels in 1..2)
        require(playback.frameDurationMs in setOf(10, 20, 40, 60))
    }
    private val encoder = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(24000)
    }
    private val decoder = OpusDecoder(playback.sampleRate, playback.channels)

    override fun encodeUplink(frame: ShortArray): ByteArray {
        require(frame.size == 960) { "Uplink requires exactly 960 mono PCM16 samples" }
        val output = ByteArray(4000)
        val count = encoder.encode(frame, 0, 960, output, 0, output.size)
        check(count > 0)
        return output.copyOf(count)
    }

    override fun decodeDownlink(packet: ByteArray): ShortArray {
        require(packet.isNotEmpty() && packet.size <= 65536)
        // Max legal Opus packet duration, not an assumption about Hello frame_duration.
        val capacityPerChannel = playback.sampleRate * 120 / 1000
        val pcm = ShortArray(capacityPerChannel * playback.channels)
        val samplesPerChannel = decoder.decode(packet, 0, packet.size, pcm, 0, capacityPerChannel, false)
        check(samplesPerChannel in 1..capacityPerChannel)
        return pcm.copyOf(samplesPerChannel * playback.channels)
    }

    override fun reset() { encoder.resetState(); decoder.resetState() }
}
