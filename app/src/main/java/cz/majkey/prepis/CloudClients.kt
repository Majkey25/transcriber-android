package cz.majkey.prepis

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

internal class CloudClient(private val resolver: ContentResolver) {
    private val deadline = HttpDeadline(TimeUnit.MINUTES.toMillis(30))

    suspend fun transcribe(profile: TranscriptionProfile, rawKey: String, recording: Recording): String {
        currentCoroutineContext().ensureActive()
        deadline.check()
        val provider = profile.model.provider
            ?: throw IllegalArgumentException("Local transcription does not use a cloud client")
        require(profile.model.supports(profile.language)) { "The model does not support this language" }
        val key = validateApiKey(rawKey)
        val length = recording.size.takeIf { it > 0 } ?: resolver
            .openAssetFileDescriptor(recording.uri, "r")
            ?.use { it.length }
            ?.takeIf { it > 0 }
            ?: throw IOException("The recording size is unavailable")
        val maxBytes = provider.maxUploadBytes()
        // ponytail: direct upload only; add provider-aware audio chunking when real files exceed this limit.
        if (length > maxBytes) {
            throw IOException("The recording exceeds this provider's ${maxBytes / MEGABYTE} MB upload limit")
        }

        return when (provider) {
            CloudProvider.OPENAI -> multipartTranscript(
                OPENAI_URL,
                OPENAI_HOST,
                key,
                recording,
                maxBytes,
                multipartFields(profile),
            )

            CloudProvider.GROQ -> multipartTranscript(
                GROQ_URL,
                GROQ_HOST,
                key,
                recording,
                maxBytes,
                multipartFields(profile),
            )

            CloudProvider.XAI -> multipartTranscript(
                XAI_URL,
                XAI_HOST,
                key,
                recording,
                maxBytes,
                multipartFields(profile),
            )

            CloudProvider.GEMINI -> geminiTranscript(profile, key, recording, length, maxBytes)
        }
    }

    private suspend fun multipartTranscript(
        endpoint: String,
        host: String,
        key: String,
        recording: Recording,
        maxBytes: Long,
        fields: LinkedHashMap<String, String>,
    ): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart("file", safeUploadName(recording.name), audioBody(recording.uri, maxBytes))
        }.build()
        val request = request(endpoint, host).header("Authorization", "Bearer $key").post(body).build()
        return deadline.execute(request) { response ->
            formatProviderTranscript(parseCloudText(deadline.readResponse(response)))
        }
    }

    private suspend fun geminiTranscript(
        profile: TranscriptionProfile,
        key: String,
        recording: Recording,
        length: Long,
        maxBytes: Long,
    ): String {
        val uploadUrl = startGeminiUpload(key, recording.name, length)
        val uploaded = uploadGeminiFile(uploadUrl, key, recording.uri, length, maxBytes)
        return try {
            val active = waitForGeminiFile(key, uploaded)
            formatProviderTranscript(generateGeminiTranscript(profile, key, active))
        } finally {
            deleteGeminiFile(key, uploaded.name)
        }
    }

    private suspend fun startGeminiUpload(key: String, name: String, length: Long): String {
        val metadata = JSONObject()
            .put("file", JSONObject().put("display_name", safeUploadName(name)))
            .toString()
        val request = request(GEMINI_UPLOAD_URL, GEMINI_HOST)
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", length.toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/mp4")
            .post(metadata.toRequestBody("application/json".toMediaType()))
            .build()
        return deadline.execute(request) { response ->
            deadline.readResponse(response, allowEmpty = true)
            val uploadUrl = response.header("X-Goog-Upload-URL")
                ?: throw IOException("Gemini did not return an upload URL")
            validateHttpsUrl(uploadUrl, GEMINI_HOST)
            uploadUrl
        }
    }

    private suspend fun uploadGeminiFile(
        uploadUrl: String,
        key: String,
        uri: Uri,
        length: Long,
        maxBytes: Long,
    ): GeminiFile {
        val request = request(uploadUrl, GEMINI_HOST)
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(audioBody(uri, maxBytes, length))
            .build()
        return deadline.execute(request) { response ->
            parseGeminiFile(JSONObject(deadline.readResponse(response)).getJSONObject("file"))
        }
    }

    private suspend fun waitForGeminiFile(key: String, initial: GeminiFile): GeminiFile {
        var current = initial
        repeat(GEMINI_PROCESSING_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            deadline.check()
            when (current.state.uppercase()) {
                "ACTIVE" -> return current
                "FAILED" -> throw IOException("Gemini could not process the recording")
            }
            delay(GEMINI_POLL_INTERVAL_MS)
            current = getGeminiFile(key, current.name)
        }
        throw IOException("Gemini file processing timed out")
    }

    private suspend fun getGeminiFile(key: String, name: String): GeminiFile {
        val safeName = validateGeminiFileName(name)
        val request = request("$GEMINI_FILES_URL/$safeName", GEMINI_HOST)
            .header("x-goog-api-key", key).get().build()
        return deadline.execute(request) { response ->
            parseGeminiFile(JSONObject(deadline.readResponse(response)))
        }
    }

    private suspend fun generateGeminiTranscript(
        profile: TranscriptionProfile,
        key: String,
        file: GeminiFile,
    ): String {
        val prompt = geminiPrompt(profile.language)
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "file_data",
                    JSONObject()
                        .put("mime_type", file.mimeType)
                        .put("file_uri", file.uri),
                ),
            )
            .put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts)),
            )
            .toString()
        val request = request(geminiGenerateUrl(profile), GEMINI_HOST)
            .header("x-goog-api-key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return deadline.execute(request) { response ->
            parseGeminiText(deadline.readResponse(response))
        }
    }

    private suspend fun deleteGeminiFile(key: String, name: String) {
        val safeName = validateGeminiFileName(name)
        val request = request("$GEMINI_FILES_URL/$safeName", GEMINI_HOST)
            .header("x-goog-api-key", key).delete().build()
        deleteCloudFile(request)
    }

    private fun parseGeminiText(body: String): String {
        val candidates = JSONObject(body).optJSONArray("candidates")
            ?: throw IOException("Gemini returned no transcript")
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IOException("Gemini returned no transcript")
        return buildList {
            for (index in 0 until parts.length()) {
                parts.optJSONObject(index)?.optString("text")?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.joinToString("\n\n").ifBlank { throw IOException("Gemini returned an empty transcript") }
    }

    private fun parseGeminiFile(json: JSONObject): GeminiFile = GeminiFile(
        name = validateGeminiFileName(json.getString("name")),
        uri = json.getString("uri"),
        mimeType = json.optString("mimeType", "audio/mp4"),
        state = json.optString("state", "PROCESSING"),
    )

    private fun validateGeminiFileName(name: String): String {
        require(GEMINI_FILE_NAME.matches(name)) { "Gemini returned an invalid file name" }
        return name
    }

    private data class GeminiFile(
        val name: String,
        val uri: String,
        val mimeType: String,
        val state: String,
    )

    private fun request(rawUrl: String, expectedHost: String): Request.Builder =
        Request.Builder().url(validateHttpsUrl(rawUrl, expectedHost))

    internal suspend fun audioBody(uri: Uri, maxBytes: Long, length: Long = -1): RequestBody {
        val context = currentCoroutineContext()
        return object : RequestBody() {
            override fun contentType() = "audio/mp4".toMediaType()
            override fun contentLength() = length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val copied = try {
                    resolver.openInputStream(uri)?.use { input ->
                        copyBounded(input, sink.outputStream(), maxBytes) {
                            if (!context.isActive) throw IOException("Cloud upload was cancelled")
                            deadline.check()
                        }
                    } ?: throw IOException("The recording cannot be opened")
                } catch (exception: RuntimeException) {
                    throw IOException("The recording cannot be read", exception)
                }
                if (length >= 0 && copied != length) throw IOException("The recording changed during upload")
            }
        }
    }

    private companion object {
        const val OPENAI_HOST = "api.openai.com"
        const val OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val GROQ_HOST = "api.groq.com"
        const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val XAI_HOST = "api.x.ai"
        const val XAI_URL = "https://api.x.ai/v1/stt"
        const val GEMINI_HOST = "generativelanguage.googleapis.com"
        const val GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        const val GEMINI_FILES_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MEGABYTE = 1024L * 1024L
        const val GEMINI_PROCESSING_ATTEMPTS = 300
        const val GEMINI_POLL_INTERVAL_MS = 1_000L
        val GEMINI_FILE_NAME = Regex("files/[A-Za-z0-9_-]+")
    }
}

internal fun geminiGenerateUrl(profile: TranscriptionProfile): String {
    require(profile.model.provider == CloudProvider.GEMINI) { "This is not a Gemini model" }
    return "https://generativelanguage.googleapis.com/v1beta/models/" +
        "${checkNotNull(profile.model.apiId)}:generateContent"
}

internal fun geminiPrompt(language: TranscriptionLanguage): String {
    val target = language.promptName()?.let { "The spoken language is $it. " }
        ?: "Detect the spoken language. "
    return "${target}Transcribe the complete audio verbatim. Preserve punctuation, paragraphs, " +
        "and speaker labels when evident. Do not summarize, translate, or correct the speaker. " +
        "Return only the transcript."
}

private fun TranscriptionLanguage.promptName(): String? = when (this) {
    TranscriptionLanguage.AUTO -> null
    TranscriptionLanguage.CZECH -> "Czech"
    TranscriptionLanguage.ENGLISH -> "English"
    TranscriptionLanguage.SLOVAK -> "Slovak"
    TranscriptionLanguage.GERMAN -> "German"
    TranscriptionLanguage.POLISH -> "Polish"
    TranscriptionLanguage.UKRAINIAN -> "Ukrainian"
    TranscriptionLanguage.RUSSIAN -> "Russian"
    TranscriptionLanguage.FRENCH -> "French"
    TranscriptionLanguage.SPANISH -> "Spanish"
    TranscriptionLanguage.ITALIAN -> "Italian"
    TranscriptionLanguage.PORTUGUESE -> "Portuguese"
    TranscriptionLanguage.DUTCH -> "Dutch"
    TranscriptionLanguage.HUNGARIAN -> "Hungarian"
}

internal fun multipartFields(profile: TranscriptionProfile): LinkedHashMap<String, String> {
    require(profile.model.supports(profile.language)) { "The model does not support this language" }
    val language = profile.language.apiCode
    return when (profile.model.provider) {
        CloudProvider.OPENAI -> linkedMapOf<String, String>().apply {
            put("model", checkNotNull(profile.model.apiId))
            language?.let { put("language", it) }
            put("response_format", "json")
        }

        CloudProvider.GROQ -> linkedMapOf<String, String>().apply {
            put("model", checkNotNull(profile.model.apiId))
            language?.let { put("language", it) }
            put("response_format", "json")
            put("temperature", "0")
        }

        CloudProvider.XAI -> linkedMapOf<String, String>().apply {
            language?.let {
                put("format", "true")
                put("language", it)
            }
        }

        else -> throw IllegalArgumentException("This model does not use multipart transcription")
    }
}

private fun CloudProvider.maxUploadBytes(): Long = when (this) {
    CloudProvider.OPENAI, CloudProvider.GROQ -> 25L * 1024L * 1024L
    CloudProvider.XAI -> 500L * 1024L * 1024L
    CloudProvider.GEMINI -> 500L * 1024L * 1024L
}

private fun validateHttpsUrl(rawUrl: String, expectedHost: String): URL = URL(rawUrl).also {
    require(it.protocol == "https" && it.host == expectedHost) { "Unexpected provider URL" }
}

internal fun copyBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    checkDeadline: () -> Unit = {},
): Long {
    var total = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkDeadline()
        val count = input.read(buffer)
        checkDeadline()
        if (count < 0) return total
        total += count
        if (total > maxBytes) throw IOException("The input exceeds the allowed size")
        output.write(buffer, 0, count)
    }
}

private fun readBounded(input: InputStream, maxBytes: Int, deadline: HttpDeadline): String {
    val output = ByteArrayOutputStream()
    BufferedInputStream(input).use { buffered ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            deadline.check()
            val count = buffered.read(buffer)
            deadline.check()
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("Provider response is too large")
            output.write(buffer, 0, count)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

internal suspend fun deleteCloudFile(request: Request) {
    withContext(NonCancellable) {
        runCatching {
            val cleanup = HttpDeadline(TimeUnit.SECONDS.toMillis(30))
            cleanup.execute(request) { cleanup.readResponse(it, allowEmpty = true) }
        }
    }
}

private fun HttpDeadline.readResponse(response: Response, allowEmpty: Boolean = false): String {
    check()
    val body = response.body.byteStream().use { readBounded(it, 1024 * 1024, this) }
    check()
    if (!response.isSuccessful) throw CloudApiException(response.code)
    if (!allowEmpty && body.isBlank()) throw IOException("Provider returned an empty response")
    return body
}

internal class HttpDeadline(timeoutMillis: Long) {
    private val expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    fun check() {
        if (System.nanoTime() >= expiresAt) {
            throw SocketTimeoutException("Cloud transcription exceeded its time limit")
        }
    }

    suspend fun <T> execute(request: Request, consume: (Response) -> T): T {
        check()
        val call = CLIENT.newCall(request)
        call.timeout().deadlineNanoTime(expiresAt)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!continuation.isActive) return
                            consume(it)
                        }
                        continuation.resume(result)
                    } catch (exception: Exception) {
                        continuation.resumeWithException(exception)
                    }
                }
            })
        }
    }

    private companion object {
        val CLIENT = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

internal class CloudApiException(val statusCode: Int) :
    IOException("Provider request failed (HTTP $statusCode)") {
    val retryable: Boolean get() = statusCode == 429 || statusCode in 500..599
}
