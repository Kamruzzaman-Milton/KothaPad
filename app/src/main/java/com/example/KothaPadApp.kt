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
                resumedActivities++
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    companion object {
        @Volatile
        private var resumedActivities = 0

        val isAppInForeground: Boolean
            get() = resumedActivities > 0
    }
}
