package cz.majkey.prepis

import android.content.Context
import androidx.core.content.edit

enum class TranscriptionLanguage(
    val id: String,
    val apiCode: String?,
) {
    AUTO("auto", null),
    CZECH("cs", "cs"),
    ENGLISH("en", "en"),
    SLOVAK("sk", "sk"),
    GERMAN("de", "de"),
    POLISH("pl", "pl"),
    UKRAINIAN("uk", "uk"),
    RUSSIAN("ru", "ru"),
    FRENCH("fr", "fr"),
    SPANISH("es", "es"),
    ITALIAN("it", "it"),
    PORTUGUESE("pt", "pt"),
    DUTCH("nl", "nl"),
    HUNGARIAN("hu", "hu"),
    ;

    companion object {
        fun fromId(id: String): TranscriptionLanguage = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown transcription language")
    }
}

enum class TranscriptionModel(
    val id: String,
    val provider: CloudProvider?,
    val apiId: String?,
) {
    GROQ_LARGE_V3("groq-whisper-large-v3", CloudProvider.GROQ, "whisper-large-v3"),
    GROQ_LARGE_V3_TURBO(
        "groq-whisper-large-v3-turbo",
        CloudProvider.GROQ,
        "whisper-large-v3-turbo",
    ),
    GEMINI_3_7_FLASH("gemini-3-7-flash", CloudProvider.GEMINI, "gemini-3.7-flash"),
    GEMINI_3_6_FLASH("gemini-3-6-flash", CloudProvider.GEMINI, "gemini-3.6-flash"),
    OPENAI_GPT_TRANSCRIBE("openai-gpt-transcribe", CloudProvider.OPENAI, "gpt-transcribe"),
    OPENAI_GPT_4O("openai-gpt-4o-transcribe", CloudProvider.OPENAI, "gpt-4o-transcribe"),
    OPENAI_GPT_4O_MINI(
        "openai-gpt-4o-mini-transcribe",
        CloudProvider.OPENAI,
        "gpt-4o-mini-transcribe",
    ),
    OPENAI_WHISPER_1("openai-whisper-1", CloudProvider.OPENAI, "whisper-1"),
    XAI_SPEECH_TO_TEXT("xai-speech-to-text", CloudProvider.XAI, null),
    LOCAL_WHISPER_SMALL("local-whisper-small", null, null),
    ;

    val isLocal: Boolean get() = provider == null

    fun supports(language: TranscriptionLanguage): Boolean =
        this != XAI_SPEECH_TO_TEXT || language == TranscriptionLanguage.AUTO ||
            language.apiCode in XAI_LANGUAGE_CODES

    companion object {
        fun fromId(id: String): TranscriptionModel = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown transcription model")
    }
}

private val XAI_LANGUAGE_CODES = setOf(
    "cs",
    "nl",
    "en",
    "pl",
    "pt",
    "fr",
    "ru",
    "de",
    "es",
    "it",
)

data class TranscriptionProfile(
    val model: TranscriptionModel,
    val language: TranscriptionLanguage,
) {
    val id: String get() = "${model.id}-${language.id}"

    companion object {
        val LOCAL_CZECH = TranscriptionProfile(
            TranscriptionModel.LOCAL_WHISPER_SMALL,
            TranscriptionLanguage.CZECH,
        )
        val DEFAULT = TranscriptionProfile(
            TranscriptionModel.LOCAL_WHISPER_SMALL,
            TranscriptionLanguage.AUTO,
        )
        val ALL = TranscriptionModel.entries.flatMap { model ->
            TranscriptionLanguage.entries.filter(model::supports).map { language ->
                TranscriptionProfile(model, language)
            }
        }

        fun fromIds(modelId: String, languageId: String): TranscriptionProfile {
            val profile = TranscriptionProfile(
                TranscriptionModel.fromId(modelId),
                TranscriptionLanguage.fromId(languageId),
            )
            require(profile.model.supports(profile.language)) {
                "The model does not support this language"
            }
            return profile
        }
    }
}

class TranscriptionSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TranscriptionProfile {
        val model = preferences.getString(KEY_MODEL, null) ?: return TranscriptionProfile.DEFAULT
        val language = preferences.getString(KEY_LANGUAGE, null) ?: return TranscriptionProfile.DEFAULT
        return runCatching { TranscriptionProfile.fromIds(model, language) }
            .getOrDefault(TranscriptionProfile.DEFAULT)
    }

    fun save(profile: TranscriptionProfile) {
        preferences.edit {
            putString(KEY_MODEL, profile.model.id)
            putString(KEY_LANGUAGE, profile.language.id)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "transcription_settings"
        const val KEY_MODEL = "model"
        const val KEY_LANGUAGE = "language"
    }
}
