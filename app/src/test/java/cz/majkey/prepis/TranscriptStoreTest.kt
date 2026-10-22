package cz.majkey.prepis

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranscriptStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `full file store preserves history and permits replacing an existing result`() {
        val directory = temporary.newFolder()
        val store = TranscriptStore(directory)
        store.write("current", "Original", TranscriptionProfile.DEFAULT)
        repeat(MAX_TRANSCRIPT_FILES - 1) { directory.resolve("history-$it.txt").createNewFile() }

        assertFailsWith<TranscriptStorageException> { store.checkCapacity("new", TranscriptionProfile.DEFAULT) }
        assertFailsWith<TranscriptStorageException> { store.write("new", "New", TranscriptionProfile.DEFAULT) }
        assertEquals(MAX_TRANSCRIPT_FILES, directory.listFiles()!!.size)
        assertEquals("Original", store.read("current", TranscriptionProfile.DEFAULT))
        store.checkCapacity("current", TranscriptionProfile.DEFAULT)
        store.write("current", "Updated", TranscriptionProfile.DEFAULT)
        assertEquals("Updated", store.read("current", TranscriptionProfile.DEFAULT))
        assertEquals(MAX_TRANSCRIPT_FILES, directory.listFiles()!!.size)
    }

    @Test
    fun `concurrent stores cannot exceed the total byte budget`() {
        val directory = temporary.newFolder()
        val history = directory.resolve("history.txt")
        RandomAccessFile(history, "rw").use {
            it.writeUTF("Kept")
            it.setLength(MAX_TRANSCRIPT_STORE_BYTES - MAX_TRANSCRIPT_BYTES)
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { index ->
                executor.submit<Boolean> {
                    start.await()
                    try {
                        TranscriptStore(directory).write("new-$index", "a".repeat(MAX_TRANSCRIPT_BYTES), TranscriptionProfile.DEFAULT)
                        true
                    } catch (_: TranscriptStorageException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(MAX_TRANSCRIPT_STORE_BYTES, directory.listFiles()!!.sumOf(File::length))
            RandomAccessFile(history, "r").use { assertEquals("Kept", it.readUTF()) }
            assertFailsWith<TranscriptStorageException> {
                TranscriptStore(directory).checkCapacity("another", TranscriptionProfile.DEFAULT)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `oversized UTF-8 result cannot replace a saved transcript`() {
        val directory = temporary.newFolder()
        val store = TranscriptStore(directory)
        store.write("current", "Saved", TranscriptionProfile.DEFAULT)
        assertFailsWith<TranscriptSizeException> {
            store.write("current", "é".repeat(MAX_TRANSCRIPT_BYTES / 2 + 1), TranscriptionProfile.DEFAULT)
        }
        assertEquals("Saved", store.read("current", TranscriptionProfile.DEFAULT))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun `one listing finds all profiles and next snapshot reflects writes and deletes`() {
        val directory = object : File(temporary.newFolder(), "transcripts") {
            var listings = 0
            override fun listFiles(): Array<File>? = super.listFiles().also { listings++ }
        }
        val store = TranscriptStore(directory)
        val local = recordingKey("fixture://local", 1, 1)
        val legacy = recordingKey("fixture://legacy", 1, 1)
        val missing = recordingKey("fixture://missing", 1, 1)
        assertTrue(store.keysWithTranscripts().isEmpty())
        store.write(local, "Local transcript.", TranscriptionProfile.DEFAULT)
        directory.resolve("$legacy-openai.txt").writeText("Legacy transcript.")
        directory.resolve("$missing.txt.tmp").writeText("Incomplete transcript.")
        assertTrue(directory.resolve("$missing.txt").mkdir())
        directory.listings = 0

        val snapshot = store.keysWithTranscripts()
        assertEquals(setOf(local, legacy), snapshot)
        assertEquals(1, directory.listings)
        assertTrue(store.exists(local, TranscriptionProfile.DEFAULT))
        assertFalse(store.exists(legacy, TranscriptionProfile.DEFAULT))
        assertTrue(store.delete(local, TranscriptionProfile.DEFAULT))
        assertEquals(setOf(legacy), store.keysWithTranscripts())
        store.write(local, "New transcript.", TranscriptionProfile.LOCAL_CZECH)
        assertEquals(setOf(local, legacy), store.keysWithTranscripts())
        assertEquals("New transcript.", store.read(local, TranscriptionProfile.LOCAL_CZECH))
        assertTrue(store.delete(legacy, TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH)))
        assertEquals(setOf(local), store.keysWithTranscripts())
    }
}
