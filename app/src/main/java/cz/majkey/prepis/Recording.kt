package cz.majkey.prepis

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!isM4a(name)) continue

                recordings += Recording(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex)),
                    name = name,
                    size = cursor.getLong(sizeIndex).coerceAtLeast(0),
                    lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0),
                )
            }
        }

        return recordings.sortedByDescending(Recording::lastModified)
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

    fun write(key: String, transcript: String, profile: TranscriptionProfile) {
        require(transcript.isNotBlank()) { "Transcript is blank" }
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create transcript directory" }
        writeAtomically(directory.resolve(transcriptFileName(key, profile)), transcript)
    }

    private fun profileFiles(key: String, profile: TranscriptionProfile): List<File> = buildList {
        add(directory.resolve(transcriptFileName(key, profile)))
        legacyTranscriptFileName(key, profile)?.let { add(directory.resolve(it)) }
    }.distinct()

    private fun writeAtomically(target: File, transcript: String) {
        val temporary = directory.resolve("${target.name}.tmp")
        temporary.writeText(transcript, Charsets.UTF_8)
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
