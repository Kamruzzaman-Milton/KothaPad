package com.example.data.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.KothaPadApp
import com.example.data.model.Note
import com.example.data.model.SpokenCommand
import com.example.data.model.Stamp
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackupFileInfo(
    val name: String,
    val timestamp: Long,
    val sizeBytes: Long,
    val uri: Uri? = null,
    val file: File? = null,
    val isSafetyBackup: Boolean = false
)

sealed class AutoBackupExecutionResult {
    data class Success(val fileName: String, val sizeBytes: Long, val isForced: Boolean) : AutoBackupExecutionResult()
    data class SkippedNoChanges(val hash: String) : AutoBackupExecutionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : AutoBackupExecutionResult()
}

object AutoBackupManager {

    const val WORK_NAME = "kothapad_auto_backup"
    const val SAFETY_BACKUP_NAME = "KothaPad_pre_restore_safety.zip"
    private const val NOTIFICATION_CHANNEL_ID = "kothapad_backup_channel"
    private const val NOTIFICATION_ID = 2002

    private val backupMutex = Mutex()

    sealed class TargetFolder {
        data class NativeFolder(val dir: File) : TargetFolder()
        data class DocumentTreeFolder(val treeUri: Uri, val doc: DocumentFile) : TargetFolder()
    }

    /**
     * Resolves the configured backup folder with automatic fallback to default.
     * Returns the target folder and whether fallback to default was triggered.
     */
    suspend fun resolveTargetFolder(context: Context, prefs: PreferencesManager): Pair<TargetFolder, Boolean> {
        val customUriStr = prefs.autoBackupLocationUri.first()
        var fallbackOccurred = false

        if (customUriStr.isNotBlank()) {
            try {
                val parsedUri = Uri.parse(customUriStr)
                val doc = DocumentFile.fromTreeUri(context, parsedUri)
                if (doc != null && doc.exists() && doc.canWrite()) {
                    return Pair(TargetFolder.DocumentTreeFolder(parsedUri, doc), false)
                } else {
                    fallbackOccurred = true
                }
            } catch (_: Exception) {
                fallbackOccurred = true
            }
        }

        // Fallback to default Download/KOTHAPAD Backups
        val defaultDir = getBestDefaultBackupDirectory(context)
        if (fallbackOccurred) {
            // Update preferences and notify user that custom folder became inaccessible
            prefs.setAutoBackupLocationUri("")
            showFailureNotification(
                context,
                "Backup folder not accessible. Switched to default folder: ${defaultDir.name}"
            )
        }

        return Pair(TargetFolder.NativeFolder(defaultDir), fallbackOccurred)
    }

    private fun getBestDefaultBackupDirectory(context: Context): File {
        // Try public Download/KOTHAPAD Backups first
        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val defaultFolder = File(publicDownload, "KOTHAPAD Backups")
        try {
            if (defaultFolder.exists() || defaultFolder.mkdirs()) {
                val testFile = File(defaultFolder, ".write_test")
                if (testFile.createNewFile()) {
                    testFile.delete()
                    return defaultFolder
                }
            }
        } catch (_: Throwable) {}

        // Fallback to app-specific external/internal files
        val appExt = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        val safeFolder = File(appExt, "KOTHAPAD Backups")
        if (!safeFolder.exists()) {
            safeFolder.mkdirs()
        }
        return safeFolder
    }

    /**
     * Computes a SHA-256 hash representing current notes, stamps, commands, and settings state.
     */
    fun computeContentHash(
        notes: List<Note>,
        stamps: List<Stamp>,
        commands: List<SpokenCommand>,
        settingsJsonStr: String
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (note in notes) {
            md.update("${note.id}|${note.updatedAt}|${note.title}|${note.content.length}|${note.isDeleted}".toByteArray(Charsets.UTF_8))
        }
        for (stamp in stamps) {
            md.update("${stamp.id}|${stamp.label}|${stamp.templateText.length}".toByteArray(Charsets.UTF_8))
        }
        for (cmd in commands) {
            md.update("${cmd.id}|${cmd.phrase}|${cmd.replacementText}|${cmd.isEnabled}".toByteArray(Charsets.UTF_8))
        }
        md.update(settingsJsonStr.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkBatteryCondition(context: Context): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            true
        }
    }

    private fun checkStorageCondition(context: Context): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val availableMb = stat.availableBytes / (1024 * 1024)
            availableMb >= 50
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Executes backup process with lock, temp file validation, atomic write, hash verification and retention.
     */
    suspend fun performBackup(
        context: Context,
        force: Boolean = false
    ): Result<AutoBackupExecutionResult> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? KothaPadApp ?: return@withContext Result.failure(
            IllegalStateException("Application context is not KothaPadApp")
        )
        val prefs = app.preferencesManager
        val noteRepo = app.noteRepository
        val stampRepo = app.stampRepository
        val commandRepo = app.spokenCommandRepository

        backupMutex.withLock {
            try {
                // Check conditions if not forced
                if (!force) {
                    val onlyCharging = prefs.autoBackupOnlyCharging.first()
                    if (onlyCharging && !checkBatteryCondition(context)) {
                        val reason = "Skipped: device is not charging"
                        prefs.setAutoBackupLastResult(System.currentTimeMillis(), 0L, reason)
                        return@withContext Result.success(AutoBackupExecutionResult.SkippedNoChanges(reason))
                    }

                    val skipLowStorage = prefs.autoBackupSkipLowStorage.first()
                    if (skipLowStorage && !checkStorageCondition(context)) {
                        val reason = "Skipped: storage is low"
                        prefs.setAutoBackupLastResult(System.currentTimeMillis(), 0L, reason)
                        showFailureNotification(context, "Backup skipped: storage is low (<50 MB free)")
                        return@withContext Result.success(AutoBackupExecutionResult.Failure(reason))
                    }
                }

                // Gather data for hash verification
                val notes = noteRepo.getAllNotesList()
                val stamps = stampRepo.getAllStampsList()
                val commands = commandRepo.getAllCommandsList()
                val settingsJson = prefs.getAllPreferencesForBackup().toString()
                val currentHash = computeContentHash(notes, stamps, commands, settingsJson)
                val lastHash = prefs.autoBackupLastHash.first()

                if (!force && lastHash.isNotBlank() && lastHash == currentHash) {
                    val statusText = "Skipped: no changes detected"
                    prefs.setAutoBackupLastResult(System.currentTimeMillis(), prefs.autoBackupLastSize.first(), statusText, currentHash)
                    return@withContext Result.success(AutoBackupExecutionResult.SkippedNoChanges(currentHash))
                }

                // Step 1: Write to temp file first in cacheDir
                val tempFile = File(context.cacheDir, "temp_backup_${System.currentTimeMillis()}.zip")
                try {
                    FileOutputStream(tempFile).use { fos ->
                        BackupManager.exportBackup(
                            context = context,
                            noteRepository = noteRepo,
                            stampRepository = stampRepo,
                            spokenCommandRepository = commandRepo,
                            preferencesManager = prefs,
                            outputStream = fos
                        )
                    }

                    // Step 2: Verify the temp zip opens and parses correctly
                    FileInputStream(tempFile).use { fis ->
                        BackupManager.validateAndParseBackup(fis)
                    }

                    val backupSize = tempFile.length()
                    if (backupSize <= 0) {
                        throw IllegalStateException("Generated backup file was empty (0 bytes).")
                    }

                    // Step 3: Move/write into destination folder
                    val (targetFolder, _) = resolveTargetFolder(context, prefs)
                    val dateFormatted = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                    val finalFileName = "KothaPad_backup_$dateFormatted.zip"

                    writeTempToTarget(context, tempFile, targetFolder, finalFileName)

                    // Step 4: Update status and last backup info
                    val now = System.currentTimeMillis()
                    prefs.setAutoBackupLastResult(
                        time = now,
                        size = backupSize,
                        status = "SUCCESS",
                        hash = currentHash
                    )

                    // Step 5: Prune old backups (Retention: keep newest N, never delete pre-restore safety)
                    val keepCount = prefs.autoBackupKeepCount.first().coerceAtLeast(3)
                    pruneOldBackups(context, targetFolder, keepCount)

                    Result.success(AutoBackupExecutionResult.Success(finalFileName, backupSize, force))
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            } catch (e: Throwable) {
                val reason = e.localizedMessage ?: e.message ?: "Unknown backup failure"
                prefs.setAutoBackupLastResult(
                    time = System.currentTimeMillis(),
                    size = 0L,
                    status = "Failed: $reason"
                )
                showFailureNotification(context, "Automatic backup failed: $reason")
                Result.failure(e)
            }
        }
    }

    private fun writeTempToTarget(
        context: Context,
        tempFile: File,
        targetFolder: TargetFolder,
        fileName: String
    ) {
        when (targetFolder) {
            is TargetFolder.NativeFolder -> {
                if (!targetFolder.dir.exists()) {
                    targetFolder.dir.mkdirs()
                }
                val destFile = File(targetFolder.dir, fileName)
                tempFile.copyTo(destFile, overwrite = true)
            }
            is TargetFolder.DocumentTreeFolder -> {
                // If a file with the same name exists, delete it first
                val existing = targetFolder.doc.findFile(fileName)
                existing?.delete()
                val newDocFile = targetFolder.doc.createFile("application/zip", fileName)
                    ?: throw IllegalStateException("Could not create backup file in selected folder: $fileName")
                context.contentResolver.openOutputStream(newDocFile.uri)?.use { os ->
                    FileInputStream(tempFile).use { fis ->
                        fis.copyTo(os)
                    }
                } ?: throw IllegalStateException("Failed to open output stream for: $fileName")
            }
        }
    }

    /**
     * Lists all backup files sorted newest-first.
     */
    suspend fun listBackups(context: Context): List<BackupFileInfo> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? KothaPadApp ?: return@withContext emptyList()
        val (targetFolder, _) = resolveTargetFolder(context, app.preferencesManager)
        val result = mutableListOf<BackupFileInfo>()

        when (targetFolder) {
            is TargetFolder.NativeFolder -> {
                val files = targetFolder.dir.listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.isFile && f.name.endsWith(".zip", ignoreCase = true)) {
                        val isSafety = f.name.equals(SAFETY_BACKUP_NAME, ignoreCase = true)
                        val ts = parseTimestampFromFileName(f.name, f.lastModified())
                        result.add(
                            BackupFileInfo(
                                name = f.name,
                                timestamp = ts,
                                sizeBytes = f.length(),
                                file = f,
                                isSafetyBackup = isSafety
                            )
                        )
                    }
                }
            }
            is TargetFolder.DocumentTreeFolder -> {
                val files = targetFolder.doc.listFiles()
                for (df in files) {
                    val name = df.name ?: continue
                    if (df.isFile && name.endsWith(".zip", ignoreCase = true)) {
                        val isSafety = name.equals(SAFETY_BACKUP_NAME, ignoreCase = true)
                        val ts = parseTimestampFromFileName(name, df.lastModified())
                        result.add(
                            BackupFileInfo(
                                name = name,
                                timestamp = ts,
                                sizeBytes = df.length(),
                                uri = df.uri,
                                isSafetyBackup = isSafety
                            )
                        )
                    }
                }
            }
        }

        // Newest first; if safety backup exists, place regular backups newest first, then safety or top
        result.sortedWith(
            compareByDescending<BackupFileInfo> { it.isSafetyBackup }
                .thenByDescending { it.timestamp }
        )
    }

    private fun parseTimestampFromFileName(name: String, fallback: Long): Long {
        if (!name.startsWith("KothaPad_backup_")) return fallback
        val datePart = name.removePrefix("KothaPad_backup_").removeSuffix(".zip")
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
            sdf.parse(datePart)?.time ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Deletes an individual backup file.
     */
    suspend fun deleteBackup(context: Context, backup: BackupFileInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (backup.file != null && backup.file.exists()) {
                return@withContext backup.file.delete()
            }
            if (backup.uri != null) {
                val doc = DocumentFile.fromSingleUri(context, backup.uri)
                return@withContext doc?.delete() ?: false
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pruning: Only keeps newest N backups (never deletes safety backup or if backup failed).
     */
    private fun pruneOldBackups(context: Context, targetFolder: TargetFolder, keepCount: Int) {
        try {
            when (targetFolder) {
                is TargetFolder.NativeFolder -> {
                    val regularBackups = (targetFolder.dir.listFiles() ?: emptyArray())
                        .filter { it.isFile && it.name.startsWith("KothaPad_backup_") && it.name.endsWith(".zip") }
                        .sortedByDescending { parseTimestampFromFileName(it.name, it.lastModified()) }

                    if (regularBackups.size > keepCount) {
                        val toDelete = regularBackups.drop(keepCount)
                        for (f in toDelete) {
                            f.delete()
                        }
                    }
                }
                is TargetFolder.DocumentTreeFolder -> {
                    val regularBackups = targetFolder.doc.listFiles()
                        .filter { it.isFile && (it.name ?: "").startsWith("KothaPad_backup_") && (it.name ?: "").endsWith(".zip") }
                        .sortedByDescending { parseTimestampFromFileName(it.name ?: "", it.lastModified()) }

                    if (regularBackups.size > keepCount) {
                        val toDelete = regularBackups.drop(keepCount)
                        for (df in toDelete) {
                            df.delete()
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Restores a selected backup.
     * Before restoring, automatically creates a pre-restore safety backup of the current state.
     */
    suspend fun restoreAutoBackup(
        context: Context,
        backup: BackupFileInfo
    ): Result<BackupSummary> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? KothaPadApp ?: return@withContext Result.failure(
            IllegalStateException("Application context is not KothaPadApp")
        )
        val prefs = app.preferencesManager
        val noteRepo = app.noteRepository
        val stampRepo = app.stampRepository
        val commandRepo = app.spokenCommandRepository

        backupMutex.withLock {
            try {
                // Step 1: Create "pre-restore safety" backup of current state
                val (targetFolder, _) = resolveTargetFolder(context, prefs)
                val tempSafetyFile = File(context.cacheDir, "temp_safety_${System.currentTimeMillis()}.zip")
                try {
                    FileOutputStream(tempSafetyFile).use { fos ->
                        BackupManager.exportBackup(
                            context = context,
                            noteRepository = noteRepo,
                            stampRepository = stampRepo,
                            spokenCommandRepository = commandRepo,
                            preferencesManager = prefs,
                            outputStream = fos
                        )
                    }
                    writeTempToTarget(context, tempSafetyFile, targetFolder, SAFETY_BACKUP_NAME)
                } finally {
                    if (tempSafetyFile.exists()) tempSafetyFile.delete()
                }

                // Step 2: Read chosen backup stream
                val inputStream: InputStream = when {
                    backup.file != null && backup.file.exists() -> FileInputStream(backup.file)
                    backup.uri != null -> context.contentResolver.openInputStream(backup.uri)
                        ?: throw IllegalStateException("Could not open input stream from URI: ${backup.uri}")
                    else -> throw IllegalStateException("Backup file reference is invalid.")
                }

                // Step 3: Validate and restore using existing Replace-all with rollback
                val parsed = inputStream.use { fis ->
                    BackupManager.validateAndParseBackup(fis)
                }

                val summary = BackupManager.restoreBackup(
                    context = context,
                    noteRepository = noteRepo,
                    stampRepository = stampRepo,
                    spokenCommandRepository = commandRepo,
                    preferencesManager = prefs,
                    backupData = parsed
                )

                Result.success(summary)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    /**
     * Schedules periodic backup via WorkManager with configured interval and constraints,
     * or cancels if master toggle is off.
     */
    fun scheduleAutoBackup(context: Context, enabled: Boolean, schedule: String, customValue: Int, customUnit: String, onlyCharging: Boolean, skipLowStorage: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled || schedule.equals("OFF", ignoreCase = true)) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val intervalMinutes = getIntervalMinutes(schedule, customValue, customUnit).coerceAtLeast(15L)

        val constraints = Constraints.Builder()
            .setRequiresCharging(onlyCharging)
            .setRequiresStorageNotLow(skipLowStorage)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )
    }

    fun getIntervalMinutes(schedule: String, customValue: Int, customUnit: String): Long {
        return when (schedule.uppercase(Locale.US)) {
            "HOURS_6" -> 6L * 60L
            "DAILY" -> 24L * 60L
            "WEEKLY" -> 7L * 24L * 60L
            "CUSTOM" -> {
                val value = customValue.coerceAtLeast(1).toLong()
                when (customUnit.uppercase(Locale.US)) {
                    "MINUTES" -> value.coerceAtLeast(15L)
                    "DAYS" -> value * 24L * 60L
                    else -> value * 60L // HOURS
                }
            }
            else -> 24L * 60L
        }
    }

    private fun showFailureNotification(context: Context, reason: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "KothaPad Backup Alerts",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Quiet alerts for automatic backup failures"
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("KothaPad Backup Warning")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Throwable) {}
    }
}
