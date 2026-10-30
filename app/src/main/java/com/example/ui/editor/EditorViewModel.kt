package com.example.ui.editor

import android.app.Application
import android.net.Uri
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.KothaPadApp
import com.example.data.backup.BackupManager
import com.example.data.backup.BackupSummary
import com.example.data.backup.ParsedBackupData
import com.example.data.model.AudioSourceType
import com.example.data.model.DictationMode
import com.example.data.model.DictationState
import com.example.data.model.InsertionMode
import com.example.data.model.KeyActionType
import com.example.data.model.KeyboardKey
import com.example.data.model.KeyboardLayout
import com.example.data.model.LanguageItem
import com.example.data.model.Note
import com.example.data.model.SpokenCommand
import com.example.data.model.Stamp
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.NoteRepository
import com.example.data.repository.SpokenCommandRepository
import com.example.data.repository.StampRepository
import com.example.engine.dictation.SpeechSupervisor
import com.example.engine.pipeline.PipelineContext
import com.example.engine.pipeline.StampProcessor
import com.example.engine.pipeline.TextPipeline
import com.example.service.DictationService
import com.example.ui.keyboard.KeyboardLayouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class EditorViewModel(
    application: Application,
    private val noteRepository: NoteRepository,
    private val stampRepository: StampRepository,
    private val spokenCommandRepository: SpokenCommandRepository,
    private val preferencesManager: PreferencesManager,
    private val speechSupervisor: SpeechSupervisor,
    val vaultManager: com.example.data.vault.VaultManager = (application as KothaPadApp).vaultManager,
    val settingsRepository: com.example.data.vault.SettingsRepository = (application as KothaPadApp).settingsRepository,
    val vaultNoteRepository: com.example.data.vault.VaultNoteRepository = (application as KothaPadApp).vaultNoteRepository,
    val vaultMigrationManager: com.example.data.vault.VaultMigrationManager = (application as KothaPadApp).vaultMigrationManager
) : AndroidViewModel(application) {

    // Vault State
    val vaultState: StateFlow<com.example.data.vault.VaultState> = vaultManager.vaultState
    val showFinishSetupDialog = MutableStateFlow(false)

    // Vault Migration Dialog & State
    val showMigrationPromptDialog = MutableStateFlow(false)
    val showVaultConflictPromptDialog = MutableStateFlow(false)
    val isMigrating = MutableStateFlow(false)
    val migrationProgress = MutableStateFlow<String?>(null)
    val migrationError = MutableStateFlow<String?>(null)
    private var pendingVaultDoc: androidx.documentfile.provider.DocumentFile? = null

    // External change conflict dialog
    val showExternalConflictDialog = MutableStateFlow(false)
    val pendingExternalChange = MutableStateFlow<com.example.data.vault.ExternalNoteChange?>(null)

    // Vault Scanner State
    val isVaultScanning: StateFlow<Boolean> = vaultNoteRepository.isScanning
    val vaultScanProgress: StateFlow<String?> = vaultNoteRepository.scanProgress
    val vaultSaveError: StateFlow<String?> = vaultNoteRepository.lastSaveError
    val safeModeWarning: StateFlow<String?> = vaultNoteRepository.vaultConfigStore.safeModeWarning

    // Notes Data
    val allNotes: StateFlow<List<Note>> = noteRepository.allNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaultNotesCount: StateFlow<Int> = allNotes
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeNotes: StateFlow<List<Note>> = noteRepository.activeNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedNotes: StateFlow<List<Note>> = noteRepository.archivedNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashedNotes: StateFlow<List<Note>> = noteRepository.trashedNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStamps: StateFlow<List<Stamp>> = stampRepository.allStamps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashAutoEmptyDays: StateFlow<Int> = preferencesManager.trashAutoEmptyDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)

    val defaultNewNoteFolder: StateFlow<String> = preferencesManager.defaultNewNoteFolder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "General")

    private val _currentNote = MutableStateFlow<Note?>(null)
    val currentNote: StateFlow<Note?> = _currentNote.asStateFlow()

    // Editor Text State
    private val _textFieldValue = MutableStateFlow(TextFieldValue())
    val textFieldValue: StateFlow<TextFieldValue> = _textFieldValue.asStateFlow()

    // Undo / Redo in-memory stack
    private val undoStack = mutableListOf<TextFieldValue>()
    private val redoStack = mutableListOf<TextFieldValue>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // Reading Mode State
    private val _isReadingMode = MutableStateFlow(false)
    val isReadingMode: StateFlow<Boolean> = _isReadingMode.asStateFlow()

    private val _editorFocusTrigger = MutableStateFlow(0L)
    val editorFocusTrigger: StateFlow<Long> = _editorFocusTrigger.asStateFlow()

    fun triggerEditorFocus() {
        _editorFocusTrigger.value = System.currentTimeMillis()
    }

    fun setReadingMode(enabled: Boolean) {
        if (enabled && dictationState.value !is DictationState.Idle) {
            return
        }
        _isReadingMode.value = enabled
        settingsRepository.updateSettings { it.copy(readingModeEnabled = enabled) }
    }

    fun toggleReadingMode() {
        setReadingMode(!_isReadingMode.value)
    }

    // Supervisor states
    val dictationState: StateFlow<DictationState> = speechSupervisor.state
    val partialText: StateFlow<String> = speechSupervisor.partialText
    val audioLevelRms: StateFlow<Float> = speechSupervisor.audioLevelRms
    val audioSourceType: StateFlow<AudioSourceType> = speechSupervisor.audioSourceDetector.currentSource
    val isOnDevice: StateFlow<Boolean> = speechSupervisor.isOnDevice

    // Preferences Flows
    val defaultLanguage: StateFlow<String> = preferencesManager.defaultLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "bn-BD")
    val favoriteLanguages: StateFlow<Set<String>> = preferencesManager.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf("bn-BD", "bn-IN", "en-US"))
    val appTheme: StateFlow<String> = preferencesManager.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "AMOLED")
    val editorFontSize: StateFlow<Float> = preferencesManager.editorFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 18f)
    val editorLineSpacing: StateFlow<Float> = preferencesManager.editorLineSpacing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.5f)
    val editorFontFamily: StateFlow<String> = preferencesManager.editorFontFamily
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "BENGALI")
    val dictationModePref: StateFlow<String> = preferencesManager.dictationMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "CONTINUOUS")
    val timedMinutesPref: StateFlow<Int> = preferencesManager.timedMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)
    val pauseToleranceSecPref: StateFlow<Int> = preferencesManager.pauseToleranceSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)
    val idleAutoStopMinPref: StateFlow<Int> = preferencesManager.idleAutoStopMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val muteBeepMethodPref: StateFlow<String> = preferencesManager.muteBeepMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MEDIA_SYSTEM")
    val insertionModePref: StateFlow<String> = preferencesManager.insertionMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "AT_CURSOR")
    val offlinePreferredPref: StateFlow<Boolean> = preferencesManager.offlinePreferred
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val keepScreenOnPref: StateFlow<Boolean> = preferencesManager.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val pipelineSpacingPref: StateFlow<Boolean> = preferencesManager.pipelineSpacing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val pipelineAutoCapPref: StateFlow<Boolean> = preferencesManager.pipelineAutoCap
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val pipelineCommandsPref: StateFlow<Boolean> = preferencesManager.pipelineCommands
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val onboardingCompletedPref: StateFlow<Boolean> = preferencesManager.onboardingCompleted
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferencesManager.isOnboardingCompletedSync())
    val showWordCountPref: StateFlow<Boolean> = preferencesManager.showWordCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val showNoteMetricsInList: StateFlow<Boolean> = preferencesManager.showNoteMetricsInList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val noteMetricsStyle: StateFlow<String> = preferencesManager.noteMetricsStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "COMPACT")
    val noteMetricsOrder: StateFlow<String> = preferencesManager.noteMetricsOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.ui.notes.NoteMetricsConfig.DEFAULT_ORDER_STRING)
    val noteMetricsHiddenItems: StateFlow<Set<String>> = preferencesManager.noteMetricsHiddenItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val expandedFolders: StateFlow<Set<String>> = preferencesManager.expandedFolders
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferencesManager.getExpandedFoldersSync())
    val customFolders: StateFlow<Set<String>> = preferencesManager.customFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val noteSortOption: StateFlow<String> = preferencesManager.noteSortOption
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferencesManager.getNoteSortOptionSync())

    fun setNoteSortOption(option: String) {
        viewModelScope.launch {
            preferencesManager.setNoteSortOption(option)
        }
    }

    // Custom Theme Colors
    val customPrimaryColor: StateFlow<Int> = preferencesManager.customPrimaryColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFFFF7043.toInt())
    val customBackgroundColor: StateFlow<Int> = preferencesManager.customBackgroundColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFF000000.toInt())
    val customSurfaceColor: StateFlow<Int> = preferencesManager.customSurfaceColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFF121212.toInt())
    val customTextColor: StateFlow<Int> = preferencesManager.customTextColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFFFFFFFF.toInt())
    val customSecondaryColor: StateFlow<Int> = preferencesManager.customSecondaryColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFFFF8A65.toInt())

    // Typography Ext Flows
    val customFontPath: StateFlow<String?> = preferencesManager.customFontPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val customFontName: StateFlow<String> = preferencesManager.customFontName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val editorLetterSpacing: StateFlow<Float> = preferencesManager.editorLetterSpacing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
    val editorParagraphSpacing: StateFlow<Int> = preferencesManager.editorParagraphSpacing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)
    val editorTextAlignment: StateFlow<String> = preferencesManager.editorTextAlignment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "LEFT")
    val editorMarginDp: StateFlow<Int> = preferencesManager.editorMarginDp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 16)
    val editorMaxWidthDp: StateFlow<Int> = preferencesManager.editorMaxWidthDp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Toolbar Settings Flows
    val toolbarItemOrder: StateFlow<String> = preferencesManager.toolbarItemOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DICTATION_STATUS,METRICS,KEYBOARD_MODE,TEXT_SIZE,TEXT_STYLE,AUDIO_SOURCE")
    val toolbarHiddenItems: StateFlow<Set<String>> = preferencesManager.toolbarHiddenItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Mic Button & Cluster Flows
    val micButtonSize: StateFlow<String> = preferencesManager.micButtonSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MEDIUM")
    val micButtonShape: StateFlow<String> = preferencesManager.micButtonShape
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "CIRCLE")
    val micButtonColor: StateFlow<String> = preferencesManager.micButtonColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DEFAULT")
    val micButtonPosition: StateFlow<String> = preferencesManager.micButtonPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "RIGHT")
    val micButtonOffsetY: StateFlow<Int> = preferencesManager.micButtonOffsetY
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)

    // Visual Polish, Animation & Haptics Flows
    val statusChipStyle: StateFlow<String> = preferencesManager.statusChipStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "PILL")
    val uiDensity: StateFlow<String> = preferencesManager.uiDensity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "NORMAL")
    val cornerRadiusDp: StateFlow<Int> = preferencesManager.cornerRadiusDp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 12)
    val dictationAnimationStyle: StateFlow<String> = preferencesManager.dictationAnimationStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "PULSE_RING")
    val hapticsLevel: StateFlow<String> = preferencesManager.hapticsLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MEDIUM")

    // Keyboard Settings Flows
    val keyboardKeyHeightDp: StateFlow<Int> = preferencesManager.keyboardKeyHeightDp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 40)
    val keyboardFontSizeSp: StateFlow<Int> = preferencesManager.keyboardFontSizeSp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val keyboardHaptic: StateFlow<Boolean> = preferencesManager.keyboardHaptic
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val keyboardHoldBehavior: StateFlow<String> = preferencesManager.keyboardHoldBehavior
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "CUSTOMIZE")
    val keyboardEditMode: StateFlow<Boolean> = preferencesManager.keyboardEditMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Markdown Settings Flow
    val markdownSettingsRepository = com.example.markdown.MarkdownSettingsRepository(preferencesManager)
    val markdownSettings: StateFlow<com.example.markdown.MarkdownSettings> = markdownSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.markdown.MarkdownSettings())

    // Floating Controls Layout ("Side Rail") Flow
    val floatingLayoutSettings: StateFlow<com.example.data.model.FloatingLayoutSettings> = preferencesManager.floatingLayoutSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.model.FloatingLayoutSettings())

    // UI state
    // userWantsBuiltIn reflects the toggle in the toolbar / settings
    private val _userWantsBuiltInKeyboard = MutableStateFlow(true)
    val userWantsBuiltInKeyboard: StateFlow<Boolean> = _userWantsBuiltInKeyboard.asStateFlow()

    // 3x10 Layout State
    private val _keyboardLayout = MutableStateFlow(KeyboardLayouts.getDefault3x10Layout())
    val keyboardLayout: StateFlow<KeyboardLayout> = _keyboardLayout.asStateFlow()

    private val _currentScreen = MutableStateFlow("EDITOR") // "EDITOR" or "SETTINGS"
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    private val _currentDailyJournalDate = MutableStateFlow<String?>(null)
    val currentDailyJournalDate: StateFlow<String?> = _currentDailyJournalDate.asStateFlow()

    enum class NoteNavigationContext {
        NOTE_MANAGER,
        DAILY_JOURNAL
    }

    private val _noteNavigationContext = MutableStateFlow(NoteNavigationContext.NOTE_MANAGER)
    val noteNavigationContext: StateFlow<NoteNavigationContext> = _noteNavigationContext.asStateFlow()

    // Dialog & Sheet states
    val showStyleSheet = MutableStateFlow(false)
    val showLanguagePicker = MutableStateFlow(false)
    val showRenameDialog = MutableStateFlow(false)
    val showTemplatesSheet = MutableStateFlow(false)
    val editingKey = MutableStateFlow<KeyboardKey?>(null)

    // Auto-save debounce job
    private var autoSaveJob: Job? = null
    private var lastDeletedNote: Note? = null
    private var isCurrentNoteAutoCreated: Boolean = false

    init {
        // Collect supervisor finalized text
        viewModelScope.launch {
            speechSupervisor.finalCommittedText.collect { rawText ->
                processAndCommitDictatedText(rawText)
            }
        }

        // Load persisted keyboard layout from DataStore
        viewModelScope.launch {
            val v = preferencesManager.keyboardLayoutVersion.first()
            if (v < 2) {
                preferencesManager.setKeyboardLayoutJson(null)
                preferencesManager.setKeyboardLayoutVersion(2)
                _keyboardLayout.value = KeyboardLayouts.getDefault3x10Layout()
            }
            preferencesManager.keyboardLayoutJson.collect { jsonStr ->
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        _keyboardLayout.value = KeyboardLayouts.deserializeFromJson(jsonStr)
                    } catch (_: Exception) {
                        _keyboardLayout.value = KeyboardLayouts.getDefault3x10Layout()
                    }
                } else {
                    _keyboardLayout.value = KeyboardLayouts.getDefault3x10Layout()
                }
            }
        }

        // Auto-clean trash on start if configured
        viewModelScope.launch(Dispatchers.IO) {
            val days = preferencesManager.trashAutoEmptyDays.first()
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
                noteRepository.autoCleanTrash(cutoff)
            }
        }

        // Ensure returning users with existing notes are never prompted for onboarding
        viewModelScope.launch(Dispatchers.IO) {
            val existing = noteRepository.getLatestNote()
            if (existing != null && !preferencesManager.isOnboardingCompletedSync()) {
                preferencesManager.setOnboardingCompleted(true)
            }
        }

        // Load initial vault settings and notes if already selected and accessible
        viewModelScope.launch {
            if (vaultManager.vaultState.value.isAccessible) {
                val doc = vaultManager.getCurrentVaultDocumentFile()
                if (doc != null) {
                    val loaded = settingsRepository.loadVaultSettings(doc, preferencesManager)
                    _isReadingMode.value = loaded.readingModeEnabled
                    checkMicrophonePermission()
                    vaultNoteRepository.onVaultOpened(doc)
                    val ws = vaultNoteRepository.getWorkspaceData()
                    if (ws.lastActiveFolder.isNotBlank()) {
                        preferencesManager.setLastActiveFolder(ws.lastActiveFolder)
                    }
                    if (ws.expandedFolders.isNotEmpty()) {
                        preferencesManager.setExpandedFolders(ws.expandedFolders)
                    }
                    vaultNoteRepository.syncTemplatesFromVault(allStamps.value) { stamp ->
                        stampRepository.insert(stamp)
                    }
                    val today = LocalDate.now()
                    val dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val noteToLoad = vaultNoteRepository.getOrCreateDailyJournalNote(today)
                    withContext(Dispatchers.Main) {
                        openDailyJournalNote(noteToLoad, dateStr, startInEditMode = false)
                    }
                    checkAndPromptMigration(doc)
                }
            }
        }

        // Listen for external changes from other apps (e.g. Obsidian)
        viewModelScope.launch {
            vaultNoteRepository.externalNoteChange.collect { change ->
                if (change != null) {
                    val current = _currentNote.value
                    if (current != null && current.id == change.note.id) {
                        val hasUnsavedEdits = (_textFieldValue.value.text != current.content) || (autoSaveJob?.isActive == true)
                        if (!hasUnsavedEdits) {
                            // Reload silently!
                            _currentNote.value = change.note.copy(content = change.diskContent)
                            _textFieldValue.value = TextFieldValue(
                                text = change.diskContent,
                                selection = TextRange(change.diskContent.length)
                            )
                            undoStack.clear()
                            redoStack.clear()
                            updateUndoRedoStates()
                            vaultNoteRepository.clearExternalNoteChange()
                        } else {
                            pendingExternalChange.value = change
                            showExternalConflictDialog.value = true
                        }
                    } else {
                        vaultNoteRepository.clearExternalNoteChange()
                    }
                }
            }
        }

        // Keep session timing synchronized with screens
        viewModelScope.launch {
            _currentScreen.collect { screen ->
                if (screen != "EDITOR") {
                    cleanupEmptyNoteIfAbandoned(_currentNote.value)
                }
                checkAndResumeSessionTimer()
            }
        }

        // Handle files deleted externally without crashing
        viewModelScope.launch {
            allNotes.collect { notes ->
                val current = _currentNote.value
                if (current != null && notes.isNotEmpty()) {
                    val currentRel = vaultNoteRepository.getRelPathForNote(current)
                    val exists = notes.any { it.id == current.id || vaultNoteRepository.getRelPathForNote(it).equals(currentRel, ignoreCase = true) }
                    if (!exists) {
                        // Guard: never revert if note is a daily journal note or still in vault
                        if (_currentDailyJournalDate.value != null || com.example.ui.notes.isDailyJournalNote(current) || vaultNoteRepository.isNotePresentInVault(current)) {
                            return@collect
                        }
                        val activeNotesList = vaultNoteRepository.activeNotes.value
                        val next = vaultNoteRepository.getLatestActiveNote() ?: activeNotesList.firstOrNull()
                        withContext(Dispatchers.Main) {
                            if (next != null) {
                                loadNote(next)
                            } else if (activeNotesList.isEmpty() && notes.isEmpty()) {
                                createNewNote()
                            }
                        }
                    }
                }
            }
        }
    }

    private var sessionDurationJob: Job? = null
    private var isAppInForeground: Boolean = true
    private var lastPeriodicDbFlushTime: Long = 0L

    private suspend fun cleanupEmptyNoteIfAbandoned(noteToLeave: Note?) {
        if (noteToLeave == null) return
        // Daily journal notes must always remain saved and associated with their date
        if (com.example.ui.notes.isDailyJournalNote(noteToLeave) ||
            _currentDailyJournalDate.value != null ||
            _noteNavigationContext.value == NoteNavigationContext.DAILY_JOURNAL) {
            return
        }

        val currentText = _textFieldValue.value.text.trim()
        val isCompletelyEmpty = noteToLeave.content.trim().isEmpty() && currentText.isEmpty()
        val isUntitledOrAuto = isCurrentNoteAutoCreated ||
                noteToLeave.title.startsWith("Untitled", ignoreCase = true) ||
                noteToLeave.title.matches(Regex("\\d+( \\d+)?"))

        if (isCompletelyEmpty && isUntitledOrAuto && !noteToLeave.isDeleted && !noteToLeave.isArchived) {
            autoSaveJob?.cancel()
            noteRepository.delete(noteToLeave)
            vaultNoteRepository.saveWorkspace(clearLastOpenedNote = true)
            vaultNoteRepository.vaultConfigStore.flush()
            if (_currentNote.value?.id == noteToLeave.id) {
                withContext(Dispatchers.Main) {
                    _currentNote.value = null
                    _textFieldValue.value = TextFieldValue("")
                }
            }
        }
    }

    fun onAppForegroundStateChanged(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (!inForeground) {
            val current = _currentNote.value
            if (current != null && (
                com.example.ui.notes.isDailyJournalNote(current) ||
                _currentDailyJournalDate.value != null ||
                _noteNavigationContext.value == NoteNavigationContext.DAILY_JOURNAL
            )) {
                pauseSessionTimerAndPersist()
                flushAutoSave()
                return
            }

            val currentText = _textFieldValue.value.text.trim()
            val isCompletelyEmpty = (current?.content?.trim()?.isEmpty() == true) && currentText.isEmpty()
            val isUntitledOrAuto = isCurrentNoteAutoCreated ||
                    (current != null && (
                        current.title.startsWith("Untitled", ignoreCase = true) ||
                        current.title.matches(Regex("\\d+( \\d+)?"))
                    ))
            if (current != null && isCompletelyEmpty && isUntitledOrAuto && !current.isDeleted && !current.isArchived) {
                autoSaveJob?.cancel()
                (getApplication<Application>() as KothaPadApp).applicationScope.launch(Dispatchers.IO + NonCancellable) {
                    noteRepository.delete(current)
                    vaultNoteRepository.saveWorkspace(clearLastOpenedNote = true)
                    vaultNoteRepository.vaultConfigStore.flush()
                }
                _currentNote.value = null
            } else {
                pauseSessionTimerAndPersist()
                flushAutoSave()
            }
        } else {
            checkAndResumeSessionTimer()
            vaultManager.refreshAccessibility()
            viewModelScope.launch(Dispatchers.IO) {
                vaultNoteRepository.rescanIfNeeded()
            }
        }
    }

    private fun checkAndResumeSessionTimer() {
        val note = _currentNote.value
        val isEditorActive = isAppInForeground && _currentScreen.value == "EDITOR"
        if (isEditorActive && note != null) {
            startSessionTimer()
        } else {
            pauseSessionTimerAndPersist()
        }
    }

    fun loadNote(note: Note, preserveReadingMode: Boolean = true) {
        if (!preserveReadingMode) {
            _isReadingMode.value = false
        }
        val current = _currentNote.value
        if (current != null && current.id != note.id) {
            val noteToCleanup = current
            viewModelScope.launch(Dispatchers.IO) {
                cleanupEmptyNoteIfAbandoned(noteToCleanup)
            }
            if (!current.isDeleted && !current.isArchived) {
                pauseSessionTimerAndPersist()
                flushAutoSave()
            }
        } else {
            sessionDurationJob?.cancel()
            sessionDurationJob = null
            autoSaveJob?.cancel()
        }
        isCurrentNoteAutoCreated = false
        val cachedBody = vaultNoteRepository.getCachedBody(note.id)
        val noteToLoad = if (note.content.isEmpty() && !cachedBody.isNullOrEmpty()) {
            note.copy(content = cachedBody)
        } else {
            note
        }
        vaultNoteRepository.currentlyOpenNoteId = noteToLoad.id
        _currentNote.value = noteToLoad
        _textFieldValue.value = TextFieldValue(
            text = noteToLoad.content,
            selection = TextRange(noteToLoad.content.length)
        )
        undoStack.clear()
        redoStack.clear()
        updateUndoRedoStates()

        if (noteToLoad.content.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val full = vaultNoteRepository.ensureNoteContentLoaded(noteToLoad)
                if (full.content.isNotEmpty() && _currentNote.value?.id == full.id) {
                    withContext(Dispatchers.Main) {
                        if (_textFieldValue.value.text.isEmpty()) {
                            _currentNote.value = full
                            _textFieldValue.value = TextFieldValue(
                                text = full.content,
                                selection = TextRange(full.content.length)
                            )
                        }
                    }
                }
            }
        }

        if (note.folder.isNotBlank()) {
            preferencesManager.setLastActiveFolderSync(note.folder)
            viewModelScope.launch {
                preferencesManager.setLastActiveFolder(note.folder)
            }
        }

        // Persist last opened note ID and workspace relative path
        preferencesManager.setLastOpenedNoteIdSync(note.id)
        viewModelScope.launch {
            preferencesManager.setLastOpenedNoteId(note.id)
            vaultNoteRepository.onNoteOpenedForEditing(note.id)
            vaultNoteRepository.saveWorkspace(
                lastOpenedRelPath = vaultNoteRepository.getRelPathForNote(note),
                lastFolder = note.folder
            )
        }

        checkAndResumeSessionTimer()
    }

    private fun startSessionTimer() {
        sessionDurationJob?.cancel()
        sessionDurationJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000L)
                val current = _currentNote.value ?: break
                if (current.isDeleted || current.isArchived) break
                val updated = current.copy(sessionDurationSec = current.sessionDurationSec + 1L)
                _currentNote.value = updated

                val now = System.currentTimeMillis()
                if (now - lastPeriodicDbFlushTime >= 10000L) {
                    lastPeriodicDbFlushTime = now
                    viewModelScope.launch(Dispatchers.IO) {
                        val c = _currentNote.value
                        if (c != null && !c.isDeleted && !c.isArchived && c.id == updated.id) {
                            noteRepository.update(updated, writeContent = false)
                        }
                    }
                }
            }
        }
    }

    fun pauseSessionTimerAndPersist() {
        sessionDurationJob?.cancel()
        sessionDurationJob = null
        val note = _currentNote.value ?: return
        if (note.isDeleted || note.isArchived) return
        viewModelScope.launch(Dispatchers.IO) {
            noteRepository.update(note, writeContent = false)
            vaultNoteRepository.saveMetadata()
            vaultNoteRepository.vaultConfigStore.flush()
        }
    }

    fun updateTextFieldValue(newValue: TextFieldValue, isUserTyping: Boolean = true) {
        if (isUserTyping && newValue.text != _textFieldValue.value.text) {
            pushUndoState(_textFieldValue.value)
        }
        if (newValue.text.trim().isNotEmpty()) {
            isCurrentNoteAutoCreated = false
        }
        _textFieldValue.value = newValue
        triggerAutoSave(newValue.text)
    }

    fun triggerAutoSave(newContent: String) {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(800) // ~800 ms after typing stops
            val note = _currentNote.value ?: return@launch
            if (note.isDeleted || note.isArchived) return@launch
            val updated = note.copy(
                content = newContent,
                updatedAt = System.currentTimeMillis()
            )
            noteRepository.update(updated)
            _currentNote.value = updated
        }
    }

    fun flushAutoSave() {
        autoSaveJob?.cancel()
        val note = _currentNote.value ?: return
        if (note.isDeleted || note.isArchived) return
        val currentText = _textFieldValue.value.text
        (getApplication<Application>() as KothaPadApp).applicationScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                settingsRepository.flushSaves()
                vaultNoteRepository.vaultConfigStore.flush()
                val toSave = if (note.content != currentText) {
                    note.copy(content = currentText, updatedAt = System.currentTimeMillis())
                } else {
                    note
                }
                if (!toSave.isDeleted && !toSave.isArchived) {
                    noteRepository.update(toSave)
                    withContext(Dispatchers.Main) {
                        if (_currentNote.value?.id == toSave.id) {
                            _currentNote.value = toSave
                        }
                    }
                }
            } catch (_: Exception) {
                // Non-blocking error
            }
        }
    }

    // =========================================================================
    // Vault Management Actions
    // =========================================================================

    fun openVault(uri: Uri, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            flushAutoSave()
            settingsRepository.flushSaves()
            val result = vaultManager.openVaultFromUri(uri)
            result.fold(
                onSuccess = {
                    val vaultDoc = vaultManager.getCurrentVaultDocumentFile()
                    if (vaultDoc != null) {
                        val loaded = settingsRepository.loadVaultSettings(vaultDoc, preferencesManager)
                        _isReadingMode.value = loaded.readingModeEnabled
                        checkMicrophonePermission()
                        vaultNoteRepository.onVaultOpened(vaultDoc)
                        val ws = vaultNoteRepository.getWorkspaceData()
                        if (ws.lastActiveFolder.isNotBlank()) {
                            preferencesManager.setLastActiveFolder(ws.lastActiveFolder)
                        }
                        if (ws.expandedFolders.isNotEmpty()) {
                            preferencesManager.setExpandedFolders(ws.expandedFolders)
                        }
                        vaultNoteRepository.syncTemplatesFromVault(allStamps.value) { stamp ->
                            stampRepository.insert(stamp)
                        }
                        val activeNotesList = vaultNoteRepository.activeNotes.value
                        val target = if (!ws.lastOpenedNoteRelPath.isNullOrBlank()) {
                            activeNotesList.firstOrNull { !it.isDeleted && vaultNoteRepository.getRelPathForNote(it).equals(ws.lastOpenedNoteRelPath, ignoreCase = true) }
                        } else null
                        val noteToLoad = target ?: vaultNoteRepository.getLatestActiveNote() ?: activeNotesList.firstOrNull()
                        withContext(Dispatchers.Main) {
                            if (noteToLoad != null) {
                                loadNote(noteToLoad)
                            } else if (activeNotesList.isEmpty() && vaultNoteRepository.allNotes.value.isEmpty()) {
                                createNewNote()
                            }
                        }
                        checkAndPromptMigration(vaultDoc)
                    }
                    onResult?.invoke(true, null)
                },
                onFailure = { err ->
                    onResult?.invoke(false, err.message ?: "Failed to open vault")
                }
            )
        }
    }

    fun createNewVault(parentUri: Uri, vaultName: String, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            flushAutoSave()
            settingsRepository.flushSaves()
            val result = vaultManager.createNewVaultInParent(parentUri, vaultName)
            result.fold(
                onSuccess = {
                    val vaultDoc = vaultManager.getCurrentVaultDocumentFile()
                    if (vaultDoc != null) {
                        val loaded = settingsRepository.loadVaultSettings(vaultDoc, preferencesManager)
                        _isReadingMode.value = loaded.readingModeEnabled
                        checkMicrophonePermission()
                        vaultNoteRepository.onVaultOpened(vaultDoc)
                        val ws = vaultNoteRepository.getWorkspaceData()
                        if (ws.lastActiveFolder.isNotBlank()) {
                            preferencesManager.setLastActiveFolder(ws.lastActiveFolder)
                        }
                        if (ws.expandedFolders.isNotEmpty()) {
                            preferencesManager.setExpandedFolders(ws.expandedFolders)
                        }
                        val activeNotesList = vaultNoteRepository.activeNotes.value
                        val noteToLoad = vaultNoteRepository.getLatestActiveNote() ?: activeNotesList.firstOrNull()
                        withContext(Dispatchers.Main) {
                            if (noteToLoad != null) {
                                loadNote(noteToLoad)
                            } else if (activeNotesList.isEmpty() && vaultNoteRepository.allNotes.value.isEmpty()) {
                                createNewNote()
                            }
                        }
                        checkAndPromptMigration(vaultDoc)
                    }
                    onResult?.invoke(true, null)
                },
                onFailure = { err ->
                    onResult?.invoke(false, err.message ?: "Failed to create vault")
                }
            )
        }
    }

    fun switchVault(vaultInfo: com.example.data.vault.VaultInfo, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            flushAutoSave()
            settingsRepository.flushSaves()
            val uri = Uri.parse(vaultInfo.uriString)
            val result = vaultManager.openVaultFromUri(uri, vaultInfo.name)
            result.fold(
                onSuccess = {
                    val vaultDoc = vaultManager.getCurrentVaultDocumentFile()
                    if (vaultDoc != null) {
                        val loaded = settingsRepository.loadVaultSettings(vaultDoc, preferencesManager)
                        _isReadingMode.value = loaded.readingModeEnabled
                        checkMicrophonePermission()
                        vaultNoteRepository.onVaultOpened(vaultDoc)
                        val ws = vaultNoteRepository.getWorkspaceData()
                        if (ws.lastActiveFolder.isNotBlank()) {
                            preferencesManager.setLastActiveFolder(ws.lastActiveFolder)
                        }
                        if (ws.expandedFolders.isNotEmpty()) {
                            preferencesManager.setExpandedFolders(ws.expandedFolders)
                        }
                        vaultNoteRepository.syncTemplatesFromVault(allStamps.value) { stamp ->
                            stampRepository.insert(stamp)
                        }
                        val activeNotesList = vaultNoteRepository.activeNotes.value
                        val target = if (!ws.lastOpenedNoteRelPath.isNullOrBlank()) {
                            activeNotesList.firstOrNull { !it.isDeleted && vaultNoteRepository.getRelPathForNote(it).equals(ws.lastOpenedNoteRelPath, ignoreCase = true) }
                        } else null
                        val noteToLoad = target ?: vaultNoteRepository.getLatestActiveNote() ?: activeNotesList.firstOrNull()
                        withContext(Dispatchers.Main) {
                            if (noteToLoad != null) {
                                loadNote(noteToLoad)
                            } else if (activeNotesList.isEmpty() && vaultNoteRepository.allNotes.value.isEmpty()) {
                                createNewNote()
                            }
                        }
                        checkAndPromptMigration(vaultDoc)
                    }
                    onResult?.invoke(true, null)
                },
                onFailure = { err ->
                    onResult?.invoke(false, err.message ?: "Failed to switch vault")
                }
            )
        }
    }

    fun checkAndPromptMigration(vaultDoc: androidx.documentfile.provider.DocumentFile, onContinue: () -> Unit = {}) {
        viewModelScope.launch {
            val hasOld = vaultMigrationManager.hasOldInternalData()
            val isDone = vaultMigrationManager.isMigrationCompleted()
            if (hasOld && !isDone) {
                pendingVaultDoc = vaultDoc
                val hasVaultData = vaultMigrationManager.doesVaultHaveData(vaultDoc)
                if (hasVaultData) {
                    showVaultConflictPromptDialog.value = true
                } else {
                    showMigrationPromptDialog.value = true
                }
            } else {
                onContinue()
            }
        }
    }

    fun proceedWithMigration(replaceExisting: Boolean = false) {
        val vaultDoc = pendingVaultDoc ?: vaultManager.getCurrentVaultDocumentFile() ?: return
        showMigrationPromptDialog.value = false
        showVaultConflictPromptDialog.value = false
        pendingVaultDoc = null
        viewModelScope.launch {
            isMigrating.value = true
            migrationProgress.value = "Starting migration..."
            val result = vaultMigrationManager.migrateInternalDataToVault(vaultDoc, replaceExisting = replaceExisting) { progress ->
                migrationProgress.value = progress
            }
            isMigrating.value = false
            migrationProgress.value = null
            result.fold(
                onSuccess = {
                    settingsRepository.loadVaultSettings(vaultDoc, preferencesManager)
                    vaultNoteRepository.onVaultOpened(vaultDoc)
                    reloadAfterImport()
                },
                onFailure = { err ->
                    migrationError.value = err.message ?: "Migration failed"
                }
            )
        }
    }

    fun useExistingVaultData() {
        showVaultConflictPromptDialog.value = false
        showMigrationPromptDialog.value = false
        pendingVaultDoc = null
        viewModelScope.launch {
            preferencesManager.setOldDataMigrated(true)
        }
    }

    fun skipMigration() {
        showMigrationPromptDialog.value = false
        showVaultConflictPromptDialog.value = false
        pendingVaultDoc = null
        viewModelScope.launch {
            preferencesManager.setOldDataMigrated(true)
        }
    }

    fun clearMigrationError() {
        migrationError.value = null
    }

    fun resolveExternalConflict(keepMine: Boolean) {
        val change = pendingExternalChange.value
        showExternalConflictDialog.value = false
        pendingExternalChange.value = null
        vaultNoteRepository.clearExternalNoteChange()

        if (keepMine) {
            flushAutoSave()
        } else if (change != null) {
            _currentNote.value = change.note.copy(content = change.diskContent)
            _textFieldValue.value = TextFieldValue(
                text = change.diskContent,
                selection = TextRange(change.diskContent.length)
            )
            undoStack.clear()
            redoStack.clear()
            updateUndoRedoStates()
        }
    }

    fun reloadVaultFromDisk(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            flushAutoSave()
            settingsRepository.flushSaves()
            val vaultDoc = vaultManager.getCurrentVaultDocumentFile()
            if (vaultDoc != null) {
                settingsRepository.loadVaultSettings(vaultDoc, preferencesManager)
                vaultNoteRepository.startVaultScan(vaultDoc)
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun rebuildVaultIndex(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            flushAutoSave()
            settingsRepository.flushSaves()
            vaultNoteRepository.rebuildIndex()
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun deleteOldInternalData(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = vaultMigrationManager.deleteOldInternalData()
            withContext(Dispatchers.Main) {
                onResult(count)
            }
        }
    }

    fun getOldInternalNotesCount(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = vaultMigrationManager.getOldNotesCount()
            withContext(Dispatchers.Main) {
                onResult(count)
            }
        }
    }

    fun closeCurrentVault() {
        viewModelScope.launch {
            flushAutoSave()
            settingsRepository.flushSaves()
            vaultManager.closeCurrentVault()
        }
    }

    fun removeRecentVault(uriString: String) {
        vaultManager.removeFromRecentVaults(uriString)
    }

    fun onDismissInaccessibleVaultDialog() {
        vaultManager.closeCurrentVault()
    }

    fun checkMicrophonePermission() {
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            showFinishSetupDialog.value = true
        }
    }

    // In-memory Undo / Redo
    private fun pushUndoState(oldValue: TextFieldValue) {
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
        undoStack.add(oldValue)
        redoStack.clear()
        updateUndoRedoStates()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val current = _textFieldValue.value
            redoStack.add(current)
            val previous = undoStack.removeAt(undoStack.lastIndex)
            _textFieldValue.value = previous
            triggerAutoSave(previous.text)
            updateUndoRedoStates()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val current = _textFieldValue.value
            undoStack.add(current)
            val next = redoStack.removeAt(redoStack.lastIndex)
            _textFieldValue.value = next
            triggerAutoSave(next.text)
            updateUndoRedoStates()
        }
    }

    private fun updateUndoRedoStates() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // Keyboard Key Action Handler
    fun onKeyboardKey(key: KeyboardKey) {
        val currentVal = _textFieldValue.value
        val text = currentVal.text
        val selection = currentVal.selection

        when (key.actionType) {
            KeyActionType.INSERT -> {
                val output = key.outputText ?: key.label
                // Check if output contains date/time/weekday/cursor tokens
                if (output.contains("{date}") || output.contains("{time}") ||
                    output.contains("{datetime}") || output.contains("{weekday}") ||
                    output.contains("{cursor}")) {
                    val processed = StampProcessor.processTemplate(
                        template = output,
                        customDateFormat = key.dateFormat,
                        customTimeFormat = key.timeFormat
                    )
                    insertTextAtSelection(processed.outputText, processed.cursorOffsetFromStart)
                } else if (key.smartSpacing) {
                    insertSmartPunctuation(output)
                } else {
                    insertTextAtSelection(output)
                }
            }
            KeyActionType.SPACE -> {
                insertTextAtSelection(" ")
            }
            KeyActionType.ENTER -> {
                if (markdownSettings.value.markdownEnabled && markdownSettings.value.autoContinueLists) {
                    val continued = com.example.markdown.MarkdownListAutoContinuer.handleEnter(_textFieldValue.value)
                    if (continued != null) {
                        updateTextFieldValue(continued)
                    } else {
                        insertTextAtSelection("\n")
                    }
                } else {
                    insertTextAtSelection("\n")
                }
            }
            KeyActionType.NEW_PARAGRAPH -> {
                insertTextAtSelection("\n\n")
            }
            KeyActionType.BACKSPACE -> {
                if (selection.start != selection.end) {
                    val newText = text.removeRange(selection.min, selection.max)
                    updateTextFieldValue(
                        TextFieldValue(newText, TextRange(selection.min))
                    )
                } else if (selection.start > 0) {
                    val deletePos = selection.start - 1
                    val newText = text.removeRange(deletePos, deletePos + 1)
                    updateTextFieldValue(
                        TextFieldValue(newText, TextRange(deletePos))
                    )
                }
            }
            KeyActionType.DELETE_WORD -> {
                deleteLastWord()
            }
            KeyActionType.CURSOR_LEFT -> {
                if (selection.min > 0) {
                    val newPos = (selection.min - 1).coerceAtLeast(0)
                    _textFieldValue.value = currentVal.copy(selection = TextRange(newPos))
                }
            }
            KeyActionType.CURSOR_RIGHT -> {
                if (selection.max < text.length) {
                    val newPos = (selection.max + 1).coerceAtMost(text.length)
                    _textFieldValue.value = currentVal.copy(selection = TextRange(newPos))
                }
            }
            KeyActionType.CURSOR_UP -> {
                moveCursorVertically(up = true)
            }
            KeyActionType.CURSOR_DOWN -> {
                moveCursorVertically(up = false)
            }
            KeyActionType.WORD_LEFT -> {
                val cursor = selection.min
                val before = text.take(cursor).trimEnd()
                val lastSpace = before.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '।', '.', ',', '?', '!'))
                val newPos = if (lastSpace >= 0) lastSpace + 1 else 0
                _textFieldValue.value = currentVal.copy(selection = TextRange(newPos))
            }
            KeyActionType.WORD_RIGHT -> {
                val cursor = selection.max
                val after = text.substring(cursor)
                val firstSpace = after.indexOfAny(charArrayOf(' ', '\n', '\t', '।', '.', ',', '?', '!'))
                val newPos = if (firstSpace >= 0) {
                    (cursor + firstSpace + 1).coerceAtMost(text.length)
                } else text.length
                _textFieldValue.value = currentVal.copy(selection = TextRange(newPos))
            }
            KeyActionType.LINE_START -> {
                val cursor = selection.min
                val lineStart = text.lastIndexOf('\n', cursor - 1)
                val newPos = if (lineStart >= 0) lineStart + 1 else 0
                _textFieldValue.value = currentVal.copy(selection = TextRange(newPos))
            }
            KeyActionType.LINE_END -> {
                val cursor = selection.max
                val lineEnd = text.indexOf('\n', cursor)
                val newPos = if (lineEnd >= 0) lineEnd else text.length
                _textFieldValue.value = currentVal.copy(selection = TextRange(newPos))
            }
            KeyActionType.OPEN_TEMPLATES -> {
                showTemplatesSheet.value = true
            }
            KeyActionType.CHANGE_LANGUAGE -> {
                showLanguagePicker.value = true
            }
            KeyActionType.UNDO -> {
                undo()
            }
            KeyActionType.REDO -> {
                redo()
            }
            KeyActionType.SELECT_ALL -> {
                _textFieldValue.value = currentVal.copy(selection = TextRange(0, text.length))
            }
            KeyActionType.PASTE -> {
                val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val item = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                if (!item.isNullOrEmpty()) {
                    insertTextAtSelection(item)
                }
            }
            KeyActionType.TOGGLE_SYSTEM_KEYBOARD -> {
                _userWantsBuiltInKeyboard.value = !_userWantsBuiltInKeyboard.value
            }
            KeyActionType.SWITCH_LAYOUT -> {
                // Not used in 3x10 fixed grid
            }
            KeyActionType.DISMISS_KEYBOARD -> {
                _userWantsBuiltInKeyboard.value = false
            }
        }
    }

    private fun insertSmartPunctuation(mark: String) {
        val currentVal = _textFieldValue.value
        val text = currentVal.text
        val selection = currentVal.selection
        val start = selection.min
        val end = selection.max

        // Check if there is one trailing space right before the cursor
        val deleteTrailingSpace = (start > 0 && text[start - 1] == ' ')
        val actualStart = if (deleteTrailingSpace) start - 1 else start

        val insertion = "$mark "
        val newText = text.substring(0, actualStart) + insertion + text.substring(end)
        val newCursor = (actualStart + insertion.length).coerceIn(0, newText.length)

        updateTextFieldValue(
            TextFieldValue(text = newText, selection = TextRange(newCursor))
        )
    }

    private fun moveCursorVertically(up: Boolean) {
        val currentVal = _textFieldValue.value
        val text = currentVal.text
        val cursor = currentVal.selection.min
        val lines = text.split("\n")

        var currentLineIndex = 0
        var charCount = 0
        var colInLine = 0

        for ((index, line) in lines.withIndex()) {
            val lineLengthWithNewline = line.length + 1
            if (cursor <= charCount + line.length) {
                currentLineIndex = index
                colInLine = cursor - charCount
                break
            }
            charCount += lineLengthWithNewline
        }

        val targetLineIndex = if (up) currentLineIndex - 1 else currentLineIndex + 1
        if (targetLineIndex in lines.indices) {
            var targetCharOffset = 0
            for (i in 0 until targetLineIndex) {
                targetCharOffset += lines[i].length + 1
            }
            val targetLine = lines[targetLineIndex]
            val newCursor = (targetCharOffset + minOf(colInLine, targetLine.length)).coerceIn(0, text.length)
            _textFieldValue.value = currentVal.copy(selection = TextRange(newCursor))
        } else {
            // Reached start or end
            val newCursor = if (up) 0 else text.length
            _textFieldValue.value = currentVal.copy(selection = TextRange(newCursor))
        }
    }

    fun insertTextAtSelection(insertText: String, cursorRelativeOffset: Int = insertText.length) {
        val currentVal = _textFieldValue.value
        val text = currentVal.text
        val selection = currentVal.selection

        val start = selection.min
        val end = selection.max

        val newText = text.substring(0, start) + insertText + text.substring(end)
        val newCursor = (start + cursorRelativeOffset).coerceIn(0, newText.length)

        updateTextFieldValue(
            TextFieldValue(text = newText, selection = TextRange(newCursor))
        )
    }

    // Templates & Stamps
    fun insertTemplate(template: Stamp) {
        val processed = StampProcessor.processTemplate(template.templateText)
        insertTextAtSelection(processed.outputText, processed.cursorOffsetFromStart)
    }

    fun saveTemplate(template: Stamp) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = if (template.id == 0L) {
                stampRepository.insert(template)
            } else {
                stampRepository.update(template)
                template.id
            }
            vaultNoteRepository.saveTemplateFile(template.copy(id = id))
        }
    }

    fun deleteTemplate(template: Stamp) {
        viewModelScope.launch(Dispatchers.IO) {
            stampRepository.delete(template)
            vaultNoteRepository.deleteTemplateFile(template)
        }
    }

    // Keyboard Customization & Persistence
    fun updateKeyInLayout(updatedKey: KeyboardKey) {
        val current = _keyboardLayout.value
        val newRows = current.rows.map { row ->
            val newKeys = row.keys.map { k ->
                if (k.id == updatedKey.id) updatedKey else k
            }
            row.copy(keys = newKeys)
        }
        val newLayout = current.copy(rows = newRows)
        _keyboardLayout.value = newLayout

        viewModelScope.launch(Dispatchers.IO) {
            val json = KeyboardLayouts.serializeToJson(newLayout)
            preferencesManager.setKeyboardLayoutJson(json)
        }
    }

    fun resetKeyToDefault(keyToReset: KeyboardKey) {
        val defaultLayout = KeyboardLayouts.getDefault3x10Layout()
        val defaultKey = defaultLayout.rows.flatMap { it.keys }.find { it.id == keyToReset.id } ?: return
        updateKeyInLayout(defaultKey)
    }

    fun resetKeyboardLayout() {
        val defaultLayout = KeyboardLayouts.getDefault3x10Layout()
        _keyboardLayout.value = defaultLayout
        viewModelScope.launch(Dispatchers.IO) {
            preferencesManager.setKeyboardLayoutJson(null)
        }
    }

    fun importKeyboardLayoutJson(jsonStr: String): Boolean {
        return try {
            val layout = KeyboardLayouts.deserializeFromJson(jsonStr)
            _keyboardLayout.value = layout
            viewModelScope.launch(Dispatchers.IO) {
                preferencesManager.setKeyboardLayoutJson(jsonStr)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun exportKeyboardLayoutJson(): String {
        return KeyboardLayouts.serializeToJson(_keyboardLayout.value)
    }

    fun setUserWantsBuiltInKeyboard(wants: Boolean) {
        _userWantsBuiltInKeyboard.value = wants
    }

    // Dictated Text Processing via Pipeline
    private suspend fun processAndCommitDictatedText(rawSpeech: String) {
        val activeLang = _currentNote.value?.languageCode ?: defaultLanguage.value
        val activeCommands = withContext(Dispatchers.IO) {
            spokenCommandRepository.getCommandsForLanguage(if (activeLang.startsWith("bn")) "bn" else "en")
        }

        val pipeline = TextPipeline.createDefault(
            spacingEnabled = pipelineSpacingPref.value,
            autoCapEnabled = pipelineAutoCapPref.value,
            commandsEnabled = pipelineCommandsPref.value
        )

        val currentVal = _textFieldValue.value
        val preceding = currentVal.text.take(currentVal.selection.min)

        val context = PipelineContext(
            languageCode = activeLang,
            precedingText = preceding,
            activeCommands = activeCommands,
            onTriggerAction = { actionCode ->
                handleSpokenAction(actionCode)
            }
        )

        val processedText = pipeline.execute(rawSpeech, context)

        if (processedText.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                when (insertionModePref.value) {
                    "APPEND_END" -> {
                        val currentText = _textFieldValue.value.text
                        val separator = if (currentText.isNotEmpty() && !currentText.endsWith(" ") && !currentText.endsWith("\n")) " " else ""
                        val finalText = currentText + separator + processedText
                        updateTextFieldValue(
                            TextFieldValue(finalText, TextRange(finalText.length))
                        )
                    }
                    "REPLACE_SELECTION", "AT_CURSOR" -> {
                        insertTextAtSelection(processedText)
                    }
                    else -> insertTextAtSelection(processedText)
                }
            }
        }
    }

    private fun handleSpokenAction(actionCode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            when (actionCode) {
                "DELETE_LAST_WORD" -> {
                    deleteLastWord()
                }
                "UNDO" -> {
                    undo()
                }
                "STOP_DICTATION" -> {
                    stopDictation()
                }
            }
        }
    }

    private fun deleteLastWord() {
        val currentVal = _textFieldValue.value
        val text = currentVal.text
        val cursor = currentVal.selection.min
        if (cursor <= 0) return

        val beforeCursor = text.substring(0, cursor)
        val trimmed = beforeCursor.trimEnd()
        val lastSpaceIndex = trimmed.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '।', '.', ',', '?', '!'))

        val newCursor = if (lastSpaceIndex >= 0) lastSpaceIndex + 1 else 0
        val newText = text.substring(0, newCursor) + text.substring(cursor)

        updateTextFieldValue(
            TextFieldValue(newText, TextRange(newCursor))
        )
    }

    // Dictation Supervisor Controls
    fun toggleDictation() {
        when (dictationState.value) {
            is DictationState.Idle -> startDictation()
            is DictationState.Listening, is DictationState.Starting, is DictationState.Restarting -> stopDictation()
            is DictationState.Paused -> resumeDictation()
            is DictationState.Error -> startDictation()
        }
    }

    fun startDictation() {
        _isReadingMode.value = false
        val lang = _currentNote.value?.languageCode ?: defaultLanguage.value
        val mode = DictationMode.valueOf(dictationModePref.value)

        speechSupervisor.startDictation(
            language = lang,
            mode = mode,
            offline = offlinePreferredPref.value,
            muteBeepMethod = muteBeepMethodPref.value,
            pauseToleranceSec = pauseToleranceSecPref.value,
            idleStopMin = idleAutoStopMinPref.value,
            timedDurationMin = timedMinutesPref.value
        )

        // Start background foreground service for microphone
        DictationService.startService(getApplication())
    }

    fun stopDictation() {
        speechSupervisor.stopDictation()
        DictationService.stopService(getApplication())
    }

    fun pauseDictation() {
        speechSupervisor.pauseDictation()
    }

    fun resumeDictation() {
        speechSupervisor.resumeDictation()
    }

    fun setDictationMode(mode: DictationMode) {
        viewModelScope.launch {
            preferencesManager.setDictationMode(mode.name)
        }
    }

    fun onPushToTalkPress(isPressed: Boolean) {
        if (isPressed) {
            startDictation()
        } else {
            speechSupervisor.pauseDictation()
        }
    }

    // Language
    fun setNoteLanguage(langCode: String) {
        val note = _currentNote.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(languageCode = langCode)
            noteRepository.update(updated)
            _currentNote.value = updated
        }
    }

    fun cycleFavoriteLanguage() {
        val favorites = favoriteLanguages.value.toList()
        if (favorites.isEmpty()) return
        val currentLang = _currentNote.value?.languageCode ?: defaultLanguage.value
        val currentIndex = favorites.indexOfFirst { it.equals(currentLang, ignoreCase = true) }
        val nextIndex = if (currentIndex != -1) (currentIndex + 1) % favorites.size else 0
        setNoteLanguage(favorites[nextIndex])
    }

    fun toggleFavoriteLanguage(langCode: String) {
        val current = favoriteLanguages.value.toMutableSet()
        if (current.contains(langCode)) {
            if (current.size > 1) current.remove(langCode)
        } else {
            current.add(langCode)
        }
        viewModelScope.launch {
            preferencesManager.setFavoriteLanguages(current)
        }
    }

    // Notes Management
    fun createNewNote(
        folder: String? = null,
        template: Stamp? = null,
        customTitle: String? = null,
        startRecording: Boolean = false
    ) {
        clearDailyJournalNavigation()
        _isReadingMode.value = false
        flushAutoSave()
        viewModelScope.launch(Dispatchers.IO) {
            val defaultLang = defaultLanguage.value
            val locationMode = preferencesManager.newNoteLocationMode.first()
            val fixedFolder = preferencesManager.fixedNewNoteFolder.first()
            val lastActive = preferencesManager.getLastActiveFolderSync()

            val targetFolder = when {
                !folder.isNullOrBlank() -> folder
                locationMode == "LAST_WORKED_ON" -> if (lastActive.isNotBlank()) lastActive else "General"
                locationMode == "CURRENT_OPEN" -> _currentNote.value?.folder ?: "General"
                locationMode == "FIXED" -> if (fixedFolder.isNotBlank()) fixedFolder else "General"
                locationMode == "ROOT" -> "General"
                locationMode == "ASK_POPUP" -> if (lastActive.isNotBlank()) lastActive else "General"
                else -> if (lastActive.isNotBlank()) lastActive else "General"
            }

            preferencesManager.setLastActiveFolder(targetFolder)

            val namingRule = preferencesManager.newNoteNamingRule.first()
            val initialTitle = if (!customTitle.isNullOrBlank()) {
                customTitle
            } else if (template != null) {
                template.label
            } else {
                when (namingRule) {
                    "UNTITLED" -> "Untitled note"
                    "DATE_TIME" -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    "DATE_ONLY" -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    "FIRST_LINE" -> "Untitled note"
                    "ASK_ME" -> "Untitled note"
                    else -> "Untitled note"
                }
            }

            val initialContent = if (template != null) {
                StampProcessor.processTemplate(template.templateText).outputText
            } else ""

            val newNote = Note(
                title = initialTitle,
                content = initialContent,
                languageCode = defaultLang,
                folder = targetFolder,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val id = noteRepository.insert(newNote)
            val inserted = noteRepository.getNoteOnce(id)

            val afterAction = preferencesManager.afterCreatingNote.first()

            withContext(Dispatchers.Main) {
                if (inserted != null) {
                    loadNote(inserted)
                    isCurrentNoteAutoCreated = true
                    if (startRecording) {
                        startDictation()
                    }
                }
            }
        }
    }

    fun renameCurrentNote(newTitle: String) {
        val note = _currentNote.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(title = newTitle, updatedAt = System.currentTimeMillis())
            noteRepository.update(updated, writeContent = false)
            _currentNote.value = updated
        }
    }

    fun togglePinCurrentNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(isPinned = !note.isPinned)
            noteRepository.update(updated, writeContent = false)
            if (_currentNote.value?.id == note.id) {
                _currentNote.value = updated
            }
        }
    }

    // Soft Delete (Trash)
    fun trashNote(note: Note) {
        lastDeletedNote = note
        autoSaveJob?.cancel()
        val isCurrentNote = _currentNote.value?.id == note.id
        if (isCurrentNote) {
            sessionDurationJob?.cancel()
            sessionDurationJob = null
            _currentNote.value = null
        }
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(isDeleted = true, deletedAt = System.currentTimeMillis(), isPinned = false)
            noteRepository.update(updated, writeContent = false)
            if (isCurrentNote) {
                val remaining = noteRepository.getLatestActiveNote()
                withContext(Dispatchers.Main) {
                    if (remaining != null) {
                        loadNote(remaining)
                    } else {
                        createNewNote()
                    }
                }
            }
        }
    }

    // Restore from Trash
    fun restoreNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(isDeleted = false, deletedAt = null)
            noteRepository.update(updated, writeContent = false)
        }
    }

    // Permanent Delete
    fun permanentlyDeleteNote(note: Note) {
        autoSaveJob?.cancel()
        val isCurrent = _currentNote.value?.id == note.id
        if (isCurrent) {
            sessionDurationJob?.cancel()
            sessionDurationJob = null
            _currentNote.value = null
        }
        viewModelScope.launch(Dispatchers.IO) {
            noteRepository.delete(note)
            if (isCurrent) {
                val remaining = noteRepository.getLatestActiveNote()
                withContext(Dispatchers.Main) {
                    if (remaining != null) loadNote(remaining) else createNewNote()
                }
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepository.emptyTrash()
        }
    }

    // Archive / Unarchive
    fun toggleArchiveNote(note: Note) {
        val willArchive = !note.isArchived
        val isCurrent = _currentNote.value?.id == note.id
        if (willArchive && isCurrent) {
            autoSaveJob?.cancel()
            sessionDurationJob?.cancel()
            sessionDurationJob = null
            _currentNote.value = null
        }
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(isArchived = willArchive)
            noteRepository.update(updated, writeContent = false)
            if (willArchive && isCurrent) {
                val remaining = noteRepository.getLatestActiveNote()
                withContext(Dispatchers.Main) {
                    if (remaining != null) loadNote(remaining) else createNewNote()
                }
            }
        }
    }

    fun deleteNote(note: Note, onDeleted: () -> Unit) {
        trashNote(note)
        onDeleted()
    }

    fun restoreLastDeletedNote() {
        val note = lastDeletedNote ?: return
        restoreNote(note)
        loadNote(note)
    }

    // Notes Manager & Tree Explorer Operations
    fun setShowNoteMetricsInList(show: Boolean) {
        viewModelScope.launch {
            preferencesManager.setShowNoteMetricsInList(show)
        }
    }

    fun setNoteMetricsStyle(style: String) {
        viewModelScope.launch {
            preferencesManager.setNoteMetricsStyle(style)
        }
    }


    fun duplicateNote(note: Note) {
        viewModelScope.launch(Dispatchers.IO) {
            val copy = note.copy(
                id = 0,
                title = note.title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isPinned = false
            )
            val newId = noteRepository.insert(copy)
            val inserted = noteRepository.getNoteOnce(newId)
            if (inserted != null) {
                withContext(Dispatchers.Main) {
                    loadNote(inserted)
                }
            }
        }
    }

    fun renameNote(note: Note, newTitle: String) {
        val trimmed = newTitle.trim()
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(title = trimmed, updatedAt = System.currentTimeMillis())
            noteRepository.update(updated, writeContent = false)
            if (_currentNote.value?.id == note.id) {
                _currentNote.value = updated
            }
        }
    }

    // Template Operations
    fun togglePinTemplate(stamp: Stamp) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = stamp.copy(isPinned = !stamp.isPinned)
            stampRepository.update(updated)
        }
    }

    fun reorderTemplates(stamps: List<Stamp>) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = stamps.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            stampRepository.updateAll(updated)
        }
    }

    fun setTrashAutoEmptyDaysPref(days: Int) {
        viewModelScope.launch {
            preferencesManager.setTrashAutoEmptyDays(days)
        }
    }

    fun setDefaultNewNoteFolderPref(folder: String) {
        viewModelScope.launch {
            preferencesManager.setDefaultNewNoteFolder(folder)
        }
    }

    fun clearCurrentNoteText() {
        pushUndoState(_textFieldValue.value)
        updateTextFieldValue(TextFieldValue(""))
    }

    // Styling & Appearance Settings
    fun setFontSize(size: Float) {
        viewModelScope.launch { preferencesManager.setEditorFontSize(size) }
    }

    fun zoomIn() {
        val newSize = (editorFontSize.value + 2f).coerceAtMost(32f)
        setFontSize(newSize)
    }

    fun zoomOut() {
        val newSize = (editorFontSize.value - 2f).coerceAtLeast(12f)
        setFontSize(newSize)
    }

    fun setLineSpacing(spacing: Float) {
        viewModelScope.launch { preferencesManager.setEditorLineSpacing(spacing) }
    }

    fun setFontFamily(family: String) {
        viewModelScope.launch { preferencesManager.setEditorFontFamily(family) }
    }

    fun setNoteHighlight(tintKey: String?) {
        val note = _currentNote.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(highlightColor = tintKey)
            noteRepository.update(updated, writeContent = false)
            _currentNote.value = updated
        }
    }

    fun toggleKeyboard() {
        _userWantsBuiltInKeyboard.value = !_userWantsBuiltInKeyboard.value
    }

    fun setKeyboardKeyHeightDp(dp: Int) = viewModelScope.launch { preferencesManager.setKeyboardKeyHeightDp(dp) }
    fun setKeyboardFontSizeSp(sp: Int) = viewModelScope.launch { preferencesManager.setKeyboardFontSizeSp(sp) }
    fun setKeyboardHaptic(enabled: Boolean) = viewModelScope.launch { preferencesManager.setKeyboardHaptic(enabled) }
    fun setKeyboardHoldBehavior(behavior: String) = viewModelScope.launch { preferencesManager.setKeyboardHoldBehavior(behavior) }
    fun setKeyboardEditMode(editMode: Boolean) = viewModelScope.launch { preferencesManager.setKeyboardEditMode(editMode) }

    fun navigateTo(screen: String) {
        _currentScreen.value = screen
    }

    fun openDailyJournalManager() {
        _currentScreen.value = "DAILY_JOURNAL_MANAGER"
    }

    fun onBackFromDailyJournalManager() {
        _currentScreen.value = "EDITOR"
    }

    fun returnToDailyJournalManager() {
        flushAutoSave()
        _currentDailyJournalDate.value = null
        _noteNavigationContext.value = NoteNavigationContext.NOTE_MANAGER
        _currentScreen.value = "DAILY_JOURNAL_MANAGER"
    }

    fun clearDailyJournalNavigation() {
        _currentDailyJournalDate.value = null
        _noteNavigationContext.value = NoteNavigationContext.NOTE_MANAGER
    }

    fun openDailyJournalNote(note: Note, dateStr: String, startInEditMode: Boolean = false) {
        flushAutoSave()
        loadNote(note, preserveReadingMode = false)
        setReadingMode(!startInEditMode)
        _noteNavigationContext.value = NoteNavigationContext.DAILY_JOURNAL
        _currentDailyJournalDate.value = dateStr
        if (startInEditMode) {
            triggerEditorFocus()
        }
        navigateTo("EDITOR")
    }

    fun getJournalNotesSortedChronologically(): List<Pair<LocalDate, Note>> {
        return allNotes.value
            .filter {
                !it.isDeleted && (com.example.ui.notes.isDailyJournalNote(it) || it.folder.trim().equals("Daily Journal", ignoreCase = true) || it.folder.trim().equals("Daily Journals", ignoreCase = true))
            }
            .mapNotNull { note ->
                val date = com.example.ui.notes.extractNoteJournalDate(note)
                if (date != null) Pair(date, note) else null
            }
            .groupBy { it.first }
            .mapValues { (_, notesForDate) ->
                notesForDate.maxByOrNull { it.second.updatedAt }?.second ?: notesForDate.first().second
            }
            .map { Pair(it.key, it.value) }
            .sortedBy { it.first }
    }

    fun getCurrentJournalDate(): LocalDate? {
        val dateStr = _currentDailyJournalDate.value
        if (!dateStr.isNullOrBlank()) {
            val parsed = runCatching { LocalDate.parse(dateStr) }.getOrNull()
            if (parsed != null) return parsed
        }
        return _currentNote.value?.let { com.example.ui.notes.extractNoteJournalDate(it) }
    }

    fun getNextJournalNote(): Pair<LocalDate, Note>? {
        val currentDate = getCurrentJournalDate() ?: return null
        val entries = getJournalNotesSortedChronologically()
        return entries.firstOrNull { it.first > currentDate }
    }

    fun getPreviousJournalNote(): Pair<LocalDate, Note>? {
        val currentDate = getCurrentJournalDate() ?: return null
        val entries = getJournalNotesSortedChronologically()
        return entries.lastOrNull { it.first < currentDate }
    }

    fun hasNextJournalEntry(): Boolean = getNextJournalNote() != null

    fun hasPreviousJournalEntry(): Boolean = getPreviousJournalNote() != null

    fun navigateToNextJournalEntry(): Boolean {
        if (_noteNavigationContext.value != NoteNavigationContext.DAILY_JOURNAL) return false
        val next = getNextJournalNote() ?: return false
        val (nextDate, nextNote) = next
        flushAutoSave()
        loadNote(nextNote, preserveReadingMode = true)
        _noteNavigationContext.value = NoteNavigationContext.DAILY_JOURNAL
        _currentDailyJournalDate.value = nextDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return true
    }

    fun navigateToPreviousJournalEntry(): Boolean {
        if (_noteNavigationContext.value != NoteNavigationContext.DAILY_JOURNAL) return false
        val prev = getPreviousJournalNote() ?: return false
        val (prevDate, prevNote) = prev
        flushAutoSave()
        loadNote(prevNote, preserveReadingMode = true)
        _noteNavigationContext.value = NoteNavigationContext.DAILY_JOURNAL
        _currentDailyJournalDate.value = prevDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        return true
    }

    suspend fun findDailyJournalNoteForDate(date: LocalDate): Note? {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        // 0. Canonical lookup in vault cache/disk
        val vaultCanonical = vaultNoteRepository.findDailyJournalNote(dateStr)
        if (vaultCanonical != null) return vaultCanonical

        // 1. Strict title match first (exact date: "YYYY-MM-DD" or starts with "YYYY-MM-DD")
        val strictMatch = allNotes.value.firstOrNull {
            !it.isDeleted &&
                    (com.example.ui.notes.isDailyJournalNote(it) || it.folder.trim().equals("Daily Journal", ignoreCase = true) || it.folder.trim().equals("Daily Journals", ignoreCase = true)) &&
                    (it.title.trim() == dateStr || it.title.trim().startsWith(dateStr))
        } ?: vaultNoteRepository.allNotes.value.firstOrNull {
            !it.isDeleted &&
                    (com.example.ui.notes.isDailyJournalNote(it) || it.folder.trim().equals("Daily Journal", ignoreCase = true) || it.folder.trim().equals("Daily Journals", ignoreCase = true)) &&
                    (it.title.trim() == dateStr || it.title.trim().startsWith(dateStr))
        }
        if (strictMatch != null) return strictMatch

        // 2. Extracted date match
        return allNotes.value.firstOrNull {
            !it.isDeleted &&
                    (com.example.ui.notes.isDailyJournalNote(it) || it.folder.trim().equals("Daily Journal", ignoreCase = true) || it.folder.trim().equals("Daily Journals", ignoreCase = true)) &&
                    com.example.ui.notes.extractNoteJournalDate(it) == date
        } ?: vaultNoteRepository.allNotes.value.firstOrNull {
            !it.isDeleted &&
                    (com.example.ui.notes.isDailyJournalNote(it) || it.folder.trim().equals("Daily Journal", ignoreCase = true) || it.folder.trim().equals("Daily Journals", ignoreCase = true)) &&
                    com.example.ui.notes.extractNoteJournalDate(it) == date
        }
    }

    fun handleDailyJournalDateTap(date: LocalDate) {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        flushAutoSave()
        _noteNavigationContext.value = NoteNavigationContext.DAILY_JOURNAL
        _currentDailyJournalDate.value = dateStr

        viewModelScope.launch(Dispatchers.IO) {
            val existing = vaultNoteRepository.findDailyJournalNote(dateStr)
                ?: findDailyJournalNoteForDate(date)
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    openDailyJournalNote(existing, dateStr, startInEditMode = false)
                }
            } else {
                val created = vaultNoteRepository.getOrCreateDailyJournalNote(date)
                withContext(Dispatchers.Main) {
                    openDailyJournalNote(created, dateStr, startInEditMode = true)
                }
            }
        }
    }

    fun handleDailyJournalTodayFabClick() {
        val today = LocalDate.now()
        val dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        flushAutoSave()
        _noteNavigationContext.value = NoteNavigationContext.DAILY_JOURNAL
        _currentDailyJournalDate.value = dateStr

        viewModelScope.launch(Dispatchers.IO) {
            val existing = vaultNoteRepository.findDailyJournalNote(dateStr)
                ?: findDailyJournalNoteForDate(today)
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    openDailyJournalNote(existing, dateStr, startInEditMode = true)
                }
            } else {
                val created = vaultNoteRepository.getOrCreateDailyJournalNote(today)
                withContext(Dispatchers.Main) {
                    openDailyJournalNote(created, dateStr, startInEditMode = true)
                }
            }
        }
    }

    fun createNewDailyJournalNote(date: LocalDate, startInEditMode: Boolean = true) {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        flushAutoSave()
        _noteNavigationContext.value = NoteNavigationContext.DAILY_JOURNAL
        _currentDailyJournalDate.value = dateStr

        viewModelScope.launch(Dispatchers.IO) {
            val existing = vaultNoteRepository.findDailyJournalNote(dateStr)
                ?: findDailyJournalNoteForDate(date)
            val noteToOpen = existing ?: vaultNoteRepository.getOrCreateDailyJournalNote(date)
            withContext(Dispatchers.Main) {
                openDailyJournalNote(noteToOpen, dateStr, startInEditMode = startInEditMode)
            }
        }
    }

    fun updateMarkdownSettings(transform: (com.example.markdown.MarkdownSettings) -> com.example.markdown.MarkdownSettings) {
        viewModelScope.launch { markdownSettingsRepository.updateSettings(transform) }
    }

    fun resetMarkdownSettingsToDefault() {
        viewModelScope.launch { markdownSettingsRepository.resetToDefaults() }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            preferencesManager.setOnboardingCompleted(true)
        }
    }

    // Settings proxies
    fun setDictationModePref(mode: String) = viewModelScope.launch { preferencesManager.setDictationMode(mode) }
    fun setTimedMinutesPref(min: Int) = viewModelScope.launch { preferencesManager.setTimedMinutes(min) }
    fun setPauseToleranceSecPref(sec: Int) = viewModelScope.launch { preferencesManager.setPauseToleranceSeconds(sec) }
    fun setIdleAutoStopMinPref(min: Int) = viewModelScope.launch { preferencesManager.setIdleAutoStopMinutes(min) }
    fun setMuteBeepMethodPref(method: String) = viewModelScope.launch { preferencesManager.setMuteBeepMethod(method) }
    fun setInsertionModePref(mode: String) = viewModelScope.launch { preferencesManager.setInsertionMode(mode) }
    fun setOfflinePreferredPref(offline: Boolean) = viewModelScope.launch { preferencesManager.setOfflinePreferred(offline) }
    fun setKeepScreenOnPref(keep: Boolean) = viewModelScope.launch { preferencesManager.setKeepScreenOn(keep) }
    fun setAppThemePref(theme: String) = viewModelScope.launch { preferencesManager.setAppTheme(theme) }
    fun setPipelineSpacingPref(enabled: Boolean) = viewModelScope.launch { preferencesManager.setPipelineSpacing(enabled) }
    fun setPipelineAutoCapPref(enabled: Boolean) = viewModelScope.launch { preferencesManager.setPipelineAutoCap(enabled) }
    fun setPipelineCommandsPref(enabled: Boolean) = viewModelScope.launch { preferencesManager.setPipelineCommands(enabled) }
    fun setShowWordCountPref(show: Boolean) = viewModelScope.launch { preferencesManager.setShowWordCount(show) }
    fun setDefaultLanguagePref(lang: String) = viewModelScope.launch { preferencesManager.setDefaultLanguage(lang) }

    // Appearance & Custom Theme
    fun setCustomThemeColors(primary: Int, bg: Int, surface: Int, text: Int, secondary: Int) {
        viewModelScope.launch {
            preferencesManager.setCustomThemeColors(
                primary = primary,
                bg = bg,
                surface = surface,
                text = text,
                secondary = secondary,
                updateAppThemeToCustom = true
            )
            settingsRepository.updateSettings {
                it.copy(
                    appTheme = "CUSTOM",
                    customPrimaryColor = primary,
                    customBackgroundColor = bg,
                    customSurfaceColor = surface,
                    customTextColor = text,
                    customSecondaryColor = secondary
                )
            }
            settingsRepository.flushSaves()
        }
    }

    // Typography
    fun setCustomFont(name: String, path: String?) {
        viewModelScope.launch { preferencesManager.setCustomFont(name, path) }
    }
    fun setLetterSpacing(spacing: Float) = viewModelScope.launch { preferencesManager.setEditorLetterSpacing(spacing) }
    fun setParagraphSpacing(spacing: Int) = viewModelScope.launch { preferencesManager.setEditorParagraphSpacing(spacing) }
    fun setTextAlignment(align: String) = viewModelScope.launch { preferencesManager.setEditorTextAlignment(align) }
    fun setMarginDp(margin: Int) = viewModelScope.launch { preferencesManager.setEditorMarginDp(margin) }
    fun setMaxWidthDp(width: Int) = viewModelScope.launch { preferencesManager.setEditorMaxWidthDp(width) }

    // Toolbar Order & Visibility
    fun setToolbarItemOrder(order: String) = viewModelScope.launch { preferencesManager.setToolbarItemOrder(order) }
    fun setToolbarHiddenItems(hidden: Set<String>) = viewModelScope.launch { preferencesManager.setToolbarHiddenItems(hidden) }

    fun moveToolbarItem(fromIndex: Int, toIndex: Int) {
        val currentOrder = toolbarItemOrder.value.split(",").map { it.trim() }.toMutableList()
        if (fromIndex in currentOrder.indices && toIndex in currentOrder.indices) {
            val item = currentOrder.removeAt(fromIndex)
            currentOrder.add(toIndex, item)
            setToolbarItemOrder(currentOrder.joinToString(","))
        }
    }

    fun toggleToolbarItemVisibility(item: String) {
        val currentHidden = toolbarHiddenItems.value.toMutableSet()
        if (currentHidden.contains(item)) {
            currentHidden.remove(item)
        } else {
            currentHidden.add(item)
        }
        setToolbarHiddenItems(currentHidden)
    }

    fun resetToolbarOrder() {
        setToolbarItemOrder("DICTATION_STATUS,METRICS,KEYBOARD_MODE,TEXT_SIZE,TEXT_STYLE,AUDIO_SOURCE")
        setToolbarHiddenItems(emptySet())
    }

    // Mic Button & Cluster Settings
    fun setMicButtonSize(size: String) = viewModelScope.launch { preferencesManager.setMicButtonSize(size) }
    fun setMicButtonShape(shape: String) = viewModelScope.launch { preferencesManager.setMicButtonShape(shape) }
    fun setMicButtonColor(color: String) = viewModelScope.launch { preferencesManager.setMicButtonColor(color) }
    fun setMicButtonPosition(pos: String) = viewModelScope.launch { preferencesManager.setMicButtonPosition(pos) }
    fun setMicButtonOffsetY(offset: Int) = viewModelScope.launch { preferencesManager.setMicButtonOffsetY(offset) }

    // Visual Polish, Animation & Haptics
    fun setStatusChipStyle(style: String) = viewModelScope.launch { preferencesManager.setStatusChipStyle(style) }
    fun setUiDensity(density: String) = viewModelScope.launch { preferencesManager.setUiDensity(density) }
    fun setCornerRadiusDp(radius: Int) = viewModelScope.launch { preferencesManager.setCornerRadiusDp(radius) }
    fun setDictationAnimationStyle(style: String) = viewModelScope.launch { preferencesManager.setDictationAnimationStyle(style) }
    fun setHapticsLevel(level: String) = viewModelScope.launch { preferencesManager.setHapticsLevel(level) }

    // Floating Controls Layout ("Side Rail") Methods
    fun setFloatingUseSideRail(enabled: Boolean) = viewModelScope.launch { preferencesManager.setFloatingUseSideRail(enabled) }
    fun setRailSide(side: String) = viewModelScope.launch { preferencesManager.setRailSide(side) }
    fun setRailScale(scale: Float) = viewModelScope.launch { preferencesManager.setRailScale(scale) }
    fun setRailBottomOffsetDp(offset: Int) = viewModelScope.launch { preferencesManager.setRailBottomOffsetDp(offset) }
    fun setRailSpacingDp(spacing: Int) = viewModelScope.launch { preferencesManager.setRailSpacingDp(spacing) }
    fun setRailKeepTextClear(keepClear: Boolean) = viewModelScope.launch { preferencesManager.setRailKeepTextClear(keepClear) }
    fun moveRailButtonItem(fromIndex: Int, toIndex: Int) {
        val currentOrder = floatingLayoutSettings.value.railButtonOrder.toMutableList()
        if (fromIndex in currentOrder.indices && toIndex in currentOrder.indices) {
            val item = currentOrder.removeAt(fromIndex)
            currentOrder.add(toIndex, item)
            viewModelScope.launch { preferencesManager.setRailButtonOrder(currentOrder) }
        }
    }
    fun resetRailButtonOrder() = viewModelScope.launch {
        preferencesManager.setRailButtonOrder(com.example.data.model.FloatingLayoutSettings.DEFAULT_RAIL_BUTTON_ORDER)
    }
    fun setToggleScale(scale: Float) = viewModelScope.launch { preferencesManager.setToggleScale(scale) }
    fun setToggleBottomOffsetDp(offset: Int) = viewModelScope.launch { preferencesManager.setToggleBottomOffsetDp(offset) }
    fun setToggleGapDp(gap: Int) = viewModelScope.launch { preferencesManager.setToggleGapDp(gap) }
    fun setFormatBarScale(scale: Float) = viewModelScope.launch { preferencesManager.setFormatBarScale(scale) }
    fun setFormatBarBottomOffsetDp(offset: Int) = viewModelScope.launch { preferencesManager.setFormatBarBottomOffsetDp(offset) }
    fun setFormatBarWidthPercent(percent: Int) = viewModelScope.launch { preferencesManager.setFormatBarWidthPercent(percent) }
    fun setFormatBarAlignment(align: String) = viewModelScope.launch { preferencesManager.setFormatBarAlignment(align) }
    fun setFormatBarVisibleDuringDictation(visible: Boolean) = viewModelScope.launch {
        preferencesManager.setFormatBarVisibleDuringDictation(visible)
    }
    fun setFormatBarVisibleWithoutCursor(visible: Boolean) = viewModelScope.launch {
        preferencesManager.setFormatBarVisibleWithoutCursor(visible)
    }
    fun setWaveformScale(scale: Float) = viewModelScope.launch { preferencesManager.setWaveformScale(scale) }
    fun setWaveformShow(show: Boolean) = viewModelScope.launch { preferencesManager.setWaveformShow(show) }
    fun resetFloatingLayoutToDefaults() = viewModelScope.launch { preferencesManager.resetFloatingLayoutToDefaults() }

    // Notes Manager Settings Flows & Methods
    val newNoteLocationMode: StateFlow<String> = preferencesManager.newNoteLocationMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "LAST_WORKED_ON")
    val fixedNewNoteFolder: StateFlow<String> = preferencesManager.fixedNewNoteFolder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "General")
    val askBeforeCreating: StateFlow<Boolean> = preferencesManager.askBeforeCreating
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val askChooseTemplateToggle: StateFlow<Boolean> = preferencesManager.askChooseTemplateToggle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val askStartRecordingToggle: StateFlow<Boolean> = preferencesManager.askStartRecordingToggle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val newNoteNamingRule: StateFlow<String> = preferencesManager.newNoteNamingRule
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "UNTITLED")
    val afterCreatingNote: StateFlow<String> = preferencesManager.afterCreatingNote
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "OPEN_EDITOR")
    val lastActiveFolder: StateFlow<String> = preferencesManager.lastActiveFolder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "General")

    fun setNewNoteLocationMode(mode: String) = viewModelScope.launch { preferencesManager.setNewNoteLocationMode(mode) }
    fun setFixedNewNoteFolder(folder: String) = viewModelScope.launch { preferencesManager.setFixedNewNoteFolder(folder) }
    fun setAskBeforeCreating(ask: Boolean) = viewModelScope.launch { preferencesManager.setAskBeforeCreating(ask) }
    fun setAskChooseTemplateToggle(toggle: Boolean) = viewModelScope.launch { preferencesManager.setAskChooseTemplateToggle(toggle) }
    fun setAskStartRecordingToggle(toggle: Boolean) = viewModelScope.launch { preferencesManager.setAskStartRecordingToggle(toggle) }
    fun setNewNoteNamingRule(rule: String) = viewModelScope.launch { preferencesManager.setNewNoteNamingRule(rule) }
    fun setAfterCreatingNote(action: String) = viewModelScope.launch { preferencesManager.setAfterCreatingNote(action) }


    fun setNoteMetricsOrder(order: String) = viewModelScope.launch { preferencesManager.setNoteMetricsOrder(order) }
    fun setNoteMetricsHiddenItems(hidden: Set<String>) = viewModelScope.launch { preferencesManager.setNoteMetricsHiddenItems(hidden) }

    fun moveNoteMetricItem(fromIndex: Int, toIndex: Int) {
        val currentOrder = com.example.ui.notes.NoteMetricsConfig.parseOrder(noteMetricsOrder.value).toMutableList()
        if (fromIndex in currentOrder.indices && toIndex in currentOrder.indices) {
            val item = currentOrder.removeAt(fromIndex)
            currentOrder.add(toIndex, item)
            setNoteMetricsOrder(currentOrder.joinToString(","))
        }
    }

    fun toggleNoteMetricVisibility(itemKey: String) {
        val currentHidden = noteMetricsHiddenItems.value.toMutableSet()
        if (currentHidden.contains(itemKey)) {
            currentHidden.remove(itemKey)
        } else {
            currentHidden.add(itemKey)
        }
        setNoteMetricsHiddenItems(currentHidden)
    }

    fun resetNoteMetricsOrder() {
        setNoteMetricsOrder(com.example.ui.notes.NoteMetricsConfig.DEFAULT_ORDER_STRING)
        setNoteMetricsHiddenItems(emptySet())
    }

    // Backup & Restore Operations
    fun exportBackup(uri: Uri, onResult: (Result<BackupSummary>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val outputStream = app.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Unable to open output destination.")
                val summary = BackupManager.exportBackup(
                    context = app,
                    noteRepository = noteRepository,
                    stampRepository = stampRepository,
                    spokenCommandRepository = spokenCommandRepository,
                    preferencesManager = preferencesManager,
                    outputStream = outputStream,
                    vaultManager = vaultManager
                )
                withContext(Dispatchers.Main) {
                    onResult(Result.success(summary))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    fun validateBackup(uri: Uri, onResult: (Result<ParsedBackupData>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val inputStream = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open backup file.")
                val parsed = inputStream.use {
                    BackupManager.validateAndParseBackup(it)
                }
                withContext(Dispatchers.Main) {
                    onResult(Result.success(parsed))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    fun restoreBackup(backupData: ParsedBackupData, onResult: (Result<BackupSummary>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val summary = BackupManager.restoreBackup(
                    context = app,
                    noteRepository = noteRepository,
                    stampRepository = stampRepository,
                    spokenCommandRepository = spokenCommandRepository,
                    preferencesManager = preferencesManager,
                    vaultManager = vaultManager,
                    backupData = backupData
                )
                reloadAfterImport()
                withContext(Dispatchers.Main) {
                    onResult(Result.success(summary))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(e))
                }
            }
        }
    }

    suspend fun reloadAfterImport() {
        val vaultDoc = vaultManager.getCurrentVaultDocumentFile()
        if (vaultDoc != null) {
            settingsRepository.loadVaultSettings(vaultDoc, preferencesManager)
            vaultNoteRepository.onVaultOpened(vaultDoc)
        }
        val activeNotesList = vaultNoteRepository.activeNotes.value
        val ws = vaultNoteRepository.getWorkspaceData()
        var noteToLoad: Note? = null
        if (!ws.lastOpenedNoteRelPath.isNullOrBlank()) {
            noteToLoad = activeNotesList.firstOrNull { !it.isDeleted && vaultNoteRepository.getRelPathForNote(it).equals(ws.lastOpenedNoteRelPath, ignoreCase = true) }
        }
        if (noteToLoad == null && activeNotesList.isNotEmpty()) {
            noteToLoad = vaultNoteRepository.getLatestActiveNote() ?: activeNotesList.firstOrNull()
        }
        withContext(Dispatchers.Main) {
            if (noteToLoad != null) {
                loadNote(noteToLoad)
            } else if (activeNotesList.isEmpty() && vaultNoteRepository.allNotes.value.isEmpty()) {
                createNewNote()
            }
        }

        val layoutJson = preferencesManager.keyboardLayoutJson.first()
        withContext(Dispatchers.Main) {
            if (!layoutJson.isNullOrBlank()) {
                try {
                    _keyboardLayout.value = KeyboardLayouts.deserializeFromJson(layoutJson)
                } catch (_: Exception) {
                    _keyboardLayout.value = KeyboardLayouts.getDefault3x10Layout()
                }
            } else {
                _keyboardLayout.value = KeyboardLayouts.getDefault3x10Layout()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        flushAutoSave()
    }
}
