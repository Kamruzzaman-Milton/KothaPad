package com.example.data.backup

import android.content.Context
import com.example.data.model.Note
import com.example.data.model.SpokenCommand
import com.example.data.model.Stamp
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.NoteRepository
import com.example.data.repository.SpokenCommandRepository
import com.example.data.repository.StampRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(
    val notesCount: Int,
    val templatesCount: Int,
    val commandsCount: Int = 0,
    val skippedCount: Int = 0
)

data class ParsedBackupData(
    val backupVersion: Int,
    val appVersion: String,
    val exportTimestamp: Long,
    val notes: List<Note>,
    val stamps: List<Stamp>,
    val commands: List<SpokenCommand>,
    val settings: JSONObject
)

object BackupManager {

    const val CURRENT_BACKUP_VERSION = 4
    const val MANIFEST_FILE = "manifest.json"
    const val NOTES_FILE = "notes.json"
    const val TEMPLATES_FILE = "templates.json"
    const val SETTINGS_FILE = "settings.json"
    const val COMMANDS_FILE = "spoken_commands.json"

    suspend fun exportBackup(
        context: Context,
        noteRepository: NoteRepository,
        stampRepository: StampRepository,
        spokenCommandRepository: SpokenCommandRepository,
        preferencesManager: PreferencesManager,
        outputStream: OutputStream
    ): BackupSummary = withContext(Dispatchers.IO) {
        val notes = noteRepository.getAllNotesList()
        val stamps = stampRepository.getAllStampsList()
        val commands = spokenCommandRepository.getAllCommandsList()
        val settings = preferencesManager.getAllPreferencesForBackup()

        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }

        val manifestObj = JSONObject().apply {
            put("backupVersion", CURRENT_BACKUP_VERSION)
            put("appVersion", appVersion)
            put("appName", "KothaPad")
            put("exportTimestamp", System.currentTimeMillis())
            put("notesCount", notes.size)
            put("templatesCount", stamps.size)
            put("commandsCount", commands.size)
        }

        val notesArray = JSONArray()
        for (note in notes) {
            notesArray.put(noteToJson(note))
        }

        val stampsArray = JSONArray()
        for (stamp in stamps) {
            stampsArray.put(stampToJson(stamp))
        }

        val commandsArray = JSONArray()
        for (cmd in commands) {
            commandsArray.put(spokenCommandToJson(cmd))
        }

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
            // 1. Manifest
            zipOut.putNextEntry(ZipEntry(MANIFEST_FILE))
            zipOut.write(manifestObj.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 2. Structured JSON Notes
            zipOut.putNextEntry(ZipEntry(NOTES_FILE))
            zipOut.write(notesArray.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 3. Structured JSON Templates
            zipOut.putNextEntry(ZipEntry(TEMPLATES_FILE))
            zipOut.write(stampsArray.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 4. Structured Settings
            zipOut.putNextEntry(ZipEntry(SETTINGS_FILE))
            zipOut.write(settings.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 5. Spoken Commands
            zipOut.putNextEntry(ZipEntry(COMMANDS_FILE))
            zipOut.write(commandsArray.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 6. Human-readable Markdown copies in notes/ and templates/
            val usedPaths = mutableSetOf<String>()
            for (note in notes) {
                val folder = if (note.folder.isBlank()) "General" else note.folder.trim()
                val safeTitle = sanitizeFilename(note.title.ifBlank { "Untitled Note" })
                var relPath = "notes/$folder/$safeTitle.md"
                var suffix = 1
                while (usedPaths.contains(relPath)) {
                    relPath = "notes/$folder/${safeTitle}_$suffix.md"
                    suffix++
                }
                usedPaths.add(relPath)
                zipOut.putNextEntry(ZipEntry(relPath))
                zipOut.write(note.content.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }

            for (stamp in stamps) {
                val safeLabel = sanitizeFilename(stamp.label.ifBlank { "Template" })
                val relPath = "templates/$safeLabel.md"
                if (!usedPaths.contains(relPath)) {
                    usedPaths.add(relPath)
                    zipOut.putNextEntry(ZipEntry(relPath))
                    zipOut.write(stamp.templateText.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }
            }

            zipOut.finish()
            zipOut.flush()
        }

        BackupSummary(
            notesCount = notes.size,
            templatesCount = stamps.size,
            commandsCount = commands.size
        )
    }

    suspend fun validateAndParseBackup(inputStream: InputStream): ParsedBackupData = withContext(Dispatchers.IO) {
        val entries = mutableMapOf<String, ByteArray>()

        try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val buffer = ByteArrayOutputStream()
                        zipIn.copyTo(buffer)
                        entries[entry.name] = buffer.toByteArray()
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("The selected file is not a valid or readable ZIP backup: ${e.message}")
        }

        if (entries.isEmpty()) {
            throw IllegalArgumentException("The backup archive is empty or corrupted.")
        }

        val manifestBytes = entries[MANIFEST_FILE]
        val manifestObj = manifestBytes?.let {
            try {
                JSONObject(it.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }

        val appVersion = manifestObj?.optString("appVersion", "1.0") ?: "1.0"
        val timestamp = manifestObj?.let { safeOptLong(it, "exportTimestamp", System.currentTimeMillis()) } ?: System.currentTimeMillis()
        val backupVersion = manifestObj?.let { safeOptInt(it, "backupVersion", CURRENT_BACKUP_VERSION) } ?: CURRENT_BACKUP_VERSION

        if (backupVersion > CURRENT_BACKUP_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $backupVersion. Maximum supported version is $CURRENT_BACKUP_VERSION.")
        }

        // Case 1: Standard JSON backup format (has notes.json)
        if (entries.containsKey(NOTES_FILE)) {
            val notesStr = entries[NOTES_FILE]?.toString(Charsets.UTF_8)
                ?: throw IllegalArgumentException("Missing required '$NOTES_FILE' in backup.")
            val notesList = mutableListOf<Note>()
            try {
                val notesArray = JSONArray(notesStr)
                for (i in 0 until notesArray.length()) {
                    notesList.add(jsonToNote(notesArray.getJSONObject(i)))
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Corrupted notes data in '$NOTES_FILE': ${e.message}")
            }

            val stampsList = mutableListOf<Stamp>()
            val templatesStr = entries[TEMPLATES_FILE]?.toString(Charsets.UTF_8)
            if (!templatesStr.isNullOrBlank()) {
                try {
                    val templatesArray = JSONArray(templatesStr)
                    for (i in 0 until templatesArray.length()) {
                        stampsList.add(jsonToStamp(templatesArray.getJSONObject(i)))
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException("Corrupted templates data in '$TEMPLATES_FILE': ${e.message}")
                }
            }

            val settingsStr = entries[SETTINGS_FILE]?.toString(Charsets.UTF_8)
            val settingsObj = if (!settingsStr.isNullOrBlank()) {
                try {
                    JSONObject(settingsStr)
                } catch (e: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }

            val commandsList = mutableListOf<SpokenCommand>()
            val commandsStr = entries[COMMANDS_FILE]?.toString(Charsets.UTF_8)
            if (!commandsStr.isNullOrBlank()) {
                try {
                    val commandsArray = JSONArray(commandsStr)
                    for (i in 0 until commandsArray.length()) {
                        commandsList.add(jsonToSpokenCommand(commandsArray.getJSONObject(i)))
                    }
                } catch (_: Exception) {}
            }

            return@withContext ParsedBackupData(
                backupVersion = backupVersion,
                appVersion = appVersion,
                exportTimestamp = timestamp,
                notes = notesList,
                stamps = stampsList,
                commands = commandsList,
                settings = settingsObj
            )
        }

        // Case 2: Legacy Vault ZIP format or markdown file archive
        // Detects markdown files in folders like Daily Journal/, Templates/, etc.
        val extractedNotes = mutableListOf<Note>()
        val extractedStamps = mutableListOf<Stamp>()
        var extractedSettings = JSONObject()

        // Check for .kothapad/settings.json or settings.json
        val settingsKey = entries.keys.firstOrNull {
            it.equals(".kothapad/settings.json", ignoreCase = true) ||
            it.equals(".KOTHAPAD/settings.json", ignoreCase = true) ||
            it.equals(SETTINGS_FILE, ignoreCase = true)
        }
        if (settingsKey != null) {
            try {
                extractedSettings = JSONObject(entries[settingsKey]?.toString(Charsets.UTF_8) ?: "{}")
            } catch (_: Exception) {}
        }

        // Check for .kothapad/notes-meta.json
        val metaKey = entries.keys.firstOrNull {
            it.equals(".kothapad/notes-meta.json", ignoreCase = true) ||
            it.equals(".KOTHAPAD/notes-meta.json", ignoreCase = true)
        }
        val metaObj = if (metaKey != null) {
            try {
                JSONObject(entries[metaKey]?.toString(Charsets.UTF_8) ?: "{}").optJSONObject("notes")
            } catch (_: Exception) {
                null
            }
        } else null

        for ((rawPath, bytes) in entries) {
            val path = rawPath.replace('\\', '/')
            if (path.startsWith(".") || path.contains("/.")) continue
            if (path.equals(MANIFEST_FILE, ignoreCase = true)) continue

            if (path.endsWith(".md", ignoreCase = true)) {
                val cleanPath = path.removePrefix("notes/").removePrefix("vault/")
                val content = bytes.toString(Charsets.UTF_8)
                val fileName = cleanPath.substringAfterLast("/").removeSuffix(".md")

                if (cleanPath.startsWith("Templates/", ignoreCase = true) || cleanPath.startsWith("templates/", ignoreCase = true)) {
                    extractedStamps.add(
                        Stamp(
                            label = fileName,
                            templateText = content,
                            setName = "Imported"
                        )
                    )
                } else {
                    val folderName = if (cleanPath.contains("/")) cleanPath.substringBeforeLast("/") else "General"
                    val noteMeta = metaObj?.optJSONObject(cleanPath) ?: metaObj?.optJSONObject(rawPath)

                    val isPinned = noteMeta?.optBoolean("isPinned", false) ?: false
                    val isArchived = noteMeta?.optBoolean("isArchived", false) ?: false
                    val isDeleted = noteMeta?.optBoolean("isDeleted", false) ?: false
                    val createdAt = noteMeta?.optLong("createdAt", timestamp) ?: timestamp
                    val updatedAt = noteMeta?.optLong("updatedAt", timestamp) ?: timestamp
                    val tags = noteMeta?.optString("tags", "") ?: ""

                    extractedNotes.add(
                        Note(
                            title = fileName,
                            content = content,
                            folder = folderName,
                            tags = tags,
                            isPinned = isPinned,
                            isArchived = isArchived,
                            isDeleted = isDeleted,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        }

        if (extractedNotes.isEmpty() && extractedStamps.isEmpty()) {
            throw IllegalArgumentException("The backup archive contains no recognizable notes or templates.")
        }

        ParsedBackupData(
            backupVersion = backupVersion,
            appVersion = appVersion,
            exportTimestamp = timestamp,
            notes = extractedNotes,
            stamps = extractedStamps,
            commands = emptyList(),
            settings = extractedSettings
        )
    }

    suspend fun restoreBackup(
        context: Context,
        noteRepository: NoteRepository,
        stampRepository: StampRepository,
        spokenCommandRepository: SpokenCommandRepository,
        preferencesManager: PreferencesManager,
        backupData: ParsedBackupData
    ): BackupSummary = withContext(Dispatchers.IO) {
        val previousNotes = noteRepository.getAllNotesList()
        val previousStamps = stampRepository.getAllStampsList()
        val previousCommands = spokenCommandRepository.getAllCommandsList()
        val previousSettings = preferencesManager.getAllPreferencesForBackup()

        try {
            // Restore notes
            noteRepository.deleteAllNotes()
            if (backupData.notes.isNotEmpty()) {
                noteRepository.insertAll(backupData.notes)
            }

            // Restore templates/stamps
            if (backupData.stamps.isNotEmpty()) {
                stampRepository.deleteAllStamps()
                stampRepository.insertAll(backupData.stamps)
            }

            // Restore commands
            if (backupData.commands.isNotEmpty()) {
                spokenCommandRepository.deleteAllCommands()
                spokenCommandRepository.insertAll(backupData.commands)
            }

            // Restore preferences
            val skippedSettings = if (backupData.settings.length() > 0) {
                preferencesManager.restoreAllPreferencesFromBackup(backupData.settings)
            } else {
                0
            }

            BackupSummary(
                notesCount = backupData.notes.size,
                templatesCount = backupData.stamps.size,
                commandsCount = backupData.commands.size,
                skippedCount = skippedSettings
            )
        } catch (restoreException: Exception) {
            // Rollback to previous state on failure
            try {
                noteRepository.deleteAllNotes()
                if (previousNotes.isNotEmpty()) {
                    noteRepository.insertAll(previousNotes)
                }
                stampRepository.deleteAllStamps()
                if (previousStamps.isNotEmpty()) {
                    stampRepository.insertAll(previousStamps)
                }
                if (previousCommands.isNotEmpty()) {
                    spokenCommandRepository.deleteAllCommands()
                    spokenCommandRepository.insertAll(previousCommands)
                }
                preferencesManager.restoreAllPreferencesFromBackup(previousSettings)
            } catch (_: Exception) {}

            throw Exception("Restoration failed midway: ${restoreException.message}. Previous data was safely rolled back.")
        }
    }

    // --- JSON Conversion Helpers ---

    private fun noteToJson(note: Note): JSONObject {
        return JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("content", note.content)
            put("createdAt", note.createdAt)
            put("updatedAt", note.updatedAt)
            put("isPinned", note.isPinned)
            put("languageCode", note.languageCode ?: JSONObject.NULL)
            put("highlightColor", note.highlightColor ?: JSONObject.NULL)
            put("fontSizeModifier", note.fontSizeModifier.toDouble())
            put("folder", note.folder)
            put("tags", note.tags)
            put("isArchived", note.isArchived)
            put("isDeleted", note.isDeleted)
            put("deletedAt", note.deletedAt ?: JSONObject.NULL)
            put("wordCount", note.wordCount)
            put("charCount", note.charCount)
        }
    }

    private fun jsonToNote(obj: JSONObject): Note {
        return Note(
            id = safeOptLong(obj, "id", 0L),
            title = obj.optString("title", "Untitled Note"),
            content = obj.optString("content", ""),
            createdAt = safeOptLong(obj, "createdAt", System.currentTimeMillis()),
            updatedAt = safeOptLong(obj, "updatedAt", System.currentTimeMillis()),
            isPinned = safeOptBoolean(obj, "isPinned", false),
            languageCode = if (obj.isNull("languageCode")) null else obj.optString("languageCode"),
            highlightColor = if (obj.isNull("highlightColor")) null else obj.optString("highlightColor"),
            fontSizeModifier = safeOptFloat(obj, "fontSizeModifier", 0.0f),
            folder = obj.optString("folder", "General"),
            tags = obj.optString("tags", ""),
            isArchived = safeOptBoolean(obj, "isArchived", false),
            isDeleted = safeOptBoolean(obj, "isDeleted", false),
            deletedAt = if (obj.isNull("deletedAt")) null else safeOptLong(obj, "deletedAt", 0L),
            wordCount = safeOptInt(obj, "wordCount", 0),
            charCount = safeOptInt(obj, "charCount", 0)
        )
    }

    private fun stampToJson(stamp: Stamp): JSONObject {
        return JSONObject().apply {
            put("id", stamp.id)
            put("setName", stamp.setName)
            put("label", stamp.label)
            put("templateText", stamp.templateText)
            put("voiceTriggerPhrase", stamp.voiceTriggerPhrase ?: JSONObject.NULL)
            put("orderIndex", stamp.orderIndex)
            put("isPinned", stamp.isPinned)
        }
    }

    private fun jsonToStamp(obj: JSONObject): Stamp {
        return Stamp(
            id = safeOptLong(obj, "id", 0L),
            setName = obj.optString("setName", "Default"),
            label = obj.optString("label", "Untitled Template"),
            templateText = obj.optString("templateText", ""),
            voiceTriggerPhrase = if (obj.isNull("voiceTriggerPhrase")) null else obj.optString("voiceTriggerPhrase"),
            orderIndex = safeOptInt(obj, "orderIndex", 0),
            isPinned = safeOptBoolean(obj, "isPinned", false)
        )
    }

    private fun spokenCommandToJson(cmd: SpokenCommand): JSONObject {
        return JSONObject().apply {
            put("id", cmd.id)
            put("language", cmd.language)
            put("phrase", cmd.phrase)
            put("actionType", cmd.actionType)
            put("replacementText", cmd.replacementText)
            put("commandCode", cmd.commandCode ?: JSONObject.NULL)
            put("isEnabled", cmd.isEnabled)
        }
    }

    private fun jsonToSpokenCommand(obj: JSONObject): SpokenCommand {
        return SpokenCommand(
            id = safeOptLong(obj, "id", 0L),
            language = obj.optString("language", "en"),
            phrase = obj.optString("phrase", ""),
            actionType = obj.optString("actionType", "PUNCTUATION"),
            replacementText = obj.optString("replacementText", ""),
            commandCode = if (obj.isNull("commandCode")) null else obj.optString("commandCode"),
            isEnabled = safeOptBoolean(obj, "isEnabled", true)
        )
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "file" }
    }

    // --- Safe Type Conversion Helpers ---

    fun safeToLong(value: Any?, default: Long = 0L): Long {
        if (value == null || value == JSONObject.NULL) return default
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            is Boolean -> if (value) 1L else 0L
            else -> default
        }
    }

    fun safeToInt(value: Any?, default: Int = 0): Int {
        if (value == null || value == JSONObject.NULL) return default
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            is Boolean -> if (value) 1 else 0
            else -> default
        }
    }

    fun safeToFloat(value: Any?, default: Float = 0.0f): Float {
        if (value == null || value == JSONObject.NULL) return default
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: default
            else -> default
        }
    }

    fun safeToBoolean(value: Any?, default: Boolean = false): Boolean {
        if (value == null || value == JSONObject.NULL) return default
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when {
                value.equals("true", ignoreCase = true) -> true
                value.equals("false", ignoreCase = true) -> false
                value == "1" -> true
                value == "0" -> false
                else -> default
            }
            else -> default
        }
    }

    fun safeOptLong(obj: JSONObject, key: String, default: Long = 0L): Long {
        return safeToLong(obj.opt(key), default)
    }

    fun safeOptInt(obj: JSONObject, key: String, default: Int = 0): Int {
        return safeToInt(obj.opt(key), default)
    }

    fun safeOptFloat(obj: JSONObject, key: String, default: Float = 0.0f): Float {
        return safeToFloat(obj.opt(key), default)
    }

    fun safeOptBoolean(obj: JSONObject, key: String, default: Boolean = false): Boolean {
        return safeToBoolean(obj.opt(key), default)
    }
}
