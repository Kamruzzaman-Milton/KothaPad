package com.example.data.vault

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.Note
import com.example.data.model.Stamp
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class NoteMetadata(
    val id: String = java.util.UUID.randomUUID().toString(),
    val path: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val sessionCount: Int = 0,
    val timeSpent: Long = 0L,
    val sessionDurationSec: Long = 0L,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val fileSize: Long = 0L,
    val contentHash: Long = 0L,
    val languageCode: String? = null,
    val highlightColor: String? = null,
    val fontSizeModifier: Float = 0f,
    val tags: String = "",
    val originalRelPath: String? = null,
    val trashFileName: String? = null,
    val isOrphaned: Boolean = false,
    val orphanedAt: Long? = null
)

data class WorkspaceData(
    val lastOpenedNoteRelPath: String? = null,
    val lastActiveFolder: String = "General",
    val expandedFolders: Set<String> = setOf("General", "Templates")
)

data class CachedNoteEntry(
    val note: Note,
    val file: DocumentFile,
    val parentDir: DocumentFile,
    val relPath: String,
    val lastModified: Long
)

data class ExternalNoteChange(
    val note: Note,
    val diskContent: String,
    val lastModified: Long
)

class VaultNoteRepository(
    private val context: Context,
    private val vaultManager: VaultManager,
    private val preferencesManager: PreferencesManager,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val vaultConfigStore: VaultConfigStore = VaultConfigStore.getInstance(context, repositoryScope)
) {

    private val mutex = Mutex()

    // Primary StateFlows matching NoteRepository API
    private val _allNotes = MutableStateFlow<List<Note>>(emptyList())
    val allNotes: StateFlow<List<Note>> = _allNotes.asStateFlow()

    private val _activeNotes = MutableStateFlow<List<Note>>(emptyList())
    val activeNotes: StateFlow<List<Note>> = _activeNotes.asStateFlow()

    private val _archivedNotes = MutableStateFlow<List<Note>>(emptyList())
    val archivedNotes: StateFlow<List<Note>> = _archivedNotes.asStateFlow()

    private val _trashedNotes = MutableStateFlow<List<Note>>(emptyList())
    val trashedNotes: StateFlow<List<Note>> = _trashedNotes.asStateFlow()

    // Vault Scanner & State
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow<String?>(null)
    val scanProgress: StateFlow<String?> = _scanProgress.asStateFlow()

    private val _lastSaveError = MutableStateFlow<String?>(null)
    val lastSaveError: StateFlow<String?> = _lastSaveError.asStateFlow()

    // External change detection
    private val _externalNoteChange = MutableStateFlow<ExternalNoteChange?>(null)
    val externalNoteChange: StateFlow<ExternalNoteChange?> = _externalNoteChange.asStateFlow()

    var currentlyOpenNoteId: Long? = null

    fun clearExternalNoteChange() {
        _externalNoteChange.value = null
    }

    // Internal In-Memory Index
    // relPath -> CachedNoteEntry
    private val cache = ConcurrentHashMap<String, CachedNoteEntry>()
    // relPath <-> ID mapping
    private val pathToIdMap = ConcurrentHashMap<String, Long>()
    private val idToPathMap = ConcurrentHashMap<Long, String>()
    private val idGenerator = AtomicLong(1000L)

    // Metadata Store: stableId -> NoteMetadata (Single source of truth)
    private val metadataByIdMap = ConcurrentHashMap<String, NoteMetadata>()
    // relPath -> stableId index
    private val pathIndex = ConcurrentHashMap<String, String>()

    // Current Vault Reference
    private var currentVaultDoc: DocumentFile? = null
    private var scanJob: Job? = null
    private var currentlyEditingNoteId: Long? = null

    // Workspace Cache
    private var workspaceData: WorkspaceData = WorkspaceData()

    companion object {
        const val KOTHAPAD_DIR = VaultConfigStore.CONFIG_DIR_NAME
        const val NOTES_META_FILE = VaultConfigStore.FILE_NOTES_META
        const val WORKSPACE_FILE = VaultConfigStore.FILE_WORKSPACE
        const val TEMPLATES_DIR = "Templates"
        const val GENERAL_DIR = "General"
        const val DAILY_JOURNALS_DIR = "Daily Journals"
        const val TRASH_PREFIX = ".KOTHAPAD/trash/"
        private const val META_SCHEMA_VERSION = 2
        private const val WORKSPACE_SCHEMA_VERSION = 1
    }

    private val localContentCacheDir by lazy {
        context.filesDir.resolve("note_content_cache").apply { mkdirs() }
    }

    private fun saveContentToLocalCache(id: String, content: String) {
        if (id.isBlank()) return
        try {
            java.io.File(localContentCacheDir, id).writeText(content, Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun readContentFromLocalCache(id: String): String? {
        if (id.isBlank()) return null
        return try {
            val file = java.io.File(localContentCacheDir, id)
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun ensureNoteContentLoaded(note: Note): Note = withContext(Dispatchers.IO) {
        if (note.content.isNotEmpty()) return@withContext note
        val relPath = idToPathMap[note.id] ?: return@withContext note
        val meta = getMetadataForPath(relPath)
        val cachedContent = readContentFromLocalCache(meta?.id ?: "")
        if (!cachedContent.isNullOrEmpty()) {
            val updated = note.copy(content = cachedContent)
            val entry = cache[relPath]
            if (entry != null) {
                cache[relPath] = entry.copy(note = updated)
            }
            return@withContext updated
        }
        val entry = cache[relPath]
        val file = entry?.file
        val text = if (file != null && file.exists()) {
            vaultManager.readText(file)
        } else {
            val vaultDoc = currentVaultDoc ?: return@withContext note
            val f = vaultManager.findFileRecursive(vaultDoc, relPath)
            if (f != null) vaultManager.readText(f) else ""
        }
        if (text.isNotEmpty()) {
            if (meta != null) {
                saveContentToLocalCache(meta.id, text)
            }
            val updated = note.copy(content = text)
            if (entry != null) {
                cache[relPath] = entry.copy(note = updated)
            }
            return@withContext updated
        }
        note
    }

    fun countWords(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 0
        var inWord = false
        val len = text.length
        for (i in 0 until len) {
            val c = text[i]
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    fun getMetadataForPath(relPath: String): NoteMetadata? {
        val id = pathIndex[relPath]
        if (id != null) {
            val meta = metadataByIdMap[id]
            if (meta != null) return meta
        }
        return metadataByIdMap.values.firstOrNull { it.path.equals(relPath, ignoreCase = true) }
    }

    fun putMetadata(meta: NoteMetadata) {
        metadataByIdMap[meta.id] = meta
        if (meta.path.isNotBlank()) {
            pathIndex[meta.path] = meta.id
        }
    }

    fun removeMetadataForPath(relPath: String) {
        val id = pathIndex.remove(relPath)
        if (id != null) {
            metadataByIdMap.remove(id)
        } else {
            metadataByIdMap.values.firstOrNull { it.path.equals(relPath, ignoreCase = true) }?.let {
                metadataByIdMap.remove(it.id)
            }
        }
    }

    fun onNoteOpenedForEditing(noteId: Long) {
        if (currentlyEditingNoteId == noteId) return
        currentlyEditingNoteId = noteId
        val relPath = idToPathMap[noteId] ?: return
        val meta = getMetadataForPath(relPath) ?: return
        val updated = meta.copy(sessionCount = meta.sessionCount + 1)
        putMetadata(updated)
        val cached = cache[relPath]
        if (cached != null) {
            val updatedNote = cached.note.copy(sessionCount = updated.sessionCount)
            cache[relPath] = cached.copy(note = updatedNote)
        }
        repositoryScope.launch(Dispatchers.IO) {
            saveMetadata()
        }
    }

    private fun getOrCreateIdForPath(relPath: String): Long {
        return pathToIdMap.computeIfAbsent(relPath) {
            val newId = idGenerator.incrementAndGet()
            idToPathMap[newId] = relPath
            newId
        }
    }

    private fun updateIdPath(oldRelPath: String, newRelPath: String) {
        val id = pathToIdMap.remove(oldRelPath)
        if (id != null) {
            pathToIdMap[newRelPath] = id
            idToPathMap[id] = newRelPath
        } else {
            getOrCreateIdForPath(newRelPath)
        }
    }

    private fun removeIdForPath(relPath: String) {
        val id = pathToIdMap.remove(relPath)
        if (id != null) {
            idToPathMap.remove(id)
        }
    }

    // =========================================================================
    // Vault Initialization & Rescan
    // =========================================================================

    suspend fun onVaultOpened(vaultDoc: DocumentFile) = withContext(Dispatchers.IO) {
        currentVaultDoc = vaultDoc
        cache.clear()
        metadataByIdMap.clear()
        pathIndex.clear()
        pathToIdMap.clear()
        idToPathMap.clear()
        currentlyEditingNoteId = null

        // Single source of truth: initialize vault config and cleanup duplicates
        vaultConfigStore.initVault(vaultDoc)

        // Ensure critical note content folders exist
        vaultManager.getOrCreateDirRecursive(vaultDoc, TEMPLATES_DIR)
        vaultManager.getOrCreateDirRecursive(vaultDoc, GENERAL_DIR)
        vaultManager.getOrCreateDirRecursive(vaultDoc, DAILY_JOURNALS_DIR)

        // Load metadata & workspace via VaultConfigStore before scanning
        // This IMMEDIATELY populates activeNotes and allNotes flows in memory
        loadMetadata(vaultDoc)
        loadWorkspace(vaultDoc)

        // Perform fast vault scan to index files without blocking UI startup
        startVaultScan(vaultDoc)
    }

    fun startVaultScan(vaultDoc: DocumentFile) {
        scanJob?.cancel()
        scanJob = repositoryScope.launch {
            _isScanning.value = true
            _scanProgress.value = "Indexing notes..."
            try {
                scanVaultRecursive(vaultDoc)
            } finally {
                _isScanning.value = false
                _scanProgress.value = null
            }
        }
    }

    suspend fun rescanIfNeeded() = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        if (_isScanning.value) return@withContext
        startVaultScan(vaultDoc)
    }

    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        scanJob?.cancel()
        cache.clear()
        metadataByIdMap.clear()
        pathIndex.clear()
        pathToIdMap.clear()
        idToPathMap.clear()
        currentlyEditingNoteId = null
        loadMetadata(vaultDoc)
        loadWorkspace(vaultDoc)
        startVaultScan(vaultDoc)
    }

    private suspend fun scanVaultRecursive(vaultDoc: DocumentFile) = withContext(Dispatchers.IO) {
        val discoveredEntries = mutableListOf<CachedNoteEntry>()
        val foundPaths = mutableSetOf<String>()
        val discoveredFolders = mutableSetOf<String>()
        var count = 0
        var metadataChanged = false

        suspend fun traverse(dir: DocumentFile, currentRelPath: String) {
            val children = vaultManager.listChildren(dir)
            for (child in children) {
                val name = child.name ?: continue
                // Ignore hidden files/folders (names starting with ".")
                if (name.startsWith(".")) {
                    continue
                }

                if (child.isDirectory) {
                    if (currentRelPath.isEmpty() && name.equals(TEMPLATES_DIR, ignoreCase = true)) {
                        continue
                    }
                    val nextRel = if (currentRelPath.isEmpty()) name else "$currentRelPath/$name"
                    discoveredFolders.add(nextRel)
                    traverse(child, nextRel)
                } else if (child.isFile && name.endsWith(".md", ignoreCase = true)) {
                    val fileRelPath = if (currentRelPath.isEmpty()) name else "$currentRelPath/$name"
                    foundPaths.add(fileRelPath)
                    count++

                    if (count % 30 == 0) {
                        _scanProgress.value = "Indexed $count notes..."
                    }

                    val baseTitle = name.removeSuffix(".md")
                    val folderName = if (currentRelPath.isEmpty()) GENERAL_DIR else currentRelPath
                    val lastMod = child.lastModified()
                    val fileSize = child.length()

                    var meta = getMetadataForPath(fileRelPath)
                    val isUnchanged = (meta != null && Math.abs(meta.modifiedAt - lastMod) < 2000L && (meta.fileSize <= 0L || meta.fileSize == fileSize))

                    val existingCache = cache[fileRelPath]
                    val content = if (isUnchanged) {
                        if (existingCache != null && existingCache.note.content.isNotEmpty()) {
                            existingCache.note.content
                        } else {
                            readContentFromLocalCache(meta!!.id) ?: ""
                        }
                    } else {
                        val text = vaultManager.readText(child)
                        val effectiveId = meta?.id ?: java.util.UUID.randomUUID().toString()
                        saveContentToLocalCache(effectiveId, text)
                        text
                    }
                    val words = if (isUnchanged && meta!!.wordCount > 0) meta.wordCount else countWords(content)
                    val chars = if (isUnchanged && meta!!.charCount > 0) meta.charCount else content.length
                    val hash = if (isUnchanged && meta!!.contentHash != 0L) meta.contentHash else (if (content.isNotEmpty()) content.hashCode().toLong() else 0L)

                    if (meta == null) {
                        // Check for renamed / moved note: match by fileSize + lastModified OR contentHash
                        val candidate = metadataByIdMap.values.firstOrNull { unmapped ->
                            !foundPaths.contains(unmapped.path) &&
                            !unmapped.path.startsWith(TRASH_PREFIX) &&
                            ((unmapped.fileSize > 0L && unmapped.fileSize == fileSize && Math.abs(unmapped.modifiedAt - lastMod) < 2000L) ||
                             (unmapped.contentHash != 0L && unmapped.contentHash == hash))
                        }

                        if (candidate != null) {
                            // Carried over metadata for moved/renamed note
                            val oldPath = candidate.path
                            pathIndex.remove(oldPath)
                            meta = candidate.copy(
                                path = fileRelPath,
                                modifiedAt = lastMod,
                                updatedAt = lastMod,
                                fileSize = fileSize,
                                contentHash = hash,
                                wordCount = words,
                                charCount = chars,
                                isOrphaned = false,
                                orphanedAt = null
                            )
                            putMetadata(meta)
                            metadataChanged = true
                        } else {
                            // Missing entry: create new entry.
                            val createdTs = if (lastMod > 0L) lastMod else System.currentTimeMillis()
                            meta = NoteMetadata(
                                path = fileRelPath,
                                createdAt = createdTs,
                                updatedAt = lastMod,
                                modifiedAt = lastMod,
                                sessionCount = 0,
                                timeSpent = 0L,
                                sessionDurationSec = 0L,
                                wordCount = words,
                                charCount = chars,
                                fileSize = fileSize,
                                contentHash = hash
                            )
                            putMetadata(meta)
                            metadataChanged = true
                        }
                    } else {
                        // Entry exists! Check if edited outside the app
                        if (lastMod > meta.modifiedAt + 2000L && lastMod > meta.updatedAt + 2000L) {
                            meta = meta.copy(
                                modifiedAt = lastMod,
                                updatedAt = lastMod,
                                wordCount = words,
                                charCount = chars,
                                fileSize = fileSize,
                                contentHash = hash,
                                isOrphaned = false,
                                orphanedAt = null
                            )
                            putMetadata(meta)
                            metadataChanged = true
                        } else {
                            // Unchanged entry: keep ALL its fields untouched
                            if (meta.isOrphaned) {
                                meta = meta.copy(isOrphaned = false, orphanedAt = null)
                                putMetadata(meta)
                                metadataChanged = true
                            }
                        }
                    }

                    val id = getOrCreateIdForPath(fileRelPath)
                    val note = Note(
                        id = id,
                        title = baseTitle,
                        content = content,
                        createdAt = if (meta.createdAt > 0L) meta.createdAt else (if (lastMod > 0L) lastMod else System.currentTimeMillis()),
                        updatedAt = meta.updatedAt,
                        isPinned = meta.isPinned,
                        languageCode = meta.languageCode,
                        highlightColor = meta.highlightColor,
                        fontSizeModifier = meta.fontSizeModifier,
                        folder = folderName,
                        tags = meta.tags,
                        isArchived = meta.isArchived,
                        isDeleted = meta.isDeleted,
                        deletedAt = meta.deletedAt,
                        sessionDurationSec = meta.timeSpent.coerceAtLeast(meta.sessionDurationSec),
                        sessionCount = meta.sessionCount
                    )

                    // Check if this is the currently open note and content changed externally
                    if (existingCache != null && id == currentlyOpenNoteId && existingCache.note.content != content) {
                        _externalNoteChange.value = ExternalNoteChange(
                            note = note,
                            diskContent = content,
                            lastModified = lastMod
                        )
                    }

                    val entry = CachedNoteEntry(
                        note = note,
                        file = child,
                        parentDir = dir,
                        relPath = fileRelPath,
                        lastModified = lastMod
                    )
                    cache[fileRelPath] = entry
                    discoveredEntries.add(entry)
                }
            }
        }

        traverse(vaultDoc, "")

        // One-time migration: Move any note marked as deleted in metadata that is still in normal folder
        val trashDir = vaultConfigStore.getOrCreateTrashDir(vaultDoc)
        if (trashDir != null) {
            val trashedInNormalFolder = cache.entries.filter { entry ->
                val meta = getMetadataForPath(entry.key)
                meta != null && meta.isDeleted && !entry.key.startsWith(TRASH_PREFIX)
            }.toList()

            if (trashedInNormalFolder.isNotEmpty()) {
                val trashChildren = vaultManager.listChildren(trashDir)
                for (entryItem in trashedInNormalFolder) {
                    val oldRel = entryItem.key
                    val oldEntry = entryItem.value
                    val currentFileName = oldEntry.file.name ?: "${oldEntry.note.title}.md"
                    val nameCollision = trashChildren.any { it.name.equals(currentFileName, ignoreCase = true) }
                    val targetTrashName = if (nameCollision) {
                        val base = currentFileName.removeSuffix(".md")
                        "${base}_${System.currentTimeMillis()}.md"
                    } else {
                        currentFileName
                    }
                    val moved = vaultManager.move(oldEntry.file, oldEntry.parentDir, trashDir)
                    if (moved != null) {
                        if (!moved.name.equals(targetTrashName, ignoreCase = true)) {
                            vaultManager.rename(moved, targetTrashName)
                        }
                        val newRel = "$TRASH_PREFIX$targetTrashName"
                        val existingMeta = getMetadataForPath(oldRel)
                        val meta = (existingMeta ?: NoteMetadata()).copy(
                            path = newRel,
                            isDeleted = true,
                            originalRelPath = oldRel,
                            trashFileName = targetTrashName
                        )
                        removeMetadataForPath(oldRel)
                        putMetadata(meta)
                        cache.remove(oldRel)
                        updateIdPath(oldRel, newRel)
                        foundPaths.remove(oldRel)
                        discoveredEntries.removeAll { it.relPath == oldRel }
                        metadataChanged = true
                    }
                }
            }

            // Reconcile in-app Trash view with physical .KOTHAPAD/trash/ folder
            val trashFiles = vaultManager.listChildren(trashDir).filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
            val trashRelPathsOnDisk = mutableSetOf<String>()
            for (tFile in trashFiles) {
                val tName = tFile.name ?: continue
                val trashRel = "$TRASH_PREFIX$tName"
                trashRelPathsOnDisk.add(trashRel)
                foundPaths.add(trashRel)

                val meta = getMetadataForPath(trashRel) ?: metadataByIdMap.values.firstOrNull { it.trashFileName == tName }
                val origRel = meta?.originalRelPath ?: tName
                val origFolder = if (origRel.contains("/")) origRel.substringBeforeLast("/") else GENERAL_DIR
                val baseTitle = meta?.originalRelPath?.substringAfterLast("/")?.removeSuffix(".md")
                    ?: tName.removeSuffix(".md").replace(Regex("_\\d{10,}$"), "")

                val effectiveMeta = (meta ?: NoteMetadata(
                    createdAt = tFile.lastModified(),
                    updatedAt = tFile.lastModified(),
                    modifiedAt = tFile.lastModified()
                )).copy(
                    path = trashRel,
                    isDeleted = true,
                    deletedAt = meta?.deletedAt ?: tFile.lastModified(),
                    originalRelPath = origRel,
                    trashFileName = tName
                )
                putMetadata(effectiveMeta)

                val id = getOrCreateIdForPath(trashRel)
                val content = if (meta != null && Math.abs(meta.modifiedAt - tFile.lastModified()) < 2000L) {
                    val existing = cache[trashRel]?.note?.content
                    if (!existing.isNullOrEmpty()) existing else (readContentFromLocalCache(effectiveMeta.id) ?: "")
                } else {
                    val text = vaultManager.readText(tFile)
                    saveContentToLocalCache(effectiveMeta.id, text)
                    text
                }
                val note = Note(
                    id = id,
                    title = baseTitle,
                    content = content,
                    createdAt = effectiveMeta.createdAt,
                    updatedAt = effectiveMeta.updatedAt,
                    isPinned = false,
                    languageCode = effectiveMeta.languageCode,
                    highlightColor = effectiveMeta.highlightColor,
                    fontSizeModifier = effectiveMeta.fontSizeModifier,
                    folder = origFolder,
                    tags = effectiveMeta.tags,
                    isArchived = false,
                    isDeleted = true,
                    deletedAt = effectiveMeta.deletedAt,
                    sessionDurationSec = effectiveMeta.timeSpent.coerceAtLeast(effectiveMeta.sessionDurationSec),
                    sessionCount = effectiveMeta.sessionCount
                )
                val entry = CachedNoteEntry(
                    note = note,
                    file = tFile,
                    parentDir = trashDir,
                    relPath = trashRel,
                    lastModified = tFile.lastModified()
                )
                cache[trashRel] = entry
            }

            // Clean up metadata for trashed notes whose files no longer exist in trash
            val deadTrashMeta = metadataByIdMap.values.filter { it.path.startsWith(TRASH_PREFIX) && !trashRelPathsOnDisk.contains(it.path) }
            for (d in deadTrashMeta) {
                removeMetadataForPath(d.path)
                cache.remove(d.path)
                removeIdForPath(d.path)
                metadataChanged = true
            }
        }

        // Remove cached items no longer on disk
        val removedKeys = cache.keys.filter { !foundPaths.contains(it) }
        for (r in removedKeys) {
            cache.remove(r)
            removeIdForPath(r)
        }

        // Entries in metadata whose files were not found on disk: mark as orphaned (keep for grace period)
        for ((metaId, entry) in metadataByIdMap) {
            if (!foundPaths.contains(entry.path) && !entry.path.startsWith(TRASH_PREFIX)) {
                if (!entry.isOrphaned) {
                    val orphaned = entry.copy(isOrphaned = true, orphanedAt = System.currentTimeMillis())
                    metadataByIdMap[metaId] = orphaned
                    metadataChanged = true
                } else {
                    // Grace period: keep for 30 days before permanently purging
                    val age = System.currentTimeMillis() - (entry.orphanedAt ?: System.currentTimeMillis())
                    if (age > 30L * 24 * 3600 * 1000) {
                        metadataByIdMap.remove(metaId)
                        pathIndex.remove(entry.path)
                        metadataChanged = true
                    }
                }
            }
        }

        // Sync discovered real folders to customFolders so they appear in Notes Manager
        try {
            val existingCustom: Set<String> = preferencesManager.customFolders.first()
            val mergedCustom: Set<String> = (existingCustom + discoveredFolders).filter {
                !it.equals(GENERAL_DIR, ignoreCase = true) && !it.equals(TEMPLATES_DIR, ignoreCase = true)
            }.toSet()
            if (mergedCustom != existingCustom) {
                preferencesManager.setCustomFolders(mergedCustom)
            }
        } catch (_: Exception) {}

        // Clean up any old placeholder stub if present
        val welcomeStub = cache.values.firstOrNull {
            it.note.title.equals("Welcome to KothaPad", ignoreCase = true) &&
            it.note.content.contains("Welcome to KothaPad") &&
            it.note.content.contains("Obsidian-compatible")
        }
        if (welcomeStub != null && cache.size <= 3) {
            try {
                deleteInternal(welcomeStub.note)
            } catch (_: Exception) {}
        }

        // Only seed tips notes if vault is completely empty (0 user notes)
        if (cache.isEmpty() && discoveredEntries.isEmpty()) {
            val tipsNote = Note(
                title = "⚡KothaPad Tips",
                content = com.example.data.db.AppDatabase.KOTHAPAD_TIPS_CONTENT,
                folder = GENERAL_DIR,
                isPinned = true,
                tags = "tips,guide,shortcuts,scenarios",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val createdId = insertInternal(tipsNote, vaultDoc)
            val createdNote = getNoteOnce(createdId)
            if (createdNote != null) {
                cache[getRelPathForNote(createdNote)]?.let { discoveredEntries.add(it) }
            }
        }

        // NO WRITE ON PURE OPEN: only save metadata if something actually changed!
        if (metadataChanged) {
            saveMetadata()
        }
        recomputeFlows()
    }

    private fun recomputeFlows() {
        val list = cache.values.map { it.note }.sortedWith(
            compareByDescending<Note> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
        _allNotes.value = list
        _activeNotes.value = list.filter { !it.isDeleted && !it.isArchived }
        _archivedNotes.value = list.filter { !it.isDeleted && it.isArchived }
        _trashedNotes.value = list.filter { it.isDeleted }
    }

    // =========================================================================
    // Metadata Persistence (.KOTHAPAD/notes-meta.json)
    // =========================================================================

    private suspend fun loadMetadata(vaultDoc: DocumentFile) = withContext(Dispatchers.IO) {
        try {
            metadataByIdMap.clear()
            pathIndex.clear()

            val root = vaultConfigStore.readNotesMeta(vaultDoc) ?: return@withContext
            val notesObj = root.optJSONObject("notes") ?: return@withContext

            val keys = notesObj.keys()
            while (keys.hasNext()) {
                val entryKey = keys.next()
                val obj = notesObj.optJSONObject(entryKey) ?: continue

                // Backward-compatible schema migration (schema v1 -> v2):
                // If entryKey is path or obj doesn't have "id", generate a stable UUID
                val noteId = if (obj.has("id") && !obj.isNull("id") && obj.optString("id").isNotBlank()) {
                    obj.optString("id")
                } else {
                    java.util.UUID.randomUUID().toString()
                }
                val relPath = if (obj.has("path") && !obj.isNull("path") && obj.optString("path").isNotBlank()) {
                    obj.optString("path")
                } else {
                    entryKey
                }
                val timeSpentVal = if (obj.has("timeSpent")) {
                    obj.optLong("timeSpent", 0L)
                } else {
                    obj.optLong("sessionDurationSec", 0L)
                }
                val rawCreatedAt = obj.optLong("createdAt", 0L)
                val rawUpdatedAt = obj.optLong("updatedAt", 0L)
                val rawModifiedAt = if (obj.has("modifiedAt")) obj.optLong("modifiedAt", rawUpdatedAt) else rawUpdatedAt

                val meta = NoteMetadata(
                    id = noteId,
                    path = relPath,
                    isPinned = obj.optBoolean("isPinned", false),
                    isArchived = obj.optBoolean("isArchived", false),
                    isDeleted = obj.optBoolean("isDeleted", false),
                    deletedAt = if (obj.has("deletedAt") && !obj.isNull("deletedAt")) obj.optLong("deletedAt") else null,
                    createdAt = rawCreatedAt,
                    updatedAt = rawUpdatedAt,
                    modifiedAt = rawModifiedAt,
                    sessionCount = obj.optInt("sessionCount", 0),
                    timeSpent = timeSpentVal,
                    sessionDurationSec = timeSpentVal,
                    wordCount = obj.optInt("wordCount", 0),
                    charCount = obj.optInt("charCount", 0),
                    fileSize = obj.optLong("fileSize", 0L),
                    contentHash = obj.optLong("contentHash", 0L),
                    languageCode = if (obj.has("languageCode") && !obj.isNull("languageCode")) obj.optString("languageCode") else null,
                    highlightColor = if (obj.has("highlightColor") && !obj.isNull("highlightColor")) obj.optString("highlightColor") else null,
                    fontSizeModifier = obj.optDouble("fontSizeModifier", 0.0).toFloat(),
                    tags = obj.optString("tags", ""),
                    originalRelPath = if (obj.has("originalRelPath") && !obj.isNull("originalRelPath")) obj.optString("originalRelPath") else null,
                    trashFileName = if (obj.has("trashFileName") && !obj.isNull("trashFileName")) obj.optString("trashFileName") else null,
                    isOrphaned = obj.optBoolean("isOrphaned", false),
                    orphanedAt = if (obj.has("orphanedAt") && !obj.isNull("orphanedAt")) obj.optLong("orphanedAt") else null
                )
                putMetadata(meta)
            }

            // INSTANT EMISSION: Populate in-memory cache directly from metadata & local content cache
            for ((_, meta) in metadataByIdMap) {
                if (meta.isOrphaned) continue
                val noteId = getOrCreateIdForPath(meta.path)
                val isTrash = meta.isDeleted || meta.path.startsWith(TRASH_PREFIX)
                val baseTitle = if (isTrash) {
                    meta.originalRelPath?.substringAfterLast("/")?.removeSuffix(".md")
                        ?: meta.trashFileName?.removeSuffix(".md")?.replace(Regex("_\\d{10,}$"), "")
                        ?: meta.path.substringAfterLast("/").removeSuffix(".md")
                } else {
                    meta.path.substringAfterLast("/").removeSuffix(".md")
                }
                val folderName = if (isTrash) {
                    val orig = meta.originalRelPath ?: ""
                    if (orig.contains("/")) orig.substringBeforeLast("/") else GENERAL_DIR
                } else {
                    if (meta.path.contains("/")) meta.path.substringBeforeLast("/") else GENERAL_DIR
                }
                val cachedContent = readContentFromLocalCache(meta.id) ?: ""
                val note = Note(
                    id = noteId,
                    title = baseTitle.ifBlank { "Untitled Note" },
                    content = cachedContent,
                    createdAt = meta.createdAt,
                    updatedAt = meta.updatedAt,
                    isPinned = meta.isPinned && !isTrash,
                    languageCode = meta.languageCode,
                    highlightColor = meta.highlightColor,
                    fontSizeModifier = meta.fontSizeModifier,
                    folder = folderName,
                    tags = meta.tags,
                    isArchived = meta.isArchived && !isTrash,
                    isDeleted = isTrash,
                    deletedAt = meta.deletedAt,
                    sessionDurationSec = meta.timeSpent.coerceAtLeast(meta.sessionDurationSec),
                    sessionCount = meta.sessionCount
                )
                val existingEntry = cache[meta.path]
                if (existingEntry != null) {
                    cache[meta.path] = existingEntry.copy(note = note, lastModified = meta.modifiedAt)
                } else {
                    cache[meta.path] = CachedNoteEntry(
                        note = note,
                        file = vaultDoc,
                        parentDir = vaultDoc,
                        relPath = meta.path,
                        lastModified = meta.modifiedAt
                    )
                }
            }
            recomputeFlows()
        } catch (_: Exception) {
            // Tolerant fallback
        }
    }

    suspend fun saveMetadata(immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        if (vaultConfigStore.isFileInSafeMode(NOTES_META_FILE)) {
            // Safe mode prevents overwriting corrupted file!
            return@withContext
        }
        try {
            val root = JSONObject()
            root.put("schemaVersion", META_SCHEMA_VERSION)
            val notesObj = JSONObject()
            for ((id, meta) in metadataByIdMap) {
                val obj = JSONObject()
                obj.put("id", meta.id)
                obj.put("path", meta.path)
                obj.put("isPinned", meta.isPinned)
                obj.put("isArchived", meta.isArchived)
                obj.put("isDeleted", meta.isDeleted)
                if (meta.deletedAt != null) obj.put("deletedAt", meta.deletedAt)
                obj.put("createdAt", meta.createdAt)
                obj.put("updatedAt", meta.updatedAt)
                obj.put("modifiedAt", meta.modifiedAt)
                obj.put("sessionCount", meta.sessionCount)
                obj.put("timeSpent", meta.timeSpent)
                obj.put("sessionDurationSec", meta.sessionDurationSec)
                obj.put("wordCount", meta.wordCount)
                obj.put("charCount", meta.charCount)
                obj.put("fileSize", meta.fileSize)
                obj.put("contentHash", meta.contentHash)
                if (meta.languageCode != null) obj.put("languageCode", meta.languageCode)
                if (meta.highlightColor != null) obj.put("highlightColor", meta.highlightColor)
                obj.put("fontSizeModifier", meta.fontSizeModifier.toDouble())
                obj.put("tags", meta.tags)
                if (meta.originalRelPath != null) obj.put("originalRelPath", meta.originalRelPath)
                if (meta.trashFileName != null) obj.put("trashFileName", meta.trashFileName)
                if (meta.isOrphaned) obj.put("isOrphaned", true)
                if (meta.orphanedAt != null) obj.put("orphanedAt", meta.orphanedAt)
                notesObj.put(id, obj)
            }
            root.put("notes", notesObj)
            vaultConfigStore.updateNotesMeta(vaultDoc, root, immediate)
        } catch (_: Exception) {
            // Non-blocking
        }
    }

    // =========================================================================
    // Workspace Persistence (.kothapad/workspace.json)
    // =========================================================================

    private suspend fun loadWorkspace(vaultDoc: DocumentFile) = withContext(Dispatchers.IO) {
        try {
            val root = vaultConfigStore.readWorkspace(vaultDoc) ?: return@withContext

            val lastNote = if (root.has("lastOpenedNoteRelPath") && !root.isNull("lastOpenedNoteRelPath")) {
                root.optString("lastOpenedNoteRelPath")
            } else null
            val lastFolder = root.optString("lastActiveFolder", GENERAL_DIR)

            val expSet = mutableSetOf<String>()
            val expArr = root.optJSONArray("expandedFolders")
            if (expArr != null) {
                for (i in 0 until expArr.length()) {
                    expSet.add(expArr.getString(i))
                }
            } else {
                expSet.addAll(listOf(GENERAL_DIR, TEMPLATES_DIR))
            }

            workspaceData = WorkspaceData(
                lastOpenedNoteRelPath = lastNote,
                lastActiveFolder = lastFolder,
                expandedFolders = expSet
            )
        } catch (_: Exception) {
            // Tolerant fallback
        }
    }

    suspend fun saveWorkspace(
        lastOpenedRelPath: String? = null,
        clearLastOpenedNote: Boolean = false,
        lastFolder: String? = null,
        expandedFolders: Set<String>? = null
    ) = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext

        val updatedLastNote = if (clearLastOpenedNote) null else (lastOpenedRelPath ?: workspaceData.lastOpenedNoteRelPath)
        val updatedLastFolder = lastFolder ?: workspaceData.lastActiveFolder
        val updatedExp = expandedFolders ?: workspaceData.expandedFolders

        workspaceData = WorkspaceData(
            lastOpenedNoteRelPath = updatedLastNote,
            lastActiveFolder = updatedLastFolder,
            expandedFolders = updatedExp
        )

        try {
            val root = JSONObject()
            root.put("schemaVersion", WORKSPACE_SCHEMA_VERSION)
            if (updatedLastNote != null) {
                root.put("lastOpenedNoteRelPath", updatedLastNote)
            } else {
                root.put("lastOpenedNoteRelPath", JSONObject.NULL)
            }
            root.put("lastActiveFolder", updatedLastFolder)
            val arr = JSONArray()
            for (f in updatedExp) {
                arr.put(f)
            }
            root.put("expandedFolders", arr)
            vaultConfigStore.updateWorkspace(vaultDoc, root, immediate = true)
        } catch (_: Exception) {
            // Non-blocking
        }
    }

    fun getWorkspaceData(): WorkspaceData = workspaceData

    // =========================================================================
    // NoteRepository Protocol Implementations (Routed to Vault)
    // =========================================================================

    fun getNote(id: Long): Flow<Note?> {
        return allNotes.map { notes -> notes.firstOrNull { it.id == id } }
    }

    suspend fun getNoteOnce(id: Long): Note? {
        return allNotes.value.firstOrNull { it.id == id }
    }

    suspend fun getLatestActiveNote(): Note? {
        val active = activeNotes.value
        return active.firstOrNull { it.isPinned } ?: active.firstOrNull()
    }

    suspend fun getLatestNote(): Note? {
        return allNotes.value.firstOrNull()
    }

    fun searchNotes(query: String): Flow<List<Note>> {
        val q = query.trim()
        return allNotes.map { list ->
            if (q.isEmpty()) list
            else list.filter { note ->
                note.title.contains(q, ignoreCase = true) ||
                        note.content.contains(q, ignoreCase = true) ||
                        note.tags.contains(q, ignoreCase = true) ||
                        note.folder.contains(q, ignoreCase = true)
            }
        }
    }

    suspend fun insert(note: Note): Long = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext 0L
        mutex.withLock {
            insertInternal(note, vaultDoc)
        }
    }

    private suspend fun insertInternal(note: Note, vaultDoc: DocumentFile): Long {
        val targetFolder = if (note.folder.isBlank()) GENERAL_DIR else note.folder.trim()
        val targetDir = vaultManager.getOrCreateDirRecursive(vaultDoc, targetFolder) ?: vaultDoc

        val sanitizedTitle = VaultManager.sanitizeFilename(note.title.ifBlank { "Untitled Note" })
        val finalFileName = vaultManager.resolveDuplicateFileName(targetDir, sanitizedTitle, ".md")
        val cleanTitle = finalFileName.removeSuffix(".md")

        val file = vaultManager.writeTextAtomic(
            parentDir = targetDir,
            fileName = finalFileName,
            text = note.content,
            mimeType = "text/markdown"
        ) ?: return 0L

        val relPath = if (targetFolder.isEmpty() || targetFolder == ".") finalFileName else "$targetFolder/$finalFileName"
        val id = getOrCreateIdForPath(relPath)

        val now = System.currentTimeMillis()
        val words = countWords(note.content)
        val chars = note.content.length
        val hash = note.content.hashCode().toLong()
        val fileSize = file.length()

        val meta = NoteMetadata(
            id = java.util.UUID.randomUUID().toString(),
            path = relPath,
            isPinned = note.isPinned,
            isArchived = note.isArchived,
            isDeleted = note.isDeleted,
            deletedAt = note.deletedAt,
            createdAt = if (note.createdAt > 0L) note.createdAt else now,
            updatedAt = now,
            modifiedAt = now,
            sessionCount = if (note.sessionCount > 0) note.sessionCount else 1,
            timeSpent = note.sessionDurationSec,
            sessionDurationSec = note.sessionDurationSec,
            wordCount = words,
            charCount = chars,
            fileSize = fileSize,
            contentHash = hash,
            languageCode = note.languageCode,
            highlightColor = note.highlightColor,
            fontSizeModifier = note.fontSizeModifier,
            tags = note.tags
        )
        putMetadata(meta)
        saveContentToLocalCache(meta.id, note.content)

        val createdNote = note.copy(
            id = id,
            title = cleanTitle,
            folder = targetFolder,
            updatedAt = meta.updatedAt,
            sessionCount = meta.sessionCount
        )
        val entry = CachedNoteEntry(
            note = createdNote,
            file = file,
            parentDir = targetDir,
            relPath = relPath,
            lastModified = file.lastModified()
        )
        cache[relPath] = entry

        saveMetadata()
        saveWorkspace(lastOpenedRelPath = relPath, lastFolder = targetFolder)
        recomputeFlows()
        return id
    }

    suspend fun insertAll(notes: List<Note>): List<Long> = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext emptyList()
        val ids = mutableListOf<Long>()
        mutex.withLock {
            for (n in notes) {
                ids.add(insertInternal(n, vaultDoc))
            }
        }
        ids
    }

    suspend fun update(note: Note) = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        mutex.withLock {
            updateInternal(note, vaultDoc)
        }
    }

    private suspend fun updateInternal(note: Note, vaultDoc: DocumentFile) {
        val oldRelPath = idToPathMap[note.id]
        var entry = oldRelPath?.let { cache[it] }

        // If not in cache by ID mapping, search cache values by note.id
        if (entry == null) {
            entry = cache.values.firstOrNull { it.note.id == note.id }
        }

        // If not in cache by ID, search by title and folder
        if (entry == null) {
            val guessedRel1 = "${note.folder}/${note.title}.md".trimStart('/')
            val guessedRel2 = "${note.folder}/${note.title}".trimStart('/')
            val guessedRel3 = if (note.folder.equals(GENERAL_DIR, ignoreCase = true)) "${note.title}.md" else null
            entry = cache[guessedRel1] ?: cache[guessedRel2] ?: (guessedRel3?.let { cache[it] })
        }

        if (entry == null) {
            entry = cache.values.firstOrNull {
                it.note.title.equals(note.title, ignoreCase = true) &&
                it.note.folder.equals(note.folder, ignoreCase = true)
            }
        }

        if (entry == null) {
            if (note.isDeleted) {
                // If the note was already removed or cannot be found on disk, do NOT recreate or insert a duplicate file!
                return
            }
            // If completely missing, treat as insert
            insertInternal(note, vaultDoc)
            return
        }

        var currentFile = entry.file
        var currentParent = entry.parentDir
        var currentRelPath = entry.relPath
        val targetFolder = note.folder.trim().ifBlank { GENERAL_DIR }

        // Handle Physical Trashing
        if (note.isDeleted && (!entry.note.isDeleted || !currentRelPath.startsWith(TRASH_PREFIX))) {
            val trashDir = vaultConfigStore.getOrCreateTrashDir(vaultDoc)
            if (trashDir != null) {
                val currentFileName = currentFile.name ?: "${VaultManager.sanitizeFilename(note.title)}.md"
                val trashChildren = vaultManager.listChildren(trashDir)
                val nameCollision = trashChildren.any { it.name.equals(currentFileName, ignoreCase = true) }
                val targetTrashName = if (nameCollision) {
                    val base = currentFileName.removeSuffix(".md")
                    "${base}_${System.currentTimeMillis()}.md"
                } else {
                    currentFileName
                }

                val movedFile = vaultManager.move(currentFile, currentParent, trashDir)
                if (movedFile != null) {
                    if (!movedFile.name.equals(targetTrashName, ignoreCase = true)) {
                        vaultManager.rename(movedFile, targetTrashName)
                    }
                    val newTrashRelPath = "$TRASH_PREFIX$targetTrashName"
                    val now = note.deletedAt ?: System.currentTimeMillis()
                    val existingMeta = getMetadataForPath(currentRelPath)
                    val meta = (existingMeta ?: NoteMetadata()).copy(
                        path = newTrashRelPath,
                        isPinned = false,
                        isArchived = false,
                        isDeleted = true,
                        deletedAt = now,
                        createdAt = if (existingMeta != null && existingMeta.createdAt > 0L) existingMeta.createdAt else note.createdAt,
                        updatedAt = now,
                        modifiedAt = now,
                        sessionCount = existingMeta?.sessionCount ?: note.sessionCount,
                        timeSpent = note.sessionDurationSec,
                        sessionDurationSec = note.sessionDurationSec,
                        languageCode = note.languageCode,
                        highlightColor = note.highlightColor,
                        fontSizeModifier = note.fontSizeModifier,
                        tags = note.tags,
                        originalRelPath = currentRelPath,
                        trashFileName = targetTrashName
                    )
                    removeMetadataForPath(currentRelPath)
                    putMetadata(meta)
                    cache.remove(currentRelPath)
                    updateIdPath(currentRelPath, newTrashRelPath)

                    val updatedTrashedNote = note.copy(
                        id = entry.note.id,
                        title = note.title,
                        folder = note.folder,
                        isDeleted = true,
                        deletedAt = now,
                        isPinned = false,
                        updatedAt = now,
                        sessionCount = meta.sessionCount
                    )
                    cache[newTrashRelPath] = CachedNoteEntry(
                        note = updatedTrashedNote,
                        file = movedFile,
                        parentDir = trashDir,
                        relPath = newTrashRelPath,
                        lastModified = movedFile.lastModified()
                    )

                    if (workspaceData.lastOpenedNoteRelPath == currentRelPath) {
                        saveWorkspace(clearLastOpenedNote = true)
                    }
                    saveMetadata()
                    recomputeFlows()
                    return
                }
            }
        }

        // Handle Physical Restoring from Trash
        if (!note.isDeleted && (entry.note.isDeleted || currentRelPath.startsWith(TRASH_PREFIX))) {
            val meta = getMetadataForPath(currentRelPath) ?: metadataByIdMap.values.firstOrNull { it.trashFileName == currentFile.name }
            val originalRel = meta?.originalRelPath ?: "${note.folder}/${note.title}.md".trimStart('/')
            val destFolder = if (originalRel.contains("/")) originalRel.substringBeforeLast("/") else (if (note.folder.isNotBlank()) note.folder else GENERAL_DIR)
            val baseName = originalRel.substringAfterLast("/").removeSuffix(".md").ifBlank { note.title.ifBlank { "Restored Note" } }

            val targetDir = vaultManager.getOrCreateDirRecursive(vaultDoc, destFolder) ?: vaultDoc
            val uniqueName = vaultManager.resolveDuplicateFileName(targetDir, baseName, ".md")

            val movedFile = vaultManager.move(currentFile, currentParent, targetDir)
            if (movedFile != null) {
                if (!movedFile.name.equals(uniqueName, ignoreCase = true)) {
                    vaultManager.rename(movedFile, uniqueName)
                }
                val cleanRestoredTitle = uniqueName.removeSuffix(".md")
                val newRelPath = if (destFolder.isEmpty() || destFolder == ".") uniqueName else "$destFolder/$uniqueName"
                val now = System.currentTimeMillis()
                val restoredMeta = (meta ?: NoteMetadata()).copy(
                    path = newRelPath,
                    isPinned = note.isPinned,
                    isArchived = note.isArchived,
                    isDeleted = false,
                    deletedAt = null,
                    createdAt = if (meta != null && meta.createdAt > 0L) meta.createdAt else note.createdAt,
                    updatedAt = now,
                    modifiedAt = now,
                    sessionCount = meta?.sessionCount ?: note.sessionCount,
                    timeSpent = note.sessionDurationSec,
                    sessionDurationSec = note.sessionDurationSec,
                    languageCode = note.languageCode,
                    highlightColor = note.highlightColor,
                    fontSizeModifier = note.fontSizeModifier,
                    tags = note.tags,
                    originalRelPath = null,
                    trashFileName = null,
                    isOrphaned = false,
                    orphanedAt = null
                )
                removeMetadataForPath(currentRelPath)
                putMetadata(restoredMeta)
                cache.remove(currentRelPath)
                updateIdPath(currentRelPath, newRelPath)

                val restoredNote = note.copy(
                    id = entry.note.id,
                    title = cleanRestoredTitle,
                    folder = destFolder,
                    isDeleted = false,
                    deletedAt = null,
                    updatedAt = now,
                    sessionCount = restoredMeta.sessionCount
                )
                cache[newRelPath] = CachedNoteEntry(
                    note = restoredNote,
                    file = movedFile,
                    parentDir = targetDir,
                    relPath = newRelPath,
                    lastModified = movedFile.lastModified()
                )
                saveMetadata()
                recomputeFlows()
                return
            }
        }

        // 1. Check folder change (Move)
        val existingFolder = if (currentRelPath.contains("/")) currentRelPath.substringBeforeLast("/") else GENERAL_DIR
        if (!existingFolder.equals(targetFolder, ignoreCase = true)) {
            val newParentDir = vaultManager.getOrCreateDirRecursive(vaultDoc, targetFolder)
            if (newParentDir != null) {
                val movedFile = vaultManager.move(currentFile, currentParent, newParentDir)
                if (movedFile != null) {
                    currentFile = movedFile
                    currentParent = newParentDir
                    val fileName = movedFile.name ?: "${note.title}.md"
                    val newRel = "$targetFolder/$fileName"
                    getMetadataForPath(currentRelPath)?.let {
                        removeMetadataForPath(currentRelPath)
                        putMetadata(it.copy(path = newRel))
                    }
                    cache.remove(currentRelPath)
                    updateIdPath(currentRelPath, newRel)
                    currentRelPath = newRel
                }
            }
        }

        // 2. Check title change (Rename)
        val expectedFileName = "${VaultManager.sanitizeFilename(note.title)}.md"
        if (currentFile.name != null && !currentFile.name.equals(expectedFileName, ignoreCase = true)) {
            val uniqueName = vaultManager.resolveDuplicateFileName(currentParent, note.title, ".md")
            val renamed = vaultManager.rename(currentFile, uniqueName)
            if (renamed) {
                val parentPath = if (currentRelPath.contains("/")) currentRelPath.substringBeforeLast("/") else ""
                val newRel = if (parentPath.isEmpty()) uniqueName else "$parentPath/$uniqueName"
                getMetadataForPath(currentRelPath)?.let {
                    removeMetadataForPath(currentRelPath)
                    putMetadata(it.copy(path = newRel))
                }
                cache.remove(currentRelPath)
                updateIdPath(currentRelPath, newRel)
                currentRelPath = newRel
            }
        }

        // Keep workspace.json in sync if this note was the last opened note or folder changed
        if (entry.relPath != currentRelPath) {
            if (workspaceData.lastOpenedNoteRelPath == entry.relPath) {
                saveWorkspace(lastOpenedRelPath = currentRelPath, lastFolder = targetFolder)
            }
        }

        // 3. Write markdown content atomically if changed
        if (entry.note.content != note.content) {
            val fileName = currentFile.name ?: "${VaultManager.sanitizeFilename(note.title)}.md"
            val writtenFile = vaultManager.writeTextAtomic(
                parentDir = currentParent,
                fileName = fileName,
                text = note.content,
                mimeType = "text/markdown"
            )
            if (writtenFile != null) {
                currentFile = writtenFile
            } else {
                _lastSaveError.value = "Failed to write note to disk"
            }
        }

        // 4. Update metadata in notes-meta.json
        val now = System.currentTimeMillis()
        val existingMeta = getMetadataForPath(currentRelPath)
        val words = countWords(note.content)
        val chars = note.content.length
        val hash = note.content.hashCode().toLong()
        val fileSize = currentFile.length()

        val meta = (existingMeta ?: NoteMetadata(
            id = java.util.UUID.randomUUID().toString(),
            path = currentRelPath,
            createdAt = note.createdAt,
            sessionCount = note.sessionCount
        )).copy(
            path = currentRelPath,
            isPinned = note.isPinned,
            isArchived = note.isArchived,
            isDeleted = note.isDeleted,
            deletedAt = note.deletedAt,
            createdAt = if (existingMeta != null && existingMeta.createdAt > 0L) existingMeta.createdAt else note.createdAt,
            updatedAt = now,
            modifiedAt = now,
            sessionCount = existingMeta?.sessionCount ?: note.sessionCount,
            timeSpent = note.sessionDurationSec,
            sessionDurationSec = note.sessionDurationSec,
            wordCount = words,
            charCount = chars,
            fileSize = fileSize,
            contentHash = hash,
            languageCode = note.languageCode,
            highlightColor = note.highlightColor,
            fontSizeModifier = note.fontSizeModifier,
            tags = note.tags,
            isOrphaned = false,
            orphanedAt = null
        )
        putMetadata(meta)
        saveContentToLocalCache(meta.id, note.content)

        val updatedNote = note.copy(
            id = entry.note.id,
            title = currentFile.name?.removeSuffix(".md") ?: note.title,
            folder = targetFolder,
            updatedAt = now,
            sessionCount = meta.sessionCount
        )
        val newEntry = CachedNoteEntry(
            note = updatedNote,
            file = currentFile,
            parentDir = currentParent,
            relPath = currentRelPath,
            lastModified = currentFile.lastModified()
        )
        cache[currentRelPath] = newEntry

        saveMetadata()
        recomputeFlows()
    }

    suspend fun updateAll(notes: List<Note>) = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        mutex.withLock {
            for (n in notes) {
                updateInternal(n, vaultDoc)
            }
        }
    }

    suspend fun delete(note: Note) = withContext(Dispatchers.IO) {
        mutex.withLock {
            deleteInternal(note)
        }
    }

    private suspend fun deleteInternal(note: Note) {
        val relPath = idToPathMap[note.id] ?: cache.entries.firstOrNull { it.value.note.id == note.id }?.key
        if (relPath != null) {
            val entry = cache[relPath]
            if (entry != null) {
                vaultManager.delete(entry.file)
                cache.remove(relPath)
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

    suspend fun deleteAll(notes: List<Note>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (n in notes) {
                deleteInternal(n)
            }
        }
    }

    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        val note = getNoteOnce(id) ?: return@withContext
        delete(note)
    }

    suspend fun getAllNotesList(): List<Note> {
        return allNotes.value
    }

    suspend fun deleteAllNotes(): Int = withContext(Dispatchers.IO) {
        val list = allNotes.value
        deleteAll(list)
        list.size
    }

    suspend fun autoCleanTrash(olderThanTimestamp: Long): Int = withContext(Dispatchers.IO) {
        val toClean = trashedNotes.value.filter {
            val delAt = it.deletedAt
            delAt != null && delAt < olderThanTimestamp
        }
        deleteAll(toClean)
        toClean.size
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val trashed = trashedNotes.value
        val count = trashed.size
        mutex.withLock {
            for (n in trashed) {
                deleteInternal(n)
            }
            val vaultDoc = currentVaultDoc
            if (vaultDoc != null) {
                val trashDir = vaultConfigStore.getTrashDir(vaultDoc)
                if (trashDir != null && trashDir.exists()) {
                    for (f in vaultManager.listChildren(trashDir)) {
                        vaultManager.delete(f)
                    }
                }
            }
            val remainingTrash = metadataByIdMap.values.filter { it.path.startsWith(TRASH_PREFIX) }
            for (r in remainingTrash) {
                removeMetadataForPath(r.path)
                cache.remove(r.path)
                removeIdForPath(r.path)
            }
            saveMetadata()
            recomputeFlows()
        }
        count
    }

    // =========================================================================
    // Folder Operations on Disk
    // =========================================================================

    suspend fun createFolderOnDisk(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext false
        val dir = vaultManager.getOrCreateDirRecursive(vaultDoc, folderPath)
        dir != null
    }

    suspend fun renameFolderOnDisk(oldFolder: String, newFolder: String): Boolean = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext false
        val oldDir = vaultManager.findDirRecursive(vaultDoc, oldFolder) ?: return@withContext false
        val newClean = VaultManager.sanitizeFilename(newFolder.substringAfterLast("/"))
        val renamed = vaultManager.rename(oldDir, newClean)
        if (renamed) {
            // Re-index vault so all paths and metadata update smoothly
            scanVaultRecursive(vaultDoc)
        }
        renamed
    }

    suspend fun deleteFolderOnDisk(folder: String, deleteContainedNotes: Boolean): Boolean = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext false
        val dir = vaultManager.findDirRecursive(vaultDoc, folder) ?: return@withContext false
        if (!deleteContainedNotes) {
            // Move contained notes to General first
            val generalDir = vaultManager.getOrCreateDirRecursive(vaultDoc, GENERAL_DIR) ?: vaultDoc
            val children = vaultManager.listChildren(dir)
            for (ch in children) {
                if (ch.isFile && ch.name?.endsWith(".md") == true) {
                    vaultManager.move(ch, dir, generalDir)
                }
            }
        }
        val deleted = vaultManager.delete(dir)
        if (deleted) {
            scanVaultRecursive(vaultDoc)
        }
        deleted
    }

    // =========================================================================
    // Template Synchronization (<vault>/Templates/<template>.md)
    // =========================================================================

    suspend fun saveTemplateFile(template: Stamp): Boolean = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext false
        val templatesDir = vaultManager.getOrCreateDirRecursive(vaultDoc, TEMPLATES_DIR) ?: return@withContext false
        val cleanLabel = VaultManager.sanitizeFilename(template.label.ifBlank { "Template" })
        val fileName = "$cleanLabel.md"
        val written = vaultManager.writeTextAtomic(templatesDir, fileName, template.templateText, "text/markdown")
        written != null
    }

    suspend fun deleteTemplateFile(template: Stamp): Boolean = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext false
        val templatesDir = vaultManager.findDirRecursive(vaultDoc, TEMPLATES_DIR) ?: return@withContext false
        val fileName = "${VaultManager.sanitizeFilename(template.label)}.md"
        val file = templatesDir.findFile(fileName) ?: return@withContext false
        vaultManager.delete(file)
    }

    suspend fun syncTemplatesFromVault(
        existingTemplates: List<Stamp>,
        onNewTemplateDiscovered: suspend (Stamp) -> Unit
    ) = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        val templatesDir = vaultManager.findDirRecursive(vaultDoc, TEMPLATES_DIR) ?: return@withContext
        val files = vaultManager.listChildren(templatesDir)
        var order = existingTemplates.size
        for (f in files) {
            if (f.isFile && f.name?.endsWith(".md") == true) {
                val label = f.name!!.removeSuffix(".md")
                if (existingTemplates.none { it.label.equals(label, ignoreCase = true) }) {
                    val content = vaultManager.readText(f)
                    val stamp = Stamp(
                        label = label,
                        templateText = content,
                        orderIndex = order++,
                        isPinned = false
                    )
                    onNewTemplateDiscovered(stamp)
                }
            }
        }
        // Bidirectional sync: make sure existing DB templates exist on disk
        val existingFileNames = files.mapNotNull { it.name }.toSet()
        for (st in existingTemplates) {
            val fileName = "${VaultManager.sanitizeFilename(st.label)}.md"
            if (!existingFileNames.contains(fileName)) {
                saveTemplateFile(st)
            }
        }
    }

    fun getRelPathForNote(note: Note): String {
        val cached = idToPathMap[note.id]
        if (cached != null) return cached
        val path1 = "${note.folder}/${note.title}.md".trimStart('/')
        if (cache.containsKey(path1)) return path1
        val path2 = "${note.title}.md"
        if (cache.containsKey(path2)) return path2
        return path1
    }
}
