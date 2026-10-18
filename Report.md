# Vault System Comparison Report: KothaPad vs. Obsidian

**Document Type:** Architectural & Functional Research Analysis  
**Target Systems:** KothaPad (Local-first Android Markdown Note-Taking App) vs. Obsidian (Knowledge Base & Note-Taking Application)  
**Date of Evaluation:** October 2026  

---

## 1. Executive Summary

### The Verdict: Do Both Systems Work in Roughly the Same Way?

**Yes. KothaPad’s vault system and Obsidian’s vault system share the exact same foundational philosophy and architectural model.**

Both systems adhere strictly to the principle of **"Folder as a Database, Plain Files as Truth"**:
1. **No Proprietary Database Lock-In:** In both systems, a "vault" is simply an ordinary folder on the file system containing plain Markdown (`.md`) text files and subfolders. There is no SQLite or proprietary binary container storing the core note contents.
2. **Self-Contained Portable Configuration:** Both systems store all vault-specific configurations in a single hidden configuration folder located directly inside the vault root (`.obsidian` in Obsidian, `.KOTHAPAD` in KothaPad).
3. **Surviving App Reinstallation:** Because all notes and vault configurations reside inside user-accessible device storage (and not inside the app's private sandbox), uninstalling and reinstalling the app results in zero data or setting loss. Re-selecting the folder restores all notes, custom themes, formatting settings, workspace states, and trash exactly as they were left.
4. **Derived, Disposable Metadata Cache:** In both systems, indexes, search caches, word counts, and graph/metric data are treated as derived representations that can be discarded and cleanly rebuilt from disk at any time without corrupting the Markdown source files.
5. **Separation of Global Registry vs. Vault Data:** Both systems keep only minimal pointer information in global app storage (a registry of known vault URIs/paths), while keeping all user data, settings, and workspace states inside the respective vault folders.

While there are minor platform-specific differences (such as Android Storage Access Framework URI permissions and note-level metadata packaging), the two systems operate with near-identical lifecycle mechanics.

---

## 2. Core Architectural Mental Model Comparison

### 2.1 The Three-Layer Architecture

Both Obsidian and KothaPad decompose a vault into three distinct conceptual layers:

| Layer | Obsidian Architecture | KothaPad Architecture | Architectural Alignment |
|---|---|---|---|
| **1. Content Layer** | Plain `.md` files organized in user-created folders and subfolders. | Plain `.md` UTF-8 files in user-created folders (`General`, `Templates`, custom subfolders). | **Identical** |
| **2. Vault-Config Layer** | Hidden `<vault>/.obsidian/` folder at vault root storing JSON configuration files. | Hidden `<vault>/.KOTHAPAD/` folder at vault root storing JSON configuration files. | **Identical** |
| **3. Global / App Layer** | OS-level registry (`obsidian.json` in AppData / Application Support) storing registered vault paths and IDs. | Android `kothapad_vault_storage` SharedPreferences storing current vault URI, name, and recent vaults list. | **Equivalent (Adapted for Android)** |

```
Obsidian Vault Structure:
MyVault/
├── .obsidian/               <-- Vault-level config
│   ├── app.json
│   ├── appearance.json
│   └── workspace.json
├── .trash/                  <-- Deleted files
├── Projects/
│   └── Notes.md             <-- Plain markdown files
└── Daily/
    └── 2026-10-19.md

KothaPad Vault Structure:
KothaVault/
├── .KOTHAPAD/               <-- Vault-level config
│   ├── settings.json        <-- All preferences & appearance
│   ├── workspace.json       <-- Last note, active & expanded folders
│   ├── notes-meta.json      <-- Sidecar metadata cache
│   └── trash/               <-- Deleted files (.md)
├── General/
│   └── Meeting.md           <-- Plain markdown files
└── Templates/
    └── Daily Log.md
```

---

## 3. Deep-Dive Comparison by Component

### 3.1 What a Vault Is & File System Mapping
- **Obsidian:** A vault is any directory in the local file system. Subfolders are indexed recursively. Note titles correspond exactly to file names (e.g., `Meeting.md` has the title "Meeting"). External edits made via third-party text editors or file managers are detected and reflected automatically.
- **KothaPad (`VaultManager.kt`, `VaultNoteRepository.kt`):** A vault is a user-chosen directory accessed via Android's Storage Access Framework (`DocumentFile`). Subfolders are scanned recursively. Note titles directly correspond to the Markdown file base names (`child.name.removeSuffix(".md")`). External edits from outside the app (e.g., edited via Obsidian or a file explorer) are detected via file modification timestamps and content hashes, automatically refreshing the UI without overwriting user changes.

### 3.2 The Hidden Configuration Folder
- **Obsidian (`.obsidian`):**
  - Placed at the vault root.
  - Hidden by default on OS file managers (prefixed with a dot `.`).
  - Safe to delete: If deleted, notes are completely unharmed; default settings are simply regenerated on the next launch.
  - Portable: Copying `.obsidian` to another vault clones all settings and preferences.
- **KothaPad (`.KOTHAPAD` via `VaultConfigStore.kt`):**
  - Placed at the vault root (`const val CONFIG_DIR_NAME = ".KOTHAPAD"`).
  - Hidden on Android and desktop operating systems via dot-prefix.
  - Protected by a dedicated concurrency `Mutex` to prevent duplicate directory or file creation under Android's Storage Access Framework.
  - Safe to delete: If `.KOTHAPAD` is removed, all `.md` notes remain intact. KothaPad cleanly re-initializes `.KOTHAPAD` with default `settings.json`, empty `workspace.json`, and reconstructs `notes-meta.json` from disk during the recursive scan.
  - Equipped with automatic duplicate cleanup and migration: If SAF or file managers create numbered copies (e.g., `.KOTHAPAD (1)` or `settings (1).json`), `VaultConfigStore.cleanupAndMigrateDuplicatesInternal()` deep-merges keys and eliminates duplicates safely.

### 3.3 Configuration Files Breakdown

| Configuration Domain | Obsidian Implementation | KothaPad Implementation | Comparison Notes |
|---|---|---|---|
| **Core Settings** | `.obsidian/app.json` | `.KOTHAPAD/settings.json` (`dictation`, `pipeline`, `notesManager`, `markdown` blocks) | Obsidian splits settings across multiple files; KothaPad uses a structured monolithic `settings.json` with versioning (`schemaVersion`). |
| **Appearance & UI** | `.obsidian/appearance.json` | `.KOTHAPAD/settings.json` (`appearance`, `toolbar`, `micButton`, `floatingControls`) | Both store theme names, colors, font sizes, density, and UI layout settings per-vault. |
| **Workspace & State** | `.obsidian/workspace.json` (and `workspace-mobile.json`) | `.KOTHAPAD/workspace.json` (`lastOpenedNoteRelPath`, `lastActiveFolder`, `expandedFolders`) | Both persist which note was open, the active folder, and layout states across restarts. |
| **File Metadata / State** | Stored in IndexedDB and/or note YAML frontmatter | `.KOTHAPAD/notes-meta.json` | KothaPad uses a dedicated JSON file in the config folder to keep `.md` files clean while retaining pin/archive/session metrics. |

### 3.4 Global vs. Vault-Specific State

Obsidian strictly isolates machine/account-wide state from vault state:
- **Obsidian Global Registry:** Stored in `%APPDATA%\Obsidian\obsidian.json` or `~/.config/obsidian/obsidian.json`. Contains a `vaults` map with 16-character random vault IDs, folder paths, and timestamps.
- **KothaPad Global Registry:** Stored in Android private SharedPreferences (`kothapad_vault_storage`). Contains:
  - `current_vault_uri`: The persistent tree URI of the currently active vault.
  - `current_vault_name`: The display name of the vault.
  - `recent_vaults`: A JSON array of recently accessed vaults (`[{ "name": ..., "uri": ... }]`), capped at 10 entries.
- **Behavioral Parity:** Neither app stores user notes or custom preferences in the global registry. The global registry only acts as a bookmark/pointer to known folder locations.

### 3.5 Metadata Cache & Indexing

- **Obsidian:** Obsidian maintains an in-memory and IndexedDB-backed metadata cache (links, tags, headings, word counts). If the cache falls out of sync or gets corrupted, users can invoke **Settings → Files and links → Rebuild vault cache**.
- **KothaPad (`VaultNoteRepository.kt`):**
  - Keeps an in-memory cache (`ConcurrentHashMap<String, CachedNoteEntry>`) and maps relative file paths to stable runtime IDs.
  - Scans files on startup (`scanVaultRecursive`) calculating word count, char count, fileSize, and content hashes.
  - Provides `rebuildIndex()` / `rescanIfNeeded()`. If `notes-meta.json` is missing or out of sync, scanning rebuilds all metadata without losing any note content.
  - Preserves metadata when files are renamed or moved outside the app by matching `contentHash` or `fileSize + lastModified`.

### 3.6 Vault Management Operations

| Operation | Obsidian Behavior | KothaPad Behavior |
|---|---|---|
| **Create New Vault** | Pick a parent folder, name the vault; Obsidian creates a subfolder and places `.obsidian` inside. | "Create New Vault": Pick a parent directory, enter vault name; KothaPad creates the subfolder via SAF and initializes `.KOTHAPAD/`. |
| **Open Existing Vault** | "Open folder as vault": Select any directory containing `.md` files. `.obsidian` is created if absent. | "Open Existing Folder as Vault": Select any existing directory via system folder picker. Existing `.KOTHAPAD` is recognized and loaded; if absent, it is initialized. |
| **Switch Vaults** | Switch via Vault Switcher modal or recent vaults list. | Switch via Welcome Screen Recent Vaults list or Notes Manager Drawer. |
| **Remove Vault** | "Remove from list" unregisters the vault from `obsidian.json`. Files on disk are NOT deleted. | "Remove" on Welcome Screen removes the vault from `kothapad_vault_storage` and revokes persisted permissions. Files on disk are NOT deleted. |
| **Inaccessible Vault** | If a folder was moved or is on an unmounted drive, Obsidian reports vault missing. | If a directory was moved or permissions revoked, KothaPad flags `isAccessible = false` and presents an "Inaccessible Vault" recovery dialog allowing the user to re-pick the folder. |

### 3.7 Deleted Files & Trash Management

- **Obsidian:** Supports three deletion modes:
  1. System Trash (OS recycle bin).
  2. Obsidian Trash (`.trash/` folder inside the vault root).
  3. Permanent Delete.
- **KothaPad:**
  - When a note is soft-deleted, it is **physically moved** from its current folder into `.KOTHAPAD/trash/` (inside the vault's hidden configuration folder).
  - Collisions in trash are avoided by timestamping (e.g., `Note_1710000000.md`).
  - When restored, the note is physically moved back to its original parent folder.
  - When "Empty Trash" is executed, `VaultNoteRepository.emptyTrash()` permanently deletes the physical files from disk.
- **Comparison:** Both systems avoid destructive accidental deletes by physically moving deleted files into a hidden vault-local directory, keeping them completely safe and recoverable offline.

### 3.8 Android Scoped Storage & Reinstallation Resilience

This directly addresses the user's core observation: *"Even if I reinstall the app and open the same vault again, it feels as though I never reinstalled the app at all."*

1. **Storage Location:**
   - On Android, apps that store data in app-private storage (`/data/data/com.example/` or `/sdcard/Android/data/com.example/`) lose all data upon uninstallation.
   - Both Obsidian for Android and KothaPad use **Shared Device Storage** (such as `Documents/KothaVault` or `Documents/Notes`).
2. **Permission Persistence:**
   - KothaPad invokes `context.contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION)`.
3. **Reinstallation Workflow:**
   - When KothaPad is uninstalled, the user's vault folder in `Documents/` remains 100% untouched on disk.
   - When the user reinstalls KothaPad and opens it, SharedPreferences is empty, so KothaPad displays the Welcome Screen.
   - The user taps **"Open existing folder as vault"** and selects their vault folder.
   - KothaPad reads `.KOTHAPAD/settings.json`, immediately restoring themes, typography, editor preferences, keyboard layouts, and custom colors.
   - KothaPad reads `.KOTHAPAD/workspace.json`, immediately identifying the last active folder and the last opened note.
   - KothaPad scans all `.md` files and `.KOTHAPAD/trash/`, reconstructing all notes and metadata.
   - The user lands right back in their workspace exactly where they left off.

---

## 4. Evaluation Against the 15-Point Obsidian Comparison Checklist

Here is a systematic evaluation of KothaPad against every item in the Obsidian Reference Report Checklist (Section 15):

| # | Checklist Question | KothaPad Status | Implementation Evidence in KothaPad |
|---|---|---|---|
| **1** | **Vault = user-chosen folder with plain `.md` files?** | **YES (100% Match)** | `VaultManager.kt` uses `OpenDocumentTree` contract; notes are stored as plain UTF-8 text files ending in `.md`. Subfolders are supported. |
| **2** | **Settings live inside a hidden vault config folder?** | **YES (100% Match)** | `VaultConfigStore.kt` manages `.KOTHAPAD/settings.json`, `.KOTHAPAD/workspace.json`, and `.KOTHAPAD/notes-meta.json` at the vault root. |
| **3** | **One config folder, updated in place without duplicate files?** | **YES (100% Match)** | Protected by single `Mutex`, uses truncate write mode (`"wt"`), debounces saves, and includes duplicate cleaner (`cleanupAndMigrateDuplicatesInternal`). |
| **4** | **Missing config is recreated; notes are never touched?** | **YES (100% Match)** | If `.KOTHAPAD` is deleted, `VaultConfigStore` recreates defaults; `VaultNoteRepository.scanVaultRecursive` re-indexes all `.md` notes from disk without touching content. |
| **5** | **Workspace state stored in config folder (last note/layout)?** | **YES (100% Match)** | `.KOTHAPAD/workspace.json` stores `lastOpenedNoteRelPath`, `lastActiveFolder`, and `expandedFolders`. Reopened notes are restored automatically on launch. |
| **6** | **Derived data is rebuildable from notes?** | **YES (100% Match)** | `VaultNoteRepository.rebuildIndex()` re-scans files, recalculates metrics, hashes, and word counts. Cache can be discarded safely. |
| **7** | **Global vs. vault data separation?** | **YES (100% Match)** | Device `kothapad_vault_storage` SharedPreferences holds only vault URI pointers and recent vault names. All note contents and settings reside in the vault. |
| **8** | **Opening same folder restores everything after reinstall?** | **YES (100% Match)** | Re-selecting the folder via `openVaultFromUri` reloads `.KOTHAPAD/settings.json`, workspace, and notes. The user experience is seamless. |
| **9** | **Remove vs. delete: remove does not delete files?** | **YES (100% Match)** | `VaultManager.removeFromRecentVaults()` removes entries from the SharedPreferences registry only; physical files on disk remain untouched. |
| **10** | **Rename and move handled safely?** | **YES (High Parity)** | Renaming a note renames the `.md` file; changing folders moves the file via `DocumentsContract.moveDocument`. If the whole vault is moved externally, KothaPad flags inaccessible and allows re-selection. |
| **11** | **Trash behavior: files physically moved to vault trash?** | **YES (High Parity)** | Trashed notes are moved to `.KOTHAPAD/trash/`. They leave their active folder, can be restored to original folder, or emptied permanently. |
| **12** | **Default location for new notes customizable?** | **YES (100% Match)** | `AppSettings` provides `newNoteLocationMode` (`LAST_WORKED_ON`, `FIXED_FOLDER`, `ROOT`) and `defaultNewNoteFolder`. |
| **13** | **Vault rules: prevent vault in restricted/system folders?** | **YES (High Parity)** | `VaultManager.isRestrictedFolder(uri)` rejects root storage (`primary:`), `Download`, and `Android/data`/`obb` to comply with Android scoped storage safety. |
| **14** | **Android storage: shared persistent location & persisted permissions?** | **YES (100% Match)** | Enforces SAF user selection in shared storage (e.g. `Documents`), calls `takePersistableUriPermission(FLAG_GRANT_READ_URI_PERMISSION \| FLAG_GRANT_WRITE_URI_PERMISSION)`. |
| **15** | **Backup: exports notes and config folder together?** | **YES (100% Match)** | `BackupManager.exportVaultArchive()` produces a standard ZIP archive containing all folders, all `.md` notes, templates, and the full `.KOTHAPAD/` config folder. |

---

## 5. Architectural Nuances & Differences

While the high-level paradigms match, there are intentional design nuances between Obsidian and KothaPad:

### 1. Configuration Directory Naming & Trash Location
- **Obsidian:** Uses `.obsidian` for config and `.trash/` at the vault root for deleted files.
- **KothaPad:** Uses `.KOTHAPAD` for config and nests trash inside it as `.KOTHAPAD/trash/`.
- **Impact:** Nesting `trash/` inside `.KOTHAPAD/` keeps the vault root even cleaner for users viewing the folder in third-party file managers, since all metadata and deleted files are consolidated inside the single hidden dot-folder.

### 2. Sidecar Metadata vs. Frontmatter
- **Obsidian:** Stores tags, links, and aliases in Markdown YAML frontmatter at the top of each `.md` file (`--- tag: ... ---`), and caches parsed properties in IndexedDB.
- **KothaPad:** Stores extended app metadata (pin status, archive status, tags, session count, reading duration, highlight color, font size modifier) in `.KOTHAPAD/notes-meta.json` mapped by stable IDs and relative paths.
- **Impact:** KothaPad keeps Markdown files 100% pure plain text without prepending YAML frontmatter headers to the note text, while still preserving rich metadata across app re-installations. If a note is edited outside the app, KothaPad reconciles metadata by detecting file size and content hash changes.

### 3. Consolidated Settings File
- **Obsidian:** Distributes settings across multiple files (`app.json`, `appearance.json`, `community-plugins.json`, etc.).
- **KothaPad:** Consolidates all settings into `.KOTHAPAD/settings.json`, structured into distinct JSON objects (`appearance`, `dictation`, `keyboard`, `markdown`, `notesManager`, `toolbar`, `floatingControls`).
- **Impact:** Consolidating settings into a single file simplifies Android Storage Access Framework operations, significantly reducing I/O overhead on mobile devices where opening multiple DocumentFiles can cause latency.

---

## 6. Conclusion & Recommendations for Future Work

### Final Conclusion
KothaPad's vault system is an authentic, robust implementation of the **Obsidian vault mental model**. It guarantees:
- **Zero data loss on reinstall or device migration.**
- **Complete user ownership of plain Markdown files.**
- **Safe, transactional in-place configuration persistence.**
- **Full offline functionality without proprietary cloud dependencies.**

### Suggested Future Polish (For Implementation Later)
If you wish to make KothaPad even closer to Obsidian in future updates, consider:
1. **Explicit "Nested Vault" Warning:** Add a check during `openVaultFromUri` or `createNewVaultInParent` that verifies the selected folder is not an ancestor or child of an already registered vault.
2. **Optional Obsidian Trash Compatibility:** Support reading `.trash/` at the vault root if opened in a vault that is shared directly with Obsidian on desktop/Android.
3. **Manual "Rebuild Vault Cache" Button in Settings UI:** While KothaPad already has `rebuildIndex()` internally in `VaultNoteRepository`, exposing an explicit "Rebuild Vault Index" button under Settings → Vault Management would mirror Obsidian’s troubleshooting affordance.
4. **Frontmatter Export Option (Optional):** Allow users to optionally serialize tags or metadata as standard YAML frontmatter inside the `.md` files if they frequently cross-edit notes in Obsidian.

---
*Report generated and compiled for KothaPad codebase evaluation.*
