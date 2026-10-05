package cz.majkey.prepis

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TranscriberTheme {
                TranscriberApp(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val folders = FolderStore(app)
    private val scanner = RecordingScanner(app)
    private val transcripts = TranscriptStore(app)
    private val queue = TranscriptionQueue(app)
    private val cloudQueue = CloudTranscriptionQueue(app)
    private val secrets = SecretStore(app)
    private val settings = TranscriptionSettingsStore(app)
    private val cloudSettingsMutex = Mutex()
    private val workManager = WorkManager.getInstance(app)
    private val recordings = MutableStateFlow<List<Recording>>(emptyList())
    private var refreshJob: Job? = null

    private val _folderUri = MutableStateFlow(folders.load())
    val folderUri: StateFlow<Uri?> = _folderUri.asStateFlow()

    private val _loading = MutableStateFlow(_folderUri.value != null)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError: StateFlow<String?> = _folderError.asStateFlow()

    private val _configuredProviders = MutableStateFlow<Set<CloudProvider>>(emptySet())
    val configuredProviders: StateFlow<Set<CloudProvider>> = _configuredProviders.asStateFlow()

    private val _profile = MutableStateFlow(settings.load())
    val profile: StateFlow<TranscriptionProfile> = _profile.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    val localWorkInfos: StateFlow<List<WorkInfo>> = workManager
        .getWorkInfosByTagFlow(TranscriptionWorker.GLOBAL_TAG)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cloudWorkInfos: StateFlow<List<WorkInfo>> = workManager
        .getWorkInfosByTagFlow(CloudTranscriptionWorker.GLOBAL_TAG)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<RecordingRow>> = combine(
        recordings,
        localWorkInfos,
        cloudWorkInfos,
        _profile,
        _configuredProviders,
    ) { files, localWork, cloudWork, profile, configured ->
        withContext(Dispatchers.IO) {
            files.map { recording ->
                recording.toRow(localWork, cloudWork, profile, configured)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectFolder(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            _folderError.value = app.getString(R.string.folder_permission_failed)
            return
        }

        viewModelScope.launch {
            try {
                val previous = folders.load()
                if (previous != uri) {
                    queue.cancelAll()
                    cloudQueue.cancelAll()
                    folders.save(uri)
                    previous?.let {
                        runCatching {
                            app.contentResolver.releasePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                    }
                }
                _folderUri.value = uri
                _folderError.value = null
                refresh()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _folderError.value = app.getString(R.string.folder_change_failed)
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _configuredProviders.value = withContext(Dispatchers.IO) {
                secrets.configuredProviders()
            }
            val uri = folders.load()
            _folderUri.value = uri
            if (uri == null) {
                recordings.value = emptyList()
                _loading.value = false
                return@launch
            }

            _loading.value = true
            _folderError.value = null
            try {
                val files = withContext(Dispatchers.IO) { scanner.scan(uri) }
                recordings.value = files
                enqueueMissing(files)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: SecurityException) {
                folders.clear()
                _folderUri.value = null
                recordings.value = emptyList()
                _folderError.value = app.getString(R.string.folder_permission_lost)
            } catch (_: Exception) {
                _folderError.value = app.getString(R.string.folder_read_failed)
            } finally {
                _loading.value = false
            }
        }
    }

    fun enqueue(recording: Recording, replace: Boolean = false) {
        enqueue(recording, _profile.value, replace)
    }

    fun enqueue(
        recording: Recording,
        profile: TranscriptionProfile,
        replace: Boolean = false,
    ) {
        viewModelScope.launch {
            if (profile.model.isLocal) {
                queue.enqueue(recording, profile, replace)
            } else if (profile.model.provider in _configuredProviders.value) {
                cloudQueue.enqueue(recording, profile, replace)
            }
        }
    }

    fun saveApiKey(provider: CloudProvider, key: String) {
        changeCloudSettings {
            _settingsMessage.value = try {
                secrets.put(provider, key)
                _configuredProviders.value = secrets.configuredProviders()
                _profile.value.takeIf { it.model.provider == provider }?.let { profile ->
                    cloudQueue.enqueueMissing(recordings.value, profile, retryErrors = true)
                }
                app.getString(R.string.api_key_saved, app.getString(provider.nameResource()))
            } catch (_: IllegalArgumentException) {
                app.getString(R.string.api_key_invalid)
            } catch (_: Exception) {
                app.getString(R.string.api_key_save_failed)
            }
        }
    }

    fun removeApiKey(provider: CloudProvider) {
        changeCloudSettings {
            settings.revokeCloudConsent()
            cloudQueue.cancelAll()
            secrets.remove(provider)
            settings.save(TranscriptionProfile.DEFAULT)
            _profile.value = settings.load()
            _configuredProviders.value = secrets.configuredProviders()
            refresh()
            _settingsMessage.value = app.getString(
                R.string.api_key_removed,
                app.getString(provider.nameResource()),
            )
        }
    }

    fun saveProfile(profile: TranscriptionProfile) {
        require(profile.model.supports(profile.language)) { "The model does not support this language" }
        changeCloudSettings {
            settings.revokeCloudConsent()
            cloudQueue.cancelAll()
            profile.model.provider?.let(settings::allowCloud)
            settings.save(profile)
            _profile.value = profile
        }
    }

    fun confirmCloudTranscription(recording: Recording, profile: TranscriptionProfile) {
        changeCloudSettings {
            if (profile.model.isLocal) {
                queue.enqueue(recording, profile)
            } else {
                cloudQueue.enqueue(recording, profile, userConfirmed = true)
            }
        }
    }

    private fun changeCloudSettings(action: suspend () -> Unit) = viewModelScope.launch {
        cloudSettingsMutex.withLock { withContext(Dispatchers.IO) { action() } }
    }

    fun clearSettingsMessage() {
        _settingsMessage.value = null
    }

    suspend fun readTranscripts(key: String): Map<TranscriptionProfile, String> =
        withContext(Dispatchers.IO) { transcripts.readAll(key) }

    private suspend fun enqueueMissing(files: List<Recording>) {
        val profile = _profile.value
        if (profile.model.isLocal) {
            queue.enqueueMissing(files, profile)
        } else if (profile.model.provider in _configuredProviders.value) {
            cloudQueue.enqueueMissing(files, profile)
        }
    }

    private fun Recording.toRow(
        localWork: List<WorkInfo>,
        cloudWork: List<WorkInfo>,
        profile: TranscriptionProfile,
        configured: Set<CloudProvider>,
    ): RecordingRow {
        val hasTranscript = transcripts.hasAny(key)
        if (transcripts.exists(key, profile)) {
            return RecordingRow(this, RecordingStatus.DONE, hasTranscript = true)
        }
        val provider = profile.model.provider
        if (provider != null && provider !in configured) {
            return RecordingRow(this, RecordingStatus.SETUP, hasTranscript)
        }

        val tagged = if (profile.model.isLocal) {
            localWork.filter { TranscriptionWorker.recordingTag(key, profile) in it.tags }
        } else {
            cloudWork.filter { CloudTranscriptionWorker.recordingTag(key, profile) in it.tags }
        }
        val active = tagged.lastOrNull { it.state == WorkInfo.State.RUNNING } ?:
            tagged.lastOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (active != null) {
            return RecordingRow(
                this,
                when {
                    !profile.model.isLocal && active.state == WorkInfo.State.RUNNING ->
                        RecordingStatus.TRANSCRIBING
                    profile.model.isLocal -> when (
                        active.progress.getString(TranscriptionWorker.KEY_PHASE)
                    ) {
                        TranscriptionWorker.PHASE_MODEL -> RecordingStatus.MODEL
                        TranscriptionWorker.PHASE_TRANSCRIBING -> RecordingStatus.TRANSCRIBING
                        else -> RecordingStatus.WAITING
                    }
                    else -> RecordingStatus.WAITING
                },
                hasTranscript,
            )
        }

        val error = tagged.lastOrNull { it.state == WorkInfo.State.SUCCEEDED }
            ?.outputData
            ?.getString(
                if (profile.model.isLocal) {
                    TranscriptionWorker.KEY_ERROR
                } else {
                    CloudTranscriptionWorker.KEY_ERROR
                },
            )
        return RecordingRow(
            this,
            when {
                error == null -> RecordingStatus.WAITING
                hasTranscript -> RecordingStatus.ERROR_WITH_TRANSCRIPT
                else -> RecordingStatus.ERROR
            },
            hasTranscript,
        )
    }
}

data class RecordingRow(
    val recording: Recording,
    val status: RecordingStatus,
    val hasTranscript: Boolean = false,
)

enum class RecordingStatus {
    WAITING,
    MODEL,
    TRANSCRIBING,
    DONE,
    ERROR,
    ERROR_WITH_TRANSCRIPT,
    SETUP,
}

@Composable
private fun TranscriberApp(model: MainViewModel) {
    val folderUri by model.folderUri.collectAsState()
    val loading by model.loading.collectAsState()
    val folderError by model.folderError.collectAsState()
    val rows by model.rows.collectAsState()
    val configuredProviders by model.configuredProviders.collectAsState()
    val profile by model.profile.collectAsState()
    val localWorkInfos by model.localWorkInfos.collectAsState()
    val cloudWorkInfos by model.cloudWorkInfos.collectAsState()
    val settingsMessage by model.settingsMessage.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val selected = rows.firstOrNull { it.recording.key == selectedKey }
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let(model::selectFolder)
    }

    when {
        settingsOpen -> SettingsScreen(
            profile = profile,
            configuredProviders = configuredProviders,
            message = settingsMessage,
            onBack = {
                model.clearSettingsMessage()
                model.refresh()
                settingsOpen = false
            },
            onProfile = model::saveProfile,
            onSave = model::saveApiKey,
            onRemove = model::removeApiKey,
        )

        selected != null -> TranscriptScreen(
            row = selected,
            selectedProfile = profile,
            configuredProviders = configuredProviders,
            localWorkInfos = localWorkInfos,
            cloudWorkInfos = cloudWorkInfos,
            readTranscripts = model::readTranscripts,
            onBack = { selectedKey = null },
            onRetranscribe = {
                model.enqueue(selected.recording, replace = true)
                selectedKey = null
            },
            onTranscribe = { selectedProfile ->
                model.confirmCloudTranscription(selected.recording, selectedProfile)
            },
        )

        folderUri == null -> FolderScreen(
            message = folderError,
            onPickFolder = { picker.launch(null) },
        )

        else -> RecordingListScreen(
            rows = rows,
            loading = loading,
            error = folderError,
            onPickFolder = { picker.launch(folderUri) },
            onAdvancedSettings = { settingsOpen = true },
            onRetry = model::refresh,
            onRecording = { row ->
                if (row.hasTranscript) {
                    selectedKey = row.recording.key
                } else if (row.status == RecordingStatus.SETUP) {
                    settingsOpen = true
                } else {
                    model.enqueue(row.recording)
                }
            },
        )
    }
}

@Composable
private fun FolderScreen(message: String?, onPickFolder: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.choose_folder_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                message ?: stringResource(R.string.choose_folder_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPickFolder) {
                Text(stringResource(R.string.choose_folder_action))
            }
            PrivacyLink()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingListScreen(
    rows: List<RecordingRow>,
    loading: Boolean,
    error: String?,
    onPickFolder: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onRetry: () -> Unit,
    onRecording: (RecordingRow) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuDescription = stringResource(R.string.menu)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics {
                            contentDescription = menuDescription
                        },
                    ) {
                        Text("⋮", fontSize = 28.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_folder)) },
                            onClick = {
                                menuOpen = false
                                onPickFolder()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.advanced_settings)) },
                            onClick = {
                                menuOpen = false
                                onAdvancedSettings()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> CenteredProgress(padding)
            error != null -> CenteredMessage(error, stringResource(R.string.try_again), padding, onRetry)
            rows.isEmpty() -> CenteredMessage(stringResource(R.string.empty_folder), null, padding, null)
            else -> LazyColumn(contentPadding = padding) {
                items(rows, key = { it.recording.key }) { row ->
                    RecordingItem(row, onRecording)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(row: RecordingRow, onRecording: (RecordingRow) -> Unit) {
    val unknownDate = stringResource(R.string.unknown_date)
    val date = remember(row.recording.lastModified, unknownDate) {
        if (row.recording.lastModified > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(row.recording.lastModified))
        } else {
            unknownDate
        }
    }
    val status = statusText(row.status)
    val description = stringResource(
        R.string.recording_description,
        row.recording.name,
        date,
        status,
    )
    ListItem(
        headlineContent = {
            Text(
                row.recording.displayName(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text("$date · $status") },
        trailingContent = {
            when (row.status) {
                RecordingStatus.MODEL, RecordingStatus.TRANSCRIBING ->
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                RecordingStatus.DONE -> Text("✓", style = MaterialTheme.typography.titleLarge)
                RecordingStatus.ERROR,
                RecordingStatus.ERROR_WITH_TRANSCRIPT,
                -> Text("!", style = MaterialTheme.typography.titleLarge)
                RecordingStatus.WAITING -> Text("…", style = MaterialTheme.typography.titleLarge)
                RecordingStatus.SETUP -> Text("⚙", style = MaterialTheme.typography.titleLarge)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRecording(row) }
            .semantics {
                contentDescription = description
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptScreen(
    row: RecordingRow,
    selectedProfile: TranscriptionProfile,
    configuredProviders: Set<CloudProvider>,
    localWorkInfos: List<WorkInfo>,
    cloudWorkInfos: List<WorkInfo>,
    readTranscripts: suspend (String) -> Map<TranscriptionProfile, String>,
    onBack: () -> Unit,
    onRetranscribe: () -> Unit,
    onTranscribe: (TranscriptionProfile) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var languageDialogOpen by remember { mutableStateOf(false) }
    var cloudDialogOpen by remember { mutableStateOf(false) }
    var transcripts by remember(row.recording.key) {
        mutableStateOf<Map<TranscriptionProfile, String>>(emptyMap())
    }
    var selectedProfileId by rememberSaveable(row.recording.key) { mutableStateOf(selectedProfile.id) }
    var requestedProfile by remember(row.recording.key) { mutableStateOf<TranscriptionProfile?>(null) }
    var loaded by remember(row.recording.key) { mutableStateOf(false) }
    val backDescription = stringResource(R.string.back)
    val menuDescription = stringResource(R.string.menu)
    val workInfos = localWorkInfos + cloudWorkInfos
    val workTag: (TranscriptionProfile) -> String = { profile ->
        if (profile.model.isLocal) {
            TranscriptionWorker.recordingTag(row.recording.key, profile)
        } else {
            CloudTranscriptionWorker.recordingTag(row.recording.key, profile)
        }
    }
    val relevantWork = workInfos.filter { info ->
        TranscriptionProfile.ALL.any { profile ->
            workTag(profile) in info.tags
        }
    }
    val activeWork = relevantWork.lastOrNull {
        it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.BLOCKED
    }
    val activeProfile = activeWork?.let { info ->
        TranscriptionProfile.ALL.firstOrNull { profile ->
            workTag(profile) in info.tags
        }
    }
    val requestedError = requestedProfile?.let { profile ->
        relevantWork.lastOrNull {
            workTag(profile) in it.tags &&
                it.state == WorkInfo.State.SUCCEEDED
        }?.outputData?.getString(
            if (profile.model.isLocal) {
                TranscriptionWorker.KEY_ERROR
            } else {
                CloudTranscriptionWorker.KEY_ERROR
            },
        )
    }

    LaunchedEffect(row.recording.key, localWorkInfos, cloudWorkInfos, requestedProfile) {
        transcripts = readTranscripts(row.recording.key)
        val selectedExists = transcripts.keys.any { it.id == selectedProfileId }
        if (!selectedExists) {
            selectedProfileId = transcripts.keys.firstOrNull { it == selectedProfile }?.id
                ?: transcripts.keys.firstOrNull()?.id
                ?: selectedProfile.id
        }
        requestedProfile?.takeIf { it in transcripts }?.let {
            selectedProfileId = it.id
            requestedProfile = null
        }
        loaded = true
    }
    val visibleProfile = transcripts.keys.firstOrNull { it.id == selectedProfileId }
    val transcript = visibleProfile?.let(transcripts::get)
    val languageOptions = TranscriptionLanguage.entries
        .filter(selectedProfile.model::supports)
        .filterNot { it == selectedProfile.language }
    val selectedModelConfigured = selectedProfile.model.isLocal ||
        selectedProfile.model.provider in configuredProviders

    if (languageDialogOpen) {
        AlertDialog(
            onDismissRequest = { languageDialogOpen = false },
            title = { Text(stringResource(R.string.choose_transcription_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    languageOptions.forEach { language ->
                        TextButton(
                            onClick = {
                                val profile = selectedProfile.copy(language = language)
                                requestedProfile = profile
                                onTranscribe(profile)
                                languageDialogOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(language.nameResource()))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languageDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (cloudDialogOpen) {
        AlertDialog(
            onDismissRequest = { cloudDialogOpen = false },
            title = { Text(stringResource(R.string.cloud_transcription)) },
            text = {
                Column {
                    Text(stringResource(R.string.cloud_upload_notice))
                    Spacer(Modifier.height(8.dp))
                    TranscriptionModel.entries.filter { model ->
                        model.provider in configuredProviders
                    }.forEach { model ->
                        val language = selectedProfile.language.takeIf(model::supports)
                            ?: TranscriptionLanguage.AUTO
                        val profile = TranscriptionProfile(model, language)
                        TextButton(
                            onClick = {
                                requestedProfile = profile
                                onTranscribe(profile)
                                cloudDialogOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(sourceName(profile))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cloudDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
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
                title = {
                    Text(
                        row.recording.displayName(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics { contentDescription = menuDescription },
                    ) {
                        Text("⋮", fontSize = 28.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_text)) },
                            enabled = transcript != null,
                            onClick = {
                                menuOpen = false
                                copyTranscript(context, transcript.orEmpty())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transcribe_again)) },
                            onClick = {
                                menuOpen = false
                                onRetranscribe()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transcribe_language)) },
                            enabled = selectedModelConfigured && activeProfile == null &&
                                languageOptions.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                languageDialogOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cloud_transcription)) },
                            enabled = configuredProviders.isNotEmpty() && activeProfile == null,
                            onClick = {
                                menuOpen = false
                                cloudDialogOpen = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> CenteredProgress(padding)
            transcript == null -> CenteredMessage(
                stringResource(R.string.transcript_read_failed),
                stringResource(R.string.back),
                padding,
                onBack,
            )
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (transcripts.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.source), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { sourceMenuOpen = true }) {
                                Text(sourceName(visibleProfile))
                            }
                            DropdownMenu(
                                expanded = sourceMenuOpen,
                                onDismissRequest = { sourceMenuOpen = false },
                            ) {
                                transcripts.keys.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(sourceName(profile)) },
                                        onClick = {
                                            selectedProfileId = profile.id
                                            sourceMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (activeProfile != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            R.string.cloud_in_progress,
                            sourceName(activeProfile),
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (requestedError != null) {
                    Text(
                        requestedError,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transcript.orEmpty(),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceName(profile: TranscriptionProfile): String =
    "${stringResource(profile.model.nameResource())} · " +
        stringResource(profile.language.nameResource())

@Composable
private fun CenteredProgress(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    action: String?,
    padding: PaddingValues,
    onAction: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun statusText(status: RecordingStatus): String = stringResource(
    when (status) {
        RecordingStatus.WAITING -> R.string.status_waiting
        RecordingStatus.MODEL -> R.string.status_model
        RecordingStatus.TRANSCRIBING -> R.string.status_transcribing
        RecordingStatus.DONE -> R.string.status_done
        RecordingStatus.ERROR -> R.string.status_error
        RecordingStatus.ERROR_WITH_TRANSCRIPT -> R.string.status_error_existing
        RecordingStatus.SETUP -> R.string.status_setup
    },
)

private fun copyTranscript(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.transcript), text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun Recording.displayName(): String = if (isM4a(name)) name.dropLast(4) else name

@Composable
private fun TranscriberTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
