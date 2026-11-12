# MainBrain.md - KothaPad Architectural Source of Truth & Developer Reference Manual

## 1. System Identity, Tech Stack & Architecture Overview
- **Application ID**: `com.aistudio.kothapad.zhntnp` (Package namespace `com.example`)
- **Launcher Label**: "KothaPad"
- **Target Platform**: Android Native (Kotlin + Jetpack Compose with Material 3)
- **Tooling & SDK**:
  - Gradle 9.3.1 (Kotlin DSL), Android Gradle Plugin (AGP) 9.1.1
  - Compile SDK 36, Target SDK 36, Min SDK 26, JVM Target 11
  - Jetpack Compose BOM 2024+, Room Database 2.6+ with KSP compiler, Jetpack DataStore (Preferences), Jetpack WorkManager 2.10+
- **Architecture Pattern**:
  - Single-Activity Architecture (`MainActivity.kt`) hosting an in-memory persistent `EditorScreen` underneath animated overlay sub-screens (`DAILY_JOURNAL_MANAGER`, `SETTINGS`, `MARKDOWN_SETTINGS`, `AUTO_BACKUP_RESTORE`).
  - MVVM via `EditorViewModel.kt` instantiated through `ViewModelProvider.Factory` accessing singletons initialized in `KothaPadApp.kt` (`AppDatabase`, `NoteRepository`, `StampRepository`, `SpokenCommandRepository`, `PreferencesManager`, `SpeechSupervisor`).
  - Unidirectional Data Flow (UDF): ViewModels expose immutable `StateFlow` and `SharedFlow` streams; Composable screens emit event callbacks upward.
- **Offline-First & Zero-Telemetry Contract**:
  - 100% offline-functional: Zero analytics, zero crash-reporting servers, zero network calls, zero external accounts.
  - All notes, templates, settings, and audio dictations reside solely on-device in internal SQLite Room storage and DataStore.

---

## 2. Visual Design System, Theme Engine & Typography
- **Theme Engine (`com.example.ui.theme.Theme.kt`, `Color.kt`)**:
  - `KothaPadTheme(themeSetting, customPrimary, customBackground, customSurface, customText, customSecondary, cornerRadiusDp)`
  - **Presets**:
    - `DEFAULT` / `CYBER_DARK`: Rich dark slate (`#121214`), dark purple accents (`#7C4DFF`).
    - `AMOLED_BLACK`: Pure `#000000` background and surface for zero OLED pixel power consumption during continuous dictation.
    - `CLEAN_LIGHT`: High-contrast neutral light palette (`#F8F9FA` background, `#1A1A1A` text).
    - `CUSTOM`: Fully user-definable 5-color ARGB palette (`customPrimaryColor`, `customBackgroundColor`, `customSurfaceColor`, `customTextColor`, `customSecondaryColor`).
  - Dynamic corner radius (`cornerRadiusDp`: 0dp to 24dp) propagated through `MaterialTheme.shapes`.
- **Typography Engine (`com.example.ui.theme.Type.kt`)**:
  - `getFontFamilyByOption(option: String, customPath: String?)`:
    - `"INTER"` $\rightarrow$ Loads `res/font/inter.ttf` (Default modern sans-serif).
    - `"JETBRAINS_MONO"` $\rightarrow$ Loads `res/font/jetbrains_mono.ttf` (Monospace, markdown code).
    - `"NOTO_SANS_BENGALI"` $\rightarrow$ Loads `res/font/noto_sans_bengali.ttf` (Native complex script typography).
    - `"CUSTOM"` $\rightarrow$ Loads external user-supplied `.ttf`/`.otf` file from disk via `Font(File(customPath))`.
- **Note Metrics Display Config (`NoteMetricsConfig.kt`)**:
  - Real-time text statistics computed via `split("\\s+".toRegex())` (words), `.length` (characters), and average reading speed (200 WPM).
  - Configurable chip layout: `noteMetricsStyle` (`"COMPACT"` vs `"DETAILED"`), `noteMetricsOrder` (e.g. `"WORDS,CHARS,READ_TIME"`), and visibility filters.

---

## 3. Editor Interface & Tap-to-Center Cursor Engine
- **File**: `app/src/main/java/com/example/ui/editor/EditorScreen.kt`
- **Visual Interface Structure**:
  - **Top Bar (`EditorTopBar.kt`)**: Note title (tap to rename dialog), language indicator badge (tap opens `LanguagePickerDialog`, long-press cycles favorite languages), daily journal back navigation button, menu button (opens Calendar), share button, and delete note button.
  - **Toolbar (`EditorToolBar.kt`)**: Keyboard mode toggle (In-App vs System IME), dictation status chip, active audio source badge (Built-in, Wired, Bluetooth), on-device indicator, live word/char counters, text zoom controls (`zoomIn()`, `zoomOut()`), and style sheet bottom sheet launcher.
  - **Scrollable Viewport**: `Column` with `.verticalScroll(scrollState)` and dynamic padding accommodating side rails or floating toolbars.
  - **Editable Viewport vs Reading Viewport**:
    - Reading Mode: Shows `MarkdownRenderer` with interactive checkboxes or selectable `Text`.
    - Editing Mode: Shows `BasicTextField` wrapped with custom `VisualTransformation`.
- **Tap-to-Center Cursor Mechanics**:
  - **Gesture Detection**:
    ```kotlin
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            val startPos = down.position
            val startTime = System.currentTimeMillis()
            var isTap = true
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Final)
                val change = event.changes.find { it.id == down.id }
                if (change == null || (change.position - startPos).getDistance() > 18f) {
                    isTap = false; break
                }
                if (!change.pressed) {
                    val duration = System.currentTimeMillis() - startTime
                    if (isTap && duration < 500) { centerCursorTrigger++ }
                    break
                }
            }
        }
    }
    ```
  - **Centering Calculation**:
    ```kotlin
    val usableHeightPx = (viewportHeightPx - bottomControlsHeightPx).coerceAtLeast(100f)
    val targetCenterY = usableHeightPx / 2f
    val diff = currentCursorYOnScreen - targetCenterY
    if (abs(diff) > 8f) {
        val targetScroll = (scrollState.value + diff).roundToInt().coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(targetScroll, animationSpec = tween(200, easing = FastOutSlowInEasing))
    }
    ```
  - **Scroll Position Invariants**:
    - Reading $\leftrightarrow$ Editing mode transitions capture `lastScrollPosition` and re-apply it via `snapshotFlow { scrollState.maxValue }` without jumping to 0.
    - Note changes (`currentNote?.id`) strictly reset `lastScrollPosition = 0` and scroll to 0.
  - **System IME vs Custom Keyboard Interception**:
    - Built-in Keyboard: `InterceptPlatformTextInput { awaitCancellation() }` prevents Android system IME from popping up while maintaining a live, blinking cursor inside `BasicTextField`.
    - System Keyboard: Passes input method request through `nextHandler.startInputMethod(request)`.

---

## 4. Speech Supervisor & Continuous Dictation Engine
- **Files**: `app/src/main/java/com/example/engine/dictation/SpeechSupervisor.kt`, `AudioSourceDetector.kt`
- **Visuals**:
  - Floating action cluster or side rail hosting a pulsating glowing Mic button.
  - Real-time animated waveform: 5 vertical audio bars driven by `_audioLevelRms: StateFlow<Float>` (0.0f to 1.0f).
  - Status chips displaying active dictation state (`Listening`, `Paused`, `Processing`) and connected audio hardware.
- **Continuous Supervised Loop**:
  - Wraps native `android.speech.SpeechRecognizer` and `RecognitionListener`.
  - Normal Android speech recognizers terminate immediately upon 3 seconds of silence. KothaPad catches `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT`, and `onEndOfSpeech()`:
    ```kotlin
    if (isSessionActive && !isManuallyPaused) {
        mainHandler.postDelayed(restartRunnable, 150L)
    }
    ```
  - Silent recovery is bounded by user-configured `pauseToleranceSeconds` (default 30s).
  - Inactivity watchdog: `idleAutoStopMinutes` automatically closes the session if no words are spoken for $N$ minutes.
  - Timed dictation sprints: `timedMinutes` (1–60m) countdown timer that finishes session automatically.
- **Audio Routing & Hardware Detection (`AudioSourceDetector.kt`)**:
  - Registers `AudioDeviceCallback` (API 28+) and broadcasts for `ACTION_HEADSET_PLUG`, `BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED`, and `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED`.
  - Automatically routes audio input between Built-in Microphone, Wired Headset, and Bluetooth Headset (enabling Bluetooth SCO when connected).
- **Beep Suppression & Volume Recovery**:
  - Suppresses intrusive Android speech recognition start/stop beeps:
    - Mode `MEDIA_SYSTEM`: Saves system and music stream volumes (`getStreamVolume`) and sets them to 0 on session start; restores original volumes once when the entire dictation session stops.
    - Mode `VIBRATE_ONCE`: Silences streams and performs a brief 20ms haptic feedback pulse.
    - Mode `OFF`: Leaves system audio unmanaged.

---

## 5. Text Processing Pipeline & Spoken Commands
- **Files**: `app/src/main/java/com/example/engine/pipeline/` (`TextPipeline.kt`, `AutoCapitalizePipelineStep.kt`, `SpacingPipelineStep.kt`, `SpokenCommandsPipelineStep.kt`, `StampProcessor.kt`)
- **Pipeline Architecture**:
  - Inbound text from speech recognizer passes through a synchronous pipeline chain before being committed to the document:
    $$\text{Raw Speech} \xrightarrow{\text{Spoken Commands}} \xrightarrow{\text{Spacing Rules}} \xrightarrow{\text{Auto-Capitalization}} \xrightarrow{\text{Stamp Macro Expansion}} \text{Document}$$
- **Spoken Commands Engine (`SpokenCommandsPipelineStep.kt`)**:
  - Queries Room table `spoken_commands` cached in memory.
  - Matches spoken trigger phrases against registered dictionary entries.
  - Supports two action categories:
    1. `PUNCTUATION`: In-place replacement of trigger phrase with punctuation marks.
    2. `ACTION`: Triggers editor operations via `commandCode` (`DELETE_LAST_WORD`, `UNDO`, `STOP_DICTATION`).
- **Typographical Rule Matrix**:
  | Language | Spoken Trigger Phrase | Typographical / Action Output |
  | :--- | :--- | :--- |
  | **Bengali** | `"দাঁড়ি"`, `"দাড়ি"` | `। ` (Bangla Dari + trailing space) |
  | **Bengali** | `"কমা"` | `, ` |
  | **Bengali** | `"প্রশ্নবোধক চিহ্ন"`, `"প্রশ্নচিহ্ন"` | `? ` |
  | **Bengali** | `"আশ্চর্যবোধক চিহ্ন"` | `! ` |
  | **Bengali** | `"নতুন লাইন"` | `\n` |
  | **Bengali** | `"নতুন প্যারাগ্রাফ"` | `\n\n` |
  | **Bengali** | `"কোলন"` | `: ` |
  | **Bengali** | `"শব্দ কাটো"`, `"শেষ শব্দ মুছুন"` | Deletes previous word |
  | **Bengali** | `"আনডু করো"` | Reverts last voice commit |
  | **Bengali** | `"থামো"`, `"বন্ধ করো"` | Stops dictation session |
  | **English** | `"full stop"`, `"period"` | `. ` |
  | **English** | `"comma"` | `, ` |
  | **English** | `"question mark"` | `? ` |
  | **English** | `"exclamation mark"` | `! ` |
  | **English** | `"new line"` | `\n` |
  | **English** | `"new paragraph"` | `\n\n` |
  | **English** | `"colon"` | `: ` |
  | **English** | `"dash"` | ` — ` |
  | **English** | `"delete last word"` | Deletes previous word |
  | **English** | `"undo that"` | Reverts last voice commit |
  | **English** | `"stop dictation"` | Stops dictation session |
- **Smart Spacing Step (`SpacingPipelineStep.kt`)**:
  - Strips illegal leading whitespace before punctuation symbols: `\\s+([,.:?!।])` $\rightarrow$ `$1`.
  - Guarantees exactly one space following punctuation unless immediately followed by a newline.
- **Stamp Processor (`StampProcessor.kt`)**:
  - Replaces dynamic text macros in templates:
    - `{date}` $\rightarrow$ Current date (e.g. `2026-11-12`)
    - `{time}` $\rightarrow$ Current time (e.g. `14:30`)
    - `{datetime}` $\rightarrow$ Date and time combined
    - `{weekday}` $\rightarrow$ Day of week (e.g. `Thursday`)
    - `{cursor}` $\rightarrow$ Designates final caret insertion position after pasting stamp

---

## 6. Dual In-App Soft Keyboards & Custom Layout Engine
- **Files**: `app/src/main/java/com/example/ui/keyboard/` (`InAppKeyboard.kt`, `KeyboardLayouts.kt`), `app/src/main/java/com/example/data/model/KeyboardLayout.kt`
- **Visual Interface**:
  - Fixed-height or dynamic bottom sheet displaying rows of rounded soft keys.
  - Configurable key height (`keyboardKeyHeightDp`: 30dp to 56dp), font size (`keyboardFontSizeSp`: 12sp to 20sp), and haptic click vibration.
  - Active layout index pill indicator with quick switch action.
- **Dual Independent Layouts (Keyboard 1 & Keyboard 2)**:
  - System maintains two completely separate keyboard layouts serialized into DataStore: `KEY_KEYBOARD_LAYOUT_JSON` and `KEY_KEYBOARD_LAYOUT_2_JSON`.
  - **Bi-directional Horizontal Swipe**: Swiping across the keyboard surface switches between Keyboard 1 and Keyboard 2 without closing the keyboard or dropping cursor focus.
- **Drag-and-Drop Repositioning Mechanics**:
  - **Long-Press Activation**: Holding a key for > 320ms when editing mode is disabled enters repositioning mode. Key elevates with shadow elevation (`8.dp`) and follows pointer.
  - **Slot Swapping**: Dropping over a target key calls `swapKeysInLayout(fromRow, fromCol, toRow, toCol, layoutIndex)`.
  - **Cross-Keyboard Edge Dragging**: Dragging a key to the far left ($x < 24\text{dp}$) or far right ($x > \text{width} - 24\text{dp}$) and holding for $\sim 450\text{ms}$ automatically flips the active keyboard layout, allowing seamless key transfers between Keyboard 1 and Keyboard 2.
- **Editing Mode Toggle Button (`KeyActionType.TOGGLE_EDIT_MODE`)**:
  - Dedicated key labeled `"✎"` positioned by default on the top-right corner.
  - Tapping toggles edit mode ON/OFF with distinct highlight. While active, tapping any key opens `EditKeyDialog` to modify label, output text, smart spacing, action type, and width weight.
  - **Mandatory Invariant**: Every keyboard layout must have at least one `TOGGLE_EDIT_MODE` key. Deletion and remapping prevent removing the sole remaining toggle key.
- **Key Actions (`KeyActionType` Enum)**:
  - Text: `INSERT`, `SPACE`, `ENTER`, `NEW_PARAGRAPH`, `BACKSPACE`, `DELETE_WORD`.
  - Cursor: `CURSOR_LEFT`, `CURSOR_RIGHT`, `CURSOR_UP`, `CURSOR_DOWN`, `WORD_LEFT`, `WORD_RIGHT`, `LINE_START`, `LINE_END`.
  - Clipboard & System: `UNDO`, `REDO`, `SELECT_ALL`, `PASTE`, `OPEN_TEMPLATES`, `CHANGE_LANGUAGE`, `SWITCH_LAYOUT`, `TOGGLE_SYSTEM_KEYBOARD`, `DISMISS_KEYBOARD`, `EXIT`.

---

## 7. Obsidian-Grade Markdown Live Styler & Reading View
- **Files**: `app/src/main/java/com/example/markdown/` (`MarkdownLiveStyler.kt`, `MarkdownRenderer.kt`, `MarkdownListAutoContinuer.kt`, `MarkdownSettings.kt`)
- **Live In-Editor Styler (`MarkdownLiveStyler.kt`)**:
  - Uses Compose `VisualTransformation` returning `TransformedText(styledAnnotatedString, OffsetMapping.Identity)`.
  - Raw underlying text in `TextFieldValue` is never modified; only rendering spans are injected.
  - **Regular Expression Rules**:
    - Headings: `^(#{1,6})(\\s+)(.*)$` $\rightarrow$ Hashes dimmed to `0.45f` alpha; heading text scaled (H1: `1.45f`, H2: `1.30f`, H3: `1.20f`, H4: `1.10f`, H5: `1.00f`, H6: `0.95f`).
    - Bold / Italic: `(\*\*|__)(.*?)\1` (Bold), `(\*|_)(.*?)\1` (Italic), `(\*\*\*|___)(.*?)\1` (Bold+Italic). Markers dimmed; text weighted.
    - Highlight: `(==)([^=\n]+)(==)` $\rightarrow$ Markers dimmed; background tinted `HighlightGold.copy(alpha = 0.35f)`.
    - Strikethrough: `(~~)([^~\n]+)(~~)` $\rightarrow$ `TextDecoration.LineThrough`.
    - Inline Code: ``(`)([^`\n]+)(`)`` $\rightarrow$ Background filled `surfaceVariant`, font set to `FontFamily.Monospace`.
    - Fenced Code Blocks: ````^```(\w*)```` to ````^```$```` $\rightarrow$ Full-block monospace background in `jetbrains_mono.ttf`.
    - Blockquotes: `^(\s*)(>+)(\s*)(.*)$` $\rightarrow$ Prefix dimmed; quote text tinted.
    - Links: `(\[)([^\]\n]+)(\])(\()([^)\n]+)(\))` $\rightarrow$ Bracket markers dimmed; text colored `primary`.
- **List Auto-Continuation (`MarkdownListAutoContinuer.kt`)**:
  - Intercepts `onValueChange` in `BasicTextField`.
  - When user types Enter (`\n`), checks the preceding line:
    - Bullet list (`- ` or `* `): Appends new bullet item.
    - Ordered list (`^(\d+)\.\s`): Increments counter (e.g. `2. ` $\rightarrow$ `3. `).
    - Task checkbox (`- [ ] ` or `- [x] `): Inserts unchecked box `- [ ] `.
    - Double Enter on empty list line: Strips the empty prefix and ends list cleanly.
- **Reading View Interactive Checkboxes (`MarkdownRenderer.kt`)**:
  - In Reading Mode, task lines render with native interactive Compose `Checkbox` widgets.
  - Tapping a checkbox updates the raw markdown string in `EditorViewModel` (`- [ ] ` $\leftrightarrow$ `- [x] `) and triggers silent auto-save without switching to Editing Mode.

---

## 8. Ergonomic Side Rail & Floating Controls Architecture
- **Files**: `app/src/main/java/com/example/ui/editor/EditorSideRailLayout.kt`, `EditorFloatingCluster.kt`
- **Visual Models**:
  - **Ergonomic Side Rail (`EditorSideRailLayout.kt`)**:
    - Slim vertical control strip anchored along the left or right screen border (`railSide: "LEFT" | "RIGHT"`).
    - Positioned at thumb height (`railBottomOffsetDp`).
    - Contains: Mic Button, Timer Sprint button, In-App Keyboard switch, Format Bar expander, and Reading Mode toggle.
    - Reorderable buttons via `railButtonOrder` preference list.
  - **Docked Floating Cluster (`EditorFloatingCluster.kt`)**:
    - Bottom-centered floating pill cluster used when side rail is disabled.
- **Text Clearance Invariant (`railKeepTextClear`)**:
  - When enabled, calculates dynamic margin:
    $$\text{editorStartPad} = \text{marginDp} + (\text{if Rail is Left then } (48\text{dp} \times \text{railScale} + 12\text{dp}) \text{ else } 0\text{dp})$$
  - Guarantees note content never scrolls beneath floating buttons.
- **Reading Mode Floating Controls**:
  - 4 unified buttons docked at screen bottom:
    1. **Previous Note** (`ArrowBack`): Navigates to prior chronological journal entry.
    2. **Reading/Editing Toggle** (`MenuBook`): Swaps back to Editing Mode.
    3. **Next Note** (`ArrowForward`): Navigates to subsequent journal entry.
    4. **Exit App** (`ExitToApp`): Flushes auto-save and immediately terminates the application.
  - Synchronized size (`readingNavButtonSizeDp`) and vertical position (`readingNavBottomOffsetDp`).

---

## 9. Journal-First Architecture & Daily Calendar System
- **Files**: `app/src/main/java/com/example/ui/notes/DailyJournalManagerScreen.kt`, `com.example.ui.navigation.HorizontalSwipeModifier.kt`
- **Visual Interface**:
  - Top app bar displaying current month and year (e.g. "November 2026") with previous/next month navigation buttons.
  - 7-column weekday header (`Sat`, `Sun`, `Mon`, `Tue`, `Wed`, `Thu`, `Fri`).
  - Month calendar grid with custom day cells.
  - Floating Action Button (+): Always targets today's date (`LocalDate.now()`).
- **Cold-Start App Lifecycle**:
  - Cold launch immediately loads the last opened note (`KEY_LAST_OPENED_NOTE_ID`) in Reading Mode with scroll at top (0).
  - If null, locates today's date note (`yyyy-MM-dd`), creating it automatically if absent.
- **High-Performance Single-Pass Calendar Engine**:
  - Custom `Layout` composable bypassing nested row and column measurements.
  - Pre-warmed `ConcurrentHashMap<Int, FastMonthData>` indexing month offsets from epoch month (2010-01).
  - **O(1) Bitmask Note Lookup**:
    ```kotlin
    val notesMaskMap = HashMap<YearMonth, Int>()
    // Day N is encoded at bit (1 shl N) in a 32-bit integer
    map[ym] = currentMask or (1 shl d.dayOfMonth)
    ```
  - **Visual Day Cell Indicators**:
    - Has Note: Bold date number with primary accent background.
    - Active Currently Opened Note: Blinking electric cyan border (`infiniteRepeatable` alpha animation 0.3f to 1.0f) with a cyan sub-dot.
    - Today: Crisp white outline border.
- **Swipe Navigation Isolation**:
  - Reading Mode: Screen-wide horizontal swipes (Left-to-Right $\rightarrow$ Calendar, Right-to-Left $\rightarrow$ Settings).
  - Editing Mode: Horizontal swipes are strictly confined to the note text body and blank areas; gestures never fire from toolbar, format bar, side rail, or soft keyboard.

---

## 10. Room Database, Schemas & Migrations
- **File**: `app/src/main/java/com/example/data/db/AppDatabase.kt`
- **Database Identity**: `kothapad.db`, version 4.
- **Entity Schemas**:
  - **`notes` Table (`Note.kt`)**:
    ```sql
    CREATE TABLE notes (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        title TEXT NOT NULL,
        content TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL,
        folder TEXT NOT NULL DEFAULT 'General',
        tags TEXT NOT NULL DEFAULT '',
        isArchived INTEGER NOT NULL DEFAULT 0,
        isDeleted INTEGER NOT NULL DEFAULT 0,
        deletedAt INTEGER DEFAULT NULL,
        sessionDurationSec INTEGER NOT NULL DEFAULT 0,
        sessionCount INTEGER NOT NULL DEFAULT 0,
        wordCount INTEGER NOT NULL DEFAULT 0,
        charCount INTEGER NOT NULL DEFAULT 0,
        statsStale INTEGER NOT NULL DEFAULT 0
    );
    ```
  - **`stamps` Table (`Stamp.kt`)**:
    ```sql
    CREATE TABLE stamps (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        setName TEXT NOT NULL,
        label TEXT NOT NULL,
        templateText TEXT NOT NULL,
        voiceTriggerPhrase TEXT NOT NULL,
        orderIndex INTEGER NOT NULL,
        isPinned INTEGER NOT NULL DEFAULT 0
    );
    ```
  - **`spoken_commands` Table (`SpokenCommand.kt`)**:
    ```sql
    CREATE TABLE spoken_commands (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        language TEXT NOT NULL,
        phrase TEXT NOT NULL,
        actionType TEXT NOT NULL,
        replacementText TEXT NOT NULL,
        commandCode TEXT NOT NULL
    );
    ```
- **Migration History**:
  - `MIGRATION_1_2`: Added `folder`, `tags`, `isArchived`, `isDeleted`, `deletedAt`, `sessionDurationSec` to `notes`; added `isPinned` to `stamps`.
  - `MIGRATION_2_3`: Added `sessionCount` to `notes`.
  - `MIGRATION_3_4`: Added `wordCount`, `charCount`, and `statsStale` to `notes`.
- **Soft-Delete & Auto-Empty Trash**:
  - Trashed notes have `isDeleted = 1` and `deletedAt = System.currentTimeMillis()`.
  - `trash_auto_empty_days` (default 7 days): Background startup scan deletes notes where `System.currentTimeMillis() - deletedAt > days * 86400000L`.

---

## 11. WorkManager Automated Backup System
- **Files**: `app/src/main/java/com/example/data/backup/AutoBackupManager.kt`, `AutoBackupWorker.kt`
- **Visual Interface (Settings > Backup)**:
  - Schedule selector: Off, 6 Hours, Daily, Weekly, Custom (slider $\ge$ 15 min).
  - Retention count selector: Keep last 3, 5, 10, or 20 archives.
  - Backup location card showing active path (`Download/KOTHAPAD Backups` or custom SAF tree) with folder picker.
  - "Backup Now" manual trigger button and "View Auto Backups & Restore" launcher.
- **Worker Execution Architecture**:
  - Periodic Work Request:
    ```kotlin
    val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalMinutes, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder()
            .setRequiresCharging(onlyCharging)
            .setRequiresStorageNotLow(skipLowStorage)
            .build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, workRequest
    )
    ```
  - On-App-Open Trigger: Rate-limited trigger running on cold start if last successful backup is older than 1 hour.
- **Deduplication via SHA-256 Content Hashing**:
  - Generates SHA-256 digest over notes list and DataStore settings.
  - If computed hash matches `auto_backup_last_hash`, skips ZIP archive generation (`SkippedNoChanges`), saving device flash storage.
- **Pruning Engine & Rollback Safety**:
  - Retention pruning: Prunes oldest backup files only after the new backup archive has been verified on disk.
  - Pre-Restore Safety: Before restoring any backup, creates `KothaPad_pre_restore_safety.zip` in the internal cache directory.

---

## 12. Exact Alarm Notification & Floating Overlay System
- **Files**: `app/src/main/java/com/example/notification/ReminderScheduler.kt`, `ScreenOverlayReminderService.kt`, `XiaomiOverlayHelper.kt`
- **Alarm Scheduling (`ReminderScheduler.kt`)**:
  - Schedules reminders via Android `AlarmManager` with exact execution:
    ```kotlin
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
    ```
  - Broadcast receiver executes with `goAsync()` retaining process life under a 10-second `WakeLock`.
- **Foreground Smart Suppression**:
  - `ReminderScheduler` inspects `isAppInForeground`. If the user is actively viewing or typing in KothaPad, intrusive popups and alarms are suppressed to avoid disrupting focus.
- **True System Window Overlay Mode (`ScreenOverlayManager`)**:
  - Draws over third-party applications via `SYSTEM_ALERT_WINDOW`:
    ```kotlin
    val params = WindowManager.LayoutParams(
        widthPx, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER; x = 0; y = 0 }
    windowManager.addView(rootContainer, params)
    ```
  - Features scheduled time badge, custom background opacity, border stroke, and a prominent "+ Journal Today" button that unlocks straight into today's note editor.
- **MIUI / HyperOS Compatibility (`XiaomiOverlayHelper.kt`)**:
  - Detects Xiaomi devices (`getSystemProperty("ro.miui.ui.version.name")`) and prompts for "Display pop-up windows while running in the background" permission intent.

---

## 13. Backup, Restore & Universal Migration Architecture
- **Files**: `app/src/main/java/com/example/data/backup/BackupManager.kt`
- **Unified ZIP Archive Specification (`CURRENT_BACKUP_VERSION = 4`)**:
  - `manifest.json`: Metadata, app version, timestamp, entity counts.
  - `notes.json`: Array of serialized `Note` records.
  - `templates.json`: Array of serialized `Stamp` records.
  - `spoken_commands.json`: Array of serialized `SpokenCommand` records.
  - `settings.json`: JSON key-value map of preferences typed via `KNOWN_PREF_TYPES`.
- **Backward Compatibility Engine**:
  - Fully supports importing Schema v1, v2, v3, and v4 backups.
  - Supports legacy Obsidian-style directory ZIPs containing `.md` files: Parses markdown body and YAML frontmatter (`tags`, `folder`, `date`) into native Room `Note` rows.
- **Universal Backup Synchronization Rule**:
  - **Mandatory Invariant**: Every preference key defined in `PreferencesManager.Companion` MUST be registered in `KNOWN_PREF_TYPES`:
    ```kotlin
    val KNOWN_PREF_TYPES: Map<String, PrefType> = mapOf(
        KEY_DICTATION_MODE.name to PrefType.STRING,
        KEY_TIMED_MINUTES.name to PrefType.INT,
        KEY_PAUSE_TOLERANCE_SECONDS.name to PrefType.INT,
        // ... all settings registered
    )
    ```
  - Guarantees 100% of user configurations are restored without omission across device migrations.

---

## 14. Centralized Navigation Hierarchy & Back Handling
- **File**: `app/src/main/java/com/example/MainActivity.kt`
- **6-Tier Back Navigation Priority Ladder**:
  ```kotlin
  BackHandler(enabled = !isImeVisible || isReadingMode || currentScreen != "EDITOR") {
      when {
          // Priority 1: System keyboard open -> Handled by Android OS (closes keyboard)
          // Priority 2: Dialog or Sheet open -> Dismiss active overlay
          editingKey != null -> viewModel.editingKey.value = null
          showRenameDialog -> viewModel.showRenameDialog.value = false
          showLanguagePicker -> viewModel.showLanguagePicker.value = false
          showStyleSheet -> viewModel.showStyleSheet.value = false
          showTemplatesSheet -> viewModel.showTemplatesSheet.value = false
          !onboardingCompleted -> viewModel.completeOnboarding()
          
          // Priority 3: Sub-screen open -> Return to Editor
          currentScreen == "MARKDOWN_SETTINGS" -> viewModel.navigateTo("SETTINGS")
          currentScreen == "AUTO_BACKUP_RESTORE" -> viewModel.navigateTo("SETTINGS")
          currentScreen == "DAILY_JOURNAL_MANAGER" -> viewModel.onBackFromDailyJournalManager()
          currentScreen != "EDITOR" -> viewModel.navigateTo("EDITOR")
          
          // Priority 4: Voice recording active -> Prevent exit; display warning toast
          dictationState !is DictationState.Idle -> {
              Toast.makeText(this, "Recording in progress. Tap mic to pause or stop.", Toast.LENGTH_SHORT).show()
          }
          
          // Priority 5: Editing mode active -> Switch to Reading Mode
          !isReadingMode -> viewModel.setReadingMode(true)
          
          // Priority 6: Reading mode active -> Double-tap back within 2000ms to exit app
          else -> {
              val now = System.currentTimeMillis()
              if (now - lastBackPressTime < 2000L) {
                  viewModel.flushAutoSave()
                  finishAffinity()
              } else {
                  lastBackPressTime = now
                  Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
              }
          }
      }
  }
  ```
- **Immersive Mode Bounds**:
  - Immersive full-screen mode (hiding the Android system navigation bar) is strictly confined to Reading Mode on `EditorScreen`.
  - The navigation bar is immediately restored whenever navigating to Editing Mode, Calendar, Settings, or opening dialogs.
