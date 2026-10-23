package cz.majkey.prepis

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TranscriptionAdmissionsStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val local = TranscriptionProfile.LOCAL_CZECH
    private val cloud = TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH)
    private fun recording(id: Int) = Recording(Uri.parse("content://test/audio/$id"), "$id.m4a", 1, 1)
    private fun database() = SQLiteDatabase.openDatabase(
        context.getDatabasePath("transcription-admissions.db").path, null, SQLiteDatabase.OPEN_READWRITE)

    @Test
    fun `receipts survive reopening and changing metadata but preserve exact URI and profile boundaries`() {
        val original = recording(1)
        TranscriptionAdmissions(context).record(original, local)
        val reopened = TranscriptionAdmissions(context)
        assertTrue(reopened.contains(original.copy(size = 99, lastModified = 99), local))
        assertFalse(reopened.contains(original, cloud))
        assertFalse(reopened.contains(recording(2), local))
        reopened.record(original, local)
        database().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM receipts", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test
    fun `first legacy inventory suppresses every profile and leaves later new recordings automatic`() {
        FolderStore(context).save(Uri.parse("content://test/tree/recordings"))
        val store = TranscriptionAdmissions(context)
        val original = recording(1)
        assertTrue(store.contains(recording(2), cloud)) // No automatic admission before a successful inventory.
        store.baseline(listOf(original))
        for (profile in listOf(local, cloud)) {
            assertTrue(TranscriptionAdmissions(context).contains(original, profile))
            assertFalse(TranscriptionAdmissions(context).contains(recording(2), profile))
        }
        store.baseline(listOf(recording(2)))
        assertFalse(store.contains(recording(2), local))
    }

    @Test
    fun `history without a selected folder also requires a baseline without changing legacy transcripts`() {
        val original = recording(1)
        TranscriptStore(context).write(original.key, "Původní přepis.", local)
        val store = TranscriptionAdmissions(context)
        assertTrue(store.contains(original, cloud))
        store.baseline(listOf(original))
        assertTrue(store.contains(original, cloud))
        assertFalse(store.contains(recording(2), cloud))
        assertEquals("Původní přepis.", TranscriptStore(context).read(original.key, local))
    }

    @Test
    fun `full permanent history admits existing identities without eviction and rejects new ones`() {
        FolderStore(context).save(Uri.parse("content://test/tree/recordings"))
        val store = TranscriptionAdmissions(context)
        store.baseline((0 until MAX_FOLDER_DOCUMENTS).map(::recording))
        store.record(recording(0), cloud)
        assertFailsWith<TranscriptionAdmissionLimitException> { store.record(recording(MAX_FOLDER_DOCUMENTS), local) }
        assertTrue(TranscriptionAdmissions(context).contains(recording(0), cloud))
        assertFalse(store.contains(recording(MAX_FOLDER_DOCUMENTS), local))
    }

    @Test
    fun `failed legacy inventory is atomic and stays pending for a later retry`() {
        FolderStore(context).save(Uri.parse("content://test/tree/recordings"))
        val store = TranscriptionAdmissions(context)
        store.initialize()
        database().use { it.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON receipts " +
            "WHEN NEW.uri = 'content://test/audio/2' BEGIN SELECT RAISE(ABORT, 'write failed'); END") }
        assertFailsWith<TranscriptionAdmissionStorageException> { store.baseline(listOf(recording(1), recording(2))) }
        database().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM receipts", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.execSQL("DROP TRIGGER fail_receipt")
        }
        assertTrue(store.contains(recording(3), local))
        TranscriptionAdmissions(context).baseline(listOf(recording(1), recording(2)))
        assertFalse(store.contains(recording(3), local))
        assertTrue(store.contains(recording(2), cloud))
    }

    @Test
    fun `missing migration state fails closed without rebuilding the receipt history`() {
        val store = TranscriptionAdmissions(context)
        store.record(recording(1), local)
        database().use { it.execSQL("DELETE FROM migration") }
        assertFailsWith<TranscriptionAdmissionStorageException> { store.initialize() }
        assertFailsWith<TranscriptionAdmissionStorageException> { store.record(recording(2), cloud) }
        assertFailsWith<TranscriptionAdmissionStorageException> { store.contains(recording(1), local) }
        database().use { db ->
            db.rawQuery("SELECT COUNT(*) FROM receipts", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test
    fun `corrupt database is preserved instead of silently recreating empty history`() {
        TranscriptionAdmissions(context).record(recording(1), local)
        val file = context.getDatabasePath("transcription-admissions.db")
        val corrupt = ByteArray(4096) { 0x41 }
        file.writeBytes(corrupt)
        repeat(2) {
            assertFailsWith<TranscriptionAdmissionStorageException> { TranscriptionAdmissions(context).record(recording(2), local) }
            assertContentEquals(corrupt, file.readBytes())
        }
    }
}
