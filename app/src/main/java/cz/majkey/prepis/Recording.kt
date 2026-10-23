package cz.majkey.prepis

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Recording(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val key: String = recordingKey(uri.toString(), size, lastModified),
)

internal fun transcriptFileName(key: String, profile: TranscriptionProfile): String =
    if (profile == LEGACY_LOCAL_PROFILE) "$key.txt" else "$key-${profile.id}.txt"

internal fun legacyTranscriptFileName(key: String, profile: TranscriptionProfile): String? =
    LEGACY_CLOUD_PROFILES[profile]?.let { "$key-$it.txt" }

fun isM4a(name: String): Boolean = name.endsWith(".m4a", ignoreCase = true)

fun recordingKey(uri: String, size: Long, lastModified: Long): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$uri\n$size\n$lastModified".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun Recording.admissionTag(profile: TranscriptionProfile): String =
    "source-${recordingKey(uri.toString(), 0, 0)}-${profile.id}"

private val transcriptionAdmissionMutex = Mutex()

internal suspend fun <T> withTranscriptionAdmission(block: () -> T): T =
    transcriptionAdmissionMutex.withLock { withContext(Dispatchers.IO) { block() } }

internal fun WorkManager.checkTranscriptionCapacity() {
    val query = WorkQuery.Builder.fromTags(listOf(
        TranscriptionWorker.GLOBAL_TAG, CloudTranscriptionWorker.GLOBAL_TAG,
    )).addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).build()
    if (getWorkInfos(query).get().map { it.id }.toSet().size >= MAX_FOLDER_DOCUMENTS) {
        throw TranscriptionQueueFullException()
    }
}

internal class TranscriptionQueueFullException : IOException("The transcription queue is full")

internal const val MAX_TRANSCRIPT_BYTES = 1024 * 1024
internal const val MAX_TRANSCRIPT_FILES = 10_000
internal const val MAX_TRANSCRIPT_STORE_BYTES = 256L * 1024 * 1024

internal fun transcriptBytes(text: String): ByteArray {
    if (text.length > MAX_TRANSCRIPT_BYTES) throw TranscriptSizeException()
    return text.toByteArray(Charsets.UTF_8).also {
        if (it.size > MAX_TRANSCRIPT_BYTES) throw TranscriptSizeException()
    }
}

internal class TranscriptSizeException : IOException("The transcript exceeds the 1 MiB output limit")

internal class TranscriptStorageException : IOException(
    "Transcript storage is full (10,000 files or 256 MiB). Existing transcripts were kept.",
)

fun formatTranscript(parts: List<String>): String {
    val text = parts.joinToString(" ").replace(WHITESPACE, " ").trim()
    if (text.isEmpty()) return ""

    return text.split(SENTENCE_BOUNDARY)
        .chunked(SENTENCES_PER_PARAGRAPH)
        .joinToString("\n\n") { it.joinToString(" ") }
}

fun formatProviderTranscript(raw: String): String {
    val paragraphs = raw.trim()
        .replace("\r\n", "\n")
        .split(BLANK_LINE)
        .map { paragraph -> paragraph.replace(WHITESPACE, " ").trim() }
        .filter(String::isNotEmpty)
    if (paragraphs.isEmpty()) return ""
    return if (paragraphs.size > 1) paragraphs.joinToString("\n\n") else formatTranscript(paragraphs)
}

class FolderStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Uri? = preferences.getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    fun save(uri: Uri) {
        preferences.edit { putString(KEY_FOLDER_URI, uri.toString()) }
    }

    fun clear() {
        preferences.edit { remove(KEY_FOLDER_URI) }
    }

    private companion object {
        const val PREFERENCES_NAME = "folder"
        const val KEY_FOLDER_URI = "uri"
    }
}

class RecordingScanner(context: Context) {
    private val resolver = context.contentResolver

    fun scan(treeUri: Uri): List<Recording> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val recordings = mutableListOf<Recording>()

        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            var rows = 0
            while (cursor.moveToNext()) {
                if (++rows > MAX_FOLDER_DOCUMENTS) throw RecordingScanLimitException()
                val name = cursor.getString(nameIndex) ?: continue
                if (!isM4a(name)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                if (name.length > 512 || uri.toString().length > 2_048) {
                    throw RecordingScanLimitException()
                }

                recordings += Recording(
                    uri = uri,
                    name = name,
                    size = cursor.getLong(sizeIndex).coerceAtLeast(0),
                    lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0),
                )
            }
        }

        return recordings.distinctBy(Recording::uri).sortedByDescending(Recording::lastModified)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

internal const val MAX_FOLDER_DOCUMENTS = 10_000

internal class RecordingScanLimitException : IOException()

class TranscriptStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(context.filesDir.resolve("transcripts"))

    fun exists(key: String, profile: TranscriptionProfile): Boolean =
        profileFiles(key, profile).any(File::isFile)

    fun read(key: String, profile: TranscriptionProfile): String? =
        profileFiles(key, profile).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)

    fun readAll(key: String): Map<TranscriptionProfile, String> = buildMap {
        for (profile in TranscriptionProfile.ALL) {
            read(key, profile)?.let { put(profile, it) }
        }
    }

    fun keysWithTranscripts(): Set<String> = directory.listFiles().orEmpty().asSequence()
        .filter { it.name.endsWith(".txt") && it.isFile }
        // Recording keys are SHA-256 hex; a hyphen starts the profile suffix.
        .map { it.name.removeSuffix(".txt").substringBefore('-') }
        .toSet()

    fun delete(key: String, profile: TranscriptionProfile): Boolean =
        profileFiles(key, profile).all { !it.exists() || it.delete() }

    fun checkCapacity(key: String, profile: TranscriptionProfile) = synchronized(writeLock) {
        requireCapacity(directory.resolve(transcriptFileName(key, profile)), MAX_TRANSCRIPT_BYTES)
    }

    fun write(key: String, transcript: String, profile: TranscriptionProfile) = synchronized(writeLock) {
        require(transcript.isNotBlank()) { "Transcript is blank" }
        val bytes = transcriptBytes(transcript)
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create transcript directory" }
        val target = directory.resolve(transcriptFileName(key, profile))
        requireCapacity(target, bytes.size)
        writeAtomically(target, bytes)
    }

    private fun requireCapacity(target: File, bytes: Int) {
        var files = 0
        var total = 0L
        if (directory.exists()) {
            Files.newDirectoryStream(directory.toPath(), "*.txt").use { entries ->
                for (entry in entries) {
                    if (entry.toFile() == target || !Files.isRegularFile(entry)) continue
                    files++
                    val size = Files.size(entry)
                    if (files >= MAX_TRANSCRIPT_FILES || size > MAX_TRANSCRIPT_STORE_BYTES - total) {
                        throw TranscriptStorageException()
                    }
                    total += size
                }
            }
        }
        if (bytes > MAX_TRANSCRIPT_STORE_BYTES - total) throw TranscriptStorageException()
    }

    private fun profileFiles(key: String, profile: TranscriptionProfile): List<File> = buildList {
        add(directory.resolve(transcriptFileName(key, profile)))
        legacyTranscriptFileName(key, profile)?.let { add(directory.resolve(it)) }
    }.distinct()

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = directory.resolve("${target.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val writeLock = Any()
    }
}

private val WHITESPACE = Regex("\\s+")
private val BLANK_LINE = Regex("\\n\\s*\\n")
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
private const val SENTENCES_PER_PARAGRAPH = 3

private val LEGACY_LOCAL_PROFILE = TranscriptionProfile.LOCAL_CZECH

private val LEGACY_CLOUD_PROFILES = mapOf(
    TranscriptionProfile(
        TranscriptionModel.OPENAI_GPT_4O,
        TranscriptionLanguage.CZECH,
    ) to "openai",
    TranscriptionProfile(
        TranscriptionModel.GEMINI_3_7_FLASH,
        TranscriptionLanguage.CZECH,
    ) to "gemini",
    TranscriptionProfile(
        TranscriptionModel.XAI_SPEECH_TO_TEXT,
        TranscriptionLanguage.CZECH,
    ) to "xai",
    TranscriptionProfile(
        TranscriptionModel.GROQ_LARGE_V3,
        TranscriptionLanguage.CZECH,
    ) to "groq",
)
