package com.example.data.vault

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.FloatingLayoutSettings
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val schemaVersion: Int = 1,

    // Dictation
    val dictationMode: String = "CONTINUOUS",
    val timedMinutes: Int = 3,
    val pauseToleranceSeconds: Int = 30,
    val idleAutoStopMinutes: Int = 0,
    val muteBeepMethod: String = "MEDIA_SYSTEM",
    val insertionMode: String = "AT_CURSOR",
    val offlinePreferred: Boolean = false,
    val keepScreenOn: Boolean = true,

    // Language
    val defaultLanguage: String = "bn-BD",
    val favoriteLanguages: Set<String> = setOf("bn-BD", "bn-IN", "en-US"),

    // Appearance & Typography
    val appTheme: String = "AMOLED",
    val editorFontSize: Float = 18f,
    val editorLineSpacing: Float = 1.5f,
    val editorFontFamily: String = "BENGALI",
    val customPrimaryColor: Int = 0xFFFF7043.toInt(),
    val customBackgroundColor: Int = 0xFF000000.toInt(),
    val customSurfaceColor: Int = 0xFF121212.toInt(),
    val customTextColor: Int = 0xFFFFFFFF.toInt(),
    val customSecondaryColor: Int = 0xFFFF8A65.toInt(),
    val customFontName: String = "",
    val customFontPath: String? = null,
    val editorLetterSpacing: Float = 0f,
    val editorParagraphSpacing: Int = 8,
    val editorTextAlignment: String = "LEFT",
    val editorMarginDp: Int = 16,
    val editorMaxWidthDp: Int = 0,
    val statusChipStyle: String = "PILL",
    val uiDensity: String = "NORMAL",
    val cornerRadiusDp: Int = 12,
    val dictationAnimationStyle: String = "PULSE_RING",
    val hapticsLevel: String = "MEDIUM",

    // Toolbar
    val toolbarItemOrder: String = "DICTATION_STATUS,METRICS,NOTE_TIMER,KEYBOARD_MODE,TEXT_SIZE,TEXT_STYLE,AUDIO_SOURCE",
    val toolbarHiddenItems: Set<String> = emptySet(),

    // Mic Button
    val micButtonSize: String = "MEDIUM",
    val micButtonShape: String = "CIRCLE",
    val micButtonColor: String = "DEFAULT",
    val micButtonPosition: String = "RIGHT",
    val micButtonOffsetY: Int = 8,

    // Pipeline
    val pipelineSpacing: Boolean = true,
    val pipelineAutoCap: Boolean = true,
    val pipelineCommands: Boolean = true,

    // Keyboard
    val keyboardType: String = "IN_APP",
    val showWordCount: Boolean = true,
    val keyboardLayoutJson: String? = null,
    val keyboardKeyHeightDp: Int = 40,
    val keyboardFontSizeSp: Int = 15,
    val keyboardHaptic: Boolean = true,
    val keyboardHoldBehavior: String = "CUSTOMIZE",
    val keyboardEditMode: Boolean = false,
    val keyboardLayoutVersion: Int = 2,

    // Notes Manager
    val trashAutoEmptyDays: Int = 7,
    val defaultNewNoteFolder: String = "General",
    val showNoteMetricsInList: Boolean = true,
    val noteMetricsStyle: String = "COMPACT",
    val noteMetricsOrder: String = com.example.ui.notes.NoteMetricsConfig.DEFAULT_ORDER_STRING,
    val noteMetricsHiddenItems: Set<String> = emptySet(),
    val expandedFolders: Set<String> = setOf("General", "Templates"),
    val customFolders: Set<String> = emptySet(),
    val newNoteLocationMode: String = "LAST_WORKED_ON",
    val fixedNewNoteFolder: String = "General",
    val askBeforeCreating: Boolean = false,
    val askChooseTemplateToggle: Boolean = true,
    val askStartRecordingToggle: Boolean = false,
    val newNoteNamingRule: String = "UNTITLED",
    val afterCreatingNote: String = "OPEN_EDITOR",
    val lastActiveFolder: String = "General",
    val lastOpenedNoteId: Long = -1L,

    // Floating Controls
    val floatingUseSideRail: Boolean = true,
    val railSide: String = "RIGHT",
    val railScale: Float = 1.0f,
    val railBottomOffsetDp: Int = 8,
    val railSpacingDp: Int = 6,
    val railKeepTextClear: Boolean = true,
    val railButtonOrder: List<String> = listOf("MIC", "TIMER", "KEYBOARD", "EXPAND", "FORMAT"),
    val toggleScale: Float = 1.0f,
    val toggleBottomOffsetDp: Int = 8,
    val toggleGapDp: Int = 8,
    val formatBarScale: Float = 1.0f,
    val formatBarBottomOffsetDp: Int = 8,
    val formatBarWidthPercent: Int = 100,
    val formatBarAlignment: String = "LEFT",
    val waveformScale: Float = 1.0f,
    val waveformShow: Boolean = true,

    // Markdown
    val mdEnabled: Boolean = true,
    val mdHeadings: Boolean = true,
    val mdBoldItalic: Boolean = true,
    val mdStrikethrough: Boolean = true,
    val mdHighlight: Boolean = true,
    val mdInlineCode: Boolean = true,
    val mdCodeBlocks: Boolean = true,
    val mdBlockquotes: Boolean = true,
    val mdLists: Boolean = true,
    val mdTaskLists: Boolean = true,
    val mdLinks: Boolean = true,
    val mdTables: Boolean = true,
    val mdHorizontalRule: Boolean = true,
    val mdReadingFontScale: Float = 1.0f,
    val mdLiveStyling: Boolean = true,
    val mdDimSyntax: Boolean = true,
    val mdHeadingScale: Float = 1.0f,
    val mdCodeMonospace: Boolean = true,
    val mdAutoContinueLists: Boolean = true,
    val mdFormatBarEnabled: Boolean = true,
    val mdFormatBarButtons: List<String> = listOf(
        "heading", "bold", "highlight", "numbered_list", "checkbox", "horizontal_rule",
        "italic", "strikethrough", "bullet_list", "quote",
        "inline_code", "code_block", "link"
    ),
    val mdFormatBarOrder: List<String> = listOf(
        "heading", "bold", "highlight", "numbered_list", "checkbox", "horizontal_rule",
        "italic", "strikethrough", "bullet_list", "quote",
        "inline_code", "code_block", "link"
    ),
    val mdTappableCheckboxes: Boolean = true,

    // Reading / Edit mode toggle
    val readingModeEnabled: Boolean = false
)

class SettingsRepository(
    private val context: Context,
    private val vaultManager: VaultManager,
    private val coroutineScope: CoroutineScope,
    private val vaultConfigStore: VaultConfigStore = VaultConfigStore.getInstance(context, coroutineScope)
) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var isSyncingWithDataStore = false

    companion object {
        const val SETTINGS_DIR = VaultConfigStore.CONFIG_DIR_NAME
        const val SETTINGS_FILE_NAME = VaultConfigStore.FILE_SETTINGS
        const val SETTINGS_BAK_FILE_NAME = "settings.json.bak"
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (isSyncingWithDataStore) return
        val current = _settings.value
        val updated = transform(current)
        if (updated == current) return
        _settings.value = updated
        val vaultDoc = vaultManager.getCurrentVaultDocumentFile() ?: return
        coroutineScope.launch(Dispatchers.IO) {
            vaultConfigStore.initVault(vaultDoc)
            val json = serializeToJson(updated)
            vaultConfigStore.updateSettings(vaultDoc, json, immediate = true)
        }
    }

    suspend fun flushSaves() = withContext(Dispatchers.IO) {
        vaultConfigStore.flush(VaultConfigStore.FILE_SETTINGS)
    }

    /**
     * Loads settings from the vault:
     * - Reads .KOTHAPAD/settings.json as single source of truth.
     * - If does not exist, seed from app's current preferences and create settings.json once.
     */
    suspend fun loadVaultSettings(vaultDoc: DocumentFile, preferencesManager: PreferencesManager): AppSettings = withContext(Dispatchers.IO) {
        vaultConfigStore.initVault(vaultDoc)
        val loadedJson = vaultConfigStore.readSettings(vaultDoc)

        val finalSettings: AppSettings
        if (loadedJson != null) {
            finalSettings = deserializeFromJson(loadedJson)
            _settings.value = finalSettings
            // Sync loaded settings to local preferences storage so everything stays consistent
            applySettingsToPreferencesManager(finalSettings, preferencesManager)
        } else {
            // Seed from app's CURRENT settings so user loses nothing on first open
            finalSettings = seedFromPreferencesManager(preferencesManager)
            _settings.value = finalSettings
            vaultConfigStore.writeSettings(vaultDoc, serializeToJson(finalSettings))
        }

        finalSettings
    }

    suspend fun exportCurrentPreferencesToVault(vaultDoc: DocumentFile, preferencesManager: PreferencesManager) = withContext(Dispatchers.IO) {
        vaultConfigStore.initVault(vaultDoc)
        val currentSettings = seedFromPreferencesManager(preferencesManager)
        _settings.value = currentSettings
        vaultConfigStore.writeSettings(vaultDoc, serializeToJson(currentSettings))
    }

    private suspend fun seedFromPreferencesManager(pm: PreferencesManager): AppSettings = withContext(Dispatchers.IO) {
        try {
            val floatings = pm.floatingLayoutSettings.first()
            AppSettings(
                dictationMode = pm.dictationMode.first(),
                timedMinutes = pm.timedMinutes.first(),
                pauseToleranceSeconds = pm.pauseToleranceSeconds.first(),
                idleAutoStopMinutes = pm.idleAutoStopMinutes.first(),
                muteBeepMethod = pm.muteBeepMethod.first(),
                insertionMode = pm.insertionMode.first(),
                offlinePreferred = pm.offlinePreferred.first(),
                keepScreenOn = pm.keepScreenOn.first(),
                defaultLanguage = pm.defaultLanguage.first(),
                favoriteLanguages = pm.favoriteLanguages.first(),
                appTheme = pm.appTheme.first(),
                editorFontSize = pm.editorFontSize.first(),
                editorLineSpacing = pm.editorLineSpacing.first(),
                editorFontFamily = pm.editorFontFamily.first(),
                customPrimaryColor = pm.customPrimaryColor.first(),
                customBackgroundColor = pm.customBackgroundColor.first(),
                customSurfaceColor = pm.customSurfaceColor.first(),
                customTextColor = pm.customTextColor.first(),
                customSecondaryColor = pm.customSecondaryColor.first(),
                customFontName = pm.customFontName.first(),
                customFontPath = pm.customFontPath.first(),
                editorLetterSpacing = pm.editorLetterSpacing.first(),
                editorParagraphSpacing = pm.editorParagraphSpacing.first(),
                editorTextAlignment = pm.editorTextAlignment.first(),
                editorMarginDp = pm.editorMarginDp.first(),
                editorMaxWidthDp = pm.editorMaxWidthDp.first(),
                statusChipStyle = pm.statusChipStyle.first(),
                uiDensity = pm.uiDensity.first(),
                cornerRadiusDp = pm.cornerRadiusDp.first(),
                dictationAnimationStyle = pm.dictationAnimationStyle.first(),
                hapticsLevel = pm.hapticsLevel.first(),
                toolbarItemOrder = pm.toolbarItemOrder.first(),
                toolbarHiddenItems = pm.toolbarHiddenItems.first(),
                micButtonSize = pm.micButtonSize.first(),
                micButtonShape = pm.micButtonShape.first(),
                micButtonColor = pm.micButtonColor.first(),
                micButtonPosition = pm.micButtonPosition.first(),
                micButtonOffsetY = pm.micButtonOffsetY.first(),
                pipelineSpacing = pm.pipelineSpacing.first(),
                pipelineAutoCap = pm.pipelineAutoCap.first(),
                pipelineCommands = pm.pipelineCommands.first(),
                keyboardType = pm.keyboardType.first(),
                showWordCount = pm.showWordCount.first(),
                keyboardLayoutJson = pm.keyboardLayoutJson.first(),
                keyboardKeyHeightDp = pm.keyboardKeyHeightDp.first(),
                keyboardFontSizeSp = pm.keyboardFontSizeSp.first(),
                keyboardHaptic = pm.keyboardHaptic.first(),
                keyboardHoldBehavior = pm.keyboardHoldBehavior.first(),
                keyboardEditMode = pm.keyboardEditMode.first(),
                keyboardLayoutVersion = pm.keyboardLayoutVersion.first(),
                trashAutoEmptyDays = pm.trashAutoEmptyDays.first(),
                defaultNewNoteFolder = pm.defaultNewNoteFolder.first(),
                showNoteMetricsInList = pm.showNoteMetricsInList.first(),
                noteMetricsStyle = pm.noteMetricsStyle.first(),
                noteMetricsOrder = pm.noteMetricsOrder.first(),
                noteMetricsHiddenItems = pm.noteMetricsHiddenItems.first(),
                expandedFolders = pm.getExpandedFoldersSync(),
                customFolders = pm.customFolders.first(),
                newNoteLocationMode = pm.newNoteLocationMode.first(),
                fixedNewNoteFolder = pm.fixedNewNoteFolder.first(),
                askBeforeCreating = pm.askBeforeCreating.first(),
                askChooseTemplateToggle = pm.askChooseTemplateToggle.first(),
                askStartRecordingToggle = pm.askStartRecordingToggle.first(),
                newNoteNamingRule = pm.newNoteNamingRule.first(),
                afterCreatingNote = pm.afterCreatingNote.first(),
                lastActiveFolder = pm.getLastActiveFolderSync(),
                lastOpenedNoteId = pm.getLastOpenedNoteIdSync(),
                floatingUseSideRail = floatings.useSideRail,
                railSide = floatings.railSide,
                railScale = floatings.railScale,
                railBottomOffsetDp = floatings.railBottomOffsetDp,
                railSpacingDp = floatings.railSpacingDp,
                railKeepTextClear = floatings.keepTextClearOfRail,
                railButtonOrder = floatings.railButtonOrder,
                toggleScale = floatings.toggleScale,
                toggleBottomOffsetDp = floatings.toggleBottomOffsetDp,
                toggleGapDp = floatings.toggleGapDp,
                formatBarScale = floatings.formatBarScale,
                formatBarBottomOffsetDp = floatings.formatBarBottomOffsetDp,
                formatBarWidthPercent = floatings.formatBarWidthPercent,
                formatBarAlignment = floatings.formatBarAlignment,
                waveformScale = floatings.waveformScale,
                waveformShow = floatings.showWaveform
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    private suspend fun applySettingsToPreferencesManager(s: AppSettings, pm: PreferencesManager) = withContext(Dispatchers.IO) {
        isSyncingWithDataStore = true
        try {
            pm.setDictationMode(s.dictationMode)
            pm.setTimedMinutes(s.timedMinutes)
            pm.setPauseToleranceSeconds(s.pauseToleranceSeconds)
            pm.setIdleAutoStopMinutes(s.idleAutoStopMinutes)
            pm.setMuteBeepMethod(s.muteBeepMethod)
            pm.setInsertionMode(s.insertionMode)
            pm.setOfflinePreferred(s.offlinePreferred)
            pm.setKeepScreenOn(s.keepScreenOn)

            pm.setDefaultLanguage(s.defaultLanguage)
            pm.setFavoriteLanguages(s.favoriteLanguages)

            pm.setAppTheme(s.appTheme)
            pm.setEditorFontSize(s.editorFontSize)
            pm.setEditorLineSpacing(s.editorLineSpacing)
            pm.setEditorFontFamily(s.editorFontFamily)
            pm.setCustomThemeColors(
                primary = s.customPrimaryColor,
                bg = s.customBackgroundColor,
                surface = s.customSurfaceColor,
                text = s.customTextColor,
                secondary = s.customSecondaryColor,
                updateAppThemeToCustom = (s.appTheme.uppercase() == "CUSTOM")
            )
            pm.setCustomFont(s.customFontName, s.customFontPath)
            pm.setEditorLetterSpacing(s.editorLetterSpacing)
            pm.setEditorParagraphSpacing(s.editorParagraphSpacing)
            pm.setEditorTextAlignment(s.editorTextAlignment)
            pm.setEditorMarginDp(s.editorMarginDp)
            pm.setEditorMaxWidthDp(s.editorMaxWidthDp)

            pm.setToolbarItemOrder(s.toolbarItemOrder)
            pm.setToolbarHiddenItems(s.toolbarHiddenItems)

            pm.setMicButtonSize(s.micButtonSize)
            pm.setMicButtonShape(s.micButtonShape)
            pm.setMicButtonColor(s.micButtonColor)
            pm.setMicButtonPosition(s.micButtonPosition)
            pm.setMicButtonOffsetY(s.micButtonOffsetY)

            pm.setStatusChipStyle(s.statusChipStyle)
            pm.setUiDensity(s.uiDensity)
            pm.setCornerRadiusDp(s.cornerRadiusDp)
            pm.setDictationAnimationStyle(s.dictationAnimationStyle)
            pm.setHapticsLevel(s.hapticsLevel)

            pm.setPipelineSpacing(s.pipelineSpacing)
            pm.setPipelineAutoCap(s.pipelineAutoCap)
            pm.setPipelineCommands(s.pipelineCommands)

            pm.setKeyboardType(s.keyboardType)
            pm.setShowWordCount(s.showWordCount)
            pm.setKeyboardLayoutJson(s.keyboardLayoutJson)
            pm.setKeyboardKeyHeightDp(s.keyboardKeyHeightDp)
            pm.setKeyboardFontSizeSp(s.keyboardFontSizeSp)
            pm.setKeyboardHaptic(s.keyboardHaptic)
            pm.setKeyboardHoldBehavior(s.keyboardHoldBehavior)
            pm.setKeyboardEditMode(s.keyboardEditMode)
            pm.setKeyboardLayoutVersion(s.keyboardLayoutVersion)

            pm.setTrashAutoEmptyDays(s.trashAutoEmptyDays)
            pm.setDefaultNewNoteFolder(s.defaultNewNoteFolder)
            pm.setShowNoteMetricsInList(s.showNoteMetricsInList)
            pm.setNoteMetricsStyle(s.noteMetricsStyle)
            pm.setNoteMetricsOrder(s.noteMetricsOrder)
            pm.setNoteMetricsHiddenItems(s.noteMetricsHiddenItems)
            pm.setExpandedFolders(s.expandedFolders)
            pm.setCustomFolders(s.customFolders)
            pm.setNewNoteLocationMode(s.newNoteLocationMode)
            pm.setFixedNewNoteFolder(s.fixedNewNoteFolder)
            pm.setAskBeforeCreating(s.askBeforeCreating)
            pm.setAskChooseTemplateToggle(s.askChooseTemplateToggle)
            pm.setAskStartRecordingToggle(s.askStartRecordingToggle)
            pm.setNewNoteNamingRule(s.newNoteNamingRule)
            pm.setAfterCreatingNote(s.afterCreatingNote)
            pm.setLastActiveFolder(s.lastActiveFolder)
            pm.setLastOpenedNoteId(s.lastOpenedNoteId)

            pm.setFloatingUseSideRail(s.floatingUseSideRail)
            pm.setRailSide(s.railSide)
            pm.setRailScale(s.railScale)
            pm.setRailBottomOffsetDp(s.railBottomOffsetDp)
            pm.setRailSpacingDp(s.railSpacingDp)
            pm.setRailKeepTextClear(s.railKeepTextClear)
            pm.setRailButtonOrder(s.railButtonOrder)
            pm.setToggleScale(s.toggleScale)
            pm.setToggleBottomOffsetDp(s.toggleBottomOffsetDp)
            pm.setToggleGapDp(s.toggleGapDp)
            pm.setFormatBarScale(s.formatBarScale)
            pm.setFormatBarBottomOffsetDp(s.formatBarBottomOffsetDp)
            pm.setFormatBarWidthPercent(s.formatBarWidthPercent)
            pm.setFormatBarAlignment(s.formatBarAlignment)
            pm.setWaveformScale(s.waveformScale)
            pm.setWaveformShow(s.waveformShow)
        } catch (_: Exception) {
        } finally {
            isSyncingWithDataStore = false
        }
    }

    fun serializeToJson(s: AppSettings): JSONObject {
        val root = JSONObject()
        root.put("schemaVersion", s.schemaVersion)

        val dictation = JSONObject().apply {
            put("dictation_mode", s.dictationMode)
            put("timed_minutes", s.timedMinutes)
            put("pause_tolerance_seconds", s.pauseToleranceSeconds)
            put("idle_auto_stop_minutes", s.idleAutoStopMinutes)
            put("mute_beep_method", s.muteBeepMethod)
            put("insertion_mode", s.insertionMode)
            put("offline_preferred", s.offlinePreferred)
            put("keep_screen_on", s.keepScreenOn)
        }
        root.put("dictation", dictation)

        val language = JSONObject().apply {
            put("default_language", s.defaultLanguage)
            val favArr = JSONArray()
            s.favoriteLanguages.forEach { favArr.put(it) }
            put("favorite_languages", favArr)
        }
        root.put("language", language)

        val appearance = JSONObject().apply {
            put("app_theme", s.appTheme)
            put("editor_font_size", s.editorFontSize.toDouble())
            put("editor_line_spacing", s.editorLineSpacing.toDouble())
            put("editor_font_family", s.editorFontFamily)
            put("custom_primary_color", s.customPrimaryColor)
            put("custom_primary_hex", String.format("#%06X", 0xFFFFFF and s.customPrimaryColor))
            put("custom_background_color", s.customBackgroundColor)
            put("custom_background_hex", String.format("#%06X", 0xFFFFFF and s.customBackgroundColor))
            put("custom_surface_color", s.customSurfaceColor)
            put("custom_surface_hex", String.format("#%06X", 0xFFFFFF and s.customSurfaceColor))
            put("custom_text_color", s.customTextColor)
            put("custom_text_hex", String.format("#%06X", 0xFFFFFF and s.customTextColor))
            put("custom_secondary_color", s.customSecondaryColor)
            put("custom_secondary_hex", String.format("#%06X", 0xFFFFFF and s.customSecondaryColor))
            put("custom_font_name", s.customFontName)
            if (s.customFontPath != null) put("custom_font_path", s.customFontPath)
            put("editor_letter_spacing", s.editorLetterSpacing.toDouble())
            put("editor_paragraph_spacing", s.editorParagraphSpacing)
            put("editor_text_alignment", s.editorTextAlignment)
            put("editor_margin_dp", s.editorMarginDp)
            put("editor_max_width_dp", s.editorMaxWidthDp)
            put("status_chip_style", s.statusChipStyle)
            put("ui_density", s.uiDensity)
            put("corner_radius_dp", s.cornerRadiusDp)
            put("dictation_animation_style", s.dictationAnimationStyle)
            put("haptics_level", s.hapticsLevel)
        }
        root.put("appearance", appearance)

        val toolbar = JSONObject().apply {
            put("toolbar_item_order", s.toolbarItemOrder)
            val hiddenArr = JSONArray()
            s.toolbarHiddenItems.forEach { hiddenArr.put(it) }
            put("toolbar_hidden_items", hiddenArr)
        }
        root.put("toolbar", toolbar)

        val micButton = JSONObject().apply {
            put("mic_button_size", s.micButtonSize)
            put("mic_button_shape", s.micButtonShape)
            put("mic_button_color", s.micButtonColor)
            put("mic_button_position", s.micButtonPosition)
            put("mic_button_offset_y", s.micButtonOffsetY)
        }
        root.put("micButton", micButton)

        val pipeline = JSONObject().apply {
            put("pipeline_spacing", s.pipelineSpacing)
            put("pipeline_autocap", s.pipelineAutoCap)
            put("pipeline_commands", s.pipelineCommands)
        }
        root.put("pipeline", pipeline)

        val keyboard = JSONObject().apply {
            put("keyboard_type", s.keyboardType)
            put("show_word_count", s.showWordCount)
            if (s.keyboardLayoutJson != null) put("keyboard_layout_json", s.keyboardLayoutJson)
            put("keyboard_key_height_dp", s.keyboardKeyHeightDp)
            put("keyboard_font_size_sp", s.keyboardFontSizeSp)
            put("keyboard_haptic", s.keyboardHaptic)
            put("keyboard_hold_behavior", s.keyboardHoldBehavior)
            put("keyboard_edit_mode", s.keyboardEditMode)
            put("keyboard_layout_version", s.keyboardLayoutVersion)
        }
        root.put("keyboard", keyboard)

        val notesManager = JSONObject().apply {
            put("trash_auto_empty_days", s.trashAutoEmptyDays)
            put("default_new_note_folder", s.defaultNewNoteFolder)
            put("show_note_metrics_in_list", s.showNoteMetricsInList)
            put("note_metrics_style", s.noteMetricsStyle)
            put("note_metrics_order", s.noteMetricsOrder)
            val metricsHiddenArr = JSONArray()
            s.noteMetricsHiddenItems.forEach { metricsHiddenArr.put(it) }
            put("note_metrics_hidden_items", metricsHiddenArr)

            val expArr = JSONArray()
            s.expandedFolders.forEach { expArr.put(it) }
            put("expanded_folders", expArr)

            val custArr = JSONArray()
            s.customFolders.forEach { custArr.put(it) }
            put("custom_folders", custArr)

            put("new_note_location_mode", s.newNoteLocationMode)
            put("fixed_new_note_folder", s.fixedNewNoteFolder)
            put("ask_before_creating", s.askBeforeCreating)
            put("ask_choose_template_toggle", s.askChooseTemplateToggle)
            put("ask_start_recording_toggle", s.askStartRecordingToggle)
            put("new_note_naming_rule", s.newNoteNamingRule)
            put("after_creating_note", s.afterCreatingNote)
            put("last_active_folder", s.lastActiveFolder)
            put("last_opened_note_id", s.lastOpenedNoteId)
        }
        root.put("notesManager", notesManager)

        val floatingControls = JSONObject().apply {
            put("floating_use_side_rail", s.floatingUseSideRail)
            put("rail_side", s.railSide)
            put("rail_scale", s.railScale.toDouble())
            put("rail_bottom_offset_dp", s.railBottomOffsetDp)
            put("rail_spacing_dp", s.railSpacingDp)
            put("rail_keep_text_clear", s.railKeepTextClear)
            val orderArr = JSONArray()
            s.railButtonOrder.forEach { orderArr.put(it) }
            put("rail_button_order", orderArr)
            put("toggle_scale", s.toggleScale.toDouble())
            put("toggle_bottom_offset_dp", s.toggleBottomOffsetDp)
            put("toggle_gap_dp", s.toggleGapDp)
            put("format_bar_scale", s.formatBarScale.toDouble())
            put("format_bar_bottom_offset_dp", s.formatBarBottomOffsetDp)
            put("format_bar_width_percent", s.formatBarWidthPercent)
            put("format_bar_alignment", s.formatBarAlignment)
            put("waveform_scale", s.waveformScale.toDouble())
            put("waveform_show", s.waveformShow)
        }
        root.put("floatingControls", floatingControls)

        val markdown = JSONObject().apply {
            put("md_enabled", s.mdEnabled)
            put("md_headings", s.mdHeadings)
            put("md_bold_italic", s.mdBoldItalic)
            put("md_strikethrough", s.mdStrikethrough)
            put("md_highlight", s.mdHighlight)
            put("md_inline_code", s.mdInlineCode)
            put("md_code_blocks", s.mdCodeBlocks)
            put("md_blockquotes", s.mdBlockquotes)
            put("md_lists", s.mdLists)
            put("md_task_lists", s.mdTaskLists)
            put("md_links", s.mdLinks)
            put("md_tables", s.mdTables)
            put("md_horizontal_rule", s.mdHorizontalRule)
            put("md_reading_font_scale", s.mdReadingFontScale.toDouble())
            put("md_live_styling", s.mdLiveStyling)
            put("md_dim_syntax", s.mdDimSyntax)
            put("md_heading_scale", s.mdHeadingScale.toDouble())
            put("md_code_monospace", s.mdCodeMonospace)
            put("md_auto_continue_lists", s.mdAutoContinueLists)
            put("md_format_bar_enabled", s.mdFormatBarEnabled)
            val btnArr = JSONArray()
            s.mdFormatBarButtons.forEach { btnArr.put(it) }
            put("md_format_bar_buttons", btnArr)
            val orderArr = JSONArray()
            s.mdFormatBarOrder.forEach { orderArr.put(it) }
            put("md_format_bar_order", orderArr)
            put("md_tappable_checkboxes", s.mdTappableCheckboxes)
        }
        root.put("markdown", markdown)

        val readingMode = JSONObject().apply {
            put("enabled", s.readingModeEnabled)
        }
        root.put("readingMode", readingMode)

        return root
    }

    fun deserializeFromJson(root: JSONObject): AppSettings {
        val def = AppSettings()
        return try {
            val schemaVer = root.optInt("schemaVersion", 1)

            val dictObj = root.optJSONObject("dictation")
            val dictMode = dictObj?.optString("dictation_mode", def.dictationMode) ?: root.optString("dictation_mode", def.dictationMode)
            val timedMin = dictObj?.optInt("timed_minutes", def.timedMinutes) ?: root.optInt("timed_minutes", def.timedMinutes)
            val pauseTol = dictObj?.optInt("pause_tolerance_seconds", def.pauseToleranceSeconds) ?: root.optInt("pause_tolerance_seconds", def.pauseToleranceSeconds)
            val idleStop = dictObj?.optInt("idle_auto_stop_minutes", def.idleAutoStopMinutes) ?: root.optInt("idle_auto_stop_minutes", def.idleAutoStopMinutes)
            val muteMethod = dictObj?.optString("mute_beep_method", def.muteBeepMethod) ?: root.optString("mute_beep_method", def.muteBeepMethod)
            val insertMode = dictObj?.optString("insertion_mode", def.insertionMode) ?: root.optString("insertion_mode", def.insertionMode)
            val offPref = dictObj?.optBoolean("offline_preferred", def.offlinePreferred) ?: root.optBoolean("offline_preferred", def.offlinePreferred)
            val keepScreen = dictObj?.optBoolean("keep_screen_on", def.keepScreenOn) ?: root.optBoolean("keep_screen_on", def.keepScreenOn)

            val langObj = root.optJSONObject("language")
            val defLang = langObj?.optString("default_language", def.defaultLanguage) ?: root.optString("default_language", def.defaultLanguage)
            val favLangs = parseStringSet(langObj?.opt("favorite_languages") ?: root.opt("favorite_languages"), def.favoriteLanguages)

            val appObj = root.optJSONObject("appearance")
            val appTheme = when {
                appObj != null && appObj.has("app_theme") -> appObj.getString("app_theme")
                root.has("app_theme") -> root.getString("app_theme")
                else -> def.appTheme
            }
            val fontSize = when {
                appObj != null && appObj.has("editor_font_size") -> appObj.getDouble("editor_font_size").toFloat()
                root.has("editor_font_size") -> root.getDouble("editor_font_size").toFloat()
                else -> def.editorFontSize
            }
            val lineSpacing = when {
                appObj != null && appObj.has("editor_line_spacing") -> appObj.getDouble("editor_line_spacing").toFloat()
                root.has("editor_line_spacing") -> root.getDouble("editor_line_spacing").toFloat()
                else -> def.editorLineSpacing
            }
            val fontFamily = when {
                appObj != null && appObj.has("editor_font_family") -> appObj.getString("editor_font_family")
                root.has("editor_font_family") -> root.getString("editor_font_family")
                else -> def.editorFontFamily
            }
            val customPrimary = parseColorInt(appObj, root, "custom_primary_color", def.customPrimaryColor)
            val customBg = parseColorInt(appObj, root, "custom_background_color", def.customBackgroundColor)
            val customSurface = parseColorInt(appObj, root, "custom_surface_color", def.customSurfaceColor)
            val customText = parseColorInt(appObj, root, "custom_text_color", def.customTextColor)
            val customSec = parseColorInt(appObj, root, "custom_secondary_color", def.customSecondaryColor)
            val customFontName = when {
                appObj != null && appObj.has("custom_font_name") -> appObj.getString("custom_font_name")
                root.has("custom_font_name") -> root.getString("custom_font_name")
                else -> def.customFontName
            }
            val customFontPath = when {
                appObj != null && appObj.has("custom_font_path") && !appObj.isNull("custom_font_path") -> appObj.getString("custom_font_path")
                root.has("custom_font_path") && !root.isNull("custom_font_path") -> root.getString("custom_font_path")
                else -> null
            }
            val letterSpacing = when {
                appObj != null && appObj.has("editor_letter_spacing") -> appObj.getDouble("editor_letter_spacing").toFloat()
                root.has("editor_letter_spacing") -> root.getDouble("editor_letter_spacing").toFloat()
                else -> def.editorLetterSpacing
            }
            val paragraphSpacing = when {
                appObj != null && appObj.has("editor_paragraph_spacing") -> appObj.getInt("editor_paragraph_spacing")
                root.has("editor_paragraph_spacing") -> root.getInt("editor_paragraph_spacing")
                else -> def.editorParagraphSpacing
            }
            val textAlignment = when {
                appObj != null && appObj.has("editor_text_alignment") -> appObj.getString("editor_text_alignment")
                root.has("editor_text_alignment") -> root.getString("editor_text_alignment")
                else -> def.editorTextAlignment
            }
            val marginDp = when {
                appObj != null && appObj.has("editor_margin_dp") -> appObj.getInt("editor_margin_dp")
                root.has("editor_margin_dp") -> root.getInt("editor_margin_dp")
                else -> def.editorMarginDp
            }
            val maxWidthDp = when {
                appObj != null && appObj.has("editor_max_width_dp") -> appObj.getInt("editor_max_width_dp")
                root.has("editor_max_width_dp") -> root.getInt("editor_max_width_dp")
                else -> def.editorMaxWidthDp
            }
            val statusChip = when {
                appObj != null && appObj.has("status_chip_style") -> appObj.getString("status_chip_style")
                root.has("status_chip_style") -> root.getString("status_chip_style")
                else -> def.statusChipStyle
            }
            val uiDensity = when {
                appObj != null && appObj.has("ui_density") -> appObj.getString("ui_density")
                root.has("ui_density") -> root.getString("ui_density")
                else -> def.uiDensity
            }
            val cornerRadius = when {
                appObj != null && appObj.has("corner_radius_dp") -> appObj.getInt("corner_radius_dp")
                root.has("corner_radius_dp") -> root.getInt("corner_radius_dp")
                else -> def.cornerRadiusDp
            }
            val dictAnim = when {
                appObj != null && appObj.has("dictation_animation_style") -> appObj.getString("dictation_animation_style")
                root.has("dictation_animation_style") -> root.getString("dictation_animation_style")
                else -> def.dictationAnimationStyle
            }
            val haptics = when {
                appObj != null && appObj.has("haptics_level") -> appObj.getString("haptics_level")
                root.has("haptics_level") -> root.getString("haptics_level")
                else -> def.hapticsLevel
            }

            val tbObj = root.optJSONObject("toolbar")
            val tbOrder = tbObj?.optString("toolbar_item_order", def.toolbarItemOrder) ?: root.optString("toolbar_item_order", def.toolbarItemOrder)
            val tbHidden = parseStringSet(tbObj?.opt("toolbar_hidden_items") ?: root.opt("toolbar_hidden_items"), def.toolbarHiddenItems)

            val micObj = root.optJSONObject("micButton")
            val micSize = micObj?.optString("mic_button_size", def.micButtonSize) ?: root.optString("mic_button_size", def.micButtonSize)
            val micShape = micObj?.optString("mic_button_shape", def.micButtonShape) ?: root.optString("mic_button_shape", def.micButtonShape)
            val micColor = micObj?.optString("mic_button_color", def.micButtonColor) ?: root.optString("mic_button_color", def.micButtonColor)
            val micPos = micObj?.optString("mic_button_position", def.micButtonPosition) ?: root.optString("mic_button_position", def.micButtonPosition)
            val micOffsetY = micObj?.optInt("mic_button_offset_y", def.micButtonOffsetY) ?: root.optInt("mic_button_offset_y", def.micButtonOffsetY)

            val pipeObj = root.optJSONObject("pipeline")
            val pipeSpacing = pipeObj?.optBoolean("pipeline_spacing", def.pipelineSpacing) ?: root.optBoolean("pipeline_spacing", def.pipelineSpacing)
            val pipeAutoCap = pipeObj?.optBoolean("pipeline_autocap", def.pipelineAutoCap) ?: root.optBoolean("pipeline_autocap", def.pipelineAutoCap)
            val pipeCommands = pipeObj?.optBoolean("pipeline_commands", def.pipelineCommands) ?: root.optBoolean("pipeline_commands", def.pipelineCommands)

            val kbObj = root.optJSONObject("keyboard")
            val kbType = kbObj?.optString("keyboard_type", def.keyboardType) ?: root.optString("keyboard_type", def.keyboardType)
            val showWords = kbObj?.optBoolean("show_word_count", def.showWordCount) ?: root.optBoolean("show_word_count", def.showWordCount)
            val kbLayoutJson = if (kbObj?.has("keyboard_layout_json") == true && !kbObj.isNull("keyboard_layout_json")) kbObj.optString("keyboard_layout_json") else root.optString("keyboard_layout_json", null)
            val kbKeyHeight = kbObj?.optInt("keyboard_key_height_dp", def.keyboardKeyHeightDp) ?: root.optInt("keyboard_key_height_dp", def.keyboardKeyHeightDp)
            val kbFontSize = kbObj?.optInt("keyboard_font_size_sp", def.keyboardFontSizeSp) ?: root.optInt("keyboard_font_size_sp", def.keyboardFontSizeSp)
            val kbHaptic = kbObj?.optBoolean("keyboard_haptic", def.keyboardHaptic) ?: root.optBoolean("keyboard_haptic", def.keyboardHaptic)
            val kbHold = kbObj?.optString("keyboard_hold_behavior", def.keyboardHoldBehavior) ?: root.optString("keyboard_hold_behavior", def.keyboardHoldBehavior)
            val kbEdit = kbObj?.optBoolean("keyboard_edit_mode", def.keyboardEditMode) ?: root.optBoolean("keyboard_edit_mode", def.keyboardEditMode)
            val kbVer = kbObj?.optInt("keyboard_layout_version", def.keyboardLayoutVersion) ?: root.optInt("keyboard_layout_version", def.keyboardLayoutVersion)

            val nmObj = root.optJSONObject("notesManager")
            val trashDays = nmObj?.optInt("trash_auto_empty_days", def.trashAutoEmptyDays) ?: root.optInt("trash_auto_empty_days", def.trashAutoEmptyDays)
            val defFolder = nmObj?.optString("default_new_note_folder", def.defaultNewNoteFolder) ?: root.optString("default_new_note_folder", def.defaultNewNoteFolder)
            val showMetrics = nmObj?.optBoolean("show_note_metrics_in_list", def.showNoteMetricsInList) ?: root.optBoolean("show_note_metrics_in_list", def.showNoteMetricsInList)
            val metricsStyle = nmObj?.optString("note_metrics_style", def.noteMetricsStyle) ?: root.optString("note_metrics_style", def.noteMetricsStyle)
            val metricsOrder = nmObj?.optString("note_metrics_order", def.noteMetricsOrder) ?: root.optString("note_metrics_order", def.noteMetricsOrder)
            val metricsHidden = parseStringSet(nmObj?.opt("note_metrics_hidden_items") ?: root.opt("note_metrics_hidden_items"), def.noteMetricsHiddenItems)
            val expFolders = parseStringSet(nmObj?.opt("expanded_folders") ?: root.opt("expanded_folders"), def.expandedFolders)
            val custFolders = parseStringSet(nmObj?.opt("custom_folders") ?: root.opt("custom_folders"), def.customFolders)
            val locMode = nmObj?.optString("new_note_location_mode", def.newNoteLocationMode) ?: root.optString("new_note_location_mode", def.newNoteLocationMode)
            val fixedFolder = nmObj?.optString("fixed_new_note_folder", def.fixedNewNoteFolder) ?: root.optString("fixed_new_note_folder", def.fixedNewNoteFolder)
            val askCreate = nmObj?.optBoolean("ask_before_creating", def.askBeforeCreating) ?: root.optBoolean("ask_before_creating", def.askBeforeCreating)
            val askTpl = nmObj?.optBoolean("ask_choose_template_toggle", def.askChooseTemplateToggle) ?: root.optBoolean("ask_choose_template_toggle", def.askChooseTemplateToggle)
            val askRec = nmObj?.optBoolean("ask_start_recording_toggle", def.askStartRecordingToggle) ?: root.optBoolean("ask_start_recording_toggle", def.askStartRecordingToggle)
            val nameRule = nmObj?.optString("new_note_naming_rule", def.newNoteNamingRule) ?: root.optString("new_note_naming_rule", def.newNoteNamingRule)
            val afterCreate = nmObj?.optString("after_creating_note", def.afterCreatingNote) ?: root.optString("after_creating_note", def.afterCreatingNote)
            val lastActive = nmObj?.optString("last_active_folder", def.lastActiveFolder) ?: root.optString("last_active_folder", def.lastActiveFolder)
            val lastOpened = nmObj?.optLong("last_opened_note_id", def.lastOpenedNoteId) ?: root.optLong("last_opened_note_id", def.lastOpenedNoteId)

            val fcObj = root.optJSONObject("floatingControls")
            val useSideRail = fcObj?.optBoolean("floating_use_side_rail", def.floatingUseSideRail) ?: root.optBoolean("floating_use_side_rail", def.floatingUseSideRail)
            val railSide = fcObj?.optString("rail_side", def.railSide) ?: root.optString("rail_side", def.railSide)
            val railScale = (fcObj?.optDouble("rail_scale", def.railScale.toDouble()) ?: root.optDouble("rail_scale", def.railScale.toDouble())).toFloat()
            val railBottom = fcObj?.optInt("rail_bottom_offset_dp", def.railBottomOffsetDp) ?: root.optInt("rail_bottom_offset_dp", def.railBottomOffsetDp)
            val railSpacing = fcObj?.optInt("rail_spacing_dp", def.railSpacingDp) ?: root.optInt("rail_spacing_dp", def.railSpacingDp)
            val railKeep = fcObj?.optBoolean("rail_keep_text_clear", def.railKeepTextClear) ?: root.optBoolean("rail_keep_text_clear", def.railKeepTextClear)
            val railOrder = parseStringList(fcObj?.opt("rail_button_order") ?: root.opt("rail_button_order"), def.railButtonOrder)
            val toggleScale = (fcObj?.optDouble("toggle_scale", def.toggleScale.toDouble()) ?: root.optDouble("toggle_scale", def.toggleScale.toDouble())).toFloat()
            val toggleBottom = fcObj?.optInt("toggle_bottom_offset_dp", def.toggleBottomOffsetDp) ?: root.optInt("toggle_bottom_offset_dp", def.toggleBottomOffsetDp)
            val toggleGap = fcObj?.optInt("toggle_gap_dp", def.toggleGapDp) ?: root.optInt("toggle_gap_dp", def.toggleGapDp)
            val fbScale = (fcObj?.optDouble("format_bar_scale", def.formatBarScale.toDouble()) ?: root.optDouble("format_bar_scale", def.formatBarScale.toDouble())).toFloat()
            val fbBottom = fcObj?.optInt("format_bar_bottom_offset_dp", def.formatBarBottomOffsetDp) ?: root.optInt("format_bar_bottom_offset_dp", def.formatBarBottomOffsetDp)
            val fbWidth = fcObj?.optInt("format_bar_width_percent", def.formatBarWidthPercent) ?: root.optInt("format_bar_width_percent", def.formatBarWidthPercent)
            val fbAlign = fcObj?.optString("format_bar_alignment", def.formatBarAlignment) ?: root.optString("format_bar_alignment", def.formatBarAlignment)
            val wfScale = (fcObj?.optDouble("waveform_scale", def.waveformScale.toDouble()) ?: root.optDouble("waveform_scale", def.waveformScale.toDouble())).toFloat()
            val wfShow = fcObj?.optBoolean("waveform_show", def.waveformShow) ?: root.optBoolean("waveform_show", def.waveformShow)

            val mdObj = root.optJSONObject("markdown")
            val mdEn = mdObj?.optBoolean("md_enabled", def.mdEnabled) ?: root.optBoolean("md_enabled", def.mdEnabled)
            val mdH = mdObj?.optBoolean("md_headings", def.mdHeadings) ?: root.optBoolean("md_headings", def.mdHeadings)
            val mdBi = mdObj?.optBoolean("md_bold_italic", def.mdBoldItalic) ?: root.optBoolean("md_bold_italic", def.mdBoldItalic)
            val mdSt = mdObj?.optBoolean("md_strikethrough", def.mdStrikethrough) ?: root.optBoolean("md_strikethrough", def.mdStrikethrough)
            val mdHl = mdObj?.optBoolean("md_highlight", def.mdHighlight) ?: root.optBoolean("md_highlight", def.mdHighlight)
            val mdIc = mdObj?.optBoolean("md_inline_code", def.mdInlineCode) ?: root.optBoolean("md_inline_code", def.mdInlineCode)
            val mdCb = mdObj?.optBoolean("md_code_blocks", def.mdCodeBlocks) ?: root.optBoolean("md_code_blocks", def.mdCodeBlocks)
            val mdBq = mdObj?.optBoolean("md_blockquotes", def.mdBlockquotes) ?: root.optBoolean("md_blockquotes", def.mdBlockquotes)
            val mdLi = mdObj?.optBoolean("md_lists", def.mdLists) ?: root.optBoolean("md_lists", def.mdLists)
            val mdTl = mdObj?.optBoolean("md_task_lists", def.mdTaskLists) ?: root.optBoolean("md_task_lists", def.mdTaskLists)
            val mdLk = mdObj?.optBoolean("md_links", def.mdLinks) ?: root.optBoolean("md_links", def.mdLinks)
            val mdTb = mdObj?.optBoolean("md_tables", def.mdTables) ?: root.optBoolean("md_tables", def.mdTables)
            val mdHr = mdObj?.optBoolean("md_horizontal_rule", def.mdHorizontalRule) ?: root.optBoolean("md_horizontal_rule", def.mdHorizontalRule)
            val mdRfs = (mdObj?.optDouble("md_reading_font_scale", def.mdReadingFontScale.toDouble()) ?: root.optDouble("md_reading_font_scale", def.mdReadingFontScale.toDouble())).toFloat()
            val mdLs = mdObj?.optBoolean("md_live_styling", def.mdLiveStyling) ?: root.optBoolean("md_live_styling", def.mdLiveStyling)
            val mdDs = mdObj?.optBoolean("md_dim_syntax", def.mdDimSyntax) ?: root.optBoolean("md_dim_syntax", def.mdDimSyntax)
            val mdHs = (mdObj?.optDouble("md_heading_scale", def.mdHeadingScale.toDouble()) ?: root.optDouble("md_heading_scale", def.mdHeadingScale.toDouble())).toFloat()
            val mdCm = mdObj?.optBoolean("md_code_monospace", def.mdCodeMonospace) ?: root.optBoolean("md_code_monospace", def.mdCodeMonospace)
            val mdAc = mdObj?.optBoolean("md_auto_continue_lists", def.mdAutoContinueLists) ?: root.optBoolean("md_auto_continue_lists", def.mdAutoContinueLists)
            val mdFb = mdObj?.optBoolean("md_format_bar_enabled", def.mdFormatBarEnabled) ?: root.optBoolean("md_format_bar_enabled", def.mdFormatBarEnabled)
            val mdFbb = parseStringList(mdObj?.opt("md_format_bar_buttons") ?: root.opt("md_format_bar_buttons"), def.mdFormatBarButtons)
            val mdFbo = parseStringList(mdObj?.opt("md_format_bar_order") ?: root.opt("md_format_bar_order"), def.mdFormatBarOrder)
            val mdTc = mdObj?.optBoolean("md_tappable_checkboxes", def.mdTappableCheckboxes) ?: root.optBoolean("md_tappable_checkboxes", def.mdTappableCheckboxes)

            val rmObj = root.optJSONObject("readingMode")
            val readingMode = rmObj?.optBoolean("enabled", def.readingModeEnabled) ?: root.optBoolean("reading_mode_enabled", def.readingModeEnabled)

            AppSettings(
                schemaVersion = schemaVer,
                dictationMode = dictMode,
                timedMinutes = timedMin,
                pauseToleranceSeconds = pauseTol,
                idleAutoStopMinutes = idleStop,
                muteBeepMethod = muteMethod,
                insertionMode = insertMode,
                offlinePreferred = offPref,
                keepScreenOn = keepScreen,
                defaultLanguage = defLang,
                favoriteLanguages = favLangs,
                appTheme = appTheme,
                editorFontSize = fontSize,
                editorLineSpacing = lineSpacing,
                editorFontFamily = fontFamily,
                customPrimaryColor = customPrimary,
                customBackgroundColor = customBg,
                customSurfaceColor = customSurface,
                customTextColor = customText,
                customSecondaryColor = customSec,
                customFontName = customFontName,
                customFontPath = customFontPath,
                editorLetterSpacing = letterSpacing,
                editorParagraphSpacing = paragraphSpacing,
                editorTextAlignment = textAlignment,
                editorMarginDp = marginDp,
                editorMaxWidthDp = maxWidthDp,
                statusChipStyle = statusChip,
                uiDensity = uiDensity,
                cornerRadiusDp = cornerRadius,
                dictationAnimationStyle = dictAnim,
                hapticsLevel = haptics,
                toolbarItemOrder = tbOrder,
                toolbarHiddenItems = tbHidden,
                micButtonSize = micSize,
                micButtonShape = micShape,
                micButtonColor = micColor,
                micButtonPosition = micPos,
                micButtonOffsetY = micOffsetY,
                pipelineSpacing = pipeSpacing,
                pipelineAutoCap = pipeAutoCap,
                pipelineCommands = pipeCommands,
                keyboardType = kbType,
                showWordCount = showWords,
                keyboardLayoutJson = kbLayoutJson,
                keyboardKeyHeightDp = kbKeyHeight,
                keyboardFontSizeSp = kbFontSize,
                keyboardHaptic = kbHaptic,
                keyboardHoldBehavior = kbHold,
                keyboardEditMode = kbEdit,
                keyboardLayoutVersion = kbVer,
                trashAutoEmptyDays = trashDays,
                defaultNewNoteFolder = defFolder,
                showNoteMetricsInList = showMetrics,
                noteMetricsStyle = metricsStyle,
                noteMetricsOrder = metricsOrder,
                noteMetricsHiddenItems = metricsHidden,
                expandedFolders = expFolders,
                customFolders = custFolders,
                newNoteLocationMode = locMode,
                fixedNewNoteFolder = fixedFolder,
                askBeforeCreating = askCreate,
                askChooseTemplateToggle = askTpl,
                askStartRecordingToggle = askRec,
                newNoteNamingRule = nameRule,
                afterCreatingNote = afterCreate,
                lastActiveFolder = lastActive,
                lastOpenedNoteId = lastOpened,
                floatingUseSideRail = useSideRail,
                railSide = railSide,
                railScale = railScale,
                railBottomOffsetDp = railBottom,
                railSpacingDp = railSpacing,
                railKeepTextClear = railKeep,
                railButtonOrder = railOrder,
                toggleScale = toggleScale,
                toggleBottomOffsetDp = toggleBottom,
                toggleGapDp = toggleGap,
                formatBarScale = fbScale,
                formatBarBottomOffsetDp = fbBottom,
                formatBarWidthPercent = fbWidth,
                formatBarAlignment = fbAlign,
                waveformScale = wfScale,
                waveformShow = wfShow,
                mdEnabled = mdEn,
                mdHeadings = mdH,
                mdBoldItalic = mdBi,
                mdStrikethrough = mdSt,
                mdHighlight = mdHl,
                mdInlineCode = mdIc,
                mdCodeBlocks = mdCb,
                mdBlockquotes = mdBq,
                mdLists = mdLi,
                mdTaskLists = mdTl,
                mdLinks = mdLk,
                mdTables = mdTb,
                mdHorizontalRule = mdHr,
                mdReadingFontScale = mdRfs,
                mdLiveStyling = mdLs,
                mdDimSyntax = mdDs,
                mdHeadingScale = mdHs,
                mdCodeMonospace = mdCm,
                mdAutoContinueLists = mdAc,
                mdFormatBarEnabled = mdFb,
                mdFormatBarButtons = mdFbb,
                mdFormatBarOrder = mdFbo,
                mdTappableCheckboxes = mdTc,
                readingModeEnabled = readingMode
            )
        } catch (_: Exception) {
            def
        }
    }

    private fun parseStringSet(raw: Any?, default: Set<String>): Set<String> {
        if (raw == null || raw == JSONObject.NULL) return default
        return when (raw) {
            is JSONArray -> {
                val set = mutableSetOf<String>()
                for (i in 0 until raw.length()) {
                    val s = raw.optString(i)
                    if (s.isNotBlank()) set.add(s)
                }
                set
            }
            is Collection<*> -> raw.mapNotNull { it?.toString() }.toSet()
            is String -> if (raw.isBlank()) default else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            else -> default
        }
    }

    private fun parseStringList(raw: Any?, default: List<String>): List<String> {
        if (raw == null || raw == JSONObject.NULL) return default
        return when (raw) {
            is JSONArray -> {
                val list = mutableListOf<String>()
                for (i in 0 until raw.length()) {
                    val s = raw.optString(i)
                    if (s.isNotBlank()) list.add(s)
                }
                if (list.isNotEmpty()) list else default
            }
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            is String -> if (raw.isBlank()) default else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> default
        }
    }

    private fun parseColorInt(obj: JSONObject?, fallbackObj: JSONObject?, key: String, defaultVal: Int): Int {
        val hexKey = if (key.endsWith("_color")) key.replace("_color", "_hex") else "${key}_hex"
        val candidate = when {
            obj != null && obj.has(key) -> obj.opt(key)
            obj != null && obj.has(hexKey) -> obj.opt(hexKey)
            fallbackObj != null && fallbackObj.has(key) -> fallbackObj.opt(key)
            fallbackObj != null && fallbackObj.has(hexKey) -> fallbackObj.opt(hexKey)
            else -> null
        } ?: return defaultVal

        return when (candidate) {
            is Number -> candidate.toInt()
            is String -> try {
                if (candidate.startsWith("#")) android.graphics.Color.parseColor(candidate) else candidate.toLong().toInt()
            } catch (_: Exception) { defaultVal }
            else -> defaultVal
        }
    }
}
