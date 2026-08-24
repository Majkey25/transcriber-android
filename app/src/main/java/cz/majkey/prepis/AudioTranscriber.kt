package cz.majkey.prepis

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

internal class AudioTranscriber(
    private val resolver: ContentResolver,
    private val models: LocalModels,
    private val language: TranscriptionLanguage = TranscriptionLanguage.CZECH,
) {
    fun transcribe(uri: Uri): String {
        if (useOmnilingual(language)) return transcribeOmnilingual(uri)

        val whisper = transcribeWhisper(uri)
        if (language != TranscriptionLanguage.AUTO) return whisper.text
        val detected = selectDetectedLanguage(whisper.detectedLanguages)
            ?.let { runCatching { TranscriptionLanguage.fromId(it) }.getOrNull() }
        return if (detected != null && useOmnilingual(detected)) {
            transcribeOmnilingual(uri)
        } else {
            whisper.text
        }
    }

    private fun transcribeWhisper(uri: Uri): WhisperTranscript {
        val recognizer = createWhisperRecognizer()
        val parts = mutableListOf<String>()
        val detectedLanguages = mutableListOf<String>()

        try {
            decodeAudio(uri) { samples, sampleRate ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    result.text.trim().takeIf(String::isNotEmpty)?.let(parts::add)
                    detectedLanguages += result.lang
                } finally {
                    stream.release()
                }
            }
        } finally {
            recognizer.release()
        }

        return WhisperTranscript(requireTranscript(parts), detectedLanguages)
    }

    private fun transcribeOmnilingual(uri: Uri): String {
        val recognizer = createOmnilingualRecognizer()
        val parts = mutableListOf<String>()
        try {
            decodeAudio(uri) { samples, sampleRate ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    formatCtcPart(recognizer.getResult(stream).text)
                        .takeIf(String::isNotEmpty)
                        ?.let(parts::add)
                } finally {
                    stream.release()
                }
            }
        } finally {
            recognizer.release()
        }
        return requireTranscript(parts)
    }

    private fun requireTranscript(parts: List<String>): String = formatTranscript(parts).ifBlank {
        throw IOException("The recording contains no recognizable speech")
    }

    private fun createWhisperRecognizer(): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            encoder = models.whisper.resolve(ENCODER_FILE).path,
            decoder = models.whisper.resolve(DECODER_FILE).path,
            language = localWhisperLanguage(language),
            task = "transcribe",
            tailPaddings = 500,
        )
        val model = OfflineModelConfig(
            whisper = whisper,
            numThreads = min(MAX_THREADS, Runtime.getRuntime().availableProcessors()),
            provider = "cpu",
            modelType = "whisper",
            tokens = models.whisper.resolve(TOKENS_FILE).path,
        )
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model))
    }

    private fun createOmnilingualRecognizer(): OfflineRecognizer {
        val model = OfflineModelConfig(
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                model = models.omnilingual.resolve(OMNILINGUAL_MODEL_FILE).path,
            ),
            numThreads = min(MAX_THREADS, Runtime.getRuntime().availableProcessors()),
            provider = "cpu",
            tokens = models.omnilingual.resolve(OMNILINGUAL_TOKENS_FILE).path,
        )
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model))
    }

    private fun decodeAudio(uri: Uri, consume: (FloatArray, Int) -> Unit) {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("The recording cannot be opened")
        descriptor.use {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(it.fileDescriptor)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IOException("The file contains no audio track")

                extractor.selectTrack(trackIndex)
                decodeTrack(extractor, extractor.getTrackFormat(trackIndex), consume)
            } finally {
                extractor.release()
            }
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        consume: (FloatArray, Int) -> Unit,
    ) {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IOException("The audio track has no MIME type")
        val codec = MediaCodec.createDecoderByType(mime)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var chunker = PcmChunker(channelCount, sampleRate * CHUNK_SECONDS)

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idleCycles = 0

            while (!outputEnded) {
                var progressed = false
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        chunker = PcmChunker(channelCount, sampleRate * CHUNK_SECONDS)
                        progressed = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val output = checkNotNull(codec.getOutputBuffer(outputIndex)).apply {
                                position(bufferInfo.offset)
                                limit(bufferInfo.offset + bufferInfo.size)
                                order(ByteOrder.LITTLE_ENDIAN)
                            }
                            val chunks = chunker.add(output, pcmEncoding)
                            codec.releaseOutputBuffer(outputIndex, false)
                            chunks.forEach { consume(it, sampleRate) }
                        } else {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        progressed = true
                    }
                }

                idleCycles = if (progressed) 0 else idleCycles + 1
                if (idleCycles >= MAX_IDLE_CYCLES) throw IOException("The audio decoder is not responding")
            }

            chunker.finish()?.let { consume(it, sampleRate) }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    companion object {
        const val ENCODER_FILE = "small-encoder.int8.onnx"
        const val DECODER_FILE = "small-decoder.int8.onnx"
        const val TOKENS_FILE = "small-tokens.txt"
        const val OMNILINGUAL_MODEL_FILE = "model.int8.onnx"
        const val OMNILINGUAL_TOKENS_FILE = "tokens.txt"

        private const val MAX_THREADS = 4
        private const val CHUNK_SECONDS = 25
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_IDLE_CYCLES = 3_000
    }

    private data class WhisperTranscript(
        val text: String,
        val detectedLanguages: List<String>,
    )
}

internal fun localWhisperLanguage(language: TranscriptionLanguage): String =
    language.apiCode.orEmpty()

internal fun useOmnilingual(language: TranscriptionLanguage): Boolean =
    language == TranscriptionLanguage.CZECH || language == TranscriptionLanguage.FRENCH

internal fun selectDetectedLanguage(languages: List<String>): String? = languages
    .map { it.trim().lowercase() }
    .filter(String::isNotEmpty)
    .groupingBy { it }
    .eachCount()
    .maxByOrNull { it.value }
    ?.key

internal fun formatCtcPart(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    val capitalized = text.replaceFirstChar { it.titlecase() }
    return if (capitalized.last() in ".!?") capitalized else "$capitalized."
}

internal class PcmChunker(
    private val channelCount: Int,
    private val chunkSamples: Int,
) {
    private var chunk = FloatArray(chunkSamples)
    private var size = 0

    init {
        require(channelCount > 0) { "Channel count must be positive" }
        require(chunkSamples > 0) { "Chunk size must be positive" }
    }

    fun add(buffer: ByteBuffer, pcmEncoding: Int): List<FloatArray> {
        val bytesPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
            else -> throw IOException("Unsupported PCM encoding: $pcmEncoding")
        }
        val frameSize = bytesPerSample * channelCount
        if (buffer.remaining() % frameSize != 0) throw IOException("Incomplete PCM frame")

        val complete = mutableListOf<FloatArray>()
        while (buffer.hasRemaining()) {
            var mixed = 0f
            repeat(channelCount) {
                mixed += when (pcmEncoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32_768f
                    else -> buffer.float
                }
            }
            chunk[size++] = (mixed / channelCount).coerceIn(-1f, 1f)
            if (size == chunk.size) {
                complete += chunk
                chunk = FloatArray(chunkSamples)
                size = 0
            }
        }
        return complete
    }

    fun finish(): FloatArray? = if (size == 0) null else chunk.copyOf(size)
}
