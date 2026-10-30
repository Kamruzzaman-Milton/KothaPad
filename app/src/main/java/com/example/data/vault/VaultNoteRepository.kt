package com.example.data.vault

import android.content.Context
import android.provider.DocumentsContract
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
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
    val orphanedAt: Long? = null,
    val statsStale: Boolean = false
)

class NoteBodyLruCache(
    private val maxCount: Int = 20,
    private val maxSizeBytes: Int = 2 * 1024 * 1024 // 2MB
) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, String>(maxCount, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxCount || currentBytes > maxSizeBytes
        }
    }
    private var currentBytes = 0

    fun get(key: String): String? = synchronized(lock) {
        map[key]
    }

    fun put(key: String, content: String) = synchronized(lock) {
        val old = map.remove(key)
        if (old != null) {
            currentBytes -= old.length * 2
        }
        map[key] = content
        currentBytes += content.length * 2
        while (currentBytes > maxSizeBytes && map.isNotEmpty()) {
            val iterator = map.entries.iterator()
            if (iterator.hasNext()) {
                val next = iterator.next()
                currentBytes -= next.value.length * 2
                iterator.remove()
            } else {
                break
            }
        }
    }

    fun remove(key: String) = synchronized(lock) {
        val old = map.remove(key)
        if (old != null) {
            currentBytes -= old.length * 2
        }
    }

    fun clear() = synchronized(lock) {
        map.clear()
        currentBytes = 0
    }
}

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
        const val DAILY_JOURNAL_DIR = "Daily Journal"
        const val DAILY_JOURNALS_DIR = "Daily Journal"
        const val TRASH_DIR = "Trash"
        const val TRASH_PREFIX = "Trash/"
        private const val META_SCHEMA_VERSION = 2
        private const val WORKSPACE_SCHEMA_VERSION = 1

        fun isDailyJournalNote(note: Note): Boolean {
            val f = note.folder.trim()
            if (f.equals(DAILY_JOURNAL_DIR, ignoreCase = true) || f.equals("Daily Journals", ignoreCase = true)) {
                return true
            }
            val title = note.title.trim()
            return Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(title)
        }
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

    private val noteBodyLruCache = NoteBodyLruCache()
    private var statsWorkerJob: Job? = null

    fun getCachedBody(noteId: Long): String? {
        val path = idToPathMap[noteId] ?: return null
        return noteBodyLruCache.get(path)
    }

    fun getCachedBodyForPath(relPath: String): String? {
        return noteBodyLruCache.get(relPath)
    }

    suspend fun readNoteBody(relPath: String, entry: CachedNoteEntry?, meta: NoteMetadata?): String = withContext(Dispatchers.IO) {
        val cached = noteBodyLruCache.get(relPath)
        if (cached != null) return@withContext cached
        val localCached = readContentFromLocalCache(meta?.id ?: "")
        if (!localCached.isNullOrEmpty()) {
            noteBodyLruCache.put(relPath, localCached)
            return@withContext localCached
        }
        val file = entry?.file
        val text = if (file != null && file.exists()) {
            vaultManager.readText(file)
        } else {
            val vaultDoc = currentVaultDoc ?: return@withContext ""
            val f = vaultManager.findFileRecursive(vaultDoc, relPath)
            if (f != null) vaultManager.readText(f) else ""
        }
        noteBodyLruCache.put(relPath, text)
        if (meta != null && text.isNotEmpty()) {
            saveContentToLocalCache(meta.id, text)
        }
        text
    }

    suspend fun ensureNoteContentLoaded(note: Note): Note = withContext(Dispatchers.IO) {
        if (note.content.isNotEmpty()) {
            val relPath = idToPathMap[note.id]
            if (relPath != null) {
                noteBodyLruCache.put(relPath, note.content)
            }
            return@withContext note
        }
        val relPath = idToPathMap[note.id] ?: return@withContext note
        val meta = getMetadataForPath(relPath)
        val entry = cache[relPath]
        val text = readNoteBody(relPath, entry, meta)
        val words = if (meta != null && !meta.statsStale && meta.wordCount > 0) meta.wordCount else countWords(text)
        val chars = if (meta != null && !meta.statsStale && meta.charCount > 0) meta.charCount else text.length
        val hash = if (meta != null && !meta.statsStale && meta.contentHash != 0L) meta.contentHash else (if (text.isNotEmpty()) text.hashCode().toLong() else 0L)

        if (meta != null && meta.statsStale) {
            val updatedMeta = meta.copy(
                wordCount = words,
                charCount = chars,
                contentHash = hash,
                statsStale = false
            )
            putMetadata(updatedMeta)
        }

        val updated = note.copy(
            content = text,
            wordCount = words,
            charCount = chars,
            statsStale = false
        )
        if (entry != null) {
            cache[relPath] = entry.copy(note = updated)
        }
        updated
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

        // Ensure the 3 default vault folders exist: Daily Journal, Templates, and Trash
        getOrCreateDailyJournalDir(vaultDoc)
        vaultManager.getOrCreateDirRecursive(vaultDoc, TEMPLATES_DIR)
        vaultManager.getOrCreateDirRecursive(vaultDoc, TRASH_DIR)

        // Load metadata & workspace via VaultConfigStore before scanning
        // This IMMEDIATELY populates activeNotes and allNotes flows in memory
        loadMetadata(vaultDoc)
        loadWorkspace(vaultDoc)

        // Clean up or consolidate any stray markdown files accidentally saved in vault root
        consolidateStrayRootFiles(vaultDoc)

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
        consolidateStrayRootFiles(vaultDoc)
        startVaultScan(vaultDoc)
    }

    suspend fun getOrCreateDailyJournalDir(vaultDoc: DocumentFile): DocumentFile? = withContext(Dispatchers.IO) {
        val treeUri = vaultManager.getVaultTreeUri()
        var dailyDir = vaultManager.findDirRecursive(vaultDoc, DAILY_JOURNAL_DIR)
        val legacyDailyDir = vaultManager.findDirRecursive(vaultDoc, "Daily Journals")

        if (legacyDailyDir != null) {
            if (dailyDir == null) {
                val renamed = vaultManager.rename(legacyDailyDir, DAILY_JOURNAL_DIR)
                dailyDir = if (renamed) vaultManager.findDirRecursive(vaultDoc, DAILY_JOURNAL_DIR) else legacyDailyDir
            } else if (dailyDir.uri != legacyDailyDir.uri) {
                // Both exist: migrate all files from legacy into dailyDir
                val legacyChildren = if (treeUri != null) vaultManager.listChildrenFast(legacyDailyDir, treeUri) else emptyList()
                for (child in legacyChildren) {
                    if (child.isFile) {
                        val childDoc = child.toDocumentFile(context, treeUri)
                        if (childDoc != null) {
                            val existingInDaily = vaultManager.findChildFast(dailyDir, child.displayName, treeUri)
                            if (existingInDaily != null) {
                                val existingDoc = existingInDaily.toDocumentFile(context, treeUri)
                                if (existingDoc != null && existingDoc.length() == 0L && childDoc.length() > 0L) {
                                    vaultManager.delete(existingDoc)
                                    vaultManager.move(childDoc, legacyDailyDir, dailyDir)
                                } else {
                                    vaultManager.delete(childDoc)
                                }
                            } else {
                                vaultManager.move(childDoc, legacyDailyDir, dailyDir)
                            }
                        }
                    }
                }
                vaultManager.delete(legacyDailyDir)
            }
        }

        if (dailyDir == null) {
            dailyDir = vaultManager.getOrCreateDirRecursive(vaultDoc, DAILY_JOURNAL_DIR)
        }
        dailyDir
    }

    suspend fun consolidateStrayRootFiles(vaultDoc: DocumentFile) = withContext(Dispatchers.IO) {
        try {
            val treeUri = vaultManager.getVaultTreeUri() ?: vaultDoc.uri
            val rootChildren = vaultManager.listChildrenFast(vaultDoc)
            val rootMdFiles = rootChildren.filter { it.isFile && it.displayName.endsWith(".md", ignoreCase = true) }
            val dailyJournalRegex = Regex("^\\d{4}-\\d{2}-\\d{2}", RegexOption.IGNORE_CASE)

            var metadataModified = false
            val dailyDir = getOrCreateDailyJournalDir(vaultDoc)

            // 1. Process stray files in vault root
            for (fileEntry in rootMdFiles) {
                val fileName = fileEntry.displayName
                val rootFileDoc = fileEntry.toDocumentFile(context, treeUri) ?: continue

                // Check 1: Daily Journal notes in root
                if (dailyJournalRegex.containsMatchIn(fileName)) {
                    if (dailyDir != null) {
                        val dailyChildren = vaultManager.listChildrenFast(dailyDir, treeUri)
                        val duplicateInDaily = dailyChildren.firstOrNull { it.isFile && it.displayName.equals(fileName, ignoreCase = true) }
                        if (duplicateInDaily != null) {
                            val dailyDoc = duplicateInDaily.toDocumentFile(context, treeUri)
                            if (dailyDoc != null && dailyDoc.length() == 0L && rootFileDoc.length() > 0L) {
                                vaultManager.delete(dailyDoc)
                                vaultManager.move(rootFileDoc, vaultDoc, dailyDir)
                            } else {
                                vaultManager.delete(rootFileDoc)
                            }
                        } else {
                            vaultManager.move(rootFileDoc, vaultDoc, dailyDir)
                        }
                        removeMetadataForPath(fileName)
                        cache.remove(fileName)
                        removeIdForPath(fileName)
                        metadataModified = true
                    }
                    continue
                }

                // Check 2: Purge any legacy tips or features notes from root (now displayed in Settings -> About Notepad)
                if (fileName.contains("KothaPad features", ignoreCase = true) ||
                    fileName.contains("KothaPad Tips", ignoreCase = true) ||
                    fileName.contains("Notepad Features", ignoreCase = true) ||
                    fileName.contains("Notepad Tips", ignoreCase = true)) {
                    vaultManager.delete(rootFileDoc)
                    removeMetadataForPath(fileName)
                    cache.remove(fileName)
                    removeIdForPath(fileName)
                    metadataModified = true
                    continue
                }

                // Check 3: Check against metadata: if metadata says this note belongs to a folder
                val meta = getMetadataForPath(fileName)
                    ?: metadataByIdMap.values.firstOrNull { it.path.endsWith("/$fileName", ignoreCase = true) }
                if (meta != null && meta.path.contains("/")) {
                    val destFolder = meta.path.substringBeforeLast("/")
                    val targetDir = vaultManager.getOrCreateDirRecursive(vaultDoc, destFolder)
                    if (targetDir != null) {
                        val folderChildren = vaultManager.listChildrenFast(targetDir, treeUri)
                        val duplicateInFolder = folderChildren.firstOrNull { it.isFile && it.displayName.equals(fileName, ignoreCase = true) }
                        if (duplicateInFolder != null) {
                            vaultManager.delete(rootFileDoc)
                        } else {
                            vaultManager.move(rootFileDoc, vaultDoc, targetDir)
                        }
                        removeMetadataForPath(fileName)
                        cache.remove(fileName)
                        removeIdForPath(fileName)
                        metadataModified = true
                    }
                }
            }

            // 2. Also check General folder for any misplaced Daily Journal files or legacy tips
            val generalDir = vaultManager.findDirRecursive(vaultDoc, GENERAL_DIR)
            if (generalDir != null) {
                val generalChildren = vaultManager.listChildrenFast(generalDir, treeUri)
                for (gFile in generalChildren) {
                    val gName = gFile.displayName
                    val gRelPath = "$GENERAL_DIR/$gName"
                    val gDoc = gFile.toDocumentFile(context, treeUri) ?: continue
                    if (gName.contains("KothaPad features", ignoreCase = true) ||
                        gName.contains("KothaPad Tips", ignoreCase = true) ||
                        gName.contains("Notepad Features", ignoreCase = true) ||
                        gName.contains("Notepad Tips", ignoreCase = true)) {
                        vaultManager.delete(gDoc)
                        removeMetadataForPath(gRelPath)
                        cache.remove(gRelPath)
                        removeIdForPath(gRelPath)
                        metadataModified = true
                    } else if (dailyJournalRegex.containsMatchIn(gName) && dailyDir != null) {
                        val dailyChildren = vaultManager.listChildrenFast(dailyDir, treeUri)
                        val duplicateInDaily = dailyChildren.firstOrNull { it.isFile && it.displayName.equals(gName, ignoreCase = true) }
                        if (duplicateInDaily != null) {
                            val dailyDoc = duplicateInDaily.toDocumentFile(context, treeUri)
                            if (dailyDoc != null && dailyDoc.length() == 0L && gDoc.length() > 0L) {
                                vaultManager.delete(dailyDoc)
                                vaultManager.move(gDoc, generalDir, dailyDir)
                            } else {
                                vaultManager.delete(gDoc)
                            }
                        } else {
                            vaultManager.move(gDoc, generalDir, dailyDir)
                        }
                        removeMetadataForPath(gRelPath)
                        cache.remove(gRelPath)
                        removeIdForPath(gRelPath)
                        metadataModified = true
                    }
                }
                // If General folder is now empty, delete it to keep vault clean with only 3 default folders
                val remainingGeneral = vaultManager.listChildrenFast(generalDir, treeUri)
                if (remainingGeneral.isEmpty()) {
                    vaultManager.delete(generalDir)
                }
            }

            // 2b. Migrate any legacy files from .KOTHAPAD/trash to top-level Trash/ folder
            val legacyTrashDir = vaultConfigStore.getTrashDir(vaultDoc)
            if (legacyTrashDir != null && legacyTrashDir.exists()) {
                val topTrashDir = vaultManager.getOrCreateDirRecursive(vaultDoc, TRASH_DIR)
                if (topTrashDir != null) {
                    val legacyTrashFiles = vaultManager.listChildrenFast(legacyTrashDir, treeUri)
                    for (ltFile in legacyTrashFiles) {
                        val ltDoc = ltFile.toDocumentFile(context, treeUri) ?: continue
                        val oldLegacyRel = ".KOTHAPAD/trash/${ltFile.displayName}"
                        val newTrashRel = "$TRASH_PREFIX${ltFile.displayName}"
                        vaultManager.move(ltDoc, legacyTrashDir, topTrashDir)
                        val oldMeta = getMetadataForPath(oldLegacyRel)
                        if (oldMeta != null) {
                            removeMetadataForPath(oldLegacyRel)
                            putMetadata(oldMeta.copy(path = newTrashRel))
                            updateIdPath(oldLegacyRel, newTrashRel)
                        }
                        metadataModified = true
                    }
                    val checkEmpty = vaultManager.listChildrenFast(legacyTrashDir, treeUri)
                    if (checkEmpty.isEmpty()) {
                        vaultManager.delete(legacyTrashDir)
                    }
                }
            }

            // 3. Purge duplicate numbered files inside Daily Journal folder itself
            if (dailyDir != null) {
                val dailyChildren = vaultManager.listChildrenFast(dailyDir, treeUri)
                val dailyFiles = dailyChildren.filter { it.isFile && it.displayName.endsWith(".md", ignoreCase = true) }
                val dateGroupRegex = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:[ _](\\d+)|\\s*\\((\\d+)\\))?\\.md$", RegexOption.IGNORE_CASE)

                val groupedByDate = dailyFiles.groupBy { fileEntry ->
                    val match = dateGroupRegex.find(fileEntry.displayName)
                    match?.groupValues?.get(1)
                }

                for ((datePrefix, files) in groupedByDate) {
                    if (datePrefix == null || files.size <= 1) continue
                    val canonicalName = "$datePrefix.md"
                    var canonicalEntry = files.firstOrNull { it.displayName.equals(canonicalName, ignoreCase = true) }
                    var canonicalDoc = canonicalEntry?.toDocumentFile(context, treeUri)

                    if (canonicalDoc == null) {
                        val firstFile = files.first()
                        val firstDoc = firstFile.toDocumentFile(context, treeUri)
                        if (firstDoc != null) {
                            vaultManager.rename(firstDoc, canonicalName)
                            canonicalDoc = firstDoc
                        }
                    }

                    for (fileEntry in files) {
                        if (fileEntry.displayName.equals(canonicalName, ignoreCase = true)) continue
                        val dupDoc = fileEntry.toDocumentFile(context, treeUri) ?: continue
                        val dupRelPath = "$DAILY_JOURNAL_DIR/${fileEntry.displayName}"
                        if (canonicalDoc != null && canonicalDoc.length() == 0L && dupDoc.length() > 0L) {
                            val dupText = vaultManager.readText(dupDoc)
                            vaultManager.writeTextAtomic(dailyDir, canonicalName, dupText, "text/markdown")
                        }
                        vaultManager.delete(dupDoc)
                        removeMetadataForPath(dupRelPath)
                        cache.remove(dupRelPath)
                        removeIdForPath(dupRelPath)
                        metadataModified = true
                    }
                }
            }

            // 4. Clean up metadataByIdMap: ensure all date-titled notes have Daily Journal path
            val keys = ArrayList(metadataByIdMap.keys)
            for (id in keys) {
                val meta = metadataByIdMap[id] ?: continue
                val base = meta.path.substringAfterLast("/").removeSuffix(".md")
                if (dailyJournalRegex.containsMatchIn(base)) {
                    val dateMatch = Regex("^(\\d{4}-\\d{2}-\\d{2})").find(base)
                    val datePrefix = dateMatch?.groupValues?.get(1) ?: base
                    val expectedPath = "$DAILY_JOURNAL_DIR/$datePrefix.md"
                    if (!meta.path.equals(expectedPath, ignoreCase = true)) {
                        val existingDailyMeta = getMetadataForPath(expectedPath)
                        if (existingDailyMeta != null && existingDailyMeta.id != id) {
                            val merged = existingDailyMeta.copy(
                                sessionCount = maxOf(existingDailyMeta.sessionCount, meta.sessionCount),
                                timeSpent = maxOf(existingDailyMeta.timeSpent, meta.timeSpent),
                                sessionDurationSec = maxOf(existingDailyMeta.sessionDurationSec, meta.sessionDurationSec)
                            )
                            putMetadata(merged)
                            metadataByIdMap.remove(id)
                            pathIndex.remove(meta.path)
                            cache.remove(meta.path)
                        } else {
                            pathIndex.remove(meta.path)
                            cache.remove(meta.path)
                            putMetadata(meta.copy(path = expectedPath))
                            updateIdPath(meta.path, expectedPath)
                        }
                        metadataModified = true
                    }
                }
            }

            vaultManager.clearDirCache()
            if (metadataModified) {
                saveMetadata()
            }
        } catch (_: Exception) {}
    }

    private suspend fun scanVaultRecursive(vaultDoc: DocumentFile) = withContext(Dispatchers.IO) {
        val discoveredEntries = mutableListOf<CachedNoteEntry>()
        val foundPaths = mutableSetOf<String>()
        val discoveredFolders = mutableSetOf<String>()
        var count = 0
        var metadataChanged = false
        val treeUri = vaultManager.getVaultTreeUri() ?: vaultDoc.uri
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: vaultManager.fastLister.getDocumentId(treeUri, vaultDoc)

        suspend fun traverse(dir: DocumentFile?, parentDocId: String, currentRelPath: String) {
            val children = vaultManager.fastLister.listChildrenFast(treeUri, parentDocId)
            for (child in children) {
                val name = child.displayName
                // Ignore hidden files/folders (names starting with ".")
                if (name.isBlank() || name.startsWith(".")) {
                    continue
                }

                if (child.isDirectory) {
                    if (currentRelPath.isEmpty() && (name.equals(TEMPLATES_DIR, ignoreCase = true) || name.equals(TRASH_DIR, ignoreCase = true))) {
                        continue
                    }
                    val nextRel = if (currentRelPath.isEmpty()) name else "$currentRelPath/$name"
                    discoveredFolders.add(nextRel)
                    traverse(null, child.documentId, nextRel)
                } else if (child.isFile && name.endsWith(".md", ignoreCase = true)) {
                    val fileRelPath = if (currentRelPath.isEmpty()) name else "$currentRelPath/$name"
                    foundPaths.add(fileRelPath)
                    count++

                    if (count % 30 == 0) {
                        _scanProgress.value = "Indexed $count notes..."
                    }

                    val baseTitle = name.removeSuffix(".md")
                    val isDailyJournal = Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(baseTitle)
                    val folderName = if (isDailyJournal) DAILY_JOURNALS_DIR else if (currentRelPath.isEmpty()) GENERAL_DIR else currentRelPath
                    val lastMod = child.lastModified
                    val fileSize = child.size

                    var meta = getMetadataForPath(fileRelPath)
                    val existingCache = cache[fileRelPath]
                    val childDoc = child.toDocumentFile(context, treeUri)
                    val isUnchanged = (meta != null && Math.abs(meta.modifiedAt - lastMod) < 2000L && meta.fileSize == fileSize)

                    if (meta == null) {
                        // Check for renamed / moved note: match by fileSize + lastModified
                        val candidate = metadataByIdMap.values.firstOrNull { unmapped ->
                            !foundPaths.contains(unmapped.path) &&
                            !unmapped.path.startsWith(TRASH_PREFIX) &&
                            unmapped.fileSize > 0L && unmapped.fileSize == fileSize &&
                            Math.abs(unmapped.modifiedAt - lastMod) < 2000L
                        }

                        if (candidate != null) {
                            val oldPath = candidate.path
                            pathIndex.remove(oldPath)
                            meta = candidate.copy(
                                path = fileRelPath,
                                modifiedAt = lastMod,
                                updatedAt = lastMod,
                                fileSize = fileSize,
                                isOrphaned = false,
                                orphanedAt = null
                            )
                            putMetadata(meta)
                            metadataChanged = true
                        } else {
                            val createdTs = if (lastMod > 0L) lastMod else System.currentTimeMillis()
                            meta = NoteMetadata(
                                path = fileRelPath,
                                createdAt = createdTs,
                                updatedAt = lastMod,
                                modifiedAt = lastMod,
                                sessionCount = 0,
                                timeSpent = 0L,
                                sessionDurationSec = 0L,
                                wordCount = 0,
                                charCount = 0,
                                fileSize = fileSize,
                                contentHash = 0L,
                                statsStale = true
                            )
                            putMetadata(meta)
                            metadataChanged = true
                        }
                    } else {
                        // Entry exists! Check if modified on disk
                        if (!isUnchanged) {
                            meta = meta.copy(
                                modifiedAt = lastMod,
                                updatedAt = lastMod,
                                fileSize = fileSize,
                                statsStale = true,
                                isOrphaned = false,
                                orphanedAt = null
                            )
                            putMetadata(meta)
                            noteBodyLruCache.remove(fileRelPath)
                            metadataChanged = true
                        } else {
                            if (meta.isOrphaned) {
                                meta = meta.copy(isOrphaned = false, orphanedAt = null)
                                putMetadata(meta)
                                metadataChanged = true
                            }
                        }
                    }

                    val id = getOrCreateIdForPath(fileRelPath)
                    val cachedContent = noteBodyLruCache.get(fileRelPath) ?: ""
                    val note = Note(
                        id = id,
                        title = baseTitle,
                        content = cachedContent,
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
                        sessionCount = meta.sessionCount,
                        wordCount = meta.wordCount,
                        charCount = meta.charCount,
                        statsStale = meta.statsStale
                    )

                    if (existingCache != null && id == currentlyOpenNoteId && (lastMod > existingCache.lastModified + 2000L || fileSize != meta.fileSize)) {
                        noteBodyLruCache.remove(fileRelPath)
                    }

                    val targetFile = childDoc ?: DocumentFile.fromSingleUri(context, child.uri)
                    if (targetFile != null) {
                        val parentFolder = dir ?: vaultDoc
                        val entry = CachedNoteEntry(
                            note = note,
                            file = targetFile,
                            parentDir = parentFolder,
                            relPath = fileRelPath,
                            lastModified = lastMod
                        )
                        cache[fileRelPath] = entry
                        discoveredEntries.add(entry)
                    }
                }
            }
        }

        traverse(vaultDoc, rootDocId, "")

        // One-time migration: Move any note marked as deleted in metadata that is still in normal folder
        val trashDir = vaultManager.getOrCreateDirRecursive(vaultDoc, TRASH_DIR)
        if (trashDir != null) {
            val trashedInNormalFolder = cache.entries.filter { entry ->
                val meta = getMetadataForPath(entry.key)
                meta != null && meta.isDeleted && !entry.key.startsWith(TRASH_PREFIX)
            }.toList()

            if (trashedInNormalFolder.isNotEmpty()) {
                val trashChildren = vaultManager.listChildrenFast(trashDir, treeUri)
                for (entryItem in trashedInNormalFolder) {
                    val oldRel = entryItem.key
                    val oldEntry = entryItem.value
                    val currentFileName = oldEntry.file.name ?: "${oldEntry.note.title}.md"
                    val nameCollision = trashChildren.any { it.displayName.equals(currentFileName, ignoreCase = true) }
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

            // Reconcile in-app Trash view with physical Trash/ folder
            val trashFiles = vaultManager.listChildrenFast(trashDir, treeUri).filter { it.isFile && it.displayName.endsWith(".md", ignoreCase = true) }
            val trashRelPathsOnDisk = mutableSetOf<String>()
            for (tFile in trashFiles) {
                val tName = tFile.displayName
                val trashRel = "$TRASH_PREFIX$tName"
                trashRelPathsOnDisk.add(trashRel)
                foundPaths.add(trashRel)

                val meta = getMetadataForPath(trashRel) ?: metadataByIdMap.values.firstOrNull { it.trashFileName == tName }
                val origRel = meta?.originalRelPath ?: tName
                val baseTitle = meta?.originalRelPath?.substringAfterLast("/")?.removeSuffix(".md")
                    ?: tName.removeSuffix(".md").replace(Regex("_\\d{10,}$"), "")
                val isDaily = Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(baseTitle)
                val origFolder = if (isDaily) DAILY_JOURNAL_DIR else if (origRel.contains("/")) origRel.substringBeforeLast("/") else DAILY_JOURNAL_DIR

                val trashIsUnchanged = (meta != null && Math.abs(meta.modifiedAt - tFile.lastModified) < 2000L && meta.fileSize == tFile.size)
                val effectiveMeta = (meta ?: NoteMetadata(
                    createdAt = tFile.lastModified,
                    updatedAt = tFile.lastModified,
                    modifiedAt = tFile.lastModified,
                    fileSize = tFile.size,
                    statsStale = true
                )).copy(
                    path = trashRel,
                    isDeleted = true,
                    deletedAt = meta?.deletedAt ?: tFile.lastModified,
                    originalRelPath = origRel,
                    trashFileName = tName,
                    fileSize = tFile.size,
                    statsStale = if (trashIsUnchanged) (meta?.statsStale ?: false) else true
                )
                putMetadata(effectiveMeta)

                val id = getOrCreateIdForPath(trashRel)
                val cachedTrashContent = noteBodyLruCache.get(trashRel) ?: ""
                val note = Note(
                    id = id,
                    title = baseTitle,
                    content = cachedTrashContent,
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
                    sessionCount = effectiveMeta.sessionCount,
                    wordCount = effectiveMeta.wordCount,
                    charCount = effectiveMeta.charCount,
                    statsStale = effectiveMeta.statsStale
                )
                val tDocFile = tFile.toDocumentFile(context, treeUri) ?: DocumentFile.fromSingleUri(context, tFile.uri)
                if (tDocFile != null) {
                    val entry = CachedNoteEntry(
                        note = note,
                        file = tDocFile,
                        parentDir = trashDir,
                        relPath = trashRel,
                        lastModified = tFile.lastModified
                    )
                    cache[trashRel] = entry
                }
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


        // NO WRITE ON PURE OPEN: only save metadata if something actually changed!
        if (metadataChanged) {
            saveMetadata()
        }
        recomputeFlows()
        startDeferredStatsWorker()
    }

    fun startDeferredStatsWorker() {
        statsWorkerJob?.cancel()
        statsWorkerJob = repositoryScope.launch(Dispatchers.IO) {
            val stalePaths = metadataByIdMap.values
                .filter { it.statsStale && !it.isOrphaned }
                .map { it.path }
                .toMutableList()

            if (stalePaths.isEmpty()) return@launch

            // Prioritize currently open note first
            val openId = currentlyOpenNoteId
            val openPath = if (openId != null) idToPathMap[openId] else null
            if (openPath != null && stalePaths.remove(openPath)) {
                stalePaths.add(0, openPath)
            }

            var metadataChanged = false
            var batchCount = 0

            for (relPath in stalePaths) {
                if (!isActive) break

                val meta = getMetadataForPath(relPath) ?: continue
                if (!meta.statsStale) continue

                val entry = cache[relPath]
                val text = readNoteBody(relPath, entry, meta)
                val words = countWords(text)
                val chars = text.length
                val hash = if (text.isNotEmpty()) text.hashCode().toLong() else 0L

                val updatedMeta = meta.copy(
                    wordCount = words,
                    charCount = chars,
                    contentHash = hash,
                    statsStale = false
                )
                putMetadata(updatedMeta)
                metadataChanged = true

                // Check external change for currently open note
                if (openId != null && entry?.note?.id == openId) {
                    val prevContent = entry.note.content
                    if (prevContent.isNotEmpty() && prevContent != text) {
                        _externalNoteChange.value = ExternalNoteChange(
                            note = entry.note.copy(content = text, wordCount = words, charCount = chars, statsStale = false),
                            diskContent = text,
                            lastModified = entry.lastModified
                        )
                    }
                }

                if (entry != null) {
                    val updatedNote = entry.note.copy(
                        wordCount = words,
                        charCount = chars,
                        statsStale = false,
                        content = if (entry.note.content.isNotEmpty()) text else entry.note.content
                    )
                    cache[relPath] = entry.copy(note = updatedNote)
                }

                batchCount++
                if (batchCount % 3 == 0) {
                    recomputeFlows()
                    kotlinx.coroutines.yield()
                } else {
                    kotlinx.coroutines.yield()
                }
            }

            if (metadataChanged) {
                saveMetadata()
                recomputeFlows()
            }
        }
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
                    orphanedAt = if (obj.has("orphanedAt") && !obj.isNull("orphanedAt")) obj.optLong("orphanedAt") else null,
                    statsStale = obj.optBoolean("statsStale", false)
                )
                putMetadata(meta)
            }

            // INSTANT EMISSION: Populate in-memory cache directly from metadata
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
                val isDailyJournal = Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(baseTitle)
                val folderName = if (isDailyJournal) {
                    DAILY_JOURNALS_DIR
                } else if (isTrash) {
                    val orig = meta.originalRelPath ?: ""
                    if (orig.contains("/")) orig.substringBeforeLast("/") else GENERAL_DIR
                } else {
                    if (meta.path.contains("/")) meta.path.substringBeforeLast("/") else GENERAL_DIR
                }
                val cachedContent = noteBodyLruCache.get(meta.path) ?: ""
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
                    sessionCount = meta.sessionCount,
                    wordCount = meta.wordCount,
                    charCount = meta.charCount,
                    statsStale = meta.statsStale
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
                if (meta.statsStale) obj.put("statsStale", true)
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
        return cache.values.firstOrNull { it.note.id == id }?.note
            ?: allNotes.value.firstOrNull { it.id == id }
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
                        note.folder.contains(q, ignoreCase = true) ||
                        (noteBodyLruCache.get(idToPathMap[note.id] ?: "")?.contains(q, ignoreCase = true) == true)
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
        val isDailyJournal = isDailyJournalNote(note) ||
                note.folder.trim().equals(DAILY_JOURNAL_DIR, ignoreCase = true) ||
                note.folder.trim().equals(DAILY_JOURNALS_DIR, ignoreCase = true)
        val targetFolder = if (isDailyJournal) DAILY_JOURNAL_DIR else if (note.folder.isBlank()) GENERAL_DIR else note.folder.trim()
        val targetDir = if (isDailyJournal) getOrCreateDailyJournalDir(vaultDoc) ?: vaultDoc else vaultManager.getOrCreateDirRecursive(vaultDoc, targetFolder) ?: vaultDoc

        val sanitizedTitle = VaultManager.sanitizeFilename(note.title.ifBlank { "Untitled Note" })

        // Prevent duplicate notes/files for the same daily journal date
        if (isDailyJournal) {
            val dateMatch = Regex("""^(\d{4}-\d{2}-\d{2})""").find(sanitizedTitle) ?: Regex("""^(\d{4}-\d{2}-\d{2})""").find(note.title.trim())
            val datePrefix = dateMatch?.groupValues?.get(1) ?: sanitizedTitle
            val canonicalFileName = "$datePrefix.md"
            val canonicalRelPath = "$DAILY_JOURNAL_DIR/$canonicalFileName"

            // 1. Check in-memory cache for existing note on this date
            val existingEntry = cache[canonicalRelPath]
                ?: cache.values.firstOrNull {
                    it.relPath.startsWith(DAILY_JOURNAL_DIR, ignoreCase = true) &&
                    it.note.title.trim().startsWith(datePrefix) &&
                    !it.note.isDeleted
                }
            if (existingEntry != null) {
                if (note.content.isNotBlank() && existingEntry.note.content.isBlank()) {
                    vaultManager.writeTextAtomic(
                        parentDir = existingEntry.parentDir,
                        fileName = existingEntry.file.name ?: canonicalFileName,
                        text = note.content,
                        mimeType = "text/markdown"
                    )
                    noteBodyLruCache.put(existingEntry.relPath, note.content)
                    saveContentToLocalCache(getMetadataForPath(existingEntry.relPath)?.id ?: "", note.content)
                    val updatedNote = existingEntry.note.copy(content = note.content, updatedAt = System.currentTimeMillis())
                    cache[existingEntry.relPath] = existingEntry.copy(note = updatedNote)
                    recomputeFlows()
                }
                return existingEntry.note.id
            }

            // 2. Check disk in targetDir
            val treeUri = vaultManager.getVaultTreeUri()
            val existingChild = if (treeUri != null) {
                vaultManager.findChildFast(targetDir, canonicalFileName, treeUri)
            } else {
                targetDir.findFile(canonicalFileName)
            }
            if (existingChild != null) {
                val existingDoc = if (treeUri != null && existingChild is com.example.data.vault.FastEntry) {
                    existingChild.toDocumentFile(context, treeUri)
                } else {
                    targetDir.findFile(canonicalFileName)
                }
                if (existingDoc != null && existingDoc.exists()) {
                    val id = getOrCreateIdForPath(canonicalRelPath)
                    val diskText = vaultManager.readText(existingDoc)
                    val finalText = if (diskText.isBlank() && note.content.isNotBlank()) {
                        vaultManager.writeTextAtomic(targetDir, canonicalFileName, note.content, "text/markdown")
                        note.content
                    } else diskText
                    noteBodyLruCache.put(canonicalRelPath, finalText)
                    val existingNote = Note(
                        id = id,
                        title = datePrefix,
                        content = finalText,
                        folder = DAILY_JOURNAL_DIR,
                        createdAt = if (note.createdAt > 0L) note.createdAt else existingDoc.lastModified(),
                        updatedAt = System.currentTimeMillis()
                    )
                    cache[canonicalRelPath] = CachedNoteEntry(
                        note = existingNote,
                        file = existingDoc,
                        parentDir = targetDir,
                        relPath = canonicalRelPath,
                        lastModified = existingDoc.lastModified()
                    )
                    saveWorkspace(lastOpenedRelPath = canonicalRelPath, lastFolder = DAILY_JOURNAL_DIR)
                    recomputeFlows()
                    return id
                }
            }
        }

        val finalFileName = if (isDailyJournal) {
            val dateMatch = Regex("""^(\d{4}-\d{2}-\d{2})""").find(sanitizedTitle) ?: Regex("""^(\d{4}-\d{2}-\d{2})""").find(note.title.trim())
            val datePrefix = dateMatch?.groupValues?.get(1) ?: sanitizedTitle
            "$datePrefix.md"
        } else {
            vaultManager.resolveDuplicateFileName(targetDir, sanitizedTitle, ".md")
        }
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
        val hash = if (note.content.isNotEmpty()) note.content.hashCode().toLong() else 0L
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
            tags = note.tags,
            statsStale = false
        )
        putMetadata(meta)
        noteBodyLruCache.put(relPath, note.content)
        saveContentToLocalCache(meta.id, note.content)

        val createdNote = note.copy(
            id = id,
            title = cleanTitle,
            folder = targetFolder,
            updatedAt = meta.updatedAt,
            sessionCount = meta.sessionCount,
            wordCount = words,
            charCount = chars,
            statsStale = false
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
            val guessedRel3 = "${note.title}.md"
            entry = cache[guessedRel1] ?: cache[guessedRel2] ?: cache[guessedRel3]
        }

        if (entry == null) {
            entry = cache.values.firstOrNull {
                it.note.title.equals(note.title, ignoreCase = true) &&
                (it.note.folder.equals(note.folder, ignoreCase = true) || it.relPath.equals("${note.title}.md", ignoreCase = true))
            }
        }

        val isDailyJournal = isDailyJournalNote(note) ||
                note.folder.trim().equals(DAILY_JOURNAL_DIR, ignoreCase = true) ||
                note.folder.trim().equals(DAILY_JOURNALS_DIR, ignoreCase = true)

        if (entry == null && isDailyJournal) {
            val dateMatch = Regex("""^(\d{4}-\d{2}-\d{2})""").find(note.title.trim())
            val datePrefix = dateMatch?.groupValues?.get(1)
            if (datePrefix != null) {
                entry = cache["$DAILY_JOURNAL_DIR/$datePrefix.md"]
                    ?: cache.values.firstOrNull {
                        it.relPath.startsWith(DAILY_JOURNAL_DIR, ignoreCase = true) &&
                        it.note.title.trim().startsWith(datePrefix) &&
                        !it.note.isDeleted
                    }
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
        val targetFolder = if (isDailyJournal) DAILY_JOURNAL_DIR else note.folder.trim().ifBlank { GENERAL_DIR }

        // Handle Physical Trashing
        if (note.isDeleted && (!entry.note.isDeleted || !currentRelPath.startsWith(TRASH_PREFIX))) {
            val trashDir = vaultManager.getOrCreateDirRecursive(vaultDoc, TRASH_DIR)
            if (trashDir != null) {
                val currentFileName = currentFile.name ?: "${VaultManager.sanitizeFilename(note.title)}.md"
                val trashChildren = vaultManager.listChildrenFast(trashDir)
                val nameCollision = trashChildren.any { it.displayName.equals(currentFileName, ignoreCase = true) }
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
        if (!note.isDeleted && (entry.note.isDeleted || currentRelPath.startsWith(TRASH_PREFIX) || currentRelPath.startsWith(".KOTHAPAD/trash/"))) {
            val meta = getMetadataForPath(currentRelPath) ?: metadataByIdMap.values.firstOrNull { it.trashFileName == currentFile.name }
            val originalRel = meta?.originalRelPath ?: "${note.folder}/${note.title}.md".trimStart('/')
            val isDaily = isDailyJournal || Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(note.title)
            val destFolder = if (isDaily) DAILY_JOURNAL_DIR else if (originalRel.contains("/")) originalRel.substringBeforeLast("/") else (if (note.folder.isNotBlank() && !note.folder.equals(TRASH_DIR, ignoreCase = true)) note.folder else DAILY_JOURNAL_DIR)
            val baseName = if (isDaily) {
                Regex("^\\d{4}-\\d{2}-\\d{2}").find(note.title)?.value ?: note.title
            } else {
                originalRel.substringAfterLast("/").removeSuffix(".md").ifBlank { note.title.ifBlank { "Restored Note" } }
            }

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
                    val cachedText = noteBodyLruCache.get(currentRelPath)
                    noteBodyLruCache.remove(currentRelPath)
                    if (cachedText != null) {
                        noteBodyLruCache.put(newRel, cachedText)
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
                val cachedText = noteBodyLruCache.get(currentRelPath)
                noteBodyLruCache.remove(currentRelPath)
                if (cachedText != null) {
                    noteBodyLruCache.put(newRel, cachedText)
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
            orphanedAt = null,
            statsStale = false
        )
        putMetadata(meta)
        noteBodyLruCache.put(currentRelPath, note.content)
        saveContentToLocalCache(meta.id, note.content)

        val updatedNote = note.copy(
            id = entry.note.id,
            title = currentFile.name?.removeSuffix(".md") ?: note.title,
            folder = targetFolder,
            updatedAt = now,
            sessionCount = meta.sessionCount,
            wordCount = words,
            charCount = chars,
            statsStale = false
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
                val trashDir = vaultManager.findDirRecursive(vaultDoc, TRASH_DIR) ?: vaultConfigStore.getTrashDir(vaultDoc)
                if (trashDir != null && trashDir.exists()) {
                    for (f in vaultManager.listChildrenFast(trashDir)) {
                        f.toDocumentFile(context)?.let { vaultManager.delete(it) }
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
            val children = vaultManager.listChildrenFast(dir)
            for (ch in children) {
                if (ch.isFile && ch.displayName.endsWith(".md", ignoreCase = true)) {
                    val chDoc = ch.toDocumentFile(context) ?: continue
                    vaultManager.move(chDoc, dir, generalDir)
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
        val cleanName = "${VaultManager.sanitizeFilename(template.label)}.md"
        val fileEntry = vaultManager.findChildFast(templatesDir, cleanName) ?: return@withContext false
        val file = fileEntry.toDocumentFile(context) ?: return@withContext false
        vaultManager.delete(file)
    }

    suspend fun syncTemplatesFromVault(
        existingTemplates: List<Stamp>,
        onNewTemplateDiscovered: suspend (Stamp) -> Unit
    ) = withContext(Dispatchers.IO) {
        val vaultDoc = currentVaultDoc ?: return@withContext
        val templatesDir = vaultManager.findDirRecursive(vaultDoc, TEMPLATES_DIR) ?: return@withContext
        val files = vaultManager.listChildrenFast(templatesDir)
        var order = existingTemplates.size
        for (f in files) {
            if (f.isFile && f.displayName.endsWith(".md", ignoreCase = true)) {
                val label = f.displayName.removeSuffix(".md")
                if (existingTemplates.none { it.label.equals(label, ignoreCase = true) }) {
                    val fDoc = f.toDocumentFile(context)
                    val content = if (fDoc != null) vaultManager.readText(fDoc) else ""
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
        val existingFileNames = files.map { it.displayName }.toSet()
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
