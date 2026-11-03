package com.example.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = AutoBackupManager.performBackup(applicationContext, force = false)
        if (result.isSuccess) {
            Result.success()
        } else {
            // Note: Failures already post quiet notification and update status in AutoBackupManager
            Result.success()
        }
    }
}
