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

## 6. Vault & Note Management
- **Journal-First Architecture:** App operates primarily as a dedicated journal; Note Manager screens, dead routes, and redundant batch states are completely removed from the codebase while preserving underlying journal notes, templates, and storage vault integrity.
- **Strict Obsidian-Compatible 3-Folder Vault:** Canonical top-level folders: `Daily Journal` (all date-organized journal entries), `Templates` (markdown templates), and `Trash` (soft-deleted notes stored as markdown files). All configurations (`.kothapad/settings.json`), workspace state, and metadata remain self-contained in the vault folder for seamless, indistinguishable multi-device synchronization.
- **Templates & Trash Management in Settings:** Trashed notes viewing, single-note restoration back to `Daily Journal`, permanent deletion, and empty trash are integrated under the "Templates & Trash" section in System Settings.
- **Feature & Tips Guides in "About Notepad":** "Notepad Features" and "Notepad Tips" are not seeded as journal notes and have no date association; comprehensive summarized guides are displayed directly inside the "About Notepad" section in System Settings.
- **App Startup Flow:** Cold start immediately opens today's journal entry in reading mode with scroll at top; calendar is accessible one swipe away.
- **Horizontal Swipe Navigation:** In note editor: swiping left-to-right (swipe right) opens the Daily Journal Calendar; swiping right-to-left (swipe left) opens Settings. In Calendar: swiping right-to-left (swipe left) closes Calendar and returns to note editor. In Settings: swiping left-to-right (swipe right) closes Settings and returns to note editor. All transitions animate instantly with zero input lag.
- **High-Performance Architecture:** On-demand concurrent caching for calendar months instead of 200+ upfront allocations; lazy conditional rendering of settings accordion contents (composing controls only when expanded); fast substring date parsing without regex overhead; zero-delay gesture callbacks with immediate translation reset.
- Storage vault backing with SAF fast lister and local database sync.
- Daily Journal Calendar: Tapping existing note date opens in reading mode; tapping empty date auto-creates and opens in editing mode in "Daily Journal" folder without duplicates.
- Daily Journal Plus Button: Opens today's note in editing mode (creates only if absent, no duplicates).
- Daily Journal Storage Integrity: Dedicated "Daily Journal" vault folder; automatic migration, duplicate purging, and reconciliation across root/General.
- Daily journal navigation (next/previous entry by date).
- Note metrics display (word count, reading time, characters) with configurable order and style.
