# MainBrain.md - KothaPad Project Specification & Architecture Truth

## 1. Project Overview & Role
- **Application Name**: KothaPad (`com.aistudio.kothapad.zhntnp`, package namespace `com.example`)
- **Launcher Label**: "KothaPad"
- **Role & Target User**: Production-grade, speech-enabled notepad and daily journaling workstation engineered specifically for bilingual power writers (Bengali & English). Combines non-stop continuous voice dictation with an in-app soft keyboard, live Obsidian-style Markdown rendering, one-tap text stamps, and 100% local-first Room database storage.
- **Export Artifact**: Ready-to-install `KothaPad.apk` generated directly in the root workspace directory upon build (never duplicate as `app-debug.apk` in root).
- **Core Philosophy**: 100% offline-capable, zero cloud dependencies, zero external tracker/telemetry SDKs, direct internal database storage without mandatory file tree permissions or welcome screens.

---

## 2. Core Architectural Guardrails & Coding Constraints
- **Framework & Tooling**:
  - Kotlin + Jetpack Compose with Material 3.
  - Compile SDK 36, Target SDK 36, Min SDK 26, JVM target 11, AGP 9.1.1, Gradle 9.3.1.
  - Room 2.6+ with KSP compiler, Kotlin Coroutines & Flow, Jetpack DataStore (Preferences), Jetpack WorkManager 2.10+.
- **Architecture Pattern**:
  - Single-Activity (`MainActivity`) Compose UI hosting an in-memory persistent `EditorScreen` underneath all sub-screens (`DAILY_JOURNAL_MANAGER`, `SETTINGS`, `MARKDOWN_SETTINGS`, `AUTO_BACKUP_RESTORE`).
  - MVVM via `EditorViewModel` injected through `KothaPadApp` container.
  - Unidirectional Data Flow (UDF) powered by `StateFlow` and `SharedFlow`.
- **Token Efficiency & Build Verification**:
  - Do not execute `gradle testDebugUnitTest` unless explicitly requested.
  - Verify exclusively with `compile_applet` (`assembleDebug`).
  - Strict preservation of existing working business logic and API contracts.

---

## 3. Editor & Cursor Scrolling Architecture
- **Tap-to-Center Positioning (`EditorScreen.kt`)**:
  - Intentional tap detection: Movement $\le$ 18px and touch duration < 500ms.
  - When the user taps anywhere inside note text in editing mode, the editor places the cursor and smoothly animates scroll position so the active cursor rests near the vertical midpoint of the usable screen area (between top toolbar and bottom keyboard/format bar).
  - Tapping empty blank space below note text places cursor at text end (`text.length`) and centers the bottom of the document.
- **Free Manual Scrolling & Zero Recomposition Snap**:
  - Once cursor is positioned or keyboard is open, user can fling or scroll manually without rubber-banding or snapping back to cursor.
  - Auto-centering strictly triggers on intentional tap or initial keyboard display; never on drag-release, fling, recomposition, or side gestures.
- **Reading vs. Editing Mode Scroll Preservation**:
  - Toggling between Reading Mode and Editing Mode retains the exact visible scroll offset without jumping or resetting to 0.
  - Switching notes resets vertical scroll position to top (0).
  - Scrolling never alters text selection handles, dismisses active soft keyboard, or moves cursor position.

---

## 4. Speech Supervisor & Continuous Dictation Engine (`SpeechSupervisor.kt`, `AudioSourceDetector.kt`)
- **Supervised Loop Architecture**:
  - Custom `SpeechSupervisor` manages native Android `SpeechRecognizer` (`android.speech.SpeechRecognizer`).
  - Unlike standard Android recognizers that terminate after 3 seconds of silence, KothaPad's supervisor catches `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT`, and end-of-speech events, immediately re-arming the recognizer without dropping spoken words.
  - Configurable `pauseToleranceSeconds` (5s to 60s, default 30s) controls silent recovery threshold.
  - `idleAutoStopMinutes` (0 = disabled, 1–30m) watchdog cleanly stops dictation after prolonged inactivity.
- **Audio Routing & Hardware Detection (`AudioSourceDetector.kt`)**:
  - Dynamically registers `AudioDeviceCallback` and `ACTION_HEADSET_PLUG` / `ACTION_SCO_AUDIO_STATE_UPDATED`.
  - Live status detection: Built-in Mic, Wired Headset Mic, and Bluetooth SCO/A2DP Headset with visual status chips.
- **Beep Suppression & Volume Recovery**:
  - `muteBeepMethod`: `MEDIA_SYSTEM` (mutes `STREAM_MUSIC` and `STREAM_SYSTEM` once at session start and restores on teardown), `VIBRATE_ONCE` (haptic pulse instead of audio), or `OFF`.
  - Crash-safety recovery: Preserves volume and ringer modes, restoring them via `AudioManager` during cleanup or process restart.
- **RMS Waveform Visualizer**:
  - `_audioLevelRms: StateFlow<Float>` emits real-time dB/RMS input amplitudes (normalized 0.0 to 1.0) feeding `EditorFloatingCluster` animated wave bars.
- **Dictation State Machine (`DictationState.kt`)**:
  - `Idle`, `Listening`, `Paused`, `Processing`, `Error(message)`.
  - Text insertion modes: `APPEND_END`, `INSERT_AT_CURSOR`, `REPLACE_SELECTION`.

---

## 5. Text Processing Pipeline & Bilingual Voice Commands (`com.example.engine.pipeline`)
- **Modular Pipeline Interface (`PipelineStep.kt`)**:
  - Pipeline executes sequentially: `TextPipeline.process(rawText, cursorPosition, context)` passing through configured steps.
- **Pipeline Steps**:
  1. `AutoCapitalizePipelineStep`: Capitalizes sentence beginnings, post-punctuation tokens (`.`, `?`, `!`), and newline starts for English text.
  2. `SpacingPipelineStep`: Enforces typographical spacing rules for English and Bengali (strips rogue leading spaces before `,`, `.`, `।`, `?`, `!`, `:`; ensures single trailing space).
  3. `SpokenCommandsPipelineStep`: Matches transcribed speech against `SpokenCommand` dictionary stored in Room (`spoken_commands` table).
  4. `StampProcessor`: Expands date/time macros `{date}`, `{time}`, `{datetime}`, `{weekday}`, and positions `{cursor}` placeholder.
- **Bilingual Typographical & Voice Action Mappings**:
  - **Bengali (বাংলা)**:
    - `"দাঁড়ি"` / `"দাড়ি"` $\rightarrow$ `। `
    - `"কমা"` $\rightarrow$ `, `
    - `"প্রশ্নবোধক চিহ্ন"` / `"প্রশ্নচিহ্ন"` $\rightarrow$ `? `
    - `"আশ্চর্যবোধক চিহ্ন"` $\rightarrow$ `! `
    - `"নতুন লাইন"` $\rightarrow$ `\n`
    - `"নতুন প্যারাগ্রাফ"` $\rightarrow$ `\n\n`
    - `"কোলন"` $\rightarrow$ `: `
    - `"শেষ শব্দ মুছুন"` / `"শব্দ কাটো"` $\rightarrow$ Action `DELETE_LAST_WORD`
    - `"আনডু করো"` $\rightarrow$ Action `UNDO`
    - `"থামো"` / `"বন্ধ করো"` $\rightarrow$ Action `STOP_DICTATION`
  - **English**:
    - `"full stop"` / `"period"` $\rightarrow$ `. `
    - `"comma"` $\rightarrow$ `, `
    - `"question mark"` $\rightarrow$ `? `
    - `"exclamation mark"` $\rightarrow$ `! `
    - `"new line"` $\rightarrow$ `\n`
    - `"new paragraph"` $\rightarrow$ `\n\n`
    - `"colon"` $\rightarrow$ `: `
    - `"dash"` $\rightarrow$ ` — `
    - `"delete last word"` $\rightarrow$ Action `DELETE_LAST_WORD`
    - `"undo that"` $\rightarrow$ Action `UNDO`
    - `"stop dictation"` $\rightarrow$ Action `STOP_DICTATION`

---

## 6. Dual In-App Soft Keyboards & Custom Layout Engine (`InAppKeyboard.kt`, `KeyboardLayouts.kt`)
- **Dual Independent Layouts (Keyboard 1 & Keyboard 2)**:
  - Supports two fully independent custom keyboard configurations with separate key matrices, styles, and action bindings.
  - Bi-directional swipe switching: Swiping left-to-right or right-to-left across the soft keyboard toggles between Keyboard 1 and Keyboard 2 without losing text focus or layout area.
- **Drag-and-Drop Repositioning Engine**:
  - Long press (> 320ms) on any key initiates drag-and-drop repositioning with elevated shadow preview and hover slot swap.
  - Cross-Keyboard Edge Dragging: Dragging a key to the left or right edge and holding (~450ms) triggers active keyboard layout switching, allowing keys to be transferred between Keyboard 1 and Keyboard 2.
- **Editing Mode Toggle Button (`KeyActionType.TOGGLE_EDIT_MODE`)**:
  - Default label `"✎"`, positioned in top-right corner.
  - Single tap toggles layout edit mode. When active, tapping any key opens `EditKeyDialog` to customize primary label, shift label, long-press label, action type, color, and width weight.
  - **Mandatory Toggle Invariant**: Deserialization and deletion logic guarantees every layout contains at least one `TOGGLE_EDIT_MODE` key; the sole remaining toggle key cannot be deleted or remapped.
- **Dedicated Exit App Button (`KeyActionType.EXIT`)**:
  - Positioned by default on the top row of Keyboard 1. Tapping immediately flushes auto-save, cleans up persistent notifications, and terminates the application cleanly.

---

## 7. Obsidian-Grade Markdown Engine (`com.example.markdown`)
- **Live In-Editor Syntax Styler (`MarkdownLiveStyler.kt`)**:
  - Real-time syntax highlighting inside Compose `TextField` using `AnnotatedString` spans.
  - Dimmed syntax markers: Tokens like `#`, `**`, `*`, `==`, `~~`, `>`, and backticks are rendered with low alpha (`0.38f`) so formatting symbols do not distract from the reading flow.
  - Proportional heading scaling (H1 through H6) rendered dynamically with configurable scale factors (0.8x to 1.5x).
  - Inline code and fenced code blocks styled with `jetbrains_mono.ttf`.
- **List Auto-Continuation (`MarkdownListAutoContinuer.kt`)**:
  - Pressing Enter automatically continues bulleted (`- `, `* `), numbered (`1. `), and task checkbox (`- [ ] `) lists.
  - Pressing Enter on an empty list prefix cleanly terminates the list by stripping the indentation prefix.
- **Interactive Reading View (`MarkdownRenderer.kt`)**:
  - In Reading Mode, task checkboxes (`- [ ] ` and `- [x] `) are directly tappable on screen. Toggling immediately mutates the note text in Room and triggers silent auto-save.

---

## 8. Ergonomic Side Rail & Floating Controls Architecture (`EditorSideRailLayout.kt`, `EditorFloatingCluster.kt`)
- **Dual Floating Layout Models**:
  - **Ergonomic Side Rail**: Vertical strip docked to left or right screen edge designed for natural one-handed thumb ergonomics. Hosts Mic, Timer, Keyboard Switch, Format Bar toggle, and Expand action.
  - **Docked Floating Cluster**: Bottom-centered pill cluster with adjustable scale, bottom offset, and spacing.
- **Configuration & Text Clearance (`FloatingLayoutSettings.kt`)**:
  - `railKeepTextClear`: Dynamically injects lateral padding into the editor viewport so note text never hides beneath floating buttons.
  - Reading Mode Floating Bar: 4 unified buttons (`Previous Note`, `Read/Edit Toggle`, `Next Note`, `Exit`). Controlled via synchronized `readingNavBottomOffsetDp` and `readingNavButtonSizeDp` preferences.

---

## 9. Journal-First Architecture & Daily Calendar System (`DailyJournalManagerScreen.kt`)
- **Cold-Start App Lifecycle**:
  - Cold launch immediately opens the last viewed note in Reading Mode with scroll at top (0).
  - Falls back to today's date note (`LocalDate.now()`, format `yyyy-MM-dd`) or latest active note if absent.
- **High-Performance Single-Pass Calendar**:
  - Zero nested row weights to eliminate multi-pass Compose measurement overhead.
  - O(1) bitmask note presence checks against pre-warmed Room month cache.
  - Active open note displays a blinking electric cyan border and cyan sub-dot.
  - Tapping any date opens that date's note (Reading mode if existing, Editing mode if new).
  - Floating Action Button (+) always opens today's note directly in Editing Mode.
- **Swipe Navigation Isolation (`SwipeGestureModifier.kt`, `HorizontalSwipeModifier.kt`)**:
  - In Reading Mode: Screen-wide horizontal swipes navigate: Left-to-Right $\rightarrow$ Calendar, Right-to-Left $\rightarrow$ Settings.
  - In Editing Mode: Calendar and Settings swipes are strictly confined to note text/whitespace and never fire from toolbar, format bar, side rail, or keyboard touch events.
  - Swiping Right-to-Left from Calendar returns to Editor; swiping Left-to-Right from Settings returns to Editor.

---

## 10. Local-First Room Database & Schema Migrations (`AppDatabase.kt`)
- **Database Identity**: `kothapad.db`, version 4.
- **Entity Schemas**:
  - `Note`: `id: Long` (PK auto), `title: String`, `content: String`, `createdAt: Long`, `updatedAt: Long`, `folder: String` (default `"General"`), `tags: String`, `isArchived: Boolean`, `isDeleted: Boolean`, `deletedAt: Long?`, `sessionDurationSec: Long`, `sessionCount: Int`, `wordCount: Int`, `charCount: Int`, `statsStale: Boolean`.
  - `Stamp`: `id: Long` (PK auto), `setName: String`, `label: String`, `templateText: String`, `voiceTriggerPhrase: String`, `orderIndex: Int`, `isPinned: Boolean`.
  - `SpokenCommand`: `id: Long` (PK auto), `language: String`, `phrase: String`, `actionType: String` (`PUNCTUATION`, `ACTION`), `replacementText: String`, `commandCode: String`.
- **Migration History**:
  - `MIGRATION_1_2`: Added folders, tags, archive, soft-delete, session duration, and pinned stamps.
  - `MIGRATION_2_3`: Added note session counters.
  - `MIGRATION_3_4`: Added word count, character count, and stale stats tracking.
- **Templates & Trash Management**:
  - Soft-deleted notes (`isDeleted = 1`) move to Trash with configurable auto-purge (`trash_auto_empty_days`, default 7).
  - Restoring notes and emptying trash execute directly in Room under Settings > "Templates & Trash".

---

## 11. WorkManager Automated Backup System (`AutoBackupManager.kt`, `AutoBackupWorker.kt`)
- **Periodic Background Backups**:
  - Scheduled via `WorkManager` using `PeriodicWorkRequestBuilder` with unique work name `"kothapad_auto_backup"`.
  - Supported intervals: Off, 6 Hours, Daily, Weekly, or Custom ($\ge$ 15 minutes).
  - Optional `autoBackupOnAppOpen` trigger (rate-limited: executes only if last backup is > 1 hour old).
- **Storage & Retention Engine**:
  - Default target: `Download/KOTHAPAD Backups` with SAF folder picker fallback (`auto_backup_location_uri`).
  - Keep-Last-N Retention (3, 5, 10, 20): Older archives are pruned strictly after a new backup is validated and written.
  - Content-Hash Detection: Computes SHA-256 of notes and settings; skips redundant backup writes if data has not changed since last run.
  - Safety Rollback Protection: Before any restore operation, automatically generates `KothaPad_pre_restore_safety.zip` to prevent accidental data loss.

---

## 12. Exact Alarm Notification & Floating Overlay System (`ReminderScheduler.kt`, `ScreenOverlayReminderService.kt`)
- **Exact Alarms & Background Delivery**:
  - Scheduled using Android `AlarmManager` with exact alarm permissions (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`).
  - Broadcast receiver executes with `goAsync()` and acquires a `PARTIAL_WAKE_LOCK` guaranteeing on-time firing when device is locked or app is killed.
- **Foreground Smart Suppression**:
  - If user is actively typing or viewing notes in the foreground, intrusive alarm popups and head-up banners are suppressed to prevent disruption.
- **Dual Display Modes**:
  - **Normal Mode**: High-priority system notification (`IMPORTANCE_HIGH`) with scheduled time badge and "+ Journal Today" direct action.
  - **Floating Overlay Mode**: Real screen overlay rendered over external apps via `SYSTEM_ALERT_WINDOW` (`ScreenOverlayReminderService`). Features custom geometry, opacity, typography, and one-tap "+ Journal Today" button that unlocks into today's note editor.
  - Original Time Fidelity: System notification header (`setWhen`) and overlay badges strictly reflect the reminder's original scheduled time, even if delayed by system doze.
- **Xiaomi / MIUI / HyperOS Compatibility (`XiaomiOverlayHelper.kt`)**:
  - Detects MIUI/HyperOS ROMs and guides user to grant "Display pop-up windows while running in the background" and Autostart permissions.

---

## 13. Backup, Restore & Migration Architecture (`BackupManager.kt`)
- **Unified ZIP Archive Format (`CURRENT_BACKUP_VERSION = 4`)**:
  - `manifest.json`: Metadata, app version, timestamp, entity counts.
  - `notes.json`: Array of serialized `Note` records.
  - `templates.json`: Array of serialized `Stamp` records.
  - `spoken_commands.json`: Array of serialized `SpokenCommand` records.
  - `settings.json`: Key-value map of preferences typed via `KNOWN_PREF_TYPES`.
- **Backward Compatibility & Vault Migration**:
  - Seamlessly imports Schema v1, v2, v3, and v4 backups.
  - Supports legacy Obsidian-style markdown directory archives, importing `.md` files into Room notes with metadata frontmatter parsing.
  - Atomic restore: Validates all JSON entries before clearing local tables; aborts safely on corrupt files without partial wipes.

---

## 14. Universal Backup Synchronization Rule
- **Mandatory Backup Synchronization Invariant**:
  - Every setting, user preference, or configuration added to `PreferencesManager` MUST be registered inside `KNOWN_PREF_TYPES` with its explicit `PrefType` (`BOOLEAN`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, `STRING`, `STRING_SET`).
  - `getAllPreferencesForBackup()` automatically serializes all known keys into `settings.json`.
  - `restorePreferencesFromBackup()` parses and commits them back to `DataStore`.
  - Zero preference keys may exist outside this synchronization contract.

---

## 15. Theming, Typography & UI Polish (`Theme.kt`, `Color.kt`, `Type.kt`)
- **Theme Palettes**:
  - Default Dark, Pure AMOLED Black (`#000000` surface & background), Cyber Dark, Clean Light, and Full Custom Color Engine (Primary, Background, Surface, Text, Secondary ARGB pickers).
- **Font Stack**:
  - Embedded fonts: `inter.ttf` (default sans-serif UI), `jetbrains_mono.ttf` (code blocks and editor mono), `noto_sans_bengali.ttf` (Bangla script rendering).
  - External Font Loader: Supports custom user `.ttf` / `.otf` font file paths from device storage.
- **Note Metrics Display Config (`NoteMetricsConfig.kt`)**:
  - Displays word count, character count, and reading duration in note cards.
  - Fully configurable ordering, visibility chips, and styles (`COMPACT` vs. `DETAILED`).

---

## 16. Navigation Hierarchy & Back Handling (`MainActivity.kt`)
- **Centralized `BackHandler` Ladder**:
  1. System IME open $\rightarrow$ Pass through to Android OS to dismiss soft keyboard.
  2. Dialog, bottom sheet, or key editor open $\rightarrow$ Dismiss active overlay/sheet.
  3. Sub-screens (`SETTINGS`, `MARKDOWN_SETTINGS`, `AUTO_BACKUP_RESTORE`, `DAILY_JOURNAL_MANAGER`) $\rightarrow$ Navigate back to `EDITOR`.
  4. Active voice dictation $\rightarrow$ Retain recording; display toast warning.
  5. Editing Mode active $\rightarrow$ Switch to Reading Mode (primary resting state).
  6. Reading Mode active $\rightarrow$ Double-tap back within 2000ms to exit app (flushes auto-save and clears persistent notification).
- **Immersive Mode Bounds**:
  - System navigation bar hiding is strictly limited to Reading Mode on `EditorScreen`.
  - Navigation bar remains permanently visible across Editing Mode, Calendar, Settings, and Dialogs.
