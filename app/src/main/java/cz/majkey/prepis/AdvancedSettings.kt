package cz.majkey.prepis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    profile: TranscriptionProfile,
    configuredProviders: Set<CloudProvider>,
    message: String?,
    onBack: () -> Unit,
    onProfile: (TranscriptionProfile) -> Unit,
    onSave: (CloudProvider, String) -> Unit,
    onRemove: (CloudProvider) -> Unit,
) {
    val values = remember { mutableStateMapOf<CloudProvider, String>() }
    val backDescription = stringResource(R.string.back)
    var modelMenu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<TranscriptionProfile?>(null) }

    pendingProfile?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingProfile = null },
            title = { Text(stringResource(R.string.cloud_consent_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.cloud_consent_body,
                        stringResource(checkNotNull(pending.model.provider).nameResource())))
                    PrivacyLink()
                }
            },
            confirmButton = {
                TextButton(onClick = { onProfile(pending); pendingProfile = null }) {
                    Text(stringResource(R.string.cloud_consent_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProfile = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDescription },
                    ) {
                        Text("‹", fontSize = 36.sp)
                    }
                },
                title = { Text(stringResource(R.string.advanced_settings)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_transcription),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { modelMenu = true }) {
                            Text(stringResource(profile.model.nameResource()))
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            TranscriptionModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(model.nameResource())) },
                                    onClick = {
                                        val language = profile.language.takeIf(model::supports)
                                            ?: TranscriptionLanguage.AUTO
                                        val next = TranscriptionProfile(model, language)
                                        if (model.isLocal) onProfile(next) else pendingProfile = next
                                        modelMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(profile.model.descriptionResource()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { languageMenu = true }) {
                            Text(stringResource(profile.language.nameResource()))
                        }
                        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            TranscriptionLanguage.entries.filter(profile.model::supports).forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(language.nameResource())) },
                                    onClick = {
                                        onProfile(profile.copy(language = language))
                                        languageMenu = false
                                    },
                                )
                            }
                        }
                    }
                    profile.model.provider
                        ?.takeIf { it !in configuredProviders }
                        ?.let { provider ->
                            Text(
                                stringResource(
                                    R.string.settings_key_required,
                                    stringResource(provider.nameResource()),
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    Text(
                        stringResource(R.string.advanced_privacy_notice),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PrivacyLink()
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.settings_api_keys),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(CloudProvider.entries, key = CloudProvider::id) { provider ->
                val value = values[provider].orEmpty()
                val configured = provider in configuredProviders
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(provider.nameResource()), style = MaterialTheme.typography.titleMedium)
                    Text(providerDescription(provider), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(if (configured) R.string.configured else R.string.not_configured),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (it.length <= MAX_KEY_LENGTH) values[provider] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.api_key)) },
                        placeholder = {
                            Text(
                                stringResource(
                                    if (configured) {
                                        R.string.api_key_configured
                                    } else {
                                        R.string.api_key_not_configured
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Row {
                        TextButton(
                            onClick = {
                                onSave(provider, value)
                                values[provider] = ""
                            },
                            enabled = value.trim().length >= MIN_KEY_LENGTH &&
                                value.trim().none(Char::isWhitespace),
                        ) {
                            Text(stringResource(R.string.save))
                        }
                        TextButton(onClick = { onRemove(provider) }, enabled = configured) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun providerDescription(provider: CloudProvider): String = stringResource(
    when (provider) {
        CloudProvider.OPENAI -> R.string.provider_openai_description
        CloudProvider.GEMINI -> R.string.provider_gemini_description
        CloudProvider.XAI -> R.string.provider_xai_description
        CloudProvider.GROQ -> R.string.provider_groq_description
    },
)

internal fun TranscriptionModel.nameResource(): Int = when (this) {
    TranscriptionModel.GROQ_LARGE_V3 -> R.string.model_groq_large_v3
    TranscriptionModel.GROQ_LARGE_V3_TURBO -> R.string.model_groq_large_v3_turbo
    TranscriptionModel.GEMINI_3_7_FLASH -> R.string.model_gemini_3_7_flash
    TranscriptionModel.GEMINI_3_6_FLASH -> R.string.model_gemini_3_6_flash
    TranscriptionModel.OPENAI_GPT_TRANSCRIBE -> R.string.model_openai_gpt_transcribe
    TranscriptionModel.OPENAI_GPT_4O -> R.string.model_openai_gpt_4o
    TranscriptionModel.OPENAI_GPT_4O_MINI -> R.string.model_openai_gpt_4o_mini
    TranscriptionModel.OPENAI_WHISPER_1 -> R.string.model_openai_whisper_1
    TranscriptionModel.XAI_SPEECH_TO_TEXT -> R.string.model_xai_stt
    TranscriptionModel.LOCAL_WHISPER_SMALL -> R.string.model_local_whisper_small
}

private fun TranscriptionModel.descriptionResource(): Int = when (this) {
    TranscriptionModel.GROQ_LARGE_V3 -> R.string.model_groq_large_v3_description
    TranscriptionModel.GROQ_LARGE_V3_TURBO -> R.string.model_groq_large_v3_turbo_description
    TranscriptionModel.GEMINI_3_7_FLASH,
    TranscriptionModel.GEMINI_3_6_FLASH,
    -> R.string.model_gemini_description
    TranscriptionModel.OPENAI_GPT_TRANSCRIBE,
    TranscriptionModel.OPENAI_GPT_4O,
    TranscriptionModel.OPENAI_GPT_4O_MINI,
    TranscriptionModel.OPENAI_WHISPER_1,
    -> R.string.model_openai_description
    TranscriptionModel.XAI_SPEECH_TO_TEXT -> R.string.model_xai_description
    TranscriptionModel.LOCAL_WHISPER_SMALL -> R.string.model_local_description
}

internal fun TranscriptionLanguage.nameResource(): Int = when (this) {
    TranscriptionLanguage.AUTO -> R.string.language_auto
    TranscriptionLanguage.CZECH -> R.string.language_czech
    TranscriptionLanguage.ENGLISH -> R.string.language_english
    TranscriptionLanguage.SLOVAK -> R.string.language_slovak
    TranscriptionLanguage.GERMAN -> R.string.language_german
    TranscriptionLanguage.POLISH -> R.string.language_polish
    TranscriptionLanguage.UKRAINIAN -> R.string.language_ukrainian
    TranscriptionLanguage.RUSSIAN -> R.string.language_russian
    TranscriptionLanguage.FRENCH -> R.string.language_french
    TranscriptionLanguage.SPANISH -> R.string.language_spanish
    TranscriptionLanguage.ITALIAN -> R.string.language_italian
    TranscriptionLanguage.PORTUGUESE -> R.string.language_portuguese
    TranscriptionLanguage.DUTCH -> R.string.language_dutch
    TranscriptionLanguage.HUNGARIAN -> R.string.language_hungarian
}

private const val MIN_KEY_LENGTH = 8
private const val MAX_KEY_LENGTH = 512
