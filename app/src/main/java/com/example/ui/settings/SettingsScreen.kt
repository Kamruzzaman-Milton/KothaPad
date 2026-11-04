package com.example.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.example.ui.navigation.horizontalSwipeNavigation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import com.example.ui.dialogs.AnalogClockPickerDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tune
import com.example.data.model.NotificationDisplayMode
import com.example.data.model.ScheduledReminder
import com.example.data.model.FloatingNotificationConfig
import com.example.ui.dialogs.EditReminderDialog
import com.example.data.model.Note
import com.example.data.model.Stamp
import androidx.compose.runtime.mutableIntStateOf
import com.example.ui.dialogs.EditTemplateDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.example.data.backup.BackupSummary
import com.example.data.backup.ParsedBackupData
import com.example.ui.dialogs.FinishSetupDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DictationMode
import com.example.data.model.InsertionMode
import com.example.ui.theme.AmoledBorder
import com.example.ui.theme.AmoledSurface
import com.example.ui.theme.AmoledSurfaceVariant

// ==========================================
// Reusable, Extensible Setting Data Models
// ==========================================

data class ChipOption(
    val key: String,
    val label: String
)

data class SettingButtonOption(
    val label: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

sealed interface SettingItem {
    val id: String

    data class Switch(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit
    ) : SettingItem

    data class Slider(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val value: Float,
        val valueRange: ClosedFloatingPointRange<Float>,
        val steps: Int = 0,
        val displayValue: String,
        val onValueChange: (Float) -> Unit
    ) : SettingItem

    data class ChipGroup(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val options: List<ChipOption>,
        val selectedKey: String,
        val onOptionSelected: (String) -> Unit
    ) : SettingItem

    data class ButtonRow(
        override val id: String,
        val title: String = "",
        val subtitle: String? = null,
        val buttons: List<SettingButtonOption>
    ) : SettingItem

    data class InfoText(
        override val id: String,
        val title: String = "",
        val description: String,
        val badge: String? = null,
        val actionButtonText: String? = null,
        val onActionClick: (() -> Unit)? = null
    ) : SettingItem

    data class CustomContent(
        override val id: String,
        val content: @Composable () -> Unit
    ) : SettingItem
}

data class SettingHeadlineSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val items: List<SettingItem>
)

// ==========================================
// Main Settings Screen
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dictationMode: String,
    timedMinutes: Int,
    pauseToleranceSeconds: Int,
    idleAutoStopMinutes: Int,
    muteBeepMethod: String,
    insertionMode: String,
    offlinePreferred: Boolean,
    keepScreenOn: Boolean,
    defaultLanguage: String,
    appTheme: String,
    fontSize: Float,
    lineSpacing: Float,
    fontFamily: String,
    pipelineSpacing: Boolean,
    pipelineAutoCap: Boolean,
    pipelineCommands: Boolean,
    showWordCount: Boolean,
    showNoteMetrics: Boolean = true,
    noteMetricsStyle: String = "COMPACT",
    noteMetricsOrder: String = com.example.ui.notes.NoteMetricsConfig.DEFAULT_ORDER_STRING,
    noteMetricsHiddenItems: Set<String> = emptySet(),
    onShowNoteMetricsChange: (Boolean) -> Unit = {},
    onNoteMetricsStyleChange: (String) -> Unit = {},
    onMoveNoteMetricItem: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onToggleNoteMetricVisibility: (String) -> Unit = {},
    onResetNoteMetricsOrder: () -> Unit = {},
    // Custom Theme Colors
    customPrimaryColor: Int = 0xFFFF7043.toInt(),
    customBackgroundColor: Int = 0xFF000000.toInt(),
    customSurfaceColor: Int = 0xFF121212.toInt(),
    customTextColor: Int = 0xFFFFFFFF.toInt(),
    customSecondaryColor: Int = 0xFFFF8A65.toInt(),
    onCustomThemeColorsChange: (primary: Int, bg: Int, surface: Int, text: Int, secondary: Int) -> Unit = { _, _, _, _, _ -> },
    // Typography Ext
    customFontName: String = "",
    customFontPath: String? = null,
    letterSpacing: Float = 0f,
    paragraphSpacing: Int = 8,
    textAlignment: String = "LEFT",
    marginDp: Int = 16,
    maxWidthDp: Int = 0,
    onCustomFontChange: (name: String, path: String?) -> Unit = { _, _ -> },
    onLetterSpacingChange: (Float) -> Unit = {},
    onParagraphSpacingChange: (Int) -> Unit = {},
    onTextAlignmentChange: (String) -> Unit = {},
    onMarginDpChange: (Int) -> Unit = {},
    onMaxWidthDpChange: (Int) -> Unit = {},
    // Toolbar Items & Order
    toolbarItemOrder: String = "DICTATION_STATUS,METRICS,KEYBOARD_MODE,TEXT_SIZE,TEXT_STYLE,AUDIO_SOURCE",
    toolbarHiddenItems: Set<String> = emptySet(),
    onMoveToolbarItem: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onToggleToolbarItemVisibility: (String) -> Unit = {},
    onResetToolbarOrder: () -> Unit = {},
    // Mic Button & Cluster
    micButtonSize: String = "MEDIUM",
    micButtonShape: String = "CIRCLE",
    micButtonColor: String = "DEFAULT",
    micButtonPosition: String = "RIGHT",
    micButtonOffsetY: Int = 8,
    onMicButtonSizeChange: (String) -> Unit = {},
    onMicButtonShapeChange: (String) -> Unit = {},
    onMicButtonColorChange: (String) -> Unit = {},
    onMicButtonPositionChange: (String) -> Unit = {},
    onMicButtonOffsetYChange: (Int) -> Unit = {},
    // Visual Polish, Animation & Haptics
    statusChipStyle: String = "PILL",
    uiDensity: String = "NORMAL",
    cornerRadiusDp: Int = 12,
    dictationAnimationStyle: String = "PULSE_RING",
    hapticsLevel: String = "MEDIUM",
    onStatusChipStyleChange: (String) -> Unit = {},
    onUiDensityChange: (String) -> Unit = {},
    onCornerRadiusDpChange: (Int) -> Unit = {},
    onDictationAnimationStyleChange: (String) -> Unit = {},
    onHapticsLevelChange: (String) -> Unit = {},
    // Keyboard Settings
    activeKeyboardIndex: Int = 0,
    onActiveKeyboardIndexChange: (Int) -> Unit = {},
    keyboardKeyHeightDp: Int = 40,
    keyboardFontSizeSp: Int = 15,
    keyboardHaptic: Boolean = true,
    keyboardHoldBehavior: String = "CUSTOMIZE",
    onKeyboardKeyHeightChange: (Int) -> Unit = {},
    onKeyboardFontSizeChange: (Int) -> Unit = {},
    onKeyboardHapticChange: (Boolean) -> Unit = {},
    onKeyboardHoldBehaviorChange: (String) -> Unit = {},
    onResetKeyboardLayout: (Int) -> Unit = {},
    onExportKeyboardLayout: (Int) -> String = { "" },
    onImportKeyboardLayout: (String, Int) -> Boolean = { _, _ -> false },
    // Callbacks
    onDictationModeChange: (String) -> Unit,
    onTimedMinutesChange: (Int) -> Unit,
    onPauseToleranceChange: (Int) -> Unit,
    onIdleAutoStopChange: (Int) -> Unit,
    onMuteBeepMethodChange: (String) -> Unit,
    onInsertionModeChange: (String) -> Unit,
    onOfflinePreferredChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onAppThemeChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onFontFamilyChange: (String) -> Unit,
    onPipelineSpacingChange: (Boolean) -> Unit,
    onPipelineAutoCapChange: (Boolean) -> Unit,
    onPipelineCommandsChange: (Boolean) -> Unit,
    onShowWordCountChange: (Boolean) -> Unit,
    onOpenMarkdownSettings: () -> Unit = {},
    // Floating Layout Settings
    floatingLayoutSettings: com.example.data.model.FloatingLayoutSettings = com.example.data.model.FloatingLayoutSettings(),
    onFloatingUseSideRailChange: (Boolean) -> Unit = {},
    onRailSideChange: (String) -> Unit = {},
    onRailScaleChange: (Float) -> Unit = {},
    onRailBottomOffsetChange: (Int) -> Unit = {},
    onRailSpacingChange: (Int) -> Unit = {},
    onRailKeepTextClearChange: (Boolean) -> Unit = {},
    onMoveRailButtonItem: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onResetRailButtonOrder: () -> Unit = {},
    onToggleScaleChange: (Float) -> Unit = {},
    onToggleBottomOffsetChange: (Int) -> Unit = {},
    onToggleGapChange: (Int) -> Unit = {},
    onFormatBarScaleChange: (Float) -> Unit = {},
    onFormatBarBottomOffsetChange: (Int) -> Unit = {},
    onFormatBarWidthPercentChange: (Int) -> Unit = {},
    onFormatBarAlignmentChange: (String) -> Unit = {},
    onFormatBarVisibleDuringDictationChange: (Boolean) -> Unit = {},
    onFormatBarVisibleWithoutCursorChange: (Boolean) -> Unit = {},
    markdownSettings: com.example.markdown.MarkdownSettings = com.example.markdown.MarkdownSettings(),
    onUpdateMarkdownSettings: ((com.example.markdown.MarkdownSettings) -> com.example.markdown.MarkdownSettings) -> Unit = {},
    onWaveformScaleChange: (Float) -> Unit = {},
    onWaveformShowChange: (Boolean) -> Unit = {},
    onReadingNavBottomOffsetChange: (Int) -> Unit = {},
    onResetFloatingLayout: () -> Unit = {},
    // Backup & Restore Callbacks
    onExportBackup: (Uri, (Result<BackupSummary>) -> Unit) -> Unit = { _, _ -> },
    onValidateBackup: (Uri, (Result<ParsedBackupData>) -> Unit) -> Unit = { _, _ -> },
    onRestoreBackup: (ParsedBackupData, (Result<BackupSummary>) -> Unit) -> Unit = { _, _ -> },
    // Automatic Backup Callbacks & State
    autoBackupEnabled: Boolean = false,
    autoBackupSchedule: String = "OFF",
    autoBackupCustomValue: Int = 1,
    autoBackupCustomUnit: String = "HOURS",
    autoBackupOnAppOpen: Boolean = true,
    autoBackupKeepCount: Int = 5,
    autoBackupLocationUri: String = "",
    autoBackupOnlyCharging: Boolean = false,
    autoBackupSkipLowStorage: Boolean = false,
    autoBackupLastTime: Long = 0L,
    autoBackupLastSize: Long = 0L,
    autoBackupLastStatus: String = "",
    onAutoBackupEnabledChange: (Boolean) -> Unit = {},
    onAutoBackupScheduleChange: (String) -> Unit = {},
    onAutoBackupCustomIntervalChange: (value: Int, unit: String) -> Unit = { _, _ -> },
    onAutoBackupOnAppOpenChange: (Boolean) -> Unit = {},
    onAutoBackupKeepCountChange: (Int) -> Unit = {},
    onAutoBackupLocationUriChange: (String) -> Unit = {},
    onAutoBackupOnlyChargingChange: (Boolean) -> Unit = {},
    onAutoBackupSkipLowStorageChange: (Boolean) -> Unit = {},
    onTriggerManualAutoBackup: ((Result<com.example.data.backup.AutoBackupExecutionResult>) -> Unit) -> Unit = {},
    onViewAutoBackupsClick: () -> Unit = {},
    // Templates & Trash Callbacks & State
    templates: List<Stamp> = emptyList(),
    onSaveTemplate: (Stamp) -> Unit = {},
    onDeleteTemplate: (Stamp) -> Unit = {},
    onPinTemplate: (Stamp) -> Unit = {},
    onReorderTemplates: (List<Stamp>) -> Unit = {},
    trashedNotes: List<Note> = emptyList(),
    onRestoreNote: (Note) -> Unit = {},
    onPermanentlyDeleteNote: (Note) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    // Notifications & Reminders Callbacks & State
    scheduledReminders: List<ScheduledReminder> = emptyList(),
    floatingNotificationConfig: FloatingNotificationConfig = FloatingNotificationConfig(),
    onSaveScheduledReminder: (ScheduledReminder) -> Unit = {},
    onDeleteScheduledReminder: (String) -> Unit = {},
    onToggleScheduledReminderEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onUpdateFloatingNotificationConfig: (FloatingNotificationConfig) -> Unit = {},
    onPreviewFloatingNotification: (ScheduledReminder) -> Unit = {},
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {


    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }

    // Templates & Trash state
    var editingTemplate by remember { mutableStateOf<Stamp?>(null) }
    var isCreatingTemplate by remember { mutableStateOf(false) }
    var selectedTemplatesTrashTab by remember { mutableIntStateOf(0) }
    var showEmptyTrashConfirmDialog by remember { mutableStateOf(false) }
    var noteToDeletePermanently by remember { mutableStateOf<Note?>(null) }

    // Backup & Restore states
    var isExporting by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var isManualBackingUp by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var pendingRestoreData by remember { mutableStateOf<ParsedBackupData?>(null) }
    var showFinishSetupDialog by remember { mutableStateOf(false) }

    // Notifications & Reminders state
    var editingReminder by remember { mutableStateOf<ScheduledReminder?>(null) }
    var isCreatingReminder by remember { mutableStateOf(false) }
    var showInitialClockPicker by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            isExporting = true
            onExportBackup(uri) { result ->
                isExporting = false
                result.onSuccess { summary ->
                    Toast.makeText(
                        context,
                        "Backup exported: ${summary.notesCount} notes, ${summary.templatesCount} templates",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        "Export failed: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isValidating = true
            onValidateBackup(uri) { result ->
                isValidating = false
                result.onSuccess { parsed ->
                    pendingRestoreData = parsed
                }.onFailure { err ->
                    validationError = err.message ?: "Invalid or unsupported backup file"
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                onAutoBackupLocationUriChange(uri.toString())
                Toast.makeText(context, "Folder selected for backups", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to persist folder permission: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Exactly one section open at a time; starts with ALL collapsed
    var expandedSectionId by remember { mutableStateOf<String?>(null) }

    // Check notification policy access for vibrate once
    val notifManager = remember {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }
    val needsDndPermission = muteBeepMethod == "VIBRATE_ONCE" &&
            notifManager != null && !notifManager.isNotificationPolicyAccessGranted

    // Construct the fully data-driven sections list
    val sections = remember(
        activeKeyboardIndex, keyboardKeyHeightDp, keyboardFontSizeSp, keyboardHaptic, keyboardHoldBehavior,
        dictationMode, muteBeepMethod, pauseToleranceSeconds, insertionMode,
        offlinePreferred, keepScreenOn, idleAutoStopMinutes,
        appTheme, fontSize, lineSpacing, fontFamily, showWordCount, showNoteMetrics, noteMetricsStyle, noteMetricsOrder, noteMetricsHiddenItems,
        customPrimaryColor, customBackgroundColor, customSurfaceColor, customTextColor, customSecondaryColor,
        customFontName, customFontPath, letterSpacing, paragraphSpacing, textAlignment, marginDp, maxWidthDp,
        toolbarItemOrder, toolbarHiddenItems,
        micButtonSize, micButtonShape, micButtonColor, micButtonPosition, micButtonOffsetY,
        statusChipStyle, uiDensity, cornerRadiusDp, dictationAnimationStyle, hapticsLevel,
        pipelineSpacing, pipelineAutoCap, pipelineCommands, needsDndPermission,
        floatingLayoutSettings, markdownSettings, templates,
        autoBackupEnabled, autoBackupSchedule, autoBackupCustomValue, autoBackupCustomUnit,
        autoBackupOnAppOpen, autoBackupKeepCount, autoBackupLocationUri, autoBackupOnlyCharging,
        autoBackupSkipLowStorage, autoBackupLastTime, autoBackupLastSize, autoBackupLastStatus,
        isManualBackingUp, scheduledReminders, floatingNotificationConfig
    ) {
        listOf(
            // Templates & Trash Section
            SettingHeadlineSection(
                id = "templates_section",
                title = "Templates & Trash",
                subtitle = "Manage reusable templates, view trashed journal notes & restore them",
                icon = Icons.Outlined.PostAdd,
                items = listOf(
                    SettingItem.CustomContent(
                        id = "templates_content",
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Sub-Tabs: Templates vs Trash
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = selectedTemplatesTrashTab == 0,
                                        onClick = { selectedTemplatesTrashTab = 0 },
                                        label = { Text("Templates (${templates.size})") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.PostAdd,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                    FilterChip(
                                        selected = selectedTemplatesTrashTab == 1,
                                        onClick = { selectedTemplatesTrashTab = 1 },
                                        label = { Text("Trash (${trashedNotes.size})") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }

                                if (selectedTemplatesTrashTab == 0) {
                                    // ==========================================
                                    // 1. Templates Tab
                                    // ==========================================
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Saved Templates (${templates.size})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Button(
                                            onClick = {
                                                editingTemplate = Stamp(
                                                    id = 0,
                                                    label = "",
                                                    templateText = "",
                                                    isPinned = false
                                                )
                                                isCreatingTemplate = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add Template", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }

                                    if (templates.isEmpty()) {
                                        Text(
                                            text = "No templates saved yet. Tap 'Add Template' to create reusable templates for your notes and journal entries.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    } else {
                                        templates.forEachIndexed { index, tmpl ->
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                text = tmpl.label.ifBlank { "Untitled Template" },
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                            )
                                                            if (tmpl.isPinned) {
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Icon(
                                                                    imageVector = Icons.Default.PushPin,
                                                                    contentDescription = "Pinned",
                                                                    tint = MaterialTheme.colorScheme.primary,
                                                                    modifier = Modifier.size(14.dp)
                                                                )
                                                            }
                                                        }
                                                        if (tmpl.templateText.isNotBlank()) {
                                                            Text(
                                                                text = tmpl.templateText.trim(),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 2,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }

                                                    // Reorder Up
                                                    IconButton(
                                                        onClick = {
                                                            if (index > 0) {
                                                                val mutable = templates.toMutableList()
                                                                val item = mutable.removeAt(index)
                                                                mutable.add(index - 1, item)
                                                                onReorderTemplates(mutable)
                                                            }
                                                        },
                                                        enabled = index > 0,
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowUpward,
                                                            contentDescription = "Move Up",
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }

                                                    // Reorder Down
                                                    IconButton(
                                                        onClick = {
                                                            if (index < templates.size - 1) {
                                                                val mutable = templates.toMutableList()
                                                                val item = mutable.removeAt(index)
                                                                mutable.add(index + 1, item)
                                                                onReorderTemplates(mutable)
                                                            }
                                                        },
                                                        enabled = index < templates.size - 1,
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowDownward,
                                                            contentDescription = "Move Down",
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }

                                                    // Pin toggle
                                                    IconButton(
                                                        onClick = { onPinTemplate(tmpl) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (tmpl.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                                            contentDescription = if (tmpl.isPinned) "Unpin" else "Pin",
                                                            tint = if (tmpl.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }

                                                    // Edit
                                                    IconButton(
                                                        onClick = {
                                                            editingTemplate = tmpl
                                                            isCreatingTemplate = false
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit Template",
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }

                                                    // Delete
                                                    IconButton(
                                                        onClick = { onDeleteTemplate(tmpl) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete Template",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // ==========================================
                                    // 2. Trash Tab
                                    // ==========================================
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Deleted Journal Notes (${trashedNotes.size})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (trashedNotes.isNotEmpty()) {
                                            TextButton(
                                                onClick = { showEmptyTrashConfirmDialog = true },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Empty Trash", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }

                                    if (trashedNotes.isEmpty()) {
                                        Text(
                                            text = "Trash is empty. Whenever you delete a note associated with a specific date, it is automatically moved to the Trash folder and will appear here for viewing or restoring.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    } else {
                                        trashedNotes.forEach { trNote ->
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = trNote.title.ifBlank { "Untitled Note" },
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                        if (trNote.deletedAt != null && trNote.deletedAt > 0L) {
                                                            Text(
                                                                text = "Deleted: ${com.example.util.NoteTimeFormatter.formatTimestamp(trNote.deletedAt)}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                                            )
                                                        }
                                                        if (trNote.content.isNotBlank()) {
                                                            Text(
                                                                text = trNote.content.trim(),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 2,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }

                                                    // Restore Button
                                                    IconButton(
                                                        onClick = { onRestoreNote(trNote) },
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Restore,
                                                            contentDescription = "Restore Note",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }

                                                    // Delete Permanently Button
                                                    IconButton(
                                                        onClick = { noteToDeletePermanently = trNote },
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteForever,
                                                            contentDescription = "Delete Permanently",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )
                )
            ),

            // Markdown Settings Entry Section
            SettingHeadlineSection(
                id = "markdown_settings_entry",
                title = "Markdown",
                subtitle = "Obsidian-style formatting, reading mode rendering & styling",
                icon = Icons.Outlined.Tune,
                items = listOf(
                    SettingItem.ButtonRow(
                        id = "open_markdown_settings",
                        title = "Markdown Settings",
                        subtitle = "Master toggle, per-element switches & reading font scale",
                        buttons = listOf(
                            SettingButtonOption(
                                label = "Open Markdown Settings",
                                onClick = onOpenMarkdownSettings
                            )
                        )
                    )
                )
            ),

            // 1. In-App Keyboard Section
            SettingHeadlineSection(
                id = "keyboard",
                title = "In-App Keyboard",
                subtitle = "3x10 grid size, font, haptics & custom layout",
                icon = Icons.Outlined.Keyboard,
                items = listOf(
                    SettingItem.Slider(
                        id = "key_height",
                        title = "Key Height",
                        value = keyboardKeyHeightDp.toFloat(),
                        valueRange = 30f..56f,
                        steps = 25,
                        displayValue = "$keyboardKeyHeightDp dp",
                        onValueChange = { onKeyboardKeyHeightChange(it.toInt()) }
                    ),
                    SettingItem.Slider(
                        id = "key_font_size",
                        title = "Key Font Size",
                        value = keyboardFontSizeSp.toFloat(),
                        valueRange = 11f..22f,
                        steps = 10,
                        displayValue = "$keyboardFontSizeSp sp",
                        onValueChange = { onKeyboardFontSizeChange(it.toInt()) }
                    ),
                    SettingItem.Switch(
                        id = "keyboard_haptic",
                        title = "Haptic Feedback on Tap",
                        subtitle = "Vibrates lightly on key press",
                        checked = keyboardHaptic,
                        onCheckedChange = onKeyboardHapticChange
                    ),
                    SettingItem.ChipGroup(
                        id = "keyboard_hold_behavior",
                        title = "Hold Behavior",
                        subtitle = "Action when long-pressing a key",
                        options = listOf(
                            ChipOption("CUSTOMIZE", "Customize Key (Default)"),
                            ChipOption("REPEAT", "Repeat Key")
                        ),
                        selectedKey = keyboardHoldBehavior,
                        onOptionSelected = onKeyboardHoldBehaviorChange
                    ),
                    SettingItem.ChipGroup(
                        id = "active_keyboard_selector",
                        title = "Configured Keyboard",
                        subtitle = "Select which keyboard layout to customize, reset, export, or import (Swipe ◀ ▶ on keyboard in editor to toggle)",
                        options = listOf(
                            ChipOption("0", "Keyboard 1 (Primary)"),
                            ChipOption("1", "Keyboard 2 (Secondary)")
                        ),
                        selectedKey = activeKeyboardIndex.toString(),
                        onOptionSelected = { onActiveKeyboardIndexChange(it.toIntOrNull() ?: 0) }
                    ),
                    SettingItem.ButtonRow(
                        id = "keyboard_layout_actions",
                        title = "Layout Actions (${if (activeKeyboardIndex == 1) "Keyboard 2" else "Keyboard 1"})",
                        buttons = listOf(
                            SettingButtonOption(
                                label = "Reset Layout",
                                isDestructive = true,
                                onClick = { onResetKeyboardLayout(activeKeyboardIndex) }
                            ),
                            SettingButtonOption(
                                label = "Export JSON",
                                onClick = { showExportDialog = true }
                            ),
                            SettingButtonOption(
                                label = "Import JSON",
                                onClick = {
                                    importJsonText = ""
                                    importError = false
                                    showImportDialog = true
                                }
                            )
                        )
                    )
                )
            ),

            // Floating layout Section (placed next to keyboard size setting)
            SettingHeadlineSection(
                id = "floating_layout",
                title = "Floating layout",
                subtitle = "Side rail, button order, format bar & waveform positioning",
                icon = Icons.Outlined.Tune,
                items = listOf(
                    SettingItem.Switch(
                        id = "use_side_rail",
                        title = "Use side rail layout",
                        subtitle = "Slim vertical column of action buttons anchored above keyboard",
                        checked = floatingLayoutSettings.useSideRail,
                        onCheckedChange = onFloatingUseSideRailChange
                    ),
                    SettingItem.ChipGroup(
                        id = "rail_side",
                        title = "Rail: Side",
                        subtitle = "Screen edge position of the vertical rail",
                        options = listOf(
                            ChipOption("RIGHT", "Right (Default)"),
                            ChipOption("LEFT", "Left")
                        ),
                        selectedKey = floatingLayoutSettings.railSide,
                        onOptionSelected = onRailSideChange
                    ),
                    SettingItem.Slider(
                        id = "rail_scale",
                        title = "Rail: Size Scale",
                        value = floatingLayoutSettings.railScale,
                        valueRange = 0.5f..1.5f,
                        steps = 20,
                        displayValue = "${"%.2f".format(floatingLayoutSettings.railScale)}x",
                        onValueChange = onRailScaleChange
                    ),
                    SettingItem.Slider(
                        id = "rail_bottom_offset",
                        title = "Rail: Bottom Offset Above Keyboard",
                        value = floatingLayoutSettings.railBottomOffsetDp.toFloat(),
                        valueRange = 0f..200f,
                        steps = 40,
                        displayValue = "${floatingLayoutSettings.railBottomOffsetDp} dp",
                        onValueChange = { onRailBottomOffsetChange(it.toInt()) }
                    ),
                    SettingItem.Slider(
                        id = "rail_spacing",
                        title = "Rail: Spacing Between Buttons",
                        value = floatingLayoutSettings.railSpacingDp.toFloat(),
                        valueRange = 0f..16f,
                        steps = 16,
                        displayValue = "${floatingLayoutSettings.railSpacingDp} dp",
                        onValueChange = { onRailSpacingChange(it.toInt()) }
                    ),
                    SettingItem.Switch(
                        id = "rail_keep_text_clear",
                        title = "Keep text clear of rail",
                        subtitle = "Reserves editor side margin so text never slides behind the rail",
                        checked = floatingLayoutSettings.keepTextClearOfRail,
                        onCheckedChange = onRailKeepTextClearChange
                    ),
                    SettingItem.CustomContent(
                        id = "rail_button_order_control",
                        content = {
                            RailButtonOrderReorderControl(
                                railButtonOrder = floatingLayoutSettings.railButtonOrder,
                                onMoveItem = onMoveRailButtonItem,
                                onResetOrder = onResetRailButtonOrder
                            )
                        }
                    ),
                    SettingItem.Slider(
                        id = "toggle_scale",
                        title = "Reading/Edit Toggle: Size Scale",
                        value = floatingLayoutSettings.toggleScale,
                        valueRange = 0.5f..1.5f,
                        steps = 20,
                        displayValue = "${"%.2f".format(floatingLayoutSettings.toggleScale)}x",
                        onValueChange = onToggleScaleChange
                    ),
                    SettingItem.Slider(
                        id = "toggle_bottom_offset",
                        title = "Reading/Edit Toggle: Bottom Offset",
                        subtitle = "Offset above active keyboard",
                        value = floatingLayoutSettings.toggleBottomOffsetDp.toFloat(),
                        valueRange = 0f..200f,
                        steps = 40,
                        displayValue = "${floatingLayoutSettings.toggleBottomOffsetDp} dp",
                        onValueChange = { onToggleBottomOffsetChange(it.toInt()) }
                    ),
                    SettingItem.Slider(
                        id = "toggle_gap",
                        title = "Reading/Edit Toggle: Gap to Neighbors",
                        value = floatingLayoutSettings.toggleGapDp.toFloat(),
                        valueRange = 0f..24f,
                        steps = 24,
                        displayValue = "${floatingLayoutSettings.toggleGapDp} dp",
                        onValueChange = { onToggleGapChange(it.toInt()) }
                    ),
                    SettingItem.Switch(
                        id = "format_bar_enabled",
                        title = "Quick Format Bar",
                        subtitle = "Toolbar of Markdown syntax buttons for bold, headings, lists & more",
                        checked = markdownSettings.formatBarEnabled,
                        onCheckedChange = { chk -> onUpdateMarkdownSettings { it.copy(formatBarEnabled = chk) } }
                    ),
                    SettingItem.Switch(
                        id = "format_bar_visible_during_dictation",
                        title = "Format Bar: Visible During Dictation",
                        subtitle = "Keep format bar visible while actively dictating your voice",
                        checked = floatingLayoutSettings.formatBarVisibleDuringDictation,
                        onCheckedChange = onFormatBarVisibleDuringDictationChange
                    ),
                    SettingItem.Switch(
                        id = "format_bar_visible_without_cursor",
                        title = "Format Bar: Visible Without Cursor",
                        subtitle = "Keep format bar visible even when the cursor is not placed in the note",
                        checked = floatingLayoutSettings.formatBarVisibleWithoutCursor,
                        onCheckedChange = onFormatBarVisibleWithoutCursorChange
                    ),
                    SettingItem.Slider(
                        id = "format_bar_scale",
                        title = "Format Bar: Size Scale",
                        subtitle = "Scales height, icons and padding together",
                        value = floatingLayoutSettings.formatBarScale,
                        valueRange = 0.5f..1.5f,
                        steps = 20,
                        displayValue = "${"%.2f".format(floatingLayoutSettings.formatBarScale)}x",
                        onValueChange = onFormatBarScaleChange
                    ),
                    SettingItem.Slider(
                        id = "format_bar_bottom_offset",
                        title = "Format Bar: Bottom Offset",
                        value = floatingLayoutSettings.formatBarBottomOffsetDp.toFloat(),
                        valueRange = 0f..200f,
                        steps = 40,
                        displayValue = "${floatingLayoutSettings.formatBarBottomOffsetDp} dp",
                        onValueChange = { onFormatBarBottomOffsetChange(it.toInt()) }
                    ),
                    SettingItem.Slider(
                        id = "format_bar_width",
                        title = "Format Bar: Width",
                        subtitle = "Percentage of available space",
                        value = floatingLayoutSettings.formatBarWidthPercent.toFloat(),
                        valueRange = 50f..100f,
                        steps = 50,
                        displayValue = "${floatingLayoutSettings.formatBarWidthPercent}%",
                        onValueChange = { onFormatBarWidthPercentChange(it.toInt()) }
                    ),
                    SettingItem.ChipGroup(
                        id = "format_bar_alignment",
                        title = "Format Bar: Alignment",
                        subtitle = "Alignment when width is below 100%",
                        options = listOf(
                            ChipOption("LEFT", "Left (Default)"),
                            ChipOption("CENTER", "Center"),
                            ChipOption("RIGHT", "Right")
                        ),
                        selectedKey = floatingLayoutSettings.formatBarAlignment,
                        onOptionSelected = onFormatBarAlignmentChange
                    ),
                    SettingItem.CustomContent(
                        id = "format_bar_buttons_reorder_control",
                        content = {
                            FormatBarButtonsReorderControl(
                                formatBarOrder = markdownSettings.formatBarOrder,
                                formatBarButtons = markdownSettings.formatBarButtons,
                                enabled = true,
                                onReorder = { newOrder ->
                                    onUpdateMarkdownSettings { current ->
                                        val newButtons = newOrder.filter { current.formatBarButtons.contains(it) }
                                        current.copy(formatBarOrder = newOrder, formatBarButtons = newButtons)
                                    }
                                },
                                onToggleCheck = { buttonId, isChecked ->
                                    onUpdateMarkdownSettings { current ->
                                        val newButtons = if (isChecked) {
                                            current.formatBarOrder.filter {
                                                it == buttonId || current.formatBarButtons.contains(it)
                                            }
                                        } else {
                                            current.formatBarButtons.filter { it != buttonId }
                                        }
                                        current.copy(formatBarButtons = newButtons)
                                    }
                                },
                                onSelectAll = {
                                    onUpdateMarkdownSettings { it.copy(formatBarButtons = it.formatBarOrder) }
                                },
                                onSelectNone = {
                                    onUpdateMarkdownSettings { it.copy(formatBarButtons = emptyList()) }
                                },
                                onResetOrder = {
                                    onUpdateMarkdownSettings {
                                        val defOrder = com.example.markdown.MarkdownSettings.DEFAULT_FORMAT_BAR_ORDER
                                        val defButtons = defOrder.filter { id -> it.formatBarButtons.contains(id) }
                                            .takeIf { it.isNotEmpty() } ?: defOrder
                                        it.copy(formatBarOrder = defOrder, formatBarButtons = defButtons)
                                    }
                                }
                            )
                        }
                    ),
                    SettingItem.Slider(
                        id = "waveform_scale",
                        title = "Waveform: Size Scale",
                        subtitle = "Scales height of live dictation waveform",
                        value = floatingLayoutSettings.waveformScale,
                        valueRange = 0.5f..1.5f,
                        steps = 20,
                        displayValue = "${"%.2f".format(floatingLayoutSettings.waveformScale)}x",
                        onValueChange = onWaveformScaleChange
                    ),
                    SettingItem.Switch(
                        id = "waveform_show",
                        title = "Show waveform",
                        subtitle = "Live audio wave animation while recording",
                        checked = floatingLayoutSettings.showWaveform,
                        onCheckedChange = onWaveformShowChange
                    ),
                    SettingItem.Slider(
                        id = "reading_nav_bottom_offset",
                        title = "Reading Nav Buttons: Bottom Offset",
                        subtitle = "Adjust vertical position of Previous, Toggle, and Next buttons",
                        value = floatingLayoutSettings.readingNavBottomOffsetDp.toFloat(),
                        valueRange = 0f..200f,
                        steps = 40,
                        displayValue = "${floatingLayoutSettings.readingNavBottomOffsetDp} dp",
                        onValueChange = { onReadingNavBottomOffsetChange(it.toInt()) }
                    ),
                    SettingItem.ButtonRow(
                        id = "reset_floating_layout_row",
                        title = "Reset Floating Layout",
                        subtitle = "Restore all layout sizes, positions, scales and order to defaults",
                        buttons = listOf(
                            SettingButtonOption(
                                label = "Reset layout to defaults",
                                isDestructive = true,
                                onClick = onResetFloatingLayout
                            )
                        )
                    )
                )
            ),

            // 2. Dictation Engine Section
            SettingHeadlineSection(
                id = "dictation",
                title = "Dictation Engine",
                subtitle = "Continuous speech, timeouts, beep muting & insertion",
                icon = Icons.Outlined.Mic,
                items = buildList {
                    add(
                        SettingItem.ChipGroup(
                            id = "dictation_mode",
                            title = "Default Dictation Mode",
                            subtitle = "Dictation behavior on mic tap",
                            options = DictationMode.values().map {
                                ChipOption(it.name, it.displayName)
                            },
                            selectedKey = dictationMode,
                            onOptionSelected = onDictationModeChange
                        )
                    )
                    add(
                        SettingItem.ChipGroup(
                            id = "mute_beep_method",
                            title = "Mute Recognizer Beep",
                            subtitle = "Method to eliminate repetitive recognition beeps across sessions",
                            options = listOf(
                                ChipOption("MEDIA_SYSTEM", "Media + System streams (Default)"),
                                ChipOption("OFF", "Off"),
                                ChipOption("VIBRATE_ONCE", "Vibrate mode once per session")
                            ),
                            selectedKey = muteBeepMethod,
                            onOptionSelected = onMuteBeepMethodChange
                        )
                    )
                    if (muteBeepMethod == "VIBRATE_ONCE") {
                        add(
                            SettingItem.InfoText(
                                id = "vibrate_once_info",
                                title = "Vibrate Mode",
                                description = "Phone vibrates once when dictation starts." +
                                        if (needsDndPermission) " Requires Do Not Disturb / Notification Policy access." else "",
                                actionButtonText = if (needsDndPermission) "Grant Policy Access" else null,
                                onActionClick = if (needsDndPermission) {
                                    {
                                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                } else null
                            )
                        )
                    }
                    add(
                        SettingItem.Slider(
                            id = "pause_tolerance",
                            title = "Pause Tolerance",
                            subtitle = "Max silence duration before session auto-restarts",
                            value = pauseToleranceSeconds.toFloat(),
                            valueRange = 5f..120f,
                            steps = 22,
                            displayValue = "$pauseToleranceSeconds s",
                            onValueChange = { onPauseToleranceChange(it.toInt()) }
                        )
                    )
                    add(
                        SettingItem.ChipGroup(
                            id = "insertion_mode",
                            title = "Text Insertion Position",
                            options = InsertionMode.values().map {
                                ChipOption(it.name, it.displayName)
                            },
                            selectedKey = insertionMode,
                            onOptionSelected = onInsertionModeChange
                        )
                    )
                    add(
                        SettingItem.Switch(
                            id = "offline_preferred",
                            title = "Offline Preferred",
                            subtitle = "Prefer on-device speech engine if available",
                            checked = offlinePreferred,
                            onCheckedChange = onOfflinePreferredChange
                        )
                    )
                    add(
                        SettingItem.Switch(
                            id = "keep_screen_on",
                            title = "Keep Screen On While Dictating",
                            subtitle = "Prevents device from sleeping during long dictation sessions",
                            checked = keepScreenOn,
                            onCheckedChange = onKeepScreenOnChange
                        )
                    )
                }
            ),

            // 3. Editor & Appearance Section
            SettingHeadlineSection(
                id = "appearance",
                title = "Editor & Appearance",
                subtitle = "Themes, color pickers, typography & metrics",
                icon = Icons.Outlined.Palette,
                items = buildList {
                    add(
                        SettingItem.ChipGroup(
                            id = "app_theme",
                            title = "Theme Palette",
                            subtitle = "Select AMOLED, Dark, Light, System, or build a Full Custom theme",
                            options = listOf("AMOLED", "DARK", "LIGHT", "SYSTEM", "CUSTOM").map {
                                ChipOption(it, it)
                            },
                            selectedKey = appTheme,
                            onOptionSelected = onAppThemeChange
                        )
                    )
                    // Full Custom Theme with Color Pickers
                    add(
                        SettingItem.CustomContent(
                            id = "custom_theme_colors",
                            content = {
                                FullCustomThemeColorPicker(
                                    primaryColor = customPrimaryColor,
                                    backgroundColor = customBackgroundColor,
                                    surfaceColor = customSurfaceColor,
                                    textColor = customTextColor,
                                    secondaryColor = customSecondaryColor,
                                    onColorsChanged = { p, b, s, t, sec ->
                                        onCustomThemeColorsChange(p, b, s, t, sec)
                                        if (appTheme != "CUSTOM") {
                                            onAppThemeChange("CUSTOM")
                                        }
                                    }
                                )
                            }
                        )
                    )
                    // Typography: Font Family
                    add(
                        SettingItem.ChipGroup(
                            id = "editor_font_family",
                            title = "Font Family",
                            subtitle = "Choose from bundled fonts or import your own TTF/OTF",
                            options = listOf(
                                ChipOption("BENGALI", "Bengali (Noto)"),
                                ChipOption("INTER", "Latin (Inter)"),
                                ChipOption("MONOSPACE", "Monospace"),
                                ChipOption("SYSTEM", "System Default"),
                                ChipOption("CUSTOM", if (customFontName.isNotBlank()) customFontName else "Custom TTF/OTF")
                            ),
                            selectedKey = fontFamily,
                            onOptionSelected = onFontFamilyChange
                        )
                    )
                    // Custom TTF/OTF file picker control
                    add(
                        SettingItem.CustomContent(
                            id = "custom_font_import",
                            content = {
                                CustomFontImportControl(
                                    customFontName = customFontName,
                                    customFontPath = customFontPath,
                                    onCustomFontSelected = onCustomFontChange,
                                    onFontOptionChanged = onFontFamilyChange
                                )
                            }
                        )
                    )
                    // Font Size Slider
                    add(
                        SettingItem.Slider(
                            id = "editor_font_size",
                            title = "Font Size",
                            value = fontSize,
                            valueRange = 12f..36f,
                            steps = 12,
                            displayValue = "${fontSize.toInt()} sp",
                            onValueChange = onFontSizeChange
                        )
                    )
                    // Line Spacing
                    add(
                        SettingItem.ChipGroup(
                            id = "line_spacing",
                            title = "Line Spacing Multiplier",
                            options = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.4f).map {
                                ChipOption(it.toString(), "${it}x")
                            },
                            selectedKey = lineSpacing.toString(),
                            onOptionSelected = { it.toFloatOrNull()?.let(onLineSpacingChange) }
                        )
                    )
                    // Letter Spacing
                    add(
                        SettingItem.Slider(
                            id = "letter_spacing",
                            title = "Letter Spacing",
                            subtitle = "Adjust tracking between characters",
                            value = letterSpacing,
                            valueRange = -1.0f..4.0f,
                            steps = 10,
                            displayValue = "${"%.1f".format(letterSpacing)} sp",
                            onValueChange = onLetterSpacingChange
                        )
                    )
                    // Paragraph Spacing
                    add(
                        SettingItem.Slider(
                            id = "paragraph_spacing",
                            title = "Paragraph Spacing",
                            subtitle = "Extra breathing space below paragraphs",
                            value = paragraphSpacing.toFloat(),
                            valueRange = 0f..24f,
                            steps = 12,
                            displayValue = "$paragraphSpacing dp",
                            onValueChange = { onParagraphSpacingChange(it.toInt()) }
                        )
                    )
                    // Text Alignment
                    add(
                        SettingItem.ChipGroup(
                            id = "text_alignment",
                            title = "Text Alignment",
                            options = listOf(
                                ChipOption("LEFT", "Left"),
                                ChipOption("CENTER", "Center"),
                                ChipOption("RIGHT", "Right"),
                                ChipOption("JUSTIFY", "Justify")
                            ),
                            selectedKey = textAlignment,
                            onOptionSelected = onTextAlignmentChange
                        )
                    )
                    // Editor Margins / Horizontal Padding
                    add(
                        SettingItem.Slider(
                            id = "editor_margin",
                            title = "Horizontal Margins",
                            subtitle = "Editor side padding",
                            value = marginDp.toFloat(),
                            valueRange = 8f..40f,
                            steps = 16,
                            displayValue = "$marginDp dp",
                            onValueChange = { onMarginDpChange(it.toInt()) }
                        )
                    )
                    // Max Line Width
                    add(
                        SettingItem.ChipGroup(
                            id = "max_line_width",
                            title = "Max Line Width",
                            subtitle = "Constrain text column width on wide screens / foldables",
                            options = listOf(
                                ChipOption("0", "Full Width"),
                                ChipOption("480", "480 dp"),
                                ChipOption("600", "600 dp"),
                                ChipOption("720", "720 dp")
                            ),
                            selectedKey = maxWidthDp.toString(),
                            onOptionSelected = { it.toIntOrNull()?.let(onMaxWidthDpChange) }
                        )
                    )
                    // UI Density
                    add(
                        SettingItem.ChipGroup(
                            id = "ui_density",
                            title = "UI Density",
                            options = listOf(
                                ChipOption("COMPACT", "Compact"),
                                ChipOption("NORMAL", "Normal"),
                                ChipOption("COMFORTABLE", "Comfortable")
                            ),
                            selectedKey = uiDensity,
                            onOptionSelected = onUiDensityChange
                        )
                    )
                    // Corner Radius
                    add(
                        SettingItem.ChipGroup(
                            id = "corner_radius",
                            title = "UI Corner Radius",
                            options = listOf(
                                ChipOption("4", "4 dp (Sharp)"),
                                ChipOption("12", "12 dp (Smooth)"),
                                ChipOption("24", "24 dp (Round)")
                            ),
                            selectedKey = cornerRadiusDp.toString(),
                            onOptionSelected = { it.toIntOrNull()?.let(onCornerRadiusDpChange) }
                        )
                    )
                    // Word count switch
                    add(
                        SettingItem.Switch(
                            id = "word_count",
                            title = "Show Word & Character Count",
                            subtitle = "Displays live metrics in the secondary toolbar",
                            checked = showWordCount,
                            onCheckedChange = onShowWordCountChange
                        )
                    )
                    // Note metrics in list switch
                    add(
                        SettingItem.Switch(
                            id = "note_metrics_in_list",
                            title = "Show note metrics in list",
                            subtitle = "Displays word count, reading time, and date under note titles in Notes Manager",
                            checked = showNoteMetrics,
                            onCheckedChange = onShowNoteMetricsChange
                        )
                    )
                    // Note metrics format chip group
                    add(
                        SettingItem.ChipGroup(
                            id = "note_metrics_style",
                            title = "Note metrics format",
                            subtitle = "Choose between compact abbreviations and detailed descriptions",
                            options = listOf(
                                ChipOption("COMPACT", "Compact (e.g. 120w • ~1m)"),
                                ChipOption("DETAILED", "Detailed (e.g. 120 words • 650 chars • ~1m read)")
                            ),
                            selectedKey = noteMetricsStyle,
                            onOptionSelected = onNoteMetricsStyleChange
                        )
                    )
                    add(
                        SettingItem.CustomContent(
                            id = "note_metrics_reorder_content",
                            content = {
                                NoteMetricsReorderAndVisibilityControl(
                                    metricsOrderStr = noteMetricsOrder,
                                    hiddenItems = noteMetricsHiddenItems,
                                    metricsStyle = noteMetricsStyle,
                                    onMoveItem = onMoveNoteMetricItem,
                                    onToggleVisibility = onToggleNoteMetricVisibility,
                                    onResetOrder = onResetNoteMetricsOrder
                                )
                            }
                        )
                    )
                }
            ),

            // 4. Toolbar & Mic Controls Section
            SettingHeadlineSection(
                id = "toolbar_mic",
                title = "Toolbar, Mic & Animations",
                subtitle = "Reorder toolbar, visibility, mic shape/size & animation styles",
                icon = Icons.Outlined.Tune,
                items = listOf(
                    SettingItem.CustomContent(
                        id = "toolbar_reorder_content",
                        content = {
                            ToolbarReorderAndVisibilityControl(
                                toolbarOrderStr = toolbarItemOrder,
                                hiddenItems = toolbarHiddenItems,
                                onMoveItem = onMoveToolbarItem,
                                onToggleVisibility = onToggleToolbarItemVisibility,
                                onResetOrder = onResetToolbarOrder
                            )
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "mic_size",
                        title = "Mic Button Size",
                        subtitle = "Floating dictation action button size",
                        options = listOf(
                            ChipOption("SMALL", "Small (48dp)"),
                            ChipOption("MEDIUM", "Medium (60dp)"),
                            ChipOption("LARGE", "Large (72dp)"),
                            ChipOption("EXTRA_LARGE", "XL (84dp)")
                        ),
                        selectedKey = micButtonSize,
                        onOptionSelected = onMicButtonSizeChange
                    ),
                    SettingItem.ChipGroup(
                        id = "mic_shape",
                        title = "Mic Button Shape",
                        options = listOf(
                            ChipOption("CIRCLE", "Circle"),
                            ChipOption("SQUIRCLE", "Squircle"),
                            ChipOption("SQUARE", "Square")
                        ),
                        selectedKey = micButtonShape,
                        onOptionSelected = onMicButtonShapeChange
                    ),
                    SettingItem.ChipGroup(
                        id = "mic_color",
                        title = "Mic Button Color Accent",
                        options = listOf(
                            ChipOption("DEFAULT", "Dynamic State"),
                            ChipOption("SAFFRON", "Saffron"),
                            ChipOption("CORAL", "Coral"),
                            ChipOption("EMERALD", "Emerald"),
                            ChipOption("BLUE", "Blue"),
                            ChipOption("PURPLE", "Purple")
                        ),
                        selectedKey = micButtonColor,
                        onOptionSelected = onMicButtonColorChange
                    ),
                    SettingItem.ChipGroup(
                        id = "mic_position",
                        title = "Mic Button Position",
                        options = listOf(
                            ChipOption("RIGHT", "Bottom-Right (Default)"),
                            ChipOption("CENTER", "Bottom-Center"),
                            ChipOption("LEFT", "Bottom-Left")
                        ),
                        selectedKey = micButtonPosition,
                        onOptionSelected = onMicButtonPositionChange
                    ),
                    SettingItem.Slider(
                        id = "mic_offset_y",
                        title = "Mic Bottom Offset",
                        subtitle = "Vertical distance from bottom edge",
                        value = micButtonOffsetY.toFloat(),
                        valueRange = 0f..32f,
                        steps = 8,
                        displayValue = "$micButtonOffsetY dp",
                        onValueChange = { onMicButtonOffsetYChange(it.toInt()) }
                    ),
                    SettingItem.ChipGroup(
                        id = "status_chip_style",
                        title = "Status Chip Style",
                        subtitle = "Shape style for dictation status & metrics chips",
                        options = listOf(
                            ChipOption("PILL", "Pill (Rounded)"),
                            ChipOption("ROUNDED", "Rounded Rect"),
                            ChipOption("OUTLINE", "Outline Border")
                        ),
                        selectedKey = statusChipStyle,
                        onOptionSelected = onStatusChipStyleChange
                    ),
                    SettingItem.ChipGroup(
                        id = "dictation_animation",
                        title = "Voice Dictation Animation Style",
                        subtitle = "Animation effects while speech engine is listening",
                        options = listOf(
                            ChipOption("PULSE_RING", "Pulsing Ring (around button)"),
                            ChipOption("HORIZONTAL_WAVE", "Horizontal Waveform (Equalizer)"),
                            ChipOption("GLOW", "Glow Aura"),
                            ChipOption("NONE", "None (Static)")
                        ),
                        selectedKey = dictationAnimationStyle,
                        onOptionSelected = onDictationAnimationStyleChange
                    ),
                    SettingItem.ChipGroup(
                        id = "haptics_level",
                        title = "Haptics Feedback Level",
                        subtitle = "Vibration strength on mic tap, toolbar clicks and typing",
                        options = listOf(
                            ChipOption("OFF", "Off"),
                            ChipOption("LIGHT", "Light"),
                            ChipOption("MEDIUM", "Medium (Default)"),
                            ChipOption("HEAVY", "Heavy")
                        ),
                        selectedKey = hapticsLevel,
                        onOptionSelected = onHapticsLevelChange
                    )
                )
            ),

            // 4. Text Pipeline & Spoken Commands Section
            SettingHeadlineSection(
                id = "pipeline",
                title = "Text Pipeline & Commands",
                subtitle = "Automatic punctuation, spacing cleanups & spoken commands",
                icon = Icons.Outlined.Tune,
                items = listOf(
                    SettingItem.Switch(
                        id = "pipeline_spacing",
                        title = "Spacing Cleanups",
                        subtitle = "Removes duplicate spaces and cleans spacing around punctuation",
                        checked = pipelineSpacing,
                        onCheckedChange = onPipelineSpacingChange
                    ),
                    SettingItem.Switch(
                        id = "pipeline_autocap",
                        title = "Auto-Capitalization",
                        subtitle = "Capitalizes sentences after periods, exclamation, and question marks",
                        checked = pipelineAutoCap,
                        onCheckedChange = onPipelineAutoCapChange
                    ),
                    SettingItem.Switch(
                        id = "pipeline_commands",
                        title = "Spoken Commands & Punctuation",
                        subtitle = "Converts spoken words (e.g. 'দাঁড়ি' → '।', 'full stop' → '.') and actions ('delete last word')",
                        checked = pipelineCommands,
                        onCheckedChange = onPipelineCommandsChange
                    )
                )
            ),

            // Notifications & Reminders Section
            SettingHeadlineSection(
                id = "notifications_reminders_section",
                title = "Notifications & Reminders",
                subtitle = "Unlimited daily alarms, custom text, system & floating popup modes",
                icon = Icons.Outlined.Notifications,
                items = listOf(
                    SettingItem.CustomContent(
                        id = "scheduled_reminders_list",
                        content = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Scheduled Reminders (${scheduledReminders.size})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Set as many reminders throughout the day as you wish",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            showInitialClockPicker = true
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("add_reminder_fab"),
                                        shape = CircleShape,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add Reminder",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                if (scheduledReminders.isEmpty()) {
                                    Text(
                                        text = "No reminders scheduled yet. Tap 'Add Reminder' to set daily alarms (e.g. 7:00 AM, 1:00 PM, 9:00 PM) with optional custom text and display modes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                } else {
                                    scheduledReminders.forEach { rem ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    editingReminder = rem
                                                    isCreatingReminder = false
                                                },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = rem.formattedTime(),
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (rem.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = if (rem.displayMode == NotificationDisplayMode.FLOATING)
                                                                MaterialTheme.colorScheme.secondaryContainer
                                                            else MaterialTheme.colorScheme.surfaceVariant
                                                        ) {
                                                            Text(
                                                                text = if (rem.displayMode == NotificationDisplayMode.FLOATING) "Floating Popup" else "Normal",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = rem.getEffectiveText(),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }

                                                // Test / Preview button
                                                TextButton(
                                                    onClick = {
                                                        if (rem.displayMode == NotificationDisplayMode.FLOATING) {
                                                            onPreviewFloatingNotification(rem)
                                                        } else {
                                                            com.example.notification.ReminderScheduler.showSystemNotification(
                                                                context,
                                                                rem.id,
                                                                rem.getEffectiveText(),
                                                                rem.formattedTime()
                                                            )
                                                        }
                                                    }
                                                ) {
                                                    Text("Test", fontSize = 12.sp)
                                                }

                                                // Enable / Disable switch
                                                Switch(
                                                    checked = rem.isEnabled,
                                                    onCheckedChange = { onToggleScheduledReminderEnabled(rem.id, it) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    ),
                    // Live Preview of Floating Overlay Popup
                    SettingItem.CustomContent(
                        id = "floating_live_preview",
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Live Preview (Interactive)",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Updates in real-time",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                val previewReminder = remember(scheduledReminders) {
                                    val firstRem = scheduledReminders.firstOrNull()
                                    if (firstRem != null) {
                                        firstRem
                                    } else {
                                        val now = java.util.Calendar.getInstance()
                                        ScheduledReminder(
                                            id = "sample_preview",
                                            hour = now.get(java.util.Calendar.HOUR_OF_DAY),
                                            minute = now.get(java.util.Calendar.MINUTE),
                                            customText = "Did I forget to journal today?",
                                            useCustomText = false
                                        )
                                    }
                                }

                                com.example.ui.editor.FloatingNotificationBanner(
                                    reminder = previewReminder,
                                    config = floatingNotificationConfig,
                                    onTodayPlusClick = {},
                                    onDismiss = {},
                                    isPreview = true
                                )
                            }
                        }
                    ),

                    // Permission & Xiaomi/MIUI compatibility checklist
                    SettingItem.CustomContent(
                        id = "floating_permissions_guide",
                        content = {
                            val isOverlayGranted = com.example.notification.XiaomiOverlayHelper.canDrawOverlays(context)
                            val isXiaomi = com.example.notification.XiaomiOverlayHelper.isXiaomiOrMiui()

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isOverlayGranted)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isOverlayGranted) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isOverlayGranted) "✓ Screen Overlay Permission Granted" else "⚠️ Screen Overlay Permission Required",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isOverlayGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Required to display prominent floating popups over other apps when reminders trigger.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                com.example.notification.XiaomiOverlayHelper.openOverlaySettings(context)
                                            }
                                        ) {
                                            Text(if (isOverlayGranted) "Manage Overlay" else "Grant Overlay Permission", fontSize = 12.sp)
                                        }

                                        if (isXiaomi) {
                                            OutlinedButton(
                                                onClick = {
                                                    com.example.notification.XiaomiOverlayHelper.openMiuiPopupPermissions(context)
                                                }
                                            ) {
                                                Text("Xiaomi Pop-up Permission", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    ),

                    // Size & Positioning
                    SettingItem.Slider(
                        id = "floating_width",
                        title = "Popup Width",
                        subtitle = "Overall width of the screen overlay card",
                        value = floatingNotificationConfig.popupWidthDp.toFloat(),
                        valueRange = 260f..400f,
                        steps = 7,
                        displayValue = "${floatingNotificationConfig.popupWidthDp} dp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(popupWidthDp = it.toInt()))
                        }
                    ),
                    SettingItem.Slider(
                        id = "floating_min_height",
                        title = "Popup Minimum Height",
                        subtitle = "Minimum vertical height of the popup card",
                        value = floatingNotificationConfig.minHeightDp.toFloat(),
                        valueRange = 120f..260f,
                        steps = 7,
                        displayValue = "${floatingNotificationConfig.minHeightDp} dp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(minHeightDp = it.toInt()))
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "floating_vertical_pos",
                        title = "Screen Vertical Position",
                        subtitle = "Select where on the screen the popup appears",
                        options = listOf(
                            ChipOption("TOP", "Top"),
                            ChipOption("CENTER", "Center (Prominent)"),
                            ChipOption("BOTTOM", "Bottom")
                        ),
                        selectedKey = floatingNotificationConfig.verticalPosition,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(verticalPosition = it))
                        }
                    ),
                    SettingItem.Slider(
                        id = "floating_vertical_offset",
                        title = "Fine Vertical Offset",
                        subtitle = "Shift upward or downward from selected position",
                        value = floatingNotificationConfig.verticalOffsetDp.toFloat(),
                        valueRange = -100f..100f,
                        steps = 8,
                        displayValue = "${floatingNotificationConfig.verticalOffsetDp} dp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(verticalOffsetDp = it.toInt()))
                        }
                    ),

                    // Colors & Styling
                    SettingItem.ChipGroup(
                        id = "floating_bg_color",
                        title = "Background Color Theme",
                        subtitle = "Preset color palettes for the floating card",
                        options = listOf(
                            ChipOption("#1E1E2E", "Dark Navy (#1E1E2E)"),
                            ChipOption("#000000", "AMOLED Black"),
                            ChipOption("#121212", "Charcoal Gray"),
                            ChipOption("#263238", "Deep Slate"),
                            ChipOption("#2E1065", "Midnight Violet")
                        ),
                        selectedKey = floatingNotificationConfig.backgroundColorHex,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(backgroundColorHex = it))
                        }
                    ),
                    SettingItem.Slider(
                        id = "floating_opacity",
                        title = "Background Opacity",
                        subtitle = "Transparency level of the floating card",
                        value = floatingNotificationConfig.backgroundOpacity,
                        valueRange = 0.60f..1.0f,
                        steps = 8,
                        displayValue = "${(floatingNotificationConfig.backgroundOpacity * 100).toInt()}%",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(backgroundOpacity = it))
                        }
                    ),
                    SettingItem.Slider(
                        id = "floating_corner_radius",
                        title = "Corner Radius",
                        subtitle = "Smoothness of card corners",
                        value = floatingNotificationConfig.cornerRadiusDp.toFloat(),
                        valueRange = 0f..32f,
                        steps = 8,
                        displayValue = "${floatingNotificationConfig.cornerRadiusDp} dp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(cornerRadiusDp = it.toInt()))
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "floating_border_color",
                        title = "Accent Border Color",
                        subtitle = "Highlight border and action button tint",
                        options = listOf(
                            ChipOption("#7C4DFF", "Purple (#7C4DFF)"),
                            ChipOption("#FF7043", "Coral Orange"),
                            ChipOption("#00E5FF", "Cyan Glow"),
                            ChipOption("#00E676", "Emerald Green"),
                            ChipOption("#FFD600", "Amber Gold")
                        ),
                        selectedKey = floatingNotificationConfig.borderColorHex,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(borderColorHex = it))
                        }
                    ),
                    SettingItem.Slider(
                        id = "floating_border_width",
                        title = "Border Width",
                        subtitle = "Thickness of accent outline (0 for none)",
                        value = floatingNotificationConfig.borderWidthDp.toFloat(),
                        valueRange = 0f..4f,
                        steps = 4,
                        displayValue = "${floatingNotificationConfig.borderWidthDp} dp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(borderWidthDp = it.toInt()))
                        }
                    ),

                    // Typography
                    SettingItem.Slider(
                        id = "floating_text_size",
                        title = "Reminder Text Size",
                        subtitle = "Font size for the reminder message",
                        value = floatingNotificationConfig.textSizeSp.toFloat(),
                        valueRange = 12f..24f,
                        steps = 6,
                        displayValue = "${floatingNotificationConfig.textSizeSp} sp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(textSizeSp = it.toInt()))
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "floating_text_font",
                        title = "Text Font Style",
                        subtitle = "Font family for reminder message",
                        options = listOf(
                            ChipOption("DEFAULT", "Modern Sans"),
                            ChipOption("SERIF", "Classic Serif"),
                            ChipOption("MONOSPACE", "Monospace Code")
                        ),
                        selectedKey = floatingNotificationConfig.textFont,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(textFont = it))
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "floating_font_weight",
                        title = "Font Weight",
                        options = listOf(
                            ChipOption("NORMAL", "Regular"),
                            ChipOption("MEDIUM", "Medium"),
                            ChipOption("BOLD", "Bold")
                        ),
                        selectedKey = floatingNotificationConfig.fontWeight,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(fontWeight = it))
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "floating_text_align",
                        title = "Text Alignment",
                        options = listOf(
                            ChipOption("START", "Left"),
                            ChipOption("CENTER", "Centered"),
                            ChipOption("END", "Right")
                        ),
                        selectedKey = floatingNotificationConfig.textAlignment,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(textAlignment = it))
                        }
                    ),
                    SettingItem.Slider(
                        id = "floating_padding",
                        title = "Internal Padding",
                        subtitle = "Spacing around text and buttons inside popup",
                        value = floatingNotificationConfig.paddingDp.toFloat(),
                        valueRange = 10f..30f,
                        steps = 5,
                        displayValue = "${floatingNotificationConfig.paddingDp} dp",
                        onValueChange = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(paddingDp = it.toInt()))
                        }
                    ),

                    // Animation & Dismissal
                    SettingItem.ChipGroup(
                        id = "floating_animation",
                        title = "Popup Appearance Animation",
                        subtitle = "Animation effect when overlay popup appears",
                        options = listOf(
                            ChipOption("SCALE", "Scale & Pop"),
                            ChipOption("FADE", "Smooth Fade"),
                            ChipOption("SLIDE", "Slide")
                        ),
                        selectedKey = floatingNotificationConfig.animationEffect,
                        onOptionSelected = {
                            onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(animationEffect = it))
                        }
                    ),
                    SettingItem.ChipGroup(
                        id = "floating_auto_dismiss",
                        title = "Display Duration & Dismissal",
                        subtitle = "How long the overlay popup remains before auto-dismissing",
                        options = listOf(
                            ChipOption("0", "Keep Until Dismissed"),
                            ChipOption("10", "10 seconds"),
                            ChipOption("15", "15 seconds (Default)"),
                            ChipOption("30", "30 seconds"),
                            ChipOption("60", "60 seconds")
                        ),
                        selectedKey = floatingNotificationConfig.autoDismissSeconds.toString(),
                        onOptionSelected = {
                            it.toIntOrNull()?.let { s ->
                                onUpdateFloatingNotificationConfig(floatingNotificationConfig.copy(autoDismissSeconds = s))
                            }
                        }
                    )
                )
            ),

            // Automatic Backup Section (collapsed by default)
            SettingHeadlineSection(
                id = "auto_backup_section",
                title = "Automatic Backup",
                subtitle = "Scheduled archives, retention limit & safety restore",
                icon = Icons.Outlined.Backup,
                items = buildList {
                    add(
                        SettingItem.Switch(
                            id = "auto_backup_master",
                            title = "Enable automatic backup",
                            subtitle = "Regularly creates verified archives of notes & settings in the background",
                            checked = autoBackupEnabled,
                            onCheckedChange = onAutoBackupEnabledChange
                        )
                    )

                    if (autoBackupEnabled) {
                        add(
                            SettingItem.ChipGroup(
                                id = "auto_backup_schedule",
                                title = "Schedule",
                                subtitle = "Frequency of background periodic backups",
                                options = listOf(
                                    ChipOption("OFF", "Off"),
                                    ChipOption("HOURS_6", "Every 6 hours"),
                                    ChipOption("DAILY", "Daily"),
                                    ChipOption("WEEKLY", "Weekly"),
                                    ChipOption("CUSTOM", "Custom")
                                ),
                                selectedKey = autoBackupSchedule,
                                onOptionSelected = onAutoBackupScheduleChange
                            )
                        )

                        if (autoBackupSchedule == "CUSTOM") {
                            add(
                                SettingItem.CustomContent(
                                    id = "auto_backup_custom_interval",
                                    content = {
                                        var customValueStr by remember(autoBackupCustomValue) { mutableStateOf(autoBackupCustomValue.toString()) }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Custom Interval (Minimum 15 minutes)",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                OutlinedTextField(
                                                    value = customValueStr,
                                                    onValueChange = { input ->
                                                        val filtered = input.filter { it.isDigit() }
                                                        customValueStr = filtered
                                                        val parsed = filtered.toIntOrNull() ?: 1
                                                        onAutoBackupCustomIntervalChange(parsed, autoBackupCustomUnit)
                                                    },
                                                    label = { Text("Every") },
                                                    modifier = Modifier.weight(1f),
                                                    singleLine = true
                                                )
                                                Row(
                                                    modifier = Modifier.weight(2f),
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    listOf("MINUTES", "HOURS", "DAYS").forEach { unit ->
                                                        FilterChip(
                                                            selected = autoBackupCustomUnit.equals(unit, ignoreCase = true),
                                                            onClick = {
                                                                val parsed = customValueStr.toIntOrNull() ?: 1
                                                                onAutoBackupCustomIntervalChange(parsed, unit)
                                                            },
                                                            label = {
                                                                Text(
                                                                    unit.lowercase().replaceFirstChar { it.uppercase() }
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            if (autoBackupCustomUnit.equals("MINUTES", ignoreCase = true) && (customValueStr.toIntOrNull() ?: 0) < 15) {
                                                Text(
                                                    text = "⚠️ Minimum interval is 15 minutes (Android system limit).",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                )
                            )
                        }

                        add(
                            SettingItem.Switch(
                                id = "auto_backup_on_app_open",
                                title = "Also back up when I open the app",
                                subtitle = "Skips if the last successful backup is less than 1 hour old to prevent rapid duplicate backups",
                                checked = autoBackupOnAppOpen,
                                onCheckedChange = onAutoBackupOnAppOpenChange
                            )
                        )

                        add(
                            SettingItem.ChipGroup(
                                id = "auto_backup_keep_count",
                                title = "Keep last N backups",
                                subtitle = "Older backups are deleted only after a new backup is created and verified",
                                options = listOf(
                                    ChipOption("3", "3"),
                                    ChipOption("5", "5 (Default)"),
                                    ChipOption("10", "10"),
                                    ChipOption("20", "20")
                                ),
                                selectedKey = autoBackupKeepCount.toString(),
                                onOptionSelected = { onAutoBackupKeepCountChange(it.toIntOrNull() ?: 5) }
                            )
                        )

                        add(
                            SettingItem.CustomContent(
                                id = "auto_backup_location",
                                content = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Backup Folder",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (autoBackupLocationUri.isBlank()) {
                                                "Default: Phone's Download / KOTHAPAD Backups"
                                            } else {
                                                "Custom Folder: $autoBackupLocationUri"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { folderPickerLauncher.launch(null) }
                                            ) {
                                                Text("Choose another folder")
                                            }
                                            if (autoBackupLocationUri.isNotBlank()) {
                                                TextButton(
                                                    onClick = { onAutoBackupLocationUriChange("") }
                                                ) {
                                                    Text("Reset to default")
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        )

                        add(
                            SettingItem.Switch(
                                id = "auto_backup_only_charging",
                                title = "Only when charging",
                                subtitle = "Automatic backup will wait until the device is connected to power",
                                checked = autoBackupOnlyCharging,
                                onCheckedChange = onAutoBackupOnlyChargingChange
                            )
                        )

                        add(
                            SettingItem.Switch(
                                id = "auto_backup_skip_low_storage",
                                title = "Skip when storage is low",
                                subtitle = "Prevents backups if storage has less than 50 MB free space",
                                checked = autoBackupSkipLowStorage,
                                onCheckedChange = onAutoBackupSkipLowStorageChange
                            )
                        )
                    }

                    // Status line
                    val statusText = if (autoBackupLastTime > 0L) {
                        val diff = System.currentTimeMillis() - autoBackupLastTime
                        val mins = diff / (60 * 1000)
                        val hours = diff / (60 * 60 * 1000)
                        val days = diff / (24 * 60 * 60 * 1000)
                        val relTime = when {
                            mins < 1 -> "just now"
                            mins < 60 -> "$mins minute${if (mins == 1L) "" else "s"} ago"
                            hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
                            else -> "$days day${if (days == 1L) "" else "s"} ago"
                        }
                        val formattedSize = if (autoBackupLastSize >= 1024 * 1024) {
                            "%.1f MB".format(java.util.Locale.US, autoBackupLastSize / (1024.0 * 1024.0))
                        } else {
                            "%.1f KB".format(java.util.Locale.US, autoBackupLastSize / 1024.0)
                        }

                        if (autoBackupLastStatus == "SUCCESS") {
                            "Last backup: $relTime, success ($formattedSize)"
                        } else {
                            "Last backup: $relTime ($autoBackupLastStatus)"
                        }
                    } else {
                        "No automatic backups completed yet."
                    }

                    add(
                        SettingItem.InfoText(
                            id = "auto_backup_status",
                            title = "Status",
                            description = statusText,
                            badge = if (autoBackupLastStatus == "SUCCESS") "✓ Operational" else if (autoBackupLastStatus.isNotBlank()) "Notice" else null
                        )
                    )

                    // Buttons: "Back up now", "View backups / Restore"
                    add(
                        SettingItem.ButtonRow(
                            id = "auto_backup_actions",
                            title = "Backup Actions",
                            buttons = listOf(
                                SettingButtonOption(
                                    label = if (isManualBackingUp) "Backing up..." else "Back up now",
                                    onClick = {
                                        isManualBackingUp = true
                                        onTriggerManualAutoBackup { res ->
                                            isManualBackingUp = false
                                            res.onSuccess {
                                                Toast.makeText(context, "Backup created successfully!", Toast.LENGTH_SHORT).show()
                                            }.onFailure { err ->
                                                Toast.makeText(context, "Backup failed: ${err.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                ),
                                SettingButtonOption(
                                    label = "View backups / Restore",
                                    onClick = onViewAutoBackupsClick
                                )
                            )
                        )
                    )
                }
            ),

            // Backup & Restore Section
            SettingHeadlineSection(
                id = "backup_restore",
                title = "Backup & Restore",
                subtitle = "Export or replace all notes, folders, templates & app settings",
                icon = Icons.Outlined.Backup,
                items = listOf(
                    SettingItem.ButtonRow(
                        id = "export_backup_action",
                        title = "Export Backup",
                        subtitle = "Saves all notes, templates, folder tree & settings into a ZIP archive",
                        buttons = listOf(
                            SettingButtonOption(
                                label = "Export Backup",
                                onClick = {
                                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                                    exportLauncher.launch("KothaPad_backup_$dateStr.zip")
                                }
                            )
                        )
                    ),
                    SettingItem.ButtonRow(
                        id = "import_backup_action",
                        title = "Import Backup (Replace mode)",
                        subtitle = "Validates and restores notes, templates & settings from a ZIP backup",
                        buttons = listOf(
                            SettingButtonOption(
                                label = "Import Backup",
                                isDestructive = true,
                                onClick = {
                                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                                }
                            )
                        )
                    )
                )
            ),

            // About Section
            SettingHeadlineSection(
                id = "about",
                title = "About Notepad",
                subtitle = "Features tour, productivity tips, version & privacy",
                icon = Icons.Outlined.Info,
                items = listOf(
                    SettingItem.InfoText(
                        id = "about_info",
                        title = "KothaPad v1.0",
                        description = "100% Kotlin & Jetpack Compose native speech notepad for power users. Native local-first storage with Room, DataStore, complete Export/Import backup, and Android SpeechRecognizer.",
                        badge = "🔒 Privacy: 0 accounts • 0 ads • 0 analytics • Audio processed only on device/system recognizer."
                    ),
                    SettingItem.CustomContent(
                        id = "about_guides",
                        content = {
                            AboutNotepadGuides()
                        }
                    )
                )
            )
        )
    }

    BackHandler(enabled = true) {
        onBackClick()
    }

    val isAnyDialogOpen = showExportDialog || showImportDialog ||
            showFinishSetupDialog || showEmptyTrashConfirmDialog ||
            (noteToDeletePermanently != null) || (validationError != null)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .horizontalSwipeNavigation(
                enabled = !isAnyDialogOpen,
                allowSwipeLeft = false,  // right-to-left: disabled (does NOT open Note Manager)
                allowSwipeRight = true,  // left-to-right: closes Settings menu and returns to Editor
                onSwipeRight = onBackClick
            )
            .testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = sections, key = { it.id }) { section ->
                val isExpanded = expandedSectionId == section.id
                val sectionId = section.id
                HeadlineCard(
                    section = section,
                    isExpanded = isExpanded,
                    onToggle = {
                        expandedSectionId = if (expandedSectionId == sectionId) null else sectionId
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Export Layout Dialog
    if (showExportDialog) {
        val exportedJson = remember(showExportDialog, activeKeyboardIndex) { onExportKeyboardLayout(activeKeyboardIndex) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export ${if (activeKeyboardIndex == 1) "Keyboard 2" else "Keyboard 1"} Layout (JSON)") },
            text = {
                OutlinedTextField(
                    value = exportedJson,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(exportedJson))
                        showExportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Copy to Clipboard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Import Layout Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import ${if (activeKeyboardIndex == 1) "Keyboard 2" else "Keyboard 1"} Layout (JSON)") },
            text = {
                Column {
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = {
                            importJsonText = it
                            importError = false
                        },
                        placeholder = { Text("Paste layout JSON here...") },
                        minLines = 6,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                        isError = importError
                    )
                    if (importError) {
                        Text(
                            text = "Invalid keyboard JSON format",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = onImportKeyboardLayout(importJsonText, activeKeyboardIndex)
                        if (success) {
                            showImportDialog = false
                        } else {
                            importError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Backup Confirmation Dialog (Replace All)
    pendingRestoreData?.let { restoreData ->
        AlertDialog(
            onDismissRequest = {
                if (!isRestoring) pendingRestoreData = null
            },
            modifier = Modifier.testTag("confirm_restore_dialog"),
            title = {
                Text(
                    text = "Replace All Data?",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This will delete all current notes and settings and replace them with the backup. Continue?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Backup details:\n• Notes: ${restoreData.notes.size}\n• Templates: ${restoreData.stamps.size}\n• Backup Version: v${restoreData.backupVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRestore = restoreData
                        pendingRestoreData = null
                        isRestoring = true
                        onRestoreBackup(toRestore) { result ->
                            isRestoring = false
                            result.onSuccess { summary ->
                                Toast.makeText(
                                    context,
                                    "Backup restored: ${summary.notesCount} notes, ${summary.templatesCount} templates",
                                    Toast.LENGTH_LONG
                                ).show()
                                showFinishSetupDialog = true
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: "Restoration failed",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_restore_button")
                ) {
                    Text("Replace", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingRestoreData = null },
                    modifier = Modifier.testTag("cancel_restore_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Validation Error Dialog
    validationError?.let { errText ->
        AlertDialog(
            onDismissRequest = { validationError = null },
            title = {
                Text(
                    text = "Backup Verification Failed",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = errText,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { validationError = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("OK")
                }
            }
        )
    }

    // Progress Indicator Dialog
    if (isExporting || isValidating || isRestoring) {
        val progressMessage = when {
            isExporting -> "Exporting backup to ZIP..."
            isValidating -> "Verifying backup file..."
            isRestoring -> "Restoring notes and settings..."
            else -> "Processing..."
        }
        AlertDialog(
            onDismissRequest = { /* Non-dismissible while active */ },
            title = {
                Text(
                    text = progressMessage,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Please wait, do not close the app...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }

    // Finish Setup Dialog (Permission & System Setting Checklist)
    if (showFinishSetupDialog) {
        FinishSetupDialog(
            onDismiss = { showFinishSetupDialog = false }
        )
    }


    // Edit Template Dialog
    editingTemplate?.let { template ->
        EditTemplateDialog(
            template = template,
            isNew = isCreatingTemplate,
            onSave = { updated ->
                onSaveTemplate(updated)
                editingTemplate = null
            },
            onDismiss = { editingTemplate = null }
        )
    }

    // Initial Analog Clock Picker Dialog (opened directly upon pressing Plus button)
    if (showInitialClockPicker) {
        val now = remember { java.util.Calendar.getInstance() }
        val currentHour = remember { now.get(java.util.Calendar.HOUR_OF_DAY) }
        val currentMinute = remember { now.get(java.util.Calendar.MINUTE) }

        AnalogClockPickerDialog(
            initialHour = currentHour,
            initialMinute = currentMinute,
            onConfirm = { selectedHour, selectedMinute ->
                showInitialClockPicker = false
                editingReminder = ScheduledReminder(
                    id = "",
                    hour = selectedHour,
                    minute = selectedMinute,
                    isEnabled = true
                )
                isCreatingReminder = true
            },
            onDismiss = {
                showInitialClockPicker = false
            }
        )
    }

    // Edit / Create Reminder Dialog
    editingReminder?.let { rem ->
        EditReminderDialog(
            reminder = rem,
            onSave = { updated ->
                onSaveScheduledReminder(updated)
                editingReminder = null
            },
            onDelete = if (rem.id.isNotBlank() && !isCreatingReminder) {
                {
                    onDeleteScheduledReminder(rem.id)
                    editingReminder = null
                }
            } else null,
            onDismiss = { editingReminder = null }
        )
    }

    // Confirm Empty Trash Dialog
    if (showEmptyTrashConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirmDialog = false },
            title = { Text("Empty Trash?") },
            text = {
                Text("All notes currently in Trash will be permanently deleted. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEmptyTrash()
                        showEmptyTrashConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm Permanent Delete Single Note Dialog
    noteToDeletePermanently?.let { noteToDelete ->
        AlertDialog(
            onDismissRequest = { noteToDeletePermanently = null },
            title = { Text("Permanently Delete Note?") },
            text = {
                Text("Are you sure you want to permanently delete \"${noteToDelete.title.ifBlank { "Untitled Note" }}\"? This cannot be recovered.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onPermanentlyDeleteNote(noteToDelete)
                        noteToDeletePermanently = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDeletePermanently = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// Direct Navigation Setting Card
// ==========================================
// Collapsible Headline Card with Rotating Chevron
// ==========================================

@Composable
fun HeadlineCard(
    section: SettingHeadlineSection,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "chevron_rotation_${section.id}"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("headline_${section.id}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = 1.dp,
            color = if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Tappable Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (section.icon != null) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    )
                    if (!section.subtitle.isNullOrBlank()) {
                        Text(
                            text = section.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }

            // In-place animated expansion of controls
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100))
            ) {
                if (isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.padding(bottom = 14.dp)
                        )

                        section.items.forEachIndexed { index, item ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            SettingItemControl(item = item)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Reusable Control Renderers
// ==========================================

@Composable
fun SettingItemControl(item: SettingItem) {
    when (item) {
        is SettingItem.Switch -> SwitchControlRow(item)
        is SettingItem.Slider -> SliderControlRow(item)
        is SettingItem.ChipGroup -> ChipGroupControlRow(item)
        is SettingItem.ButtonRow -> ButtonRowControl(item)
        is SettingItem.InfoText -> InfoTextControl(item)
        is SettingItem.CustomContent -> item.content()
    }
}

@Composable
private fun SwitchControlRow(item: SettingItem.Switch) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = item.checked,
            onCheckedChange = item.onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun SliderControlRow(item: SettingItem.Slider) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.displayValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (!item.subtitle.isNullOrBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = item.value,
            onValueChange = item.onValueChange,
            valueRange = item.valueRange,
            steps = item.steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroupControlRow(item: SettingItem.ChipGroup) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (!item.subtitle.isNullOrBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item.options.forEach { opt ->
                val isSelected = item.selectedKey.equals(opt.key, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { item.onOptionSelected(opt.key) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    ),
                    border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    label = { Text(opt.label, fontSize = 11.sp) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRowControl(item: SettingItem.ButtonRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (item.title.isNotBlank()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item.buttons.forEach { btn ->
                OutlinedButton(
                    onClick = btn.onClick,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = btn.label,
                        fontSize = 12.sp,
                        color = if (btn.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoTextControl(item: SettingItem.InfoText) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (item.title.isNotBlank()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!item.badge.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (!item.actionButtonText.isNullOrBlank() && item.onActionClick != null) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = item.onActionClick,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(item.actionButtonText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AboutNotepadGuides() {
    var selectedGuideTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedGuideTab == 0,
                onClick = { selectedGuideTab = 0 },
                label = { Text("⚡ Features Tour", fontSize = 12.sp) }
            )
            FilterChip(
                selected = selectedGuideTab == 1,
                onClick = { selectedGuideTab = 1 },
                label = { Text("💡 Tips & Workflows", fontSize = 12.sp) }
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedGuideTab == 0) {
                    GuideItem(
                        icon = "🎙️",
                        title = "Advanced Voice Dictation",
                        desc = "Proprietary non-stop Speech Supervisor loop prevents 3-second timeouts. Supports Bluetooth/wired headsets and offline-preferred on-device transcription."
                    )
                    GuideItem(
                        icon = "🗣️",
                        title = "Bilingual Spoken Commands",
                        desc = "Typographic punctuation in Bengali (\"দাঁড়ি\", \"কমা\", \"নতুন লাইন\") and English (\"period\", \"comma\", \"new paragraph\"), plus spoken undo/delete."
                    )
                    GuideItem(
                        icon = "✍️",
                        title = "Obsidian-Style Markdown",
                        desc = "Live styling in editor mode, dimmed syntax markers, auto-continuing lists (- [ ], 1., -), and rich reading mode with tap-to-complete checkboxes."
                    )
                    GuideItem(
                        icon = "🎛️",
                        title = "Quick Format Bar & Side Rail",
                        desc = "Customizable drag-and-drop format bar and ergonomic thumb side rail for fluid one-handed control during active speech."
                    )
                    GuideItem(
                        icon = "⌨️",
                        title = "In-App Dual-Language Keyboard",
                        desc = "Dedicated Bengali & English soft keyboard allowing concurrent typing and cursor navigation without pausing voice dictation."
                    )
                    GuideItem(
                        icon = "📁",
                        title = "Full Backup & Restore",
                        desc = "Export your entire setup—notes, templates, settings, and commands—to a standard archive and restore it anytime on any device seamlessly."
                    )
                } else {
                    GuideItem(
                        icon = "🎯",
                        title = "High-Accuracy Dictation",
                        desc = "Speak naturally near your mic. Speak punctuation commands directly while dictating to produce clean, ready-to-publish paragraphs hands-free."
                    )
                    GuideItem(
                        icon = "✂️",
                        title = "Voice Editing",
                        desc = "Made a mispronunciation? Simply say \"delete last word\" (or \"শব্দ কাটো\") to erase it instantly without touching the screen."
                    )
                    GuideItem(
                        icon = "📅",
                        title = "Daily Journaling",
                        desc = "Notes are organized by date in 'Daily Journal'. Swipe left or right or use the date picker to quickly navigate between journal days."
                    )
                    GuideItem(
                        icon = "🏷️",
                        title = "Templates & Fast Stamps",
                        desc = "Tap the Stamp button above the keyboard to insert timestamps [{date} {time}] or fill out structured daily log prompts."
                    )
                    GuideItem(
                        icon = "🚀",
                        title = "Offline Privacy",
                        desc = "Turn on 'Offline Preferred' in Settings for zero-latency dictation with zero internet transmission. All data stays strictly private on your device."
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideItem(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = icon, fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
