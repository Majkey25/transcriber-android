package cz.majkey.prepis

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecordingScannerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val tree = Uri.parse("content://recordings/tree/folder")
    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    @Test
    fun `oversized inventories including non audio rows fail without a partial list`() {
        val cursor = MatrixCursor(columns)
        repeat(MAX_FOLDER_DOCUMENTS + 1) { cursor.newRow().add("$it").add("other.txt").add(1).add(1) }
        useCursor(cursor)
        assertFailsWith<RecordingScanLimitException> { RecordingScanner(context).scan(tree) }
        assertTrue(cursor.isClosed)
    }

    @Test
    fun `duplicate document rows are unique and changed files receive new versions`() {
        fun scan(size: Long): Recording {
            val cursor = MatrixCursor(columns)
            repeat(2) { cursor.newRow().add("one").add("one.m4a").add(size).add(1) }
            useCursor(cursor)
            return RecordingScanner(context).scan(tree).single()
        }
        val original = scan(1)
        val updated = scan(2)
        assertEquals(original.uri, updated.uri)
        assertTrue(original.key != updated.key)
    }

    @Test
    fun `oversized metadata is refused before it reaches WorkManager`() {
        val cursor = MatrixCursor(columns)
        cursor.newRow().add("one").add("a".repeat(512) + ".m4a").add(1).add(1)
        useCursor(cursor)
        assertFailsWith<RecordingScanLimitException> { RecordingScanner(context).scan(tree) }
    }

    private fun useCursor(cursor: Cursor) {
        ShadowContentResolver.registerProviderInternal("recordings", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor = cursor
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?,
                selectionArgs: Array<out String>?) = 0
        })
    }
}
