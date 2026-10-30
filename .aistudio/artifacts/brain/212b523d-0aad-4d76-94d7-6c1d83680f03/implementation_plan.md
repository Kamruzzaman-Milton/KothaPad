# KothaPad Simplification Plan: Journal-First Architecture

Comprehensive architectural analysis and step-by-step simplification plan to transition KothaPad from a complex multi-folder note manager into a reliable, streamlined personal journal app while safeguarding existing notes, templates, and editor stability.

***

## User Review & Critical Decisions

The user has reviewed the key architectural options and confirmed the following decisions:

- **Confirmed Gesture Mapping:**
  - **Swipe Left (Right-to-Left drag):** Opens the **Daily Journal Calendar**.
  - **Swipe Right (Left-to-Right drag):** Opens **Settings**.
  - Replaces the old behavior where swiping right opened the Note Manager drawer.
- **Confirmed App Startup Flow:**
  - Upon app open and vault access, the app immediately opens **Today's Journal Entry in Reading Mode** with scroll at top.
  - The calendar is immediately accessible with a single swipe to the left.
- **Confirmed Template Location:**
  - Templates are stored and managed inside a dedicated **"Templates & Quick Text" section in Settings**.
  - The in-editor `TemplatesBottomSheet` remains available for inserting templates while drafting or dictating.

***

## 1. Existing Architecture & Complexity Analysis

### Current Structure & Pain Points

```
┌────────────────────────────────────────────────────────────────────────┐
│                              MainActivity                              │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │                  Surface & Animated Screen Overlay                 │ │
│ │ ┌──────────────────┐ ┌───────────────┐ ┌─────────────────────────┐ │ │
│ │ │   EditorScreen   │ │   Settings    │ │  DailyJournalManager    │ │ │
│ │ │ (Reading/Editing)│ │(App, Dictation│ │    (Calendar View)      │ │ │
│ │ │                  │ │  & Layout)    │ │                         │ │ │
│ │ └────────┬─────────┘ └───────────────┘ └─────────────────────────┘ │ │
│ └──────────┼─────────────────────────────────────────────────────────┘ │
└────────────┼───────────────────────────────────────────────────────────┘
             │ (Contains internal full-screen overlay)
             ▼
    ┌──────────────────────────────────────────────────────────────┐
    │              NotesManagerScreen (Internal Overlay)           │
    │  - 2,700+ lines: Folder tree, multi-select batch bar,        │
    │    search, filter chips, tags, trash/archive tabs, merge,    │
    │    folder reordering, template tree row                      │
    │  - Intercepts IME, window insets, focus requesters,          │
    │    back handlers, and auto-save events                       │
    └──────────────────────────────────────────────────────────────┘
```

1. **Dual Navigation Paradigms Clashing:**
   - The app has top-level screen switching in `MainActivity` (`currentScreen = "EDITOR" | "SETTINGS" | "DAILY_JOURNAL_MANAGER"`) **plus** an internal full-screen animated overlay (`isNoteManagerOpen`) inside `EditorScreen`.
   - This causes subtle race conditions in focus handling: `EditorScreen` has multiple `LaunchedEffect(isNoteManagerOpen)` hooks clearing/restoring focus and soft keyboards, causing cursor positioning and mode-switching quirks.
2. **Dead Code:**
   - `app/src/main/java/com/example/ui/drawer/NotesDrawer.kt` (440 lines) is completely unreferenced and obsolete.
3. **Overloaded Note Manager Responsibilities:**
   - `NotesManagerScreen.kt` contains 2,700+ lines managing batch operations, tag filters, trash retention count-downs, folder collapse state, and template creation. This tight coupling makes any fix to note loading inadvertently affect list filters or editor state.
4. **Data Isolation:**
   - Daily journal entries are stored as markdown files in the vault (`Daily Journal/YYYY-MM-DD.md`) and indexed in Room (`notes` table).
   - Templates are stored in the `stamps` Room table and vault config.
   - Removing the Note Manager from the UI does **not** delete notes or templates from SQLite or the storage vault.

***

## 2. Phased Simplification Roadmap

### Phase 1: Navigation & Gesture Simplification (Low Risk)

- **Target:** Remove `NotesManagerScreen` from the visible UI and implement clean, two-way gesture navigation.
- **Actions:**
  1. **Update Swipe Navigation in `EditorScreen.kt`:**
     - `onSwipeLeft`: Navigate directly to `DAILY_JOURNAL_MANAGER` (`viewModel.openDailyJournalManager()`).
     - `onSwipeRight`: Navigate to `SETTINGS` (`viewModel.navigateTo("SETTINGS")`).
     - Remove `viewModel.isNoteManagerOpen.value = true` triggers.
  2. **Update Top Bar Navigation:**
     - Replace the burger menu icon on `EditorTopBar` with a **Calendar icon** (`Icons.Default.CalendarMonth`) that opens the Daily Journal Calendar.
     - In Daily Journal mode, the back button returns to the Calendar view.
  3. **Update Calendar Top Bar:**
     - `DailyJournalManagerScreen` back button navigates back to `EDITOR` (the active journal entry) rather than opening the Note Manager.
  4. **Hide Note Manager Overlay:**
     - Remove the `NotesManagerScreen` overlay invocation from `EditorScreen.kt` (or bypass `isNoteManagerOpen`).
     - Clean up IME focus listeners that were tied to `isNoteManagerOpen`.
  5. **Simplify BackHandler in `MainActivity.kt`:**
     - Remove `isNoteManagerOpen` branch from the back-press chain.
     - Pressing Back from Calendar returns to Editor.
     - Pressing Back from Settings returns to Editor.
     - Pressing Back while Editing returns to Reading mode.
     - Pressing Back while in Reading mode prompts "Press back again to exit".

### Phase 2: Startup Flow — Dedicated Journal Entry (Low Risk)

- **Target:** Ensure the app opens directly into today's journal entry.
- **Actions:**
  1. In `EditorViewModel.init` / vault load completion:
     - Check if today's note (`YYYY-MM-DD`) exists in the `Daily Journal` folder.
     - If it exists, load it immediately.
     - If it does not exist, automatically prepare/create today's note in the `Daily Journal` folder.
  2. Default `isReadingMode` to `true` on cold start as requested.
  3. Ensure scroll position starts at 0 (top of document).

### Phase 3: Relocate Template Management to Settings (Low Risk)

- **Target:** Keep full template functionality without depending on the Note Manager tree.
- **Actions:**
  1. Add a **"Templates & Quick Text"** card/section in `SettingsScreen.kt`:
     - List all templates with labels, snippet previews, and pin badges.
     - "Add New Template" button triggering `EditTemplateDialog`.
     - Tap to edit label and content.
     - Delete and reorder capabilities.
  2. Maintain `TemplatesBottomSheet` in `EditorScreen`:
     - Tapping the template icon in the formatting bar or keyboard continues to open `TemplatesBottomSheet` to insert templates into notes.

### Phase 4: Clean Dead Code & Redundant State (Zero Functional Impact)

- **Target:** Trim dead weight to prevent confusion and accidental imports.
- **Actions:**
  1. Safely delete unused `NotesDrawer.kt`.
  2. Remove `onOpenNotesManagerSettings(fromNoteManager = true)` routes and obsolete folder management callbacks from the active UI path.
  3. Preserve all underlying database accessors (`NoteDao`, `StampDao`, `VaultManager`) to guarantee zero data loss.

***

## 3. Simplified Component & Data Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        KothaPad Navigation Flow                        │
│                                                                        │
│               Swipe Right                    Swipe Left                │
│       ┌────────────────────────┐      ┌────────────────────────┐       │
│       │        Settings        │      │ Daily Journal Calendar │       │
│       │                        │      │                        │       │
│       │ • Appearance & Fonts   │      │ • Monthly calendar grid│       │
│       │ • Dictation pipeline   │      │ • Date selection       │       │
│       │ • Templates manager    │      │ • Quick "Today" FAB    │       │
│       │ • Vault & Backup       │      │ • Streak/entry dots    │       │
│       └───────────┬────────────┘      └───────────┬────────────┘       │
│                   │                               │                    │
│             Back / Done                     Tap date / Back            │
│                   │                               │                    │
│                   └───────────────┬───────────────┘                    │
│                                   ▼                                    │
│                   ┌───────────────────────────────┐                    │
│                   │         Journal Editor        │                    │
│                   │                               │                    │
│                   │ • Reading Mode (Default)      │                    │
│                   │ • Edit Mode (Tap text)        │                    │
│                   │ • Audio Dictation             │                    │
│                   │ • Markdown Rendering          │                    │
│                   │ • Format Bar + Template Sheet │                    │
│                   └───────────────────────────────┘                    │
└────────────────────────────────────────────────────────────────────────┘
```

***

## 4. Stability & Regression Prevention Safeguards

To prevent breaking existing stable features during simplification:

1. **Editor & Cursor Rules (Preserved):**
   - Tap-to-center cursor logic (`centerCursorTrigger` with <= 18px movement, < 500ms) will remain untouched.
   - Reading/Editing mode scroll offset preservation (`isModeTransitioning` with snapshotFlow) will not be altered.
   - Free vertical scrolling without snaps will remain intact.
2. **Data & Vault Integrity (Preserved):**
   - No database schemas or Room tables are altered.
   - All existing notes, including past journal entries and notes in other folders, remain safely stored in the vault disk storage and SQLite cache.
   - Auto-save flush on pause/stop remains active.
3. **Build & APK Verification:**
   - Verification will compile cleanly via `compile_applet`.
   - The output APK will be named `KothaPad.apk` in the root workspace directory as required.

***

## 5. Verification Plan

### Automated Build Verification
- Execute `compile_applet` after all file changes to guarantee Gradle builds cleanly without unresolved references.

### Manual Verification Matrix
1. **Startup CUJ:**
   - Launch app -> Today's note opens immediately in Reading Mode.
2. **Gesture CUJ:**
   - Swipe from Right to Left -> Calendar opens smoothly.
   - Swipe from Left to Right -> Settings opens smoothly.
   - Back button on Calendar/Settings returns to the active journal note.
3. **Calendar Date Switching CUJ:**
   - Tap an existing date -> Loads that note in reading mode.
   - Tap an empty date -> Creates note in "Daily Journal" folder and enters editing mode.
   - Tap "Today (+)" FAB -> Opens today's note.
4. **Template CUJ:**
   - Open Settings -> Navigate to Templates section -> Create/Edit a template.
   - Open Editor -> Tap Templates button -> Insert newly created template into note text.
5. **Scroll & Cursor Preservation CUJ:**
   - Tap text in edit mode -> Centers in usable screen area.
   - Toggle reading mode -> Scroll position stays identical without jumping.
