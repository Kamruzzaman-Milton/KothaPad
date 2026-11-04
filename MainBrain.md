# KothaPad - MainBrain (Compact Source of Truth)

## 1. General & Build
- App Name: KothaPad.
- Launcher label: "KothaPad".
- Export artifact: Ready-to-install `KothaPad.apk` in root workspace directory upon build (never duplicate as `app-debug.apk` in root).

## 2. Editor & Cursor Scrolling Behavior
- **Tap to Center Cursor:** Tapping any position within note text in editing mode positions the cursor and scrolls so the cursor is near the vertical midpoint of the usable screen area (between top toolbar and bottom keyboard/formatting bar).
- **Free Manual Scrolling:** Once cursor is placed/active, user can freely scroll or fling vertically up and down without snapping back to cursor position.
- **No Continuous Auto-Scroll:** Auto-centering triggers strictly on intentional tap (movement <= 18px, duration < 500ms) or keyboard open, never during manual scrolling, fling, recomposition, or drag-release.
- **Blank Space Tap:** Tapping blank space at note bottom positions cursor at end of text and triggers centering in usable screen area.
- **Mode Transition Preservation:** Switching between Reading and Editing modes preserves the exact visible scroll position without jump or reset.
- **Note Switch Reset:** Switching or opening a different note resets scroll position to top (0).
- **Editing Integrity:** Scrolling never moves cursor, dismisses keyboard, or disrupts text selection handles.

## 3. Dictation & Audio Pipeline
- Speech-to-text supervisor with live dictation state, audio RMS waveform, and audio source detection.
- Dictation insertion modes: append to end, insert at cursor, replace selection.
- Pipeline steps: Auto-capitalization, smart spacing, spoken punctuation/commands, and custom stamps.

## 4. Markdown & Styling
- Markdown live styler, rendered preview in reading mode, and markdown format bar.
- Auto-continuation for markdown lists and checkboxes upon Enter.
- Customizable typography: font family, font size, line spacing, letter spacing, paragraph spacing, and text alignment.

## 5. Keyboards & Input Layouts
- Dual in-app keyboard interfaces (Keyboard 1 & Keyboard 2) with bi-directional swipe switching (swiping left-to-right or right-to-left across the in-app keyboard seamlessly toggles between layouts with zero loss of active editing space).
- **Default Drag-and-Drop Repositioning:** When keyboard editing mode is disabled, pressing and holding (> 320ms) any keyboard button initiates drag-and-drop repositioning with elevated preview and hover highlight, smoothly swapping button positions on release without triggering normal button tap actions.
- **Editing Mode Toggle Button:** Dedicated toggle button (`KeyActionType.TOGGLE_EDIT_MODE`, default label "✎") positioned by default in the top-right corner. Tapping toggles editing mode ON/OFF with distinct active highlighting. While ON, a single tap on any button opens the button customization menu (`EditKeyDialog`). Long-pressing the toggle button allows dragging and repositioning it anywhere on the grid like any other button.
- **Mandatory Editing Toggle Presence:** Every keyboard layout (Keyboard 1 & Keyboard 2) must strictly maintain at least one Editing Mode Toggle button. The sole remaining toggle button cannot be deleted, remapped to another action in `EditKeyDialog`, or transferred away without replacement. Layout deserialization automatically guarantees its presence.
- **Cross-Keyboard Edge Dragging:** Dragging a button to the left or right edge of the keyboard and holding briefly (~450ms) triggers active keyboard layout switching, allowing buttons to be dragged and swapped across different keyboards just like launcher icons between pages.
- Separate, independent button customization for Keyboard 1 and Keyboard 2, with independent settings, reset, export, and import options.
- Built-in custom in-app keyboard (Bengali/English/Symbols/Numbers layouts, key editor) and system IME toggle.
- **Keyboard Exit Button:** Default top-row placement at top-right corner of Keyboard 1 (second button from right, immediately left of Editing Mode toggle). Tapping immediately exits the app (identical to reading mode Exit button). Fully configurable as a selectable action (`KeyActionType.EXIT`) assignable to any button via `EditKeyDialog`.
- Floating layout controls and side rail layout with customizable scale, side (left/right), offsets, and text clearance.

## 6. Storage, Notes & Backup Management
- **Direct Internal Storage (No Vault):** App uses native SQLite Room database and Jetpack DataStore directly from launch with zero external vault selection, no SAF tree permission requirements, and no WelcomeScreen.
- **Journal-First Architecture:** App operates primarily as a dedicated daily journal. Cold start immediately opens today's journal entry in reading mode with scroll at top; calendar is accessible one swipe away.
- **App Startup Flow:** Cold start reopens the last note previously viewed or edited in reading mode with scroll at top (falling back to today's note or latest note if absent).
- **Templates & Trash Management in Settings:** Trashed notes viewing, single-note restoration, permanent deletion, and empty trash are managed directly in Room under "Templates & Trash" in Settings.
- **Feature & Tips Guides in "About Notepad":** "Notepad Features" and "Notepad Tips" are not seeded as journal notes; comprehensive summarized guides are displayed directly inside the "About Notepad" section in Settings.
- **Robust Export/Import System:** Comprehensive, flexible backup format (manifest, notes, templates, preferences, and custom commands). Fully backward-compatible with legacy JSON exports and previous Obsidian-style vault ZIP archives. Restores exact state, settings, and notes without data loss.
- **No Note Session Timers:** Individual note session timers and background timer jobs are completely removed to maintain simplicity and prevent crashes.
- **Horizontal Swipe Navigation & Editing Isolation:** Fluid gestures with immediate response. In Reading Mode: screen-wide swipes work as before (left-to-right opens Calendar, right-to-left opens Settings). In Editing Mode: calendar and settings swipe gestures are isolated strictly to the note's content area (text and blank spaces) and never trigger from interactions with floating buttons, toolbars, Markdown formatting bars, forms, recording buttons, or the in-app keyboard. In Calendar: swiping right-to-left returns to editor. In Settings: swiping left-to-right returns to editor.
- **High-Performance Architecture:** Single-pass custom calendar layout with zero nested rows and zero two-pass weights; background-prewarmed month cache; O(1) bitmask note presence checks; static day cell styles/shapes; lazy conditional rendering of settings accordion contents; fast substring date parsing; zero-delay gesture callbacks.
- **Daily Journal Calendar Visual Indicators & Layout:** Active currently opened note displays a contrasting blinking cyan border and a small cyan dot beneath the date number (clearly visible against theme fill, distinct from today's white outline). On opening, target month displays with generous bottom clearance ensuring the last row of dates sits comfortably above the floating action buttons without obstruction or manual scroll.
- **Daily Journal Calendar Date Selection:** Tapping any date opens that exact date's note (in reading mode if existing, in editing mode if created new).
- **Daily Journal Plus Button:** Always targets today's actual date (LocalDate.now()), opening today's note directly in editing mode (creates if absent, focuses editor).
- **Reading Mode Floating Controls:** 4 floating buttons in a horizontal row: Previous Note, Read/Edit Toggle, Next Note, and Exit (at the most right side). Tapping Exit immediately closes the app without any confirmation dialog or extra interaction. Treated as a single configurable group: unified bottom offset (vertical position) and button size sliders in Floating Layout settings control all 4 buttons simultaneously and sync across backups.
- **Full-Screen & Navigation Bar Visibility:** Immersive full-screen mode (hiding system navigation bar) is strictly limited to Reading Mode. All other modes and screens (Editing Mode, Calendar, Settings, Markdown Settings, Backup Restore) keep the system navigation bar visible. Immersive behavior automatically disables whenever leaving Reading Mode.
- **Note Metrics Display:** Word count, reading time, characters with configurable order, style, and toggle.
- **Unified Backup Section:** All backup-related settings (both manual export/import and automated scheduled WorkManager archives with folder selection, retention, and restore) are consolidated into a single unified "Backup" section in Settings.
- **Automatic Backup System:** Background periodic backups via WorkManager with flexible schedule (Off / 6h / Daily / Weekly / Custom >= 15m), optional on-app-open trigger (skips if last successful backup is < 1h old), keep-last-N retention policy (3/5/10/20, pruned only after new backup verified), default Download/KOTHAPAD Backups with SAF folder picker fallback, mutex isolation, content-hash change detection, pre-restore safety backup (`KothaPad_pre_restore_safety.zip`), quiet failure notifications, and dedicated restore screen with rollback protection.
- **Customizable Notification & Reminder System:** Unlimited custom reminders scheduled via AlarmManager with exact alarm precision (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`). Foreground suppression: when user is actively using KothaPad in foreground, notifications and popups are completely suppressed to avoid disruption. Reliable background delivery: whenever user leaves the app (minimized in background, screen locked, or completely closed/cleared from recents), notifications deliver reliably with `goAsync()` broadcast retention and `WakeLock` protection. Dual display modes: Normal (high-priority system notification + heads-up banner with scheduled time and "+ Journal Today" action) and Floating (true screen overlay popup via `SYSTEM_ALERT_WINDOW` over any active app near screen center with time badge, customizable geometry, colors/opacity, borders, typography, animation, and auto-dismiss). Original scheduled time fidelity: notifications, system headers (`setWhen`), overlay badges, and previews strictly display the reminder's original scheduled time (never dynamically generated from the current device clock, even if delayed). Dedicated PLUS (+) button on notifications/overlays opens today's journal note directly in editing mode. Customizable default notification message applies automatically to new reminders. Optional toggle for persistent background notification with quick journal and exit actions. Overlay permission buttons dynamically turn green when granted and retain default appearance when not granted. Xiaomi / MIUI / HyperOS background popup and autostart permission guides included. All reminders and floating preferences are registered in `KNOWN_PREF_TYPES` and preserved across restarts and backups.

## 7. Universal Backup Synchronization Rule
- **Mandatory Backup Synchronization:** Whenever any feature, setting, configuration, customization, or other app functionality is added or modified, the corresponding changes must automatically be included in the Export/Import backup system. All DataStore keys must be registered in `KNOWN_PREF_TYPES` and exported in `settings.json`, Room entities serialized and restored with rollback safety, and custom layouts/options preserved across export/import cycles. No future app modifications may be accidentally excluded from backups.
