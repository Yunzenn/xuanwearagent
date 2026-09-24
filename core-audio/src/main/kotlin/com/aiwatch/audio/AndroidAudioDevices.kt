package com.aiwatch.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.aiwatch.protocol.PlaybackAudioConfig
import java.io.Closeable

interface AudioCaptureSource : Closeable {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
}

/** start/read/close belong to capture worker; stop may be called from another thread to unblock read. */
class AndroidAudioCaptureSource(private val context: Context) : AudioCaptureSource {
    @Volatile private var record: AudioRecord? = null
    override fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission required")
        }
        check(record == null)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Native 16k capture unsupported; no resampler configured" }
        val created = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 3840))
        try {
            check(created.state == AudioRecord.STATE_INITIALIZED && created.sampleRate == 16000)
            created.startRecording()
            check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            record = created
        } catch (failure: Exception) { created.release(); throw failure }
    }
    override fun read(buffer: ShortArray): Int {
        val current = checkNotNull(record)
        return current.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
    }
    override fun stop() { record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
    override fun close() { try { stop() } finally { record?.release(); record = null } }
}

/** Called only via PlaybackQueue's serialized nonblocking sink boundary. */
class AndroidPcmPlaybackSink(config: PlaybackAudioConfig) : PcmPlaybackSink {
    private val track: AudioTrack
    val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
    init {
        require(config.channels in 1..2)
        val mask = if (config.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Server playback configuration unsupported by AudioTrack" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, config.sampleRate * config.channels * 2 * 120 / 1000))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); error("AudioTrack initialization failed") }
    }
    override fun write(pcm: ShortArray, offset: Int, length: Int): Int {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        return track.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING)
    }
    override fun pauseAndFlush() { track.pause(); track.flush() }
    override fun close() { track.release() }
}
