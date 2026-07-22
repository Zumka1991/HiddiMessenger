package ru.hiddi.messenger.security

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class RecordedVoice(val pcm: ByteArray, val durationMs: Long)

/** Records PCM directly into process memory so no plaintext voice file touches storage. */
class InMemoryVoiceRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var output: ByteArrayOutputStream? = null
    private var startedAt = 0L

    val isRecording: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        check(audioRecord == null) { "Voice recording is already active" }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "Audio recording is unavailable" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimum * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
        val memory = ByteArrayOutputStream()
        audioRecord = recorder
        output = memory
        startedAt = SystemClock.elapsedRealtime()
        recorder.startRecording()
        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minimum)
            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                SystemClock.elapsedRealtime() - startedAt < MAX_DURATION_MS
            ) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) memory.write(buffer, 0, count)
            }
        }
    }

    suspend fun stop(): RecordedVoice {
        val recorder = checkNotNull(audioRecord) { "Voice recording is not active" }
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0, MAX_DURATION_MS)
        runCatching { recorder.stop() }
        recordingJob?.cancel()
        listOfNotNull(recordingJob).joinAll()
        recorder.release()
        audioRecord = null
        recordingJob = null
        return RecordedVoice(
            pcm = checkNotNull(output).toByteArray().also { require(it.isNotEmpty()) { "Voice recording is empty" } },
            durationMs = duration,
        ).also { output = null }
    }

    fun cancel() {
        runCatching { audioRecord?.stop() }
        recordingJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        recordingJob = null
        output?.reset()
        output = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 120_000L
    }
}

suspend fun playVoicePcm(pcm: ByteArray) = withContext(Dispatchers.IO) {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "Invalid voice payload" }
    val minimum = AudioTrack.getMinBufferSize(
        InMemoryVoiceRecorder.SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(InMemoryVoiceRecorder.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minimum, 8_192))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        track.play()
        var offset = 0
        while (offset < pcm.size) {
            val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "Voice playback failed" }
            offset += written
        }
        val expectedFrames = pcm.size / 2
        while (track.playbackHeadPosition < expectedFrames) delay(20)
    } finally {
        runCatching { track.stop() }
        track.release()
    }
}
