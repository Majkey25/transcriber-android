package cz.majkey.prepis

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudLogicTest {
    @Test
    fun `bounded copies read only one excess proof byte`() {
        val input = ByteArrayInputStream(ByteArray(65_536))
        val output = ByteArrayOutputStream()
        assertFailsWith<IOException> { copyBounded(input, output, 1024) }
        assertEquals(1025, 65_536 - input.available())
        assertTrue(output.size() <= 1024)
        assertFailsWith<IOException> { copyBounded(input, output, -1) }
        assertEquals(1025, 65_536 - input.available())
    }

    @Test
    fun `bounded copies accept exact size and reject excess before writing it`() {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(65_536) { 42 }
        assertEquals(bytes.size.toLong(), copyBounded(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertEquals(bytes.size, output.size())
        assertFailsWith<IOException> {
            copyBounded(ByteArrayInputStream(bytes + 1), output, bytes.size.toLong())
        }
        assertEquals(bytes.size * 2, output.size())
        val partial = ByteArrayOutputStream().apply { write(byteArrayOf(1, 2)) }
        assertEquals(2L, copyBounded(ByteArrayInputStream(byteArrayOf(3, 4)), partial, 4L - partial.size()))
        assertEquals(4, partial.size())
        assertFailsWith<IOException> { copyBounded(ByteArrayInputStream(byteArrayOf(5)), partial, 0) }
        assertEquals(4, partial.size())
    }

    @Test
    fun `provider IDs accept only the fixed allowlist`() {
        assertEquals(CloudProvider.OPENAI, CloudProvider.fromId("openai"))
        assertEquals(CloudProvider.GEMINI, CloudProvider.fromId("gemini"))
        assertEquals(CloudProvider.XAI, CloudProvider.fromId("xai"))
        assertEquals(CloudProvider.GROQ, CloudProvider.fromId("groq"))
        assertFailsWith<IllegalArgumentException> { CloudProvider.fromId("custom") }
    }

    @Test
    fun `API key validation trims but rejects line breaks`() {
        assertEquals("sk-example123", validateApiKey("  sk-example123  "))
        assertFailsWith<IllegalArgumentException> { validateApiKey("short") }
        assertFailsWith<IllegalArgumentException> { validateApiKey("valid-key\nAuthorization: bad") }
        assertFailsWith<IllegalArgumentException> { validateApiKey("valid-key\u0000bad") }
        assertFailsWith<IllegalArgumentException> { validateApiKey("valid-key-ž") }
    }

    @Test
    fun `multipart filename cannot inject headers`() {
        assertEquals("evil___.m4a", safeUploadName("evil\"\r\n.m4a"))
        assertEquals("na_me-_.m4a", safeUploadName("na\\me-ž.m4a"))
        assertEquals("recording.m4a", safeUploadName(""))
        val longName = safeUploadName("a".repeat(200) + ".m4a")
        assertTrue(longName.length <= 120)
        assertTrue(longName.endsWith(".m4a"))
    }

    @Test
    fun `cloud JSON response returns nonblank text only`() {
        assertEquals("Dobrý den.", parseCloudText("""{"text":"  Dobrý den.  "}"""))
        assertFailsWith<IllegalArgumentException> { parseCloudText("""{"text":" "}""") }
        assertFailsWith<IllegalArgumentException> { parseCloudText("{}") }
    }

    @Test
    fun `Groq request uses selected model and Czech hint`() {
        val fields = multipartFields(
            TranscriptionProfile(
                TranscriptionModel.GROQ_LARGE_V3,
                TranscriptionLanguage.CZECH,
            ),
        )

        assertEquals("whisper-large-v3", fields["model"])
        assertEquals("cs", fields["language"])
        assertEquals("json", fields["response_format"])
        assertEquals("0", fields["temperature"])
    }

    @Test
    fun `auto detection omits language and invalid xAI formatting pair`() {
        val groq = multipartFields(
            TranscriptionProfile(
                TranscriptionModel.GROQ_LARGE_V3,
                TranscriptionLanguage.AUTO,
            ),
        )
        val xai = multipartFields(
            TranscriptionProfile(
                TranscriptionModel.XAI_SPEECH_TO_TEXT,
                TranscriptionLanguage.AUTO,
            ),
        )

        assertFalse("language" in groq)
        assertFalse("language" in xai)
        assertFalse("format" in xai)
    }

    @Test
    fun `OpenAI request uses selected model`() {
        val fields = multipartFields(
            TranscriptionProfile(
                TranscriptionModel.OPENAI_GPT_TRANSCRIBE,
                TranscriptionLanguage.ENGLISH,
            ),
        )

        assertEquals("gpt-transcribe", fields["model"])
        assertEquals("en", fields["language"])
        assertEquals("json", fields["response_format"])
    }

    @Test
    fun `Gemini URL and prompt use selected model and language`() {
        val profile = TranscriptionProfile(
            TranscriptionModel.GEMINI_3_7_FLASH,
            TranscriptionLanguage.CZECH,
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent",
            geminiGenerateUrl(profile),
        )
        assertTrue(geminiPrompt(profile.language).contains("Czech"))
        assertTrue(geminiPrompt(TranscriptionLanguage.AUTO).lowercase().contains("detect"))
        assertFailsWith<IllegalArgumentException> {
            geminiGenerateUrl(TranscriptionProfile.LOCAL_CZECH)
        }
    }

    @Test
    fun `provider formatting preserves paragraphs and cleans line whitespace`() {
        assertEquals(
            "First sentence. Second sentence.\n\nSpeaker 2: Third sentence.",
            formatProviderTranscript(
                "  First sentence.   Second sentence.\n\n  Speaker 2: Third sentence.  ",
            ),
        )
        assertEquals(
            "One. Two. Three.\n\nFour.",
            formatProviderTranscript("One. Two. Three. Four."),
        )
    }

    @Test
    fun `only transient provider errors are retryable`() {
        assertTrue(CloudApiException(429).retryable)
        assertTrue(CloudApiException(503).retryable)
        assertFalse(CloudApiException(401).retryable)
    }
}
