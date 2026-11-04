package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.NoteRepository
import com.example.data.repository.SpokenCommandRepository
import com.example.data.repository.StampRepository
import com.example.engine.dictation.SpeechSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class KothaPadApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this, applicationScope) }
    val preferencesManager by lazy { PreferencesManager(this) }
    val noteRepository by lazy { NoteRepository(database.noteDao()) }
    val stampRepository by lazy { StampRepository(database.stampDao()) }
    val spokenCommandRepository by lazy { SpokenCommandRepository(database.spokenCommandDao()) }
    val markdownSettingsRepository by lazy { com.example.markdown.MarkdownSettingsRepository(preferencesManager) }

    val speechSupervisor by lazy {
        SpeechSupervisor(this, preferencesManager, applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        speechSupervisor.restoreCrashSafetyMute()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                currentActivity = java.lang.ref.WeakReference(activity)
                resumedActivityCount++
                isActivityInForeground = true
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
                if (resumedActivityCount == 0) {
                    isActivityInForeground = false
                }
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                currentActivity = java.lang.ref.WeakReference(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {
                if (resumedActivityCount == 0) {
                    isActivityInForeground = false
                }
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentActivity?.get() == activity) {
                    currentActivity = null
                }
                if (resumedActivityCount == 0) {
                    isActivityInForeground = false
                }
            }
        })
    }

    companion object {
        @Volatile
        private var resumedActivityCount = 0

        @Volatile
        var isActivityInForeground: Boolean = false

        @Volatile
        var currentActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

        fun exitAppCompletely() {
            try {
                currentActivity?.get()?.finishAffinity()
            } catch (_: Exception) {}
        }

        /**
         * Authoritative check whether the user is actively using KothaPad in the foreground.
         * Combines OS process memory state with activity lifecycle tracking.
         * Returns false whenever the app is minimized, running in background, or closed.
         */
        fun isAppActivelyInForeground(context: android.content.Context): Boolean {
            // First check OS-level process importance (instant synchronous query without IPC)
            val appProcessInfo = android.app.ActivityManager.RunningAppProcessInfo()
            try {
                android.app.ActivityManager.getMyMemoryState(appProcessInfo)
                // If process importance is NOT IMPORTANCE_FOREGROUND (100), the app is 100% in background / minimized / cached
                if (appProcessInfo.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return false
                }
            } catch (_: Exception) {
                // Ignore and fall back to activity tracker
            }

            // If OS reports foreground, also confirm our Activity is actually resumed and visible on screen
            return isActivityInForeground && resumedActivityCount > 0
        }

        val isAppInForeground: Boolean
            get() = isActivityInForeground && resumedActivityCount > 0
    }
}
