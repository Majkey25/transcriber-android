package cz.majkey.prepis

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class TranscriptAccumulatorTest {
    @Test
    fun `UTF-8 limit rejects the next chunk before changing accumulated text`() {
        val text = "é".repeat(MAX_TRANSCRIPT_BYTES / 2)
        val accumulator = TranscriptAccumulator()
        accumulator.add(text)
        assertFailsWith<TranscriptSizeException> { accumulator.add("x") }
        assertEquals(text, accumulator.finish())
    }

    @Test
    fun `chunk separator counts toward the output limit`() {
        val accumulator = TranscriptAccumulator()
        accumulator.add("a".repeat(MAX_TRANSCRIPT_BYTES - 1))
        assertFailsWith<TranscriptSizeException> { accumulator.add("b") }
        assertEquals(MAX_TRANSCRIPT_BYTES - 1, accumulator.finish().length)
    }

    @Test
    fun `empty chunks and repeated language votes do not need a duration cutoff`() {
        val accumulator = TranscriptAccumulator()
        val votes = LanguageVotes()
        repeat(100_000) {
            accumulator.add("")
            votes.add("en")
        }
        accumulator.add("Speech remains supported.")
        votes.add("cs")
        assertEquals("Speech remains supported.", accumulator.finish())
        assertEquals("en", votes.selected())
    }

    @Test
    fun `recognizer language identifiers have a finite budget`() {
        val votes = LanguageVotes()
        repeat(128) { votes.add("language$it") }
        assertFailsWith<IOException> { votes.add("another") }
        assertFailsWith<IOException> { LanguageVotes().add("a".repeat(17)) }
    }
}
