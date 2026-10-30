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
- **High-Performance Architecture:** Single-pass custom calendar layout with zero nested rows and zero two-pass weights; background-prewarmed month cache; O(1) bitmask note presence checks; static day cell styles/shapes; lazy conditional rendering of settings accordion contents; fast substring date parsing; zero-delay gesture callbacks.
- Storage vault backing with SAF fast lister and local database sync.
- Daily Journal Calendar Date Selection: Tapping any date strictly determines which note opens or creates (never falls back to last opened note). If note exists for that exact date, opens in reading mode; if absent, creates note strictly in "Daily Journal/YYYY-MM-DD.md" and opens immediately in editing mode.
- Daily Journal Plus Button: Always targets today's actual date (LocalDate.now()) regardless of selected date or previously opened note; opens today's note directly in editing mode (creates inside "Daily Journal" if absent, zero duplicates; immediately focuses editor).
- Daily Journal Storage & Canonical Path Integrity: All date-based notes are strictly persisted to canonical path "Daily Journal/YYYY-MM-DD.md"; opening, editing, and autosaving update the original file in "Daily Journal" and never create duplicate copies outside "Daily Journal" (root/General); date lookup checks canonical vault cache and disk before creation.
- **Vault Resiliency & Data Safety (Items 1-6):** Supervisor scope with uncaught exception handler and generation tracking for scans and stats worker; placeholder note materialization before reads/deletions/renames to prevent vault-wipe risks; selective disk writes (`writeContent` flag) so metadata mutations never overwrite note text; strict exact-date regex (`^\d{4}-\d{2}-\d{2}$`) for daily journal recognition and deduplication; non-destructive duplicate reconciliation without silent data loss; legacy tips moved to Trash; folder deletion preserves contained notes in Trash and verifies empty before deletion.
- Daily journal navigation (next/previous entry by date).
- Note metrics display (word count, reading time, characters) with configurable order and style.
