package cz.majkey.prepis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.IOException
import java.nio.file.Files

internal class TranscriptionAdmissionStorageException(cause: Exception) :
    IOException("Transcription admission history or queue could not be saved", cause)

internal class TranscriptionAdmissionLimitException :
    IOException("Transcription admission history is full")

/** Calls that admit work run under withTranscriptionAdmission. */
internal class TranscriptionAdmissions(private val context: Context) {
    fun initialize() = access { Unit }

    fun baseline(recordings: List<Recording>) = access { database ->
        if (!migrationPending(database)) return@access
        for (recording in recordings) record(database, recording.uri.toString(), "*")
        database.execSQL("UPDATE migration SET pending = 0 WHERE id = 1")
    }

    fun contains(recording: Recording, profile: TranscriptionProfile): Boolean = access {
        migrationPending(it) || contains(it, recording.uri.toString(), profile.id)
    }

    fun record(recording: Recording, profile: TranscriptionProfile) = access {
        record(it, recording.uri.toString(), profile.id)
    }

    private fun migrationPending(database: SQLiteDatabase): Boolean =
        database.rawQuery("SELECT pending FROM migration WHERE id = 1", null).use {
            if (!it.moveToFirst() || it.getInt(0) !in 0..1) {
                throw SQLiteException("Invalid transcription admission migration state")
            }
            it.getInt(0) == 1
        }

    private fun contains(database: SQLiteDatabase, uri: String, profile: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM receipts WHERE uri = ? AND profile IN (?, '*') LIMIT 1",
            arrayOf(uri, profile),
        ).use { it.moveToFirst() }

    private fun record(database: SQLiteDatabase, uri: String, profile: String) {
        if (uri.length !in 1..2_048 || profile.length !in 1..128) {
            throw IOException("Invalid transcription admission identity")
        }
        if (contains(database, uri, profile)) return
        val count = database.rawQuery("SELECT COUNT(*) FROM receipts", null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        if (count >= MAX_FOLDER_DOCUMENTS) throw TranscriptionAdmissionLimitException()
        database.insertOrThrow("receipts", null, ContentValues().apply {
            put("uri", uri)
            put("profile", profile)
        })
    }

    private fun <T> access(action: (SQLiteDatabase) -> T): T {
        try {
            Helper(context).use { helper ->
                val database = helper.writableDatabase
                database.beginTransaction()
                try {
                    migrationPending(database)
                    val result = action(database)
                    database.setTransactionSuccessful()
                    return result
                } finally {
                    database.endTransaction()
                }
            }
        } catch (exception: SQLiteException) {
            throw TranscriptionAdmissionStorageException(exception)
        } catch (exception: IOException) {
            if (exception is TranscriptionAdmissionLimitException) throw exception
            throw TranscriptionAdmissionStorageException(exception)
        }
    }

    private class Helper(private val context: Context) :
        SQLiteOpenHelper(context, "transcription-admissions.db", null, 1, {
            // The default handler deletes corrupt databases, which would reopen automatic admission.
            throw SQLiteException("Transcription admission history is corrupt")
        }) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE receipts (uri TEXT NOT NULL CHECK(length(uri) BETWEEN 1 AND 2048), " +
                "profile TEXT NOT NULL CHECK(length(profile) BETWEEN 1 AND 128), PRIMARY KEY(uri, profile))")
            database.execSQL("CREATE TABLE migration (id INTEGER PRIMARY KEY CHECK(id = 1), pending INTEGER NOT NULL CHECK(pending IN (0, 1)))")
            val transcripts = context.filesDir.resolve("transcripts")
            val history = transcripts.exists() && Files.newDirectoryStream(transcripts.toPath(), "*.txt").use {
                it.any { path -> Files.isRegularFile(path) }
            }
            database.insertOrThrow("migration", null, ContentValues().apply {
                put("id", 1)
                put("pending", if (FolderStore(context).load() != null || history) 1 else 0)
            })
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported transcription admission history version")
        }
    }
}
