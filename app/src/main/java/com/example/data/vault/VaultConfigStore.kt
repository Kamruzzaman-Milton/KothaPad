package com.example.data.vault

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * VaultConfigStore: The single source of truth and ONLY class allowed to touch the .KOTHAPAD folder.
 * Operates like Obsidian's .obsidian folder:
 * - A vault is just a folder identified by its content/path.
 * - .KOTHAPAD is reused if it exists; only created with defaults if absent.
 * - All operations are protected by a single Mutex to prevent duplicate folders or files.
 * - Files are updated in-place via truncate mode ("wt"), preserving unknown keys.
 * - Automatic duplicate cleanup and migration merges stray/duplicate config files.
 */
class VaultConfigStore(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val mutex = Mutex()

    val fastLister = SafFastLister(context)

    // In-memory cache of parsed JSON per file name for the currently open vault
    private val jsonCache = ConcurrentHashMap<String, JSONObject>()
    // In-memory record of the last serialized string written to disk (for no-op write avoidance)
    private val lastWrittenContent = ConcurrentHashMap<String, String>()
    // Debounce jobs per config file
    private val debounceJobs = ConcurrentHashMap<String, Job>()

    private var activeVaultUri: String? = null
    private var activeConfigDir: DocumentFile? = null
    private var activeVaultDoc: DocumentFile? = null

    companion object {
        const val CONFIG_DIR_NAME = ".KOTHAPAD"
        const val TRASH_DIR_NAME = "trash"
        const val FILE_SETTINGS = "settings.json"
        const val FILE_NOTES_META = "notes-meta.json"
        const val FILE_WORKSPACE = "workspace.json"

        val ALLOWED_FILES = setOf(FILE_SETTINGS, FILE_NOTES_META, FILE_WORKSPACE)

        @Volatile
        private var instance: VaultConfigStore? = null

        fun getInstance(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ): VaultConfigStore {
            return instance ?: synchronized(this) {
                instance ?: VaultConfigStore(context.applicationContext, scope).also { instance = it }
            }
        }

        fun isConfigDir(dirName: String): Boolean {
            return dirName.equals(CONFIG_DIR_NAME, ignoreCase = true)
        }

        fun isConfigFile(fileName: String): Boolean {
            return ALLOWED_FILES.any { it.equals(fileName, ignoreCase = true) }
        }

        fun isConfigFileOrDuplicate(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return (lower.startsWith("settings") || lower.startsWith("notes-meta") || lower.startsWith("workspace")) &&
                    (lower.endsWith(".json") || lower.endsWith(".bak") || lower.contains(".bak") || lower.contains(".tmp"))
        }

        fun deepMerge(target: JSONObject, source: JSONObject): JSONObject {
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val srcVal = source.get(key)
                if (srcVal == JSONObject.NULL) {
                    target.remove(key)
                } else if (target.has(key)) {
                    val tgtVal = target.get(key)
                    if (tgtVal is JSONObject && srcVal is JSONObject) {
                        deepMerge(tgtVal, srcVal)
                    } else {
                        target.put(key, srcVal)
                    }
                } else {
                    target.put(key, srcVal)
                }
            }
            return target
        }
    }

    /**
     * Initializes a vault root on open:
     * Resolves the canonical .KOTHAPAD directory, performs duplicate cleanup & migration,
     * and seeds in-memory caches.
     */
    suspend fun initVault(vaultRoot: DocumentFile): DocumentFile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rootUri = vaultRoot.uri.toString()
            if (activeVaultUri != rootUri) {
                // Vault switched or newly opened
                debounceJobs.values.forEach { it.cancel() }
                debounceJobs.clear()
                jsonCache.clear()
                lastWrittenContent.clear()
                fastLister.invalidateAll()
                activeVaultUri = rootUri
                activeConfigDir = null
                activeVaultDoc = vaultRoot
            } else {
                activeVaultDoc = vaultRoot
            }

            val configDir = resolveConfigDirInternal(vaultRoot) ?: return@withContext null
            activeConfigDir = configDir

            // Run duplicate cleanup and migration once inside mutex
            cleanupAndMigrateDuplicatesInternal(vaultRoot, configDir)

            configDir
        }
    }

    /**
     * Check if a vault already has an existing .KOTHAPAD folder.
     */
    suspend fun hasConfigDir(vaultRoot: DocumentFile): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val children = fastLister.listChildrenFast(vaultRoot)
                children.any { it.isDirectory && isConfigDir(it.displayName) }
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun hasConfigFile(vaultRoot: DocumentFile, fileName: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val configDir = resolveConfigDirInternal(vaultRoot) ?: return@withContext false
                val children = fastLister.listChildrenFast(configDir)
                children.any { it.isFile && it.displayName.equals(fileName, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Resolves the canonical .KOTHAPAD directory inside vaultRoot.
     * Lists children once. Reuses existing directory if present.
     * Creates .KOTHAPAD only if absent, verifying creation.
     */
    suspend fun resolveConfigDir(vaultRoot: DocumentFile): DocumentFile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            resolveConfigDirInternal(vaultRoot)
        }
    }

    suspend fun getOrCreateTrashDir(vaultRoot: DocumentFile): DocumentFile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val configDir = resolveConfigDirInternal(vaultRoot) ?: return@withContext null
            val treeUri = fastLister.getTreeUriFromDoc(vaultRoot) ?: vaultRoot.uri
            val children = fastLister.listChildrenFast(configDir, treeUri)
            val existing = children.firstOrNull { it.isDirectory && it.displayName.equals(TRASH_DIR_NAME, ignoreCase = true) }
            if (existing != null) {
                val doc = configDir.findFile(existing.displayName)
                if (doc != null && doc.exists()) return@withContext doc
            }
            val created = configDir.createDirectory(TRASH_DIR_NAME)
            fastLister.invalidate(fastLister.getDocumentIdFromDoc(configDir))
            created
        }
    }

    suspend fun getTrashDir(vaultRoot: DocumentFile): DocumentFile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val configDir = resolveConfigDirInternal(vaultRoot) ?: return@withContext null
            val treeUri = fastLister.getTreeUriFromDoc(vaultRoot) ?: vaultRoot.uri
            val children = fastLister.listChildrenFast(configDir, treeUri)
            val existing = children.firstOrNull { it.isDirectory && it.displayName.equals(TRASH_DIR_NAME, ignoreCase = true) }
            if (existing != null) {
                configDir.findFile(existing.displayName)
            } else {
                null
            }
        }
    }

    private fun resolveConfigDirInternal(vaultRoot: DocumentFile): DocumentFile? {
        if (!vaultRoot.exists() || !vaultRoot.canWrite()) return null

        val treeUri = fastLister.getTreeUriFromDoc(vaultRoot) ?: vaultRoot.uri
        val children = fastLister.listChildrenFast(vaultRoot, treeUri)
        // Find existing folder named .KOTHAPAD (case-insensitive)
        val exactMatch = children.firstOrNull { it.isDirectory && it.displayName == CONFIG_DIR_NAME }
        val caseMatch = children.firstOrNull { it.isDirectory && isConfigDir(it.displayName) }
        val existing = exactMatch ?: caseMatch

        if (existing != null) {
            val existingDoc = vaultRoot.findFile(existing.displayName)
            if (existingDoc != null && existingDoc.exists()) {
                return existingDoc
            }
        }

        // None exists: create once with canonical name
        val created = vaultRoot.createDirectory(CONFIG_DIR_NAME)
        fastLister.invalidate(fastLister.getDocumentIdFromDoc(vaultRoot))
        return created
    }

    /**
     * Locates or creates a fixed configuration file in configDir.
     * Returns the DocumentFile. If already present, never calls createFile.
     */
    suspend fun getOrCreateFile(configDir: DocumentFile, name: String): DocumentFile? = mutex.withLock {
        withContext(Dispatchers.IO) {
            getOrCreateFileInternal(configDir, name)
        }
    }

    private fun getOrCreateFileInternal(configDir: DocumentFile, name: String): DocumentFile? {
        require(ALLOWED_FILES.contains(name)) { "Unauthorized config file: $name" }

        val treeUri = fastLister.getTreeUriFromDoc(configDir)
        val children = fastLister.listChildrenFast(configDir, treeUri)
        val existing = children.firstOrNull { it.isFile && it.displayName.equals(name, ignoreCase = true) }
        if (existing != null) {
            val doc = configDir.findFile(existing.displayName) ?: existing.toDocumentFile(context)
            if (doc != null && doc.exists()) {
                return doc
            }
        }

        // File does not exist yet; create it
        val created = configDir.createFile("application/json", name)
        fastLister.invalidate(fastLister.getDocumentIdFromDoc(configDir))
        val relisted = fastLister.listChildrenFast(configDir)

        // Check if provider renamed it (e.g. "settings (1).json" or "settings.json.json")
        val exact = relisted.firstOrNull { it.isFile && it.displayName.equals(name, ignoreCase = true) }
        if (exact != null) {
            return exact.toDocumentFile(context) ?: created
        }

        val mangled = relisted.firstOrNull { it.isFile && it.displayName.startsWith(name.removeSuffix(".json")) }
        if (mangled != null && mangled.displayName != name) {
            val mangledDoc = mangled.toDocumentFile(context)
            try {
                mangledDoc?.renameTo(name)
                fastLister.invalidate(fastLister.getDocumentIdFromDoc(configDir))
            } catch (_: Exception) {}
            val finalCheck = fastLister.listChildrenFast(configDir).firstOrNull { it.isFile && it.displayName.equals(name, ignoreCase = true) }
            if (finalCheck != null) return finalCheck.toDocumentFile(context)
        }

        return created
    }

    // Track files in safe mode to prevent overwriting corrupted files
    private val safeModeFiles = ConcurrentHashMap<String, Boolean>()
    private val _safeModeWarning = MutableStateFlow<String?>(null)
    val safeModeWarning: StateFlow<String?> = _safeModeWarning.asStateFlow()

    // =========================================================================
    // Reading Config Files
    // =========================================================================

    suspend fun readSettings(vaultRoot: DocumentFile): JSONObject? = readConfigFile(vaultRoot, FILE_SETTINGS)
    suspend fun readNotesMeta(vaultRoot: DocumentFile): JSONObject? = readConfigFile(vaultRoot, FILE_NOTES_META)
    suspend fun readWorkspace(vaultRoot: DocumentFile): JSONObject? = readConfigFile(vaultRoot, FILE_WORKSPACE)

    suspend fun isFileInSafeMode(fileName: String): Boolean = safeModeFiles[fileName] == true

    suspend fun readConfigFile(vaultRoot: DocumentFile, fileName: String): JSONObject? = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Check in-memory cache
            jsonCache[fileName]?.let { return@withContext JSONObject(it.toString()) }

            val configDir = resolveConfigDirInternal(vaultRoot) ?: return@withContext null
            val treeUri = fastLister.getTreeUriFromDoc(vaultRoot) ?: vaultRoot.uri
            val children = fastLister.listChildrenFast(configDir, treeUri)
            val entry = children.firstOrNull { it.isFile && it.displayName.equals(fileName, ignoreCase = true) }
            val file = entry?.toDocumentFile(context)

            // If file does not exist at all, return null (fresh vault case)
            if (file == null || !file.exists()) {
                return@withContext null
            }

            val text = readFileText(file)
            if (text.isNotBlank()) {
                try {
                    val json = JSONObject(text)
                    if (!json.has("schemaVersion") || json.optInt("schemaVersion", 1) < 1) {
                        json.put("schemaVersion", 1)
                    }
                    jsonCache[fileName] = JSONObject(json.toString())
                    lastWrittenContent[fileName] = text
                    safeModeFiles[fileName] = false
                    return@withContext json
                } catch (_: Exception) {
                    // Corrupted JSON inside an existing file
                }
            }

            // CRITICAL: File exists on disk, but is empty or corrupted! NEVER OVERWRITE.
            safeModeFiles[fileName] = true

            // 1. Preserve corrupt copy with timestamp
            val corruptName = "${fileName.removeSuffix(".json")}.corrupt-${System.currentTimeMillis()}.json"
            try {
                val corruptFile = configDir.createFile("application/json", corruptName)
                if (corruptFile != null && text.isNotEmpty()) {
                    context.contentResolver.openOutputStream(corruptFile.uri, "wt")?.use { stream ->
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(text); it.flush() }
                    }
                }
            } catch (_: Exception) {}

            // 2. Try to recover from internal or vault backup if present
            val bakName = "$fileName.bak"
            val internalBakFile = java.io.File(context.filesDir.resolve("kothapad_vault_backups"), bakName)
            val bakText = if (internalBakFile.exists()) {
                try { internalBakFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
            } else {
                val bakEntry = children.firstOrNull { it.isFile && (it.displayName.equals(bakName, ignoreCase = true) || it.displayName.startsWith("$fileName.bak", ignoreCase = true)) }
                val bakFile = bakEntry?.toDocumentFile(context)
                if (bakFile != null && bakFile.exists()) readFileText(bakFile) else ""
            }
            if (bakText.isNotBlank()) {
                try {
                    val recoveredJson = JSONObject(bakText)
                    jsonCache[fileName] = JSONObject(recoveredJson.toString())
                    lastWrittenContent[fileName] = bakText
                    safeModeFiles[fileName] = false
                    _safeModeWarning.value = "Warning: $fileName was corrupted. Successfully recovered from backup. Corrupt copy preserved as $corruptName."
                    return@withContext recoveredJson
                } catch (_: Exception) {}
            }

            _safeModeWarning.value = "Warning: $fileName was corrupted and could not be recovered. Safe mode enabled to prevent overwriting."
            null
        }
    }

    // =========================================================================
    // In-Place Writing & Debouncing
    // =========================================================================

    suspend fun updateSettings(vaultRoot: DocumentFile, updatedJson: JSONObject, immediate: Boolean = false) {
        updateConfigFile(vaultRoot, FILE_SETTINGS, updatedJson, immediate)
    }

    suspend fun updateNotesMeta(vaultRoot: DocumentFile, updatedJson: JSONObject, immediate: Boolean = false) {
        updateConfigFile(vaultRoot, FILE_NOTES_META, updatedJson, immediate)
    }

    suspend fun updateWorkspace(vaultRoot: DocumentFile, updatedJson: JSONObject, immediate: Boolean = false) {
        updateConfigFile(vaultRoot, FILE_WORKSPACE, updatedJson, immediate)
    }

    suspend fun writeSettings(vaultRoot: DocumentFile, json: JSONObject) {
        updateConfigFile(vaultRoot, FILE_SETTINGS, json, immediate = true)
    }

    suspend fun writeNotesMeta(vaultRoot: DocumentFile, json: JSONObject) {
        updateConfigFile(vaultRoot, FILE_NOTES_META, json, immediate = true)
    }

    suspend fun writeWorkspace(vaultRoot: DocumentFile, json: JSONObject) {
        updateConfigFile(vaultRoot, FILE_WORKSPACE, json, immediate = true)
    }

    private suspend fun updateConfigFile(
        vaultRoot: DocumentFile,
        fileName: String,
        updatedJson: JSONObject,
        immediate: Boolean = false
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (safeModeFiles[fileName] == true) {
                // In safe mode due to unrecovered corruption: NEVER overwrite!
                return@withContext
            }

            val existing = jsonCache[fileName] ?: readConfigFileInternal(vaultRoot, fileName) ?: JSONObject()
            val merged = if (existing.length() > 0) {
                // Preserve schemaVersion rule
                val oldVersion = existing.optInt("schemaVersion", 1)
                val newVersion = updatedJson.optInt("schemaVersion", 1)
                val preserved = deepMerge(existing, updatedJson)
                preserved.put("schemaVersion", maxOf(oldVersion, newVersion, 1))
                preserved
            } else {
                if (!updatedJson.has("schemaVersion") || updatedJson.optInt("schemaVersion", 1) < 1) {
                    updatedJson.put("schemaVersion", 1)
                }
                updatedJson
            }

            jsonCache[fileName] = JSONObject(merged.toString())

            debounceJobs[fileName]?.cancel()

            if (immediate) {
                writeToDiskInternal(vaultRoot, fileName, merged)
            } else {
                val job = scope.launch(Dispatchers.IO) {
                    delay(1500) // Debounce ~1.5 s
                    mutex.withLock {
                        val toWrite = jsonCache[fileName] ?: return@withLock
                        writeToDiskInternal(vaultRoot, fileName, toWrite)
                    }
                }
                debounceJobs[fileName] = job
            }
        }
    }

    suspend fun flush(fileName: String? = null) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rootDoc = activeVaultDoc ?: activeConfigDir?.parentFile ?: return@withContext
            if (fileName != null) {
                debounceJobs[fileName]?.cancel()
                debounceJobs.remove(fileName)
                val json = jsonCache[fileName] ?: return@withContext
                writeToDiskInternal(rootDoc, fileName, json)
            } else {
                for ((fName, job) in debounceJobs) {
                    job.cancel()
                    val json = jsonCache[fName] ?: continue
                    writeToDiskInternal(rootDoc, fName, json)
                }
                debounceJobs.clear()
            }
        }
    }

    private fun readConfigFileInternal(vaultRoot: DocumentFile, fileName: String): JSONObject? {
        val configDir = resolveConfigDirInternal(vaultRoot) ?: return null
        val treeUri = fastLister.getTreeUriFromDoc(vaultRoot) ?: vaultRoot.uri
        val entry = fastLister.listChildrenFast(configDir, treeUri).firstOrNull { it.isFile && it.displayName.equals(fileName, ignoreCase = true) } ?: return null
        val file = entry.toDocumentFile(context) ?: return null
        val text = readFileText(file)
        if (text.isBlank()) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeToDiskInternal(vaultRoot: DocumentFile, fileName: String, json: JSONObject): Boolean {
        if (safeModeFiles[fileName] == true) {
            return false // Safe mode protects against corrupt file overwrite
        }
        return try {
            if (!json.has("schemaVersion") || json.optInt("schemaVersion", 1) < 1) {
                json.put("schemaVersion", 1)
            }
            val formatted = json.toString(2)
            // Validate JSON formatting in memory first
            JSONObject(formatted)

            // NO WRITE IF UNCHANGED (No-op write avoidance)
            val lastSaved = lastWrittenContent[fileName]
            if (lastSaved == formatted) {
                return true
            }

            val configDir = resolveConfigDirInternal(vaultRoot) ?: return false

            // Keep an internal disaster backup copy in app's private storage (never polluting the user's vault folder)
            if (!lastSaved.isNullOrBlank()) {
                try {
                    val internalBakDir = context.filesDir.resolve("kothapad_vault_backups").apply { mkdirs() }
                    java.io.File(internalBakDir, "$fileName.bak").writeText(lastSaved, Charsets.UTF_8)
                } catch (_: Exception) {}
            }

            val file = getOrCreateFileInternal(configDir, fileName) ?: return false
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(formatted)
                    writer.flush()
                }
            }
            fastLister.invalidate(fastLister.getDocumentIdFromDoc(configDir))
            lastWrittenContent[fileName] = formatted
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readFileText(file: DocumentFile): String {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // =========================================================================
    // Duplicate Cleanup & Migration (Step 6)
    // =========================================================================

    private fun cleanupAndMigrateDuplicatesInternal(vaultRoot: DocumentFile, canonicalDir: DocumentFile) {
        try {
            val treeUri = fastLister.getTreeUriFromDoc(vaultRoot) ?: vaultRoot.uri
            val rootEntries = fastLister.listChildrenFast(vaultRoot, treeUri)
            val canonicalDocId = fastLister.getDocumentId(treeUri, canonicalDir)

            // 1. Identify stray duplicate directories (e.g. .KOTHAPAD (1), .kothapad (2), stray .kothapad)
            val duplicateDirEntries = rootEntries.filter {
                it.isDirectory &&
                it.documentId != canonicalDocId &&
                !it.displayName.equals(CONFIG_DIR_NAME, ignoreCase = false) &&
                it.displayName.startsWith(".kothapad", ignoreCase = true)
            }

            // 2. Identify stray config files sitting directly inside the vault root
            val strayRootEntries = rootEntries.filter {
                it.isFile && isConfigFileOrDuplicate(it.displayName)
            }

            // 3. Identify duplicate / backup files inside canonical .KOTHAPAD
            val canonicalEntries = fastLister.listChildrenFast(canonicalDir, treeUri)
            val duplicateCanonicalEntries = canonicalEntries.filter {
                it.isFile && !ALLOWED_FILES.contains(it.displayName) && isConfigFileOrDuplicate(it.displayName)
            }

            if (duplicateDirEntries.isEmpty() && strayRootEntries.isEmpty() && duplicateCanonicalEntries.isEmpty()) {
                return // Nothing to clean up
            }

            // Merge each allowed file type
            for (targetFileName in ALLOWED_FILES) {
                val candidateEntries = mutableListOf<FastEntry>()

                // Primary file if present
                canonicalEntries.firstOrNull { it.isFile && it.displayName.equals(targetFileName, ignoreCase = true) }?.let {
                    candidateEntries.add(it)
                }

                // Duplicates in canonical directory
                val targetBase = targetFileName.removeSuffix(".json").lowercase()
                for (f in duplicateCanonicalEntries) {
                    if (f.displayName.lowercase().startsWith(targetBase)) {
                        candidateEntries.add(f)
                    }
                }

                // Stray files in vault root
                for (f in strayRootEntries) {
                    if (f.displayName.lowercase().startsWith(targetBase)) {
                        candidateEntries.add(f)
                    }
                }

                // Files in duplicate directories
                for (dupDirEntry in duplicateDirEntries) {
                    val subEntries = if (treeUri != null) {
                        fastLister.listChildrenFast(treeUri, dupDirEntry.documentId)
                    } else {
                        val dupDirDoc = dupDirEntry.toDocumentFile(context)
                        if (dupDirDoc != null) fastLister.listChildrenFast(dupDirDoc) else emptyList()
                    }
                    for (f in subEntries) {
                        if (f.isFile && f.displayName.lowercase().startsWith(targetBase)) {
                            candidateEntries.add(f)
                        }
                    }
                }

                val hasNonCanonicalCandidate = candidateEntries.any { !it.displayName.equals(targetFileName, ignoreCase = true) }

                if (candidateEntries.isNotEmpty() && (candidateEntries.size > 1 || hasNonCanonicalCandidate)) {
                    // Sort candidate files from oldest to newest by lastModified (NO IPC!)
                    val sorted = candidateEntries.sortedBy { it.lastModified }
                    val newestCanonical = getOrCreateFileInternal(canonicalDir, targetFileName) ?: continue
                    val candidateFiles = sorted.mapNotNull { it.toDocumentFile(context) }

                    if (targetFileName == FILE_NOTES_META) {
                        mergeNotesMetaCandidates(candidateFiles, newestCanonical)
                    } else {
                        mergeGenericJsonCandidates(candidateFiles, newestCanonical)
                    }

                    // Verify canonical file can be read back cleanly before deleting duplicates
                    val verifiedText = readFileText(newestCanonical)
                    if (verifiedText.isNotBlank()) {
                        try {
                            JSONObject(verifiedText)
                            // Safe to delete duplicate files (excluding newestCanonical)
                            for (c in candidateFiles) {
                                if (c.uri != newestCanonical.uri) {
                                    c.delete()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // Explicit cleanup pass: delete any remaining backup/duplicate files in canonical .KOTHAPAD
            for (dupEntry in duplicateCanonicalEntries) {
                try {
                    val doc = dupEntry.toDocumentFile(context)
                    if (doc != null && doc.exists()) {
                        doc.delete()
                    }
                } catch (_: Exception) {}
            }

            // Also clean up any stray root files
            for (strayEntry in strayRootEntries) {
                try {
                    val doc = strayEntry.toDocumentFile(context)
                    if (doc != null && doc.exists()) {
                        doc.delete()
                    }
                } catch (_: Exception) {}
            }

            // Clean up any empty duplicate directories
            for (dupDirEntry in duplicateDirEntries) {
                try {
                    val dupDirDoc = dupDirEntry.toDocumentFile(context) ?: continue
                    val remaining = if (treeUri != null) {
                        fastLister.listChildrenFast(treeUri, dupDirEntry.documentId, useCache = false)
                    } else {
                        fastLister.listChildrenFast(dupDirDoc, useCache = false)
                    }
                    for (rem in remaining) {
                        if (rem.isFile && isConfigFileOrDuplicate(rem.displayName)) {
                            rem.toDocumentFile(context)?.delete()
                        }
                    }
                    val checkRemaining = if (treeUri != null) {
                        fastLister.listChildrenFast(treeUri, dupDirEntry.documentId, useCache = false)
                    } else {
                        fastLister.listChildrenFast(dupDirDoc, useCache = false)
                    }
                    if (checkRemaining.isEmpty()) {
                        dupDirDoc.delete()
                    }
                } catch (_: Exception) {}
            }
            fastLister.invalidateAll()
        } catch (_: Exception) {
            // Keep going safely; never block vault loading
        }
    }

    private fun mergeGenericJsonCandidates(candidates: List<DocumentFile>, targetFile: DocumentFile) {
        val merged = JSONObject()
        var maxVersion = 1

        for (file in candidates) {
            val text = readFileText(file)
            if (text.isBlank()) continue
            try {
                val json = JSONObject(text)
                val ver = json.optInt("schemaVersion", 1)
                if (ver > maxVersion) maxVersion = ver
                deepMerge(merged, json)
            } catch (_: Exception) {}
        }
        merged.put("schemaVersion", maxVersion)

        try {
            val formatted = merged.toString(2)
            context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(formatted); it.flush() }
            }
        } catch (_: Exception) {}
    }

    private fun mergeNotesMetaCandidates(candidates: List<DocumentFile>, targetFile: DocumentFile) {
        val mergedNotesObj = JSONObject()
        val mergedRoot = JSONObject()
        var maxVersion = 1

        for (file in candidates) {
            val text = readFileText(file)
            if (text.isBlank()) continue
            try {
                val json = JSONObject(text)
                val ver = json.optInt("schemaVersion", 1)
                if (ver > maxVersion) maxVersion = ver

                // Preserve any unknown root keys
                deepMerge(mergedRoot, json)

                val notes = json.optJSONObject("notes") ?: continue
                val keys = notes.keys()
                while (keys.hasNext()) {
                    val pathKey = keys.next()
                    val candidateEntry = notes.getJSONObject(pathKey)
                    val existingEntry = mergedNotesObj.optJSONObject(pathKey)

                    if (existingEntry == null) {
                        mergedNotesObj.put(pathKey, candidateEntry)
                    } else {
                        val candUpdated = candidateEntry.optLong("updatedAt", 0L)
                        val existUpdated = existingEntry.optLong("updatedAt", 0L)
                        if (candUpdated >= existUpdated) {
                            deepMerge(existingEntry, candidateEntry)
                            mergedNotesObj.put(pathKey, existingEntry)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        mergedRoot.put("schemaVersion", maxVersion)
        mergedRoot.put("notes", mergedNotesObj)

        try {
            val formatted = mergedRoot.toString(2)
            context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(formatted); it.flush() }
            }
        } catch (_: Exception) {}
    }
}
