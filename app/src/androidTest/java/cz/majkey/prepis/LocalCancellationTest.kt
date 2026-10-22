package cz.majkey.prepis

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalCancellationTest {
    @Test
    fun cancellationAfterAChunkStopsDecodingAndReleasesTheCodec() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File.createTempFile("cancellation-", ".wav", context.cacheDir)
        try {
            writeAudio(audio)
            val job = Job()
            val models = LocalModels(context.cacheDir, context.cacheDir)
            val transcriber = AudioTranscriber(context.contentResolver, models, checkCancelled = job::ensureActive)
            var consumed = 0
            try {
                transcriber.decodeAudio(Uri.fromFile(audio)) { _, _ ->
                    consumed++
                    job.cancel()
                }
                fail("Cancelled decoding completed")
            } catch (_: CancellationException) {
                assertEquals(1, consumed)
            }

            var completeChunks = 0
            var decodedSamples = 0
            val nextJob = Job()
            AudioTranscriber(context.contentResolver, models, checkCancelled = nextJob::ensureActive)
                .decodeAudio(Uri.fromFile(audio)) { samples, rate ->
                    assertEquals(SAMPLE_RATE, rate)
                    completeChunks++
                    decodedSamples += samples.size
                }
            assertEquals(2, completeChunks)
            assertEquals(SAMPLE_RATE * SECONDS, decodedSamples)
        } finally {
            audio.delete()
        }
    }

    private fun writeAudio(file: File) {
        val dataBytes = SAMPLE_RATE * SECONDS * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        file.outputStream().use { output ->
            output.write(header.array())
            val second = ByteArray(SAMPLE_RATE * 2)
            repeat(SECONDS) { output.write(second) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 8_000
        const val SECONDS = 26
    }
}
