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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CloudProvider(val id: String) {
    GROQ("groq"),
    GEMINI("gemini"),
    OPENAI("openai"),
    XAI("xai"),
    ;

    companion object {
        fun fromId(id: String): CloudProvider = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown cloud provider")
    }
}

internal fun validateApiKey(raw: String): String {
    val key = raw.trim()
    require(key.length in 8..512) { "API key must contain 8 to 512 characters" }
    require(key.all { it.code in 33..126 }) { "API key contains unsupported characters" }
    return key
}

internal fun safeUploadName(name: String): String {
    val sanitized = name.map { character ->
        if (character.code in 32..126 && character != '"' && character != '\\') character else '_'
    }
        .joinToString("")
    val stem = if (sanitized.endsWith(M4A_EXTENSION, ignoreCase = true)) {
        sanitized.dropLast(M4A_EXTENSION.length)
    } else {
        sanitized
    }
    return "${stem.take(MAX_UPLOAD_NAME_LENGTH - M4A_EXTENSION.length).ifBlank { "recording" }}$M4A_EXTENSION"
}

internal fun parseCloudText(body: String): String = JSONObject(body)
    .optString("text")
    .trim()
    .also { require(it.isNotEmpty()) { "Provider returned an empty transcript" } }

private const val MAX_UPLOAD_NAME_LENGTH = 120
private const val M4A_EXTENSION = ".m4a"
private const val CLOUD_CONSENT_VERSION = 1

internal fun cloudUploadAllowed(
    consentVersion: Int,
    userConfirmed: Boolean,
    automaticApproved: Boolean,
    stopped: Boolean,
): Boolean = !stopped && consentVersion == CLOUD_CONSENT_VERSION &&
    (userConfirmed || automaticApproved)

class CloudTranscriptionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val profile = runCatching {
            TranscriptionProfile.fromIds(
                inputData.getString(KEY_MODEL) ?: return Result.failure(),
                inputData.getString(KEY_LANGUAGE) ?: return Result.failure(),
            )
        }.getOrNull() ?: return Result.failure()
        val provider = profile.model.provider
            ?: return Result.failure()
        if (!uploadAllowed(provider)) {
            return Result.success(workDataOf(KEY_ERROR to "Cloud upload consent is required in Settings"))
        }
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val key = inputData.getString(KEY_RECORDING) ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val size = inputData.getLong(KEY_SIZE, -1)
        val apiKey = SecretStore(applicationContext).get(provider)
            ?: return Result.success(workDataOf(KEY_ERROR to "API key is not configured"))

        return try {
            setForeground(createForegroundInfo(provider, key))
            val recording = Recording(uri, name, size, lastModified = 0, key = key)
            val transcript = withContext(Dispatchers.IO) {
                if (!uploadAllowed(provider)) {
                    throw CancellationException("Cloud upload permission was withdrawn")
                }
                CloudClient(applicationContext.contentResolver).transcribe(profile, apiKey, recording)
            }
            withContext(Dispatchers.IO) {
                TranscriptStore(applicationContext).write(key, transcript, profile)
            }
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception is CloudApiException && exception.retryable &&
                runAttemptCount < MAX_TRANSIENT_RETRIES
            ) {
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_ERROR to exception.cloudUserMessage()))
            }
        }
    }

    private fun uploadAllowed(provider: CloudProvider): Boolean = cloudUploadAllowed(
        consentVersion = inputData.getInt(KEY_CONSENT_VERSION, 0),
        userConfirmed = inputData.getBoolean(KEY_USER_CONFIRMED, false),
        automaticApproved = TranscriptionSettingsStore(applicationContext).hasCloudConsent(provider),
        stopped = isStopped,
    )

    private fun createForegroundInfo(provider: CloudProvider, key: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val providerName = applicationContext.getString(provider.nameResource())
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_cloud, providerName))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        val id = ("$key-${provider.id}".hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val GLOBAL_TAG = "cloud-transcriptions"
        const val KEY_ERROR = "error"

        private const val KEY_URI = "uri"
        private const val KEY_RECORDING = "recording"
        private const val KEY_NAME = "name"
        private const val KEY_SIZE = "size"
        private const val KEY_MODEL = "model"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_USER_CONFIRMED = "user_confirmed"
        private const val KEY_CONSENT_VERSION = "consent_version"
        private const val NOTIFICATION_CHANNEL = "transcription"
        private const val MAX_TRANSIENT_RETRIES = 4

        fun recordingTag(key: String, profile: TranscriptionProfile) = "cloud-$key-${profile.id}"

        fun input(recording: Recording, profile: TranscriptionProfile, userConfirmed: Boolean) = workDataOf(
            KEY_MODEL to profile.model.id,
            KEY_LANGUAGE to profile.language.id,
            KEY_URI to recording.uri.toString(),
            KEY_RECORDING to recording.key,
            KEY_NAME to recording.name,
            KEY_SIZE to recording.size,
            KEY_USER_CONFIRMED to userConfirmed,
            KEY_CONSENT_VERSION to CLOUD_CONSENT_VERSION,
        )
    }
}

class CloudTranscriptionQueue(context: Context) {
    private val appContext = context.applicationContext
    private val manager = WorkManager.getInstance(appContext)
    private val transcripts = TranscriptStore(appContext)

    suspend fun enqueueMissing(
        recordings: List<Recording>,
        profile: TranscriptionProfile,
        retryErrors: Boolean = false,
    ) {
        for (recording in recordings) {
            if (!transcripts.exists(recording.key, profile)) {
                enqueue(recording, profile, automatic = !retryErrors)
            }
        }
    }

    suspend fun enqueue(
        recording: Recording,
        profile: TranscriptionProfile,
        replace: Boolean = false,
        automatic: Boolean = false,
        userConfirmed: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        require(profile.model.provider != null) { "Cloud queue requires a cloud model" }
        val uniqueName = CloudTranscriptionWorker.recordingTag(recording.key, profile)
        val existing = manager.getWorkInfosByTag(uniqueName).get()
        if (existing.any { it.state.isActive() }) return@withContext
        if (automatic && existing.any {
                it.state == WorkInfo.State.SUCCEEDED &&
                    it.outputData.getString(CloudTranscriptionWorker.KEY_ERROR) != null
            }
        ) {
            return@withContext
        }
        if (replace && !transcripts.delete(recording.key, profile)) {
            throw IOException("The previous transcript cannot be removed")
        }
        val request = OneTimeWorkRequestBuilder<CloudTranscriptionWorker>()
            .setInputData(CloudTranscriptionWorker.input(recording, profile, userConfirmed))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(CloudTranscriptionWorker.GLOBAL_TAG)
            .addTag(uniqueName)
            .build()
        manager.beginUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        manager.cancelUniqueWork(QUEUE_NAME).result.get()
    }

    private fun WorkInfo.State.isActive() =
        this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING || this == WorkInfo.State.BLOCKED

    private companion object {
        const val QUEUE_NAME = "cloud-transcription-queue"
    }
}

internal fun CloudProvider.nameResource(): Int = when (this) {
    CloudProvider.OPENAI -> R.string.provider_openai
    CloudProvider.GEMINI -> R.string.provider_gemini
    CloudProvider.XAI -> R.string.provider_xai
    CloudProvider.GROQ -> R.string.provider_groq
}

private fun Exception.cloudUserMessage(): String = when (this) {
    is CloudApiException, is IOException, is IllegalArgumentException ->
        message?.take(300) ?: "Cloud transcription failed"
    else -> "Cloud transcription failed"
}
