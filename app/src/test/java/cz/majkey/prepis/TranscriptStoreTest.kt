package cz.majkey.prepis

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

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
