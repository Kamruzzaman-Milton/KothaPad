# KothaPad vault fixes: items 1–6

Line numbers refer to your uploaded `VaultNoteRepository_kt.txt`.
I have not seen `VaultManager`, so helper signatures are inferred from how you call them
(`move` → `DocumentFile?`, `delete`/`rename` → `Boolean`, `readText(DocumentFile)` → `String`,
`writeTextAtomic(dir, name, text, mime)` → `DocumentFile?`). Adjust if yours differ. Not compiled or tested.

Suggested order: 6 → 1 → 3 → 4 → 2 → 5.

---

## 6. Scope crash / dead scope

Imports:
```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
```

Constructor default (line 131). If your DI passes its own scope, fix it there instead:
```kotlin
private val repositoryScope: CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, t -> android.util.Log.e("VaultRepo", "Uncaught", t) }
),
```

New field next to `scanJob`:
```kotlin
private val scanGeneration = AtomicLong(0L)
```

Replace `startVaultScan` (lines 427–439). The generation counter also fixes the old scan's `finally` resetting `isScanning` while a new scan runs:
```kotlin
fun startVaultScan(vaultDoc: DocumentFile) {
    scanJob?.cancel()
    val gen = scanGeneration.incrementAndGet()
    scanJob = repositoryScope.launch {
        _isScanning.value = true
        _scanProgress.value = "Indexing notes..."
        try {
            scanVaultRecursive(vaultDoc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("VaultRepo", "Scan failed", e)
            _lastSaveError.value = "Vault scan failed: ${e.message}"
        } finally {
            if (scanGeneration.get() == gen) {
                _isScanning.value = false
                _scanProgress.value = null
            }
        }
    }
}
```

Wrap the stats worker body (line 1071) the same way:
```kotlin
statsWorkerJob = repositoryScope.launch(Dispatchers.IO) {
    try {
        // ... existing body unchanged ...
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e("VaultRepo", "Stats worker failed", e)
    }
}
```
(`return@launch` inside still works.)

---

## 1. Placeholder `file = vaultDoc` (vault-wipe / vault-rename risk)

Add a flag to `CachedNoteEntry` (line 113):
```kotlin
data class CachedNoteEntry(
    val note: Note,
    val file: DocumentFile,
    val parentDir: DocumentFile,
    val relPath: String,
    val lastModified: Long,
    val isPlaceholder: Boolean = false
)
```

In `loadMetadata`, the `else` branch (lines 1274–1280) creates the placeholder. Mark it:
```kotlin
cache[meta.path] = CachedNoteEntry(
    note = note,
    file = vaultDoc,
    parentDir = vaultDoc,
    relPath = meta.path,
    lastModified = meta.modifiedAt,
    isPlaceholder = true
)
```

New helper (anywhere in the class). It turns a placeholder into a real file entry or returns null:
```kotlin
private suspend fun materializeEntry(entry: CachedNoteEntry): CachedNoteEntry? {
    if (!entry.isPlaceholder) return entry
    val vaultDoc = currentVaultDoc ?: return null
    val file = vaultManager.findFileRecursive(vaultDoc, entry.relPath) ?: return null
    if (file.isDirectory || file.uri == vaultDoc.uri) return null
    val parentPath = entry.relPath.substringBeforeLast("/", "")
    val parent = if (parentPath.isEmpty()) vaultDoc
                 else vaultManager.getOrCreateDirRecursive(vaultDoc, parentPath) ?: return null
    val real = entry.copy(file = file, parentDir = parent, isPlaceholder = false)
    cache[entry.relPath] = real
    return real
}
```

`readNoteBody` (lines 255–262). Never read a placeholder, and never cache a miss as `""`:
```kotlin
val file = entry?.takeIf { !it.isPlaceholder }?.file
val text: String = if (file != null && file.exists() && file.isFile) {
    vaultManager.readText(file)
} else {
    val vaultDoc = currentVaultDoc ?: return@withContext ""
    val f = vaultManager.findFileRecursive(vaultDoc, relPath) ?: return@withContext ""
    vaultManager.readText(f)
}
```

`deleteInternal` (lines 1982–1999). Replace with:
```kotlin
private suspend fun deleteInternal(note: Note) {
    val relPath = idToPathMap[note.id]
        ?: cache.entries.firstOrNull { it.value.note.id == note.id }?.key
    if (relPath != null) {
        val raw = cache[relPath]
        if (raw != null) {
            val real = materializeEntry(raw)
            if (real != null) {
                if (real.file.isDirectory || real.file.uri == currentVaultDoc?.uri) {
                    _lastSaveError.value = "Refusing to delete a directory"
                    return
                }
                if (!vaultManager.delete(real.file) && real.file.exists()) {
                    _lastSaveError.value = "Failed to delete note"
                    return          // keep it in the cache so the UI stays truthful
                }
            }
            cache.remove(relPath)
            noteBodyLruCache.remove(relPath)
            removeIdForPath(relPath)
            removeMetadataForPath(relPath)
            if (workspaceData.lastOpenedNoteRelPath == relPath) {
                saveWorkspace(clearLastOpenedNote = true)
            }
        }
    }
    saveMetadata()
    recomputeFlows()
}
```

`updateInternal`: right after the `if (entry == null) { ... return }` block (line 1678), add:
```kotlin
val resolved = materializeEntry(entry) ?: run {
    _lastSaveError.value = "Note file not found on disk"
    return
}
entry = resolved
```

---

## 3. `updateInternal`: empty-content overwrite, blank title, metadata-only updates

### 3a. Safe filename base
Add to the class:
```kotlin
private fun safeFileBase(title: String, fallback: String): String {
    val s = VaultManager.sanitizeFilename(title.trim()).trim().trimStart('.')
    return s.ifBlank { fallback }
}
```
In `updateInternal` (lines 1858–1860):
```kotlin
val safeTitle = safeFileBase(note.title, entry.note.title.ifBlank { "Untitled Note" })
val expectedFileName = "$safeTitle.md"
if (currentFile.name != null && !currentFile.name.equals(expectedFileName, ignoreCase = true)) {
    val uniqueName = vaultManager.resolveDuplicateFileName(currentParent, safeTitle, ".md")
```
Do the same in `insertInternal` (line 1458): `val sanitizedTitle = safeFileBase(note.title, "Untitled Note")`.

### 3b. Don't write content unless the caller actually has it
Change signatures:
```kotlin
suspend fun update(note: Note, writeContent: Boolean = true) = withContext(Dispatchers.IO) {
    val vaultDoc = currentVaultDoc ?: return@withContext
    mutex.withLock { updateInternal(note, vaultDoc, writeContent) }
}

suspend fun updateAll(notes: List<Note>, writeContent: Boolean = true) = withContext(Dispatchers.IO) {
    val vaultDoc = currentVaultDoc ?: return@withContext
    mutex.withLock { for (n in notes) updateInternal(n, vaultDoc, writeContent) }
}

private suspend fun updateInternal(note: Note, vaultDoc: DocumentFile, writeContent: Boolean = true) {
```
Callers that only change pin / archive / folder / trash / restore / color / tags should pass
`writeContent = false`. Only the editor's save passes `true`.

Replace steps 3 and 4 (lines 1887–1963):
```kotlin
// 3. Write markdown content only when the caller really supplied it
var contentWritten = false
if (writeContent && entry.note.content != note.content) {
    val fileName = currentFile.name ?: "$safeTitle.md"
    val writtenFile = vaultManager.writeTextAtomic(
        parentDir = currentParent, fileName = fileName,
        text = note.content, mimeType = "text/markdown"
    )
    if (writtenFile != null) { currentFile = writtenFile; contentWritten = true }
    else _lastSaveError.value = "Failed to write note to disk"
}

// 4. Metadata
val now = System.currentTimeMillis()
val existingMeta = getMetadataForPath(currentRelPath)
val renamedOrMoved = entry.relPath != currentRelPath
val words = if (contentWritten || existingMeta == null) countWords(note.content) else existingMeta.wordCount
val chars = if (contentWritten || existingMeta == null) note.content.length else existingMeta.charCount
val hash  = if (contentWritten || existingMeta == null) note.content.hashCode().toLong() else existingMeta.contentHash
val realModified = currentFile.lastModified()
val fileSize = currentFile.length()

val meta = (existingMeta ?: NoteMetadata(
    id = java.util.UUID.randomUUID().toString(),
    path = currentRelPath, createdAt = note.createdAt, sessionCount = note.sessionCount
)).copy(
    path = currentRelPath,
    isPinned = note.isPinned, isArchived = note.isArchived,
    isDeleted = note.isDeleted, deletedAt = note.deletedAt,
    createdAt = if (existingMeta != null && existingMeta.createdAt > 0L) existingMeta.createdAt else note.createdAt,
    // only bump the timestamp when something real changed (also stops spurious "external edit" on next scan)
    updatedAt = if (contentWritten || renamedOrMoved || existingMeta == null) now else existingMeta.updatedAt,
    modifiedAt = realModified,
    sessionCount = existingMeta?.sessionCount ?: note.sessionCount,
    timeSpent = note.sessionDurationSec, sessionDurationSec = note.sessionDurationSec,
    wordCount = words, charCount = chars,
    fileSize = fileSize, contentHash = hash,
    languageCode = note.languageCode, highlightColor = note.highlightColor,
    fontSizeModifier = note.fontSizeModifier, tags = note.tags,
    isOrphaned = false, orphanedAt = null,
    statsStale = if (contentWritten || existingMeta == null) false else existingMeta.statsStale
)
putMetadata(meta)
if (contentWritten) {
    noteBodyLruCache.put(currentRelPath, note.content)
    saveContentToLocalCache(meta.id, note.content)
}

val updatedNote = note.copy(
    id = entry.note.id,
    title = currentFile.name?.removeSuffix(".md") ?: safeTitle,
    folder = targetFolder,
    content = if (writeContent) note.content else entry.note.content,
    updatedAt = meta.updatedAt,
    sessionCount = meta.sessionCount,
    wordCount = words, charCount = chars,
    statsStale = meta.statsStale
)
cache[currentRelPath] = CachedNoteEntry(
    note = updatedNote, file = currentFile, parentDir = currentParent,
    relPath = currentRelPath, lastModified = realModified
)

saveMetadata()
recomputeFlows()
```
The trash and restore branches (lines 1686–1828) already skip content writes, so they need no change.

---

## 4. Date-prefix heuristic

Only an **exact** `YYYY-MM-DD` title is a daily journal. Add to the companion object (replace `isDailyJournalNote`, lines 204–211):
```kotlin
private val DAILY_BASE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
fun isDailyBase(base: String): Boolean = DAILY_BASE_REGEX.matches(base.trim())

fun isDailyJournalNote(note: Note): Boolean {
    val f = note.folder.trim()
    if (f.equals(DAILY_JOURNAL_DIR, ignoreCase = true) || f.equals("Daily Journals", ignoreCase = true)) return true
    return isDailyBase(note.title)
}
```

`insertInternal`: the canonical-file / dedupe logic must run only for exact-date titles. Right after `val sanitizedTitle` (line 1458) add:
```kotlin
val isExactDaily = isDailyBase(sanitizedTitle)
```
Then:
- Line 1461: `if (isDailyJournal && isExactDaily) {` so only exact dates dedupe, and the early `return existingEntry.note.id` can no longer swallow "2026-10-30 Cardiology".
- Lines 1534–1540:
```kotlin
val finalFileName = if (isDailyJournal && isExactDaily) {
    "${sanitizedTitle.trim()}.md"
} else {
    vaultManager.resolveDuplicateFileName(targetDir, sanitizedTitle, ".md")
}
```

Replace every `containsMatchIn` date check so a date prefix alone no longer relocates notes:

| Line | Change |
|---|---|
| 508 | `val dailyJournalRegex = Regex("^\\d{4}-\\d{2}-\\d{2}\\.md$", RegexOption.IGNORE_CASE)` |
| 695 | `if (isDailyBase(base)) {` |
| 765 | `val isExactDaily = isDailyBase(baseTitle)` |
| 766 | `val folderName = if (currentRelPath.isEmpty()) (if (isExactDaily) DAILY_JOURNALS_DIR else GENERAL_DIR) else currentRelPath` (label by the real folder) |
| 944 | `val isDaily = isDailyBase(baseTitle)` |
| 1239 | `val isDailyJournal = isDailyBase(baseTitle) && !meta.path.contains("/")` |
| 1763, 1766 | use `isDailyBase(note.title)`; the `find(...)?.value` fallback is then unnecessary |

Notes in a real subfolder keep that folder as their label. If you want exact-date notes in `Work/` to stay in `Work/`, leave line 766 as written.

---

## 2. `consolidateStrayRootFiles` and legacy-folder merge: stop silent deletes

### 2a. Helpers
```kotlin
private fun isLegacyTipsFile(name: String): Boolean {
    val n = name.trim().lowercase()
    return n == "kothapad features.md" || n == "kothapad tips.md" ||
           n == "notepad features.md" || n == "notepad tips.md"
}

/**
 * Reconcile [src] into [dst] (same file name, different folders).
 * Returns true only if [src] is gone AND no content was lost.
 */
private suspend fun reconcileDuplicate(
    src: DocumentFile, dstDir: DocumentFile, dst: DocumentFile,
    fileName: String, keepBothIfDifferent: Boolean
): Boolean {
    val srcText = vaultManager.readText(src)
    val dstText = vaultManager.readText(dst)
    return when {
        srcText == dstText || srcText.isBlank() -> vaultManager.delete(src)
        dstText.isBlank() ->
            vaultManager.writeTextAtomic(dstDir, fileName, srcText, "text/markdown") != null &&
                vaultManager.delete(src)
        keepBothIfDifferent -> {
            val unique = vaultManager.resolveDuplicateFileName(dstDir, fileName.removeSuffix(".md"), ".md")
            vaultManager.writeTextAtomic(dstDir, unique, srcText, "text/markdown") != null &&
                vaultManager.delete(src)
        }
        else -> false   // differing non-empty content: leave both untouched
    }
}
```

### 2b. Legacy tips purge (lines 542–553 and 585–593): exact name only, and move to Trash
```kotlin
if (isLegacyTipsFile(fileName)) {
    val trash = vaultManager.getOrCreateDirRecursive(vaultDoc, TRASH_DIR)
    val ok = if (trash != null) vaultManager.move(rootFileDoc, vaultDoc, trash) != null else false
    if (ok) {
        removeMetadataForPath(fileName); cache.remove(fileName); removeIdForPath(fileName)
        metadataModified = true
    }
    continue
}
```
Use the same idea in the General loop (`isLegacyTipsFile(gName)`, `move(gDoc, generalDir, trash)`). Also skip directories there: `if (gFile.isDirectory) continue`.

### 2c. Root-vs-folder duplicate (Check 3, lines 555–574)
Remove the `endsWith("/$fileName")` fallback and the `delete(rootFileDoc)` branch. A root file is only moved when its target is **free**:
```kotlin
val meta = getMetadataForPath(fileName)
    ?: metadataByIdMap.values.firstOrNull {
        it.path.endsWith("/$fileName", ignoreCase = true) &&
            vaultManager.findFileRecursive(vaultDoc, it.path) == null   // its real file is missing
    }
if (meta != null && meta.path.contains("/")) {
    val targetDir = vaultManager.getOrCreateDirRecursive(vaultDoc, meta.path.substringBeforeLast("/"))
    if (targetDir != null && vaultManager.move(rootFileDoc, vaultDoc, targetDir) != null) {
        removeMetadataForPath(fileName); cache.remove(fileName); removeIdForPath(fileName)
        metadataModified = true
    }
}
```

### 2d. Daily duplicates (Check 1 lines 519–540, General loop 594–612)
Replace each `if (dailyDoc.length()==0 ...) {...} else delete(...)` with:
```kotlin
val handled = reconcileDuplicate(rootFileDoc, dailyDir, dailyDoc, fileName, keepBothIfDifferent = true)
if (handled) { removeMetadataForPath(fileName); cache.remove(fileName); removeIdForPath(fileName); metadataModified = true }
```
(`gDoc`/`gName`/`generalDir` for the General loop.)

### 2e. Numbered duplicates inside Daily Journal (step 3, lines 658–687)
Fixes the stale-entry delete after rename, and never discards differing content:
```kotlin
for ((datePrefix, files) in groupedByDate) {
    if (datePrefix == null || files.size <= 1) continue
    val canonicalName = "$datePrefix.md"
    var canonicalDoc = files.firstOrNull { it.displayName.equals(canonicalName, ignoreCase = true) }
        ?.toDocumentFile(context, treeUri)
    var skipName: String? = null

    if (canonicalDoc == null) {
        val first = files.first()
        val firstDoc = first.toDocumentFile(context, treeUri)
        if (firstDoc != null && vaultManager.rename(firstDoc, canonicalName)) {
            skipName = first.displayName           // this entry is now stale; do not touch it
            canonicalDoc = vaultManager.findChildFast(dailyDir, canonicalName, treeUri)
                ?.toDocumentFile(context, treeUri)
        }
    }
    if (canonicalDoc == null) continue

    for (fileEntry in files) {
        if (fileEntry.displayName.equals(canonicalName, ignoreCase = true)) continue
        if (fileEntry.displayName == skipName) continue
        val dupDoc = fileEntry.toDocumentFile(context, treeUri) ?: continue
        val handled = reconcileDuplicate(dupDoc, dailyDir, canonicalDoc, canonicalName, keepBothIfDifferent = false)
        if (handled) {
            val dupRelPath = "$DAILY_JOURNAL_DIR/${fileEntry.displayName}"
            removeMetadataForPath(dupRelPath); cache.remove(dupRelPath); removeIdForPath(dupRelPath)
            metadataModified = true
        }
    }
}
```

### 2f. Legacy "Daily Journals" merge in `getOrCreateDailyJournalDir` (lines 471–494)
Never delete the legacy folder unless it is empty afterwards:
```kotlin
} else if (dailyDir.uri != legacyDailyDir.uri && treeUri != null) {
    for (child in vaultManager.listChildrenFast(legacyDailyDir, treeUri)) {
        val childDoc = child.toDocumentFile(context, treeUri) ?: continue
        val existing = vaultManager.findChildFast(dailyDir, child.displayName, treeUri)
            ?.toDocumentFile(context, treeUri)
        if (child.isFile && existing != null) {
            reconcileDuplicate(childDoc, dailyDir, existing, child.displayName, keepBothIfDifferent = true)
        } else {
            vaultManager.move(childDoc, legacyDailyDir, dailyDir)   // files without a clash, and subfolders
        }
    }
    if (vaultManager.listChildrenFast(legacyDailyDir, treeUri).isEmpty()) {
        vaultManager.delete(legacyDailyDir)
    }
}
```
If `treeUri == null`, the merge is now skipped instead of deleting everything.

---

## 5. `deleteFolderOnDisk`: move everything, verify, then delete

Replace lines 2083–2102:
```kotlin
suspend fun deleteFolderOnDisk(folder: String, deleteContainedNotes: Boolean): Boolean = withContext(Dispatchers.IO) {
    val vaultDoc = currentVaultDoc ?: return@withContext false
    val dir = vaultManager.findDirRecursive(vaultDoc, folder) ?: return@withContext false
    val treeUri = vaultManager.getVaultTreeUri() ?: return@withContext false
    scanJob?.cancelAndJoin()                       // avoid racing the background scan

    if (deleteContainedNotes) {
        // Soft-delete: send contained notes to Trash so they stay recoverable
        val prefix = "$folder/"
        val victims = cache.values.map { it.note }.filter { n ->
            val p = idToPathMap[n.id] ?: return@filter false
            p.startsWith(prefix, ignoreCase = true) && !n.isDeleted
        }
        val now = System.currentTimeMillis()
        mutex.withLock {
            for (n in victims) updateInternal(n.copy(isDeleted = true, deletedAt = now), vaultDoc, writeContent = false)
        }
    } else {
        val generalDir = vaultManager.getOrCreateDirRecursive(vaultDoc, GENERAL_DIR) ?: return@withContext false
        for (ch in vaultManager.listChildrenFast(dir, treeUri)) {           // ALL children: files and subfolders
            val chDoc = ch.toDocumentFile(context, treeUri) ?: continue
            if (vaultManager.move(chDoc, dir, generalDir) == null) {
                // name clash: copy .md under a unique name, then remove the original
                if (ch.isFile && ch.displayName.endsWith(".md", ignoreCase = true)) {
                    val unique = vaultManager.resolveDuplicateFileName(generalDir, ch.displayName.removeSuffix(".md"), ".md")
                    val ok = vaultManager.writeTextAtomic(generalDir, unique, vaultManager.readText(chDoc), "text/markdown") != null
                    if (ok) vaultManager.delete(chDoc)
                }
            }
        }
    }

    // Only remove the folder if it is really empty now
    val leftovers = vaultManager.listChildrenFast(dir, treeUri)
    val deleted = if (leftovers.isEmpty()) vaultManager.delete(dir) else false
    scanVaultRecursive(vaultDoc)
    deleted
}
```
Behaviour change: with `deleteContainedNotes = true`, notes now go to Trash instead of being erased. If something is left over (non-note files, failed moves), the folder is kept and the function returns `false`, so the UI can tell the user.
