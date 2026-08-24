package cz.majkey.prepis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TranscriptionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val profile = runCatching {
            TranscriptionProfile.fromIds(
                inputData.getString(KEY_MODEL) ?: return Result.failure(),
                inputData.getString(KEY_LANGUAGE) ?: return Result.failure(),
            )
        }.getOrNull()?.takeIf { it.model.isLocal } ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val key = inputData.getString(KEY_RECORDING) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val models = ModelStore(applicationContext)
        var modelReady = models.isReady()

        return try {
            updateState(PHASE_MODEL, applicationContext.getString(R.string.notification_model))
            val modelDirectories = withContext(Dispatchers.IO) { models.ensureInstalled() }
            modelReady = true

            updateState(
                PHASE_TRANSCRIBING,
                applicationContext.getString(R.string.notification_transcribing, name),
            )
            val transcript = withContext(Dispatchers.IO) {
                AudioTranscriber(
                    applicationContext.contentResolver,
                    modelDirectories,
                    profile.language,
                ).transcribe(uri)
            }
            withContext(Dispatchers.IO) {
                TranscriptStore(applicationContext).write(key, transcript, profile)
            }
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (!modelReady && exception is IOException &&
                exception !is ModelIntegrityException && runAttemptCount < MAX_DOWNLOAD_RETRIES
            ) {
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_ERROR to exception.userMessage()))
            }
        }
    }

    private suspend fun updateState(phase: String, notificationText: String) {
        setProgress(workDataOf(KEY_PHASE to phase))
        setForeground(createForegroundInfo(notificationText))
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        val notificationId = (inputData.getString(KEY_RECORDING).hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val GLOBAL_TAG = "transcriber-local-jobs"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "error"
        const val PHASE_MODEL = "model"
        const val PHASE_TRANSCRIBING = "transcribing"

        private const val KEY_URI = "uri"
        private const val KEY_RECORDING = "recording"
        private const val KEY_NAME = "name"
        private const val KEY_MODEL = "model"
        private const val KEY_LANGUAGE = "language"
        private const val NOTIFICATION_CHANNEL = "transcription"
        private const val MAX_DOWNLOAD_RETRIES = 4

        fun recordingTag(key: String, profile: TranscriptionProfile) = "recording-$key-${profile.id}"

        fun input(recording: Recording, profile: TranscriptionProfile) = workDataOf(
            KEY_URI to recording.uri.toString(),
            KEY_RECORDING to recording.key,
            KEY_NAME to recording.name,
            KEY_MODEL to profile.model.id,
            KEY_LANGUAGE to profile.language.id,
        )
    }
}

class TranscriptionQueue(context: Context) {
    private val appContext = context.applicationContext
    private val manager = WorkManager.getInstance(appContext)
    private val transcripts = TranscriptStore(appContext)

    suspend fun enqueueMissing(
        recordings: List<Recording>,
        profile: TranscriptionProfile,
    ) {
        for (recording in recordings) {
            if (!transcripts.exists(recording.key, profile)) {
                enqueue(recording, profile, automatic = true)
            }
        }
    }

    suspend fun enqueue(
        recording: Recording,
        profile: TranscriptionProfile,
        replace: Boolean = false,
        automatic: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        require(profile.model.isLocal) { "Local queue requires a local model" }
        val tag = TranscriptionWorker.recordingTag(recording.key, profile)
        val existing = manager.getWorkInfosByTag(tag).get()
        val active = existing.any { it.state.isActive() }
        if (active) return@withContext
        if (automatic && existing.any {
                it.state == WorkInfo.State.SUCCEEDED &&
                    it.outputData.getString(TranscriptionWorker.KEY_ERROR) != null
            }
        ) {
            return@withContext
        }

        if (replace && !transcripts.delete(recording.key, profile)) {
            throw IOException("The previous transcript cannot be removed")
        }

        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(TranscriptionWorker.input(recording, profile))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TranscriptionWorker.GLOBAL_TAG)
            .addTag(tag)
            .build()
        manager.beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        manager.cancelUniqueWork(QUEUE_NAME).result.get()
    }

    private fun WorkInfo.State.isActive() =
        this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING || this == WorkInfo.State.BLOCKED

    private companion object {
        const val QUEUE_NAME = "transcription-queue"
    }
}

internal class ModelStore(context: Context) {
    private val whisperDirectory = context.filesDir.resolve("models/whisper-small-int8")
    private val omnilingualDirectory = context.filesDir.resolve("models/omnilingual-300m-int8")

    fun isReady(): Boolean = runCatching {
        isReady(whisperDirectory, WHISPER_REVISION, WHISPER_FILES) &&
            isReady(omnilingualDirectory, OMNILINGUAL_REVISION, OMNILINGUAL_FILES)
    }.getOrDefault(false)

    fun ensureInstalled(): LocalModels {
        ensureInstalled(whisperDirectory, WHISPER_REVISION, WHISPER_FILES)
        // ponytail: install both engines up front; lazy-download Omnilingual if first-run size becomes a problem.
        ensureInstalled(omnilingualDirectory, OMNILINGUAL_REVISION, OMNILINGUAL_FILES)
        return LocalModels(whisperDirectory, omnilingualDirectory)
    }

    private fun isReady(directory: File, revision: String, files: List<ModelFile>): Boolean =
        directory.resolve(".ready").readTextOrNull()?.trim() == revision &&
            files.all { directory.resolve(it.name).length() == it.size }

    private fun ensureInstalled(directory: File, revision: String, files: List<ModelFile>) {
        if (isReady(directory, revision, files)) return
        check(directory.mkdirs() || directory.isDirectory) { "The model directory cannot be created" }

        files.forEach { install(directory, it) }
        directory.resolve(".ready").writeText(revision, Charsets.UTF_8)
    }

    private fun install(directory: File, model: ModelFile) {
        val target = directory.resolve(model.name)
        if (target.length() == model.size && target.sha256() == model.sha256) return
        if (target.exists() && !target.delete()) throw IOException("Cannot replace ${model.name}")

        val partial = directory.resolve("${model.name}.part")
        if (partial.length() > model.size && !partial.delete()) {
            throw IOException("The partial download for ${model.name} cannot be repaired")
        }
        if (partial.length() < model.size) download(model, partial)

        if (partial.length() != model.size || partial.sha256() != model.sha256) {
            partial.delete()
            throw ModelIntegrityException("The integrity check for ${model.name} failed")
        }
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun download(model: ModelFile, partial: File) {
        val offset = partial.length()
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Transcriber-Android/0.3.0")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")

        try {
            val response = connection.responseCode
            val append = offset > 0 && response == HttpURLConnection.HTTP_PARTIAL
            if (response != HttpURLConnection.HTTP_OK && !append) {
                throw IOException("Downloading ${model.name} failed: HTTP $response")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial, append).buffered().use { output ->
                    input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText(Charsets.UTF_8) else null

    private data class ModelFile(
        val name: String,
        val size: Long,
        val sha256: String,
        val url: String,
    )

    private companion object {
        const val WHISPER_REVISION = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
        const val WHISPER_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/$WHISPER_REVISION"
        const val OMNILINGUAL_REVISION = "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094"
        const val OMNILINGUAL_BASE_URL =
            "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/" +
                "resolve/$OMNILINGUAL_REVISION"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val DOWNLOAD_BUFFER_SIZE = 1024 * 1024
        const val HASH_BUFFER_SIZE = 1024 * 1024

        val WHISPER_FILES = listOf(
            ModelFile(
                AudioTranscriber.ENCODER_FILE,
                112_442_483L,
                "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
                "$WHISPER_BASE_URL/${AudioTranscriber.ENCODER_FILE}",
            ),
            ModelFile(
                AudioTranscriber.DECODER_FILE,
                262_226_114L,
                "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
                "$WHISPER_BASE_URL/${AudioTranscriber.DECODER_FILE}",
            ),
            ModelFile(
                AudioTranscriber.TOKENS_FILE,
                816_730L,
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                "$WHISPER_BASE_URL/${AudioTranscriber.TOKENS_FILE}",
            ),
        )
        val OMNILINGUAL_FILES = listOf(
            ModelFile(
                AudioTranscriber.OMNILINGUAL_MODEL_FILE,
                365_352_120L,
                "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c",
                "$OMNILINGUAL_BASE_URL/${AudioTranscriber.OMNILINGUAL_MODEL_FILE}",
            ),
            ModelFile(
                AudioTranscriber.OMNILINGUAL_TOKENS_FILE,
                86_423L,
                "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31",
                "$OMNILINGUAL_BASE_URL/${AudioTranscriber.OMNILINGUAL_TOKENS_FILE}",
            ),
        )
    }
}

internal data class LocalModels(
    val whisper: File,
    val omnilingual: File,
)

internal class ModelIntegrityException(message: String) : IOException(message)

private fun Exception.userMessage(): String = message?.take(500) ?: "Transcription failed"
