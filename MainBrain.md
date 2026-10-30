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
- Dual keyboard support: built-in custom in-app keyboard (Bengali/English layouts, key editor) and system IME.
- Floating layout controls and side rail layout with customizable scale, side (left/right), offsets, and text clearance.

## 6. Storage, Notes & Backup Management
- **Direct Internal Storage (No Vault):** App uses native SQLite Room database and Jetpack DataStore directly from launch with zero external vault selection, no SAF tree permission requirements, and no WelcomeScreen.
- **Journal-First Architecture:** App operates primarily as a dedicated daily journal. Cold start immediately opens today's journal entry in reading mode with scroll at top; calendar is accessible one swipe away.
- **App Startup Flow:** Cold start reopens the last note previously viewed or edited in reading mode with scroll at top (falling back to today's note or latest note if absent).
- **Templates & Trash Management in Settings:** Trashed notes viewing, single-note restoration, permanent deletion, and empty trash are managed directly in Room under "Templates & Trash" in Settings.
- **Feature & Tips Guides in "About Notepad":** "Notepad Features" and "Notepad Tips" are not seeded as journal notes; comprehensive summarized guides are displayed directly inside the "About Notepad" section in Settings.
- **Robust Export/Import System:** Comprehensive, flexible backup format (manifest, notes, templates, preferences, and custom commands). Fully backward-compatible with legacy JSON exports and previous Obsidian-style vault ZIP archives. Restores exact state, settings, and notes without data loss.
- **No Note Session Timers:** Individual note session timers and background timer jobs are completely removed to maintain simplicity and prevent crashes.
- **Horizontal Swipe Navigation:** Fluid, flicker-free gestures with immediate response. In note editor: swiping left-to-right immediately opens Daily Journal Calendar; swiping right-to-left opens Settings. In Calendar: swiping right-to-left immediately closes Calendar and returns to editor. In Settings: swiping left-to-right closes Settings and returns to editor. Intercepts touches before child scrolling to guarantee 100% accuracy in Calendar and Settings.
- **High-Performance Architecture:** Single-pass custom calendar layout with zero nested rows and zero two-pass weights; background-prewarmed month cache; O(1) bitmask note presence checks; static day cell styles/shapes; lazy conditional rendering of settings accordion contents; fast substring date parsing; zero-delay gesture callbacks.
- **Daily Journal Calendar Visual Indicators & Layout:** Active currently opened note displays a contrasting blinking cyan border and a small cyan dot beneath the date number (clearly visible against theme fill, distinct from today's white outline). On opening, target month displays with generous bottom clearance ensuring the last row of dates sits comfortably above the floating action buttons without obstruction or manual scroll.
- **Daily Journal Calendar Date Selection:** Tapping any date opens that exact date's note (in reading mode if existing, in editing mode if created new).
- **Daily Journal Plus Button:** Always targets today's actual date (LocalDate.now()), opening today's note directly in editing mode (creates if absent, focuses editor).
- **Reading Mode Date Navigation:** 3 floating buttons (Previous Note, Read/Edit Toggle, Next Note) in a horizontal row. Default position is elevated higher above navigation bar, with a customizable bottom offset slider in Floating Layout settings that accurately exports in backups.
- **Note Metrics Display:** Word count, reading time, characters with configurable order, style, and toggle.
