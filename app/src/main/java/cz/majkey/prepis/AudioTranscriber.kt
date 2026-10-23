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
    private val checkCancelled: () -> Unit,
) {
    fun transcribe(uri: Uri, budget: LocalAudioBudget = LocalAudioBudget()): String {
        checkActive(budget)
        val transcript = if (useOmnilingual(language)) {
            transcribeOmnilingual(uri, budget)
        } else {
            val whisper = transcribeWhisper(uri, budget)
            val detected = whisper.detectedLanguage
                ?.let { runCatching { TranscriptionLanguage.fromId(it) }.getOrNull() }
            if (language == TranscriptionLanguage.AUTO && detected != null && useOmnilingual(detected)) {
                transcribeOmnilingual(uri, budget)
            } else {
                whisper.text
            }
        }
        checkActive(budget)
        return transcript
    }

    private fun checkActive(budget: LocalAudioBudget) {
        checkCancelled()
        budget.checkTime()
    }

    private fun transcribeWhisper(uri: Uri, budget: LocalAudioBudget): WhisperTranscript {
        checkActive(budget)
        val recognizer = createWhisperRecognizer()
        val transcript = TranscriptAccumulator()
        val detectedLanguages = LanguageVotes()

        try {
            checkActive(budget)
            decodeAudio(uri, budget) { samples, sampleRate ->
                val stream = recognizer.createStream()
                try {
                    checkActive(budget)
                    stream.acceptWaveform(samples, sampleRate)
                    checkActive(budget)
                    recognizer.decode(stream)
                    checkActive(budget)
                    val result = recognizer.getResult(stream)
                    checkActive(budget)
                    transcript.add(result.text.trim())
                    detectedLanguages.add(result.lang)
                } finally {
                    stream.release()
                }
            }
        } finally {
            recognizer.release()
        }

        checkActive(budget)
        return WhisperTranscript(transcript.finish(), detectedLanguages.selected())
    }

    private fun transcribeOmnilingual(uri: Uri, budget: LocalAudioBudget): String {
        checkActive(budget)
        val recognizer = createOmnilingualRecognizer()
        val transcript = TranscriptAccumulator()
        try {
            checkActive(budget)
            decodeAudio(uri, budget) { samples, sampleRate ->
                val stream = recognizer.createStream()
                try {
                    checkActive(budget)
                    stream.acceptWaveform(samples, sampleRate)
                    checkActive(budget)
                    recognizer.decode(stream)
                    checkActive(budget)
                    val result = recognizer.getResult(stream)
                    checkActive(budget)
                    transcript.add(formatCtcPart(result.text))
                } finally {
                    stream.release()
                }
            }
        } finally {
            recognizer.release()
        }
        checkActive(budget)
        return transcript.finish()
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

    internal fun decodeAudio(
        uri: Uri,
        budget: LocalAudioBudget = LocalAudioBudget(),
        consume: (FloatArray, Int) -> Unit,
    ) {
        checkActive(budget)
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("The recording cannot be opened")
        descriptor.use {
            checkActive(budget)
            val extractor = MediaExtractor()
            try {
                checkActive(budget)
                extractor.setDataSource(it.fileDescriptor)
                checkActive(budget)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IOException("The file contains no audio track")

                extractor.selectTrack(trackIndex)
                decodeTrack(extractor, extractor.getTrackFormat(trackIndex), budget) { samples, sampleRate ->
                    checkActive(budget)
                    consume(samples, sampleRate)
                    checkActive(budget)
                }
            } finally {
                extractor.release()
            }
        }
        checkActive(budget)
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        budget: LocalAudioBudget,
        consume: (FloatArray, Int) -> Unit,
    ) {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IOException("The audio track has no MIME type")
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        budget.pcmFrameBytes(sampleRate, channelCount, pcmEncoding)
        var chunker = PcmChunker(channelCount, pcmChunkSamples(sampleRate))
        checkActive(budget)
        val codec = MediaCodec.createDecoderByType(mime)

        try {
            checkActive(budget)
            codec.configure(inputFormat, null, null, 0)
            checkActive(budget)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idleCycles = 0

            while (!outputEnded) {
                checkActive(budget)
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
                            budget.chargeCompressedBytes(size)
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
                        budget.pcmFrameBytes(sampleRate, channelCount, pcmEncoding)
                        chunker = PcmChunker(channelCount, pcmChunkSamples(sampleRate))
                        progressed = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        budget.chargeDecodedBytes(bufferInfo.size, sampleRate, channelCount, pcmEncoding)
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
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_IDLE_CYCLES = 3_000
    }

    private data class WhisperTranscript(
        val text: String,
        val detectedLanguage: String?,
    )
}

internal fun localWhisperLanguage(language: TranscriptionLanguage): String =
    language.apiCode.orEmpty()

internal fun useOmnilingual(language: TranscriptionLanguage): Boolean =
    language == TranscriptionLanguage.CZECH || language == TranscriptionLanguage.FRENCH

internal class LanguageVotes {
    private val counts = linkedMapOf<String, Long>()

    fun add(raw: String) {
        val language = raw.trim().lowercase()
        if (language.isEmpty()) return
        if (language.length > 16 || language !in counts && counts.size >= 128) {
            throw IOException("The recognizer returned too many language identifiers")
        }
        counts[language] = counts.getOrDefault(language, 0L) + 1
    }

    fun selected(): String? = counts.maxByOrNull { it.value }?.key
}

internal class TranscriptAccumulator {
    private val text = StringBuilder()
    private var bytes = 0

    fun add(part: String) {
        if (part.isEmpty()) return
        val additional = transcriptBytes(part).size + if (text.isEmpty()) 0 else 1
        if (additional > MAX_TRANSCRIPT_BYTES - bytes) throw TranscriptSizeException()
        if (text.isNotEmpty()) text.append(' ')
        text.append(part)
        bytes += additional
    }

    fun finish(): String = formatTranscript(listOf(text.toString())).ifBlank {
        throw IOException("The recording contains no recognizable speech")
    }.also { transcriptBytes(it) }
}

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
    init {
        require(channelCount in 1..MAX_CHANNEL_COUNT) { "Unsupported audio channel count" }
        require(chunkSamples in 1..MAX_CHUNK_SAMPLES) { "Unsupported audio chunk size" }
    }

    private var chunk = FloatArray(chunkSamples)
    private var size = 0

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

internal fun pcmChunkSamples(sampleRate: Int): Int {
    require(sampleRate in 1..MAX_SAMPLE_RATE) { "Unsupported audio sample rate" }
    return sampleRate * CHUNK_SECONDS
}

private const val CHUNK_SECONDS = 25
internal const val MAX_SAMPLE_RATE = 192_000
internal const val MAX_CHANNEL_COUNT = 32
private const val MAX_CHUNK_SAMPLES = MAX_SAMPLE_RATE * CHUNK_SECONDS
