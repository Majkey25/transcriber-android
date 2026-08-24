package cz.majkey.prepis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptionSettingsTest {
    @Test
    fun `fresh install defaults to local Whisper Small with auto detection`() {
        assertEquals(TranscriptionModel.LOCAL_WHISPER_SMALL, TranscriptionProfile.DEFAULT.model)
        assertEquals(TranscriptionLanguage.AUTO, TranscriptionProfile.DEFAULT.language)
    }

    @Test
    fun `model and language IDs use fixed allowlists`() {
        assertEquals(
            TranscriptionModel.OPENAI_GPT_4O,
            TranscriptionModel.fromId("openai-gpt-4o-transcribe"),
        )
        assertEquals(TranscriptionLanguage.SLOVAK, TranscriptionLanguage.fromId("sk"))
        assertFailsWith<IllegalArgumentException> { TranscriptionModel.fromId("custom-model") }
        assertFailsWith<IllegalArgumentException> { TranscriptionLanguage.fromId("xx") }
    }

    @Test
    fun `auto detection omits the provider language hint`() {
        assertNull(TranscriptionLanguage.AUTO.apiCode)
        assertEquals("cs", TranscriptionLanguage.CZECH.apiCode)
        assertEquals("", localWhisperLanguage(TranscriptionLanguage.AUTO))
        assertEquals("cs", localWhisperLanguage(TranscriptionLanguage.CZECH))
    }

    @Test
    fun `local routing uses Omnilingual only for measured winning languages`() {
        assertTrue(useOmnilingual(TranscriptionLanguage.CZECH))
        assertTrue(useOmnilingual(TranscriptionLanguage.FRENCH))
        assertFalse(useOmnilingual(TranscriptionLanguage.AUTO))
        assertFalse(useOmnilingual(TranscriptionLanguage.ENGLISH))
        assertFalse(useOmnilingual(TranscriptionLanguage.GERMAN))
    }

    @Test
    fun `detected language uses the most frequent nonblank code`() {
        assertEquals("cs", selectDetectedLanguage(listOf("cs", "", "fr", "cs")))
        assertNull(selectDetectedLanguage(listOf("", " ")))
    }

    @Test
    fun `CTC chunks receive minimal readable sentence formatting`() {
        assertEquals("Dobrý den.", formatCtcPart(" dobrý den "))
        assertEquals("Bonjour!", formatCtcPart("bonjour!"))
        assertEquals("", formatCtcPart(" "))
    }

    @Test
    fun `profile IDs separate model and language results`() {
        val czech = TranscriptionProfile(
            TranscriptionModel.GROQ_LARGE_V3,
            TranscriptionLanguage.CZECH,
        )
        val english = czech.copy(language = TranscriptionLanguage.ENGLISH)
        val turbo = czech.copy(model = TranscriptionModel.GROQ_LARGE_V3_TURBO)

        assertEquals("groq-whisper-large-v3-cs", czech.id)
        assertEquals("groq-whisper-large-v3-en", english.id)
        assertEquals("groq-whisper-large-v3-turbo-cs", turbo.id)
    }

    @Test
    fun `xAI exposes only documented language hints`() {
        assertEquals(true, TranscriptionModel.XAI_SPEECH_TO_TEXT.supports(TranscriptionLanguage.CZECH))
        assertEquals(true, TranscriptionModel.XAI_SPEECH_TO_TEXT.supports(TranscriptionLanguage.AUTO))
        assertEquals(false, TranscriptionModel.XAI_SPEECH_TO_TEXT.supports(TranscriptionLanguage.SLOVAK))
        assertEquals(true, TranscriptionModel.GROQ_LARGE_V3.supports(TranscriptionLanguage.SLOVAK))
        assertFailsWith<IllegalArgumentException> {
            TranscriptionProfile.fromIds("xai-speech-to-text", "sk")
        }
    }

    @Test
    fun `transcript filenames preserve legacy results and separate new variants`() {
        val localCzech = TranscriptionProfile(
            TranscriptionModel.LOCAL_WHISPER_SMALL,
            TranscriptionLanguage.CZECH,
        )
        val groqCzech = TranscriptionProfile(
            TranscriptionModel.GROQ_LARGE_V3,
            TranscriptionLanguage.CZECH,
        )
        val groqEnglish = groqCzech.copy(language = TranscriptionLanguage.ENGLISH)

        assertEquals("recording.txt", transcriptFileName("recording", localCzech))
        assertEquals(
            "recording-groq-whisper-large-v3-cs.txt",
            transcriptFileName("recording", groqCzech),
        )
        assertEquals(
            "recording-groq-whisper-large-v3-en.txt",
            transcriptFileName("recording", groqEnglish),
        )
        assertEquals("recording-groq.txt", legacyTranscriptFileName("recording", groqCzech))
        assertNull(legacyTranscriptFileName("recording", groqEnglish))
    }
}
