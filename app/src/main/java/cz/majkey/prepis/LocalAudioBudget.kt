package cz.majkey.prepis

import android.media.AudioFormat
import android.os.SystemClock
import java.io.IOException

internal class LocalAudioBudget(
    maxDecodedDurationUs: Long = 24L * 60 * 60 * 1_000_000,
    maxCompressedBytes: Long = MAX_LOCAL_INPUT_BYTES,
    private val maxElapsedNanos: Long = LOCAL_JOB_TIMEOUT_MS * 1_000_000,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    init {
        require(maxDecodedDurationUs > 0 && maxCompressedBytes > 0 && maxElapsedNanos > 0)
    }

    private var remainingDurationUs = maxDecodedDurationUs
    private var remainingCompressedBytes = maxCompressedBytes
    private val startedAt = nanoTime()

    fun checkTime() {
        if (nanoTime() - startedAt >= maxElapsedNanos) {
            throw LocalAudioLimitException("The local transcription exceeded its time limit")
        }
    }

    fun chargeCompressedBytes(size: Int) {
        checkTime()
        if (size < 0) throw IOException("Invalid compressed audio size")
        if (size.toLong() > remainingCompressedBytes) {
            throw LocalAudioLimitException("The recording exceeds the local compressed audio byte limit")
        }
        remainingCompressedBytes -= size
    }

    fun chargeDecodedBytes(size: Int, sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        checkTime()
        val frameBytes = pcmFrameBytes(sampleRate, channelCount, pcmEncoding)
        if (size < 0 || size % frameBytes != 0) throw IOException("Incomplete PCM frame")
        val frames = size / frameBytes
        val durationUs = (frames * 1_000_000L + sampleRate - 1) / sampleRate
        if (durationUs > remainingDurationUs) {
            throw LocalAudioLimitException("The recording exceeds the local decoded audio duration limit")
        }
        remainingDurationUs -= durationUs
    }

    fun pcmFrameBytes(sampleRate: Int, channelCount: Int, pcmEncoding: Int): Int {
        if (sampleRate !in 1..MAX_SAMPLE_RATE) throw IOException("Unsupported audio sample rate")
        if (channelCount !in 1..MAX_CHANNEL_COUNT) throw IOException("Unsupported audio channel count")
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
            else -> throw IOException("Unsupported PCM encoding: $pcmEncoding")
        }
        return bytesPerSample * channelCount
    }
}

internal class LocalAudioLimitException(message: String) : IOException(message)
