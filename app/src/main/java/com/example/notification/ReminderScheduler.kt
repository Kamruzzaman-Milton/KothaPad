package com.example.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.KothaPadApp
import com.example.MainActivity
import com.example.R
import com.example.data.model.NotificationDisplayMode
import com.example.data.model.ScheduledReminder
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Robust Scheduler for Reminders with exact AlarmManager alarms,
 * high-priority notification channel, and Screen Overlay / System notification delivery.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    const val CHANNEL_ID = "kothapad_reminders_v2"
    const val CHANNEL_NAME = "Daily Journal Reminders"

    const val ACTION_TRIGGER_REMINDER = "com.example.notification.ACTION_TRIGGER_REMINDER"
    const val ACTION_PLUS_CLICK = "com.example.notification.ACTION_PLUS_CLICK"
    const val ACTION_DISMISS_NOTIFICATION = "com.example.notification.ACTION_DISMISS_NOTIFICATION"
    const val ACTION_EXIT_APP = "com.example.notification.ACTION_EXIT_APP"

    const val PERSISTENT_CHANNEL_ID = "kothapad_persistent_channel"
    const val PERSISTENT_CHANNEL_NAME = "Active App & Background Reminder"
    const val PERSISTENT_NOTIFICATION_ID = 9999

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    const val EXTRA_REMINDER_TIME = "extra_reminder_time"
    const val EXTRA_SCHEDULED_TIMESTAMP = "extra_scheduled_timestamp"
    const val EXTRA_DISPLAY_MODE = "extra_display_mode"

    // Live state flow for in-app floating notification preview in Settings
    private val _activeFloatingReminder = MutableStateFlow<ScheduledReminder?>(null)
    val activeFloatingReminder = _activeFloatingReminder.asStateFlow()

    fun showFloatingReminder(reminder: ScheduledReminder) {
        _activeFloatingReminder.value = reminder
    }

    fun dismissFloatingReminder() {
        _activeFloatingReminder.value = null
    }

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Custom notifications and reminders for KothaPad journal"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Reschedules all enabled reminders using Android AlarmManager.
     */
    fun rescheduleAll(context: Context, reminders: List<ScheduledReminder>) {
        ensureNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        reminders.forEach { reminder ->
            val pi = getAlarmPendingIntent(context, reminder)
            alarmManager.cancel(pi)
            if (reminder.isEnabled) {
                scheduleSingle(context, alarmManager, reminder)
            }
        }
    }

    fun calculateNextTriggerTime(reminder: ScheduledReminder): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // If scheduled time has already passed today, schedule for tomorrow
            if (timeInMillis <= now) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }

    fun scheduleSingle(context: Context, alarmManager: AlarmManager, reminder: ScheduledReminder) {
        val targetTime = calculateNextTriggerTime(reminder)
        val pi = getAlarmPendingIntent(context, reminder, targetTime)

        Log.d(TAG, "Scheduling reminder ${reminder.id} at $targetTime (${reminder.formattedTime()})")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetTime,
                    pi
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    targetTime,
                    pi
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm permission restricted, falling back to setAndAllowWhileIdle / set", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetTime,
                    pi
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    targetTime,
                    pi
                )
            }
        }
    }

    fun cancelSingle(context: Context, reminder: ScheduledReminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = getAlarmPendingIntent(context, reminder)
        alarmManager.cancel(pi)
    }

    private fun getAlarmPendingIntent(
        context: Context,
        reminder: ScheduledReminder,
        targetTime: Long = calculateNextTriggerTime(reminder)
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            setPackage(context.packageName)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TEXT, reminder.getEffectiveText())
            putExtra(EXTRA_REMINDER_TIME, reminder.formattedTime())
            putExtra(EXTRA_SCHEDULED_TIMESTAMP, targetTime)
            putExtra(EXTRA_DISPLAY_MODE, reminder.displayMode.name)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            flags
        )
    }

    /**
     * Posts standard Android system notification with content text, scheduled time, heads-up priority, and direct (+) Note Action.
     * Guaranteed to display the original scheduled reminder time, never current clock time.
     */
    fun showSystemNotification(
        context: Context,
        reminderId: String,
        text: String,
        timeStr: String = "",
        scheduledTimestamp: Long = 0L
    ) {
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Tap content: open today's note directly in editing mode
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_PLUS_CLICK
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action (+) Journal: same action
        val plusAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            "+ Journal Today",
            contentPendingIntent
        ).build()

        val dismissIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DISMISS_NOTIFICATION
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Title prominently contains the exact scheduled time associated with this reminder
        val title = if (timeStr.isNotBlank()) "KothaPad Reminder • $timeStr" else "KothaPad Reminder"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(dismissPendingIntent)
            .addAction(plusAction)
            .setFullScreenIntent(contentPendingIntent, false) // Enables heads-up banner on devices

        // Ensure the notification header displays the scheduled reminder time rather than current device clock
        if (scheduledTimestamp > 0L) {
            builder.setWhen(scheduledTimestamp)
            builder.setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        nm.notify(reminderId.hashCode(), builder.build())
    }

    fun ensurePersistentNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                PERSISTENT_CHANNEL_ID,
                PERSISTENT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows an ongoing reminder and indicator when KothaPad is running"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun updatePersistentNotification(context: Context, enabled: Boolean, message: String = "") {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!enabled) {
            nm.cancel(PERSISTENT_NOTIFICATION_ID)
            return
        }

        ensurePersistentNotificationChannel(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            1001,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: "+ Journal Today"
        val plusIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_PLUS_CLICK
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val plusPendingIntent = PendingIntent.getActivity(
            context,
            1002,
            plusIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val plusAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            "+ Journal Today",
            plusPendingIntent
        ).build()

        // Action: "Exit"
        val exitIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_EXIT_APP
        }
        val exitPendingIntent = PendingIntent.getBroadcast(
            context,
            1003,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Exit",
            exitPendingIntent
        ).build()

        val text = message.ifBlank { "Did I forget to journal today?" }

        val builder = NotificationCompat.Builder(context, PERSISTENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("KothaPad • Active")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle("KothaPad • Active").bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(plusAction)
            .addAction(exitAction)

        nm.notify(PERSISTENT_NOTIFICATION_ID, builder.build())
    }
}

/**
 * BroadcastReceiver triggered by AlarmManager or Boot.
 * Uses goAsync() and WakeLock to guarantee complete execution when minimized or closed.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val prefManager = PreferencesManager(context)
                        val json = prefManager.scheduledRemindersJson.first()
                        val reminders = ScheduledReminder.listFromJson(json)
                        ReminderScheduler.rescheduleAll(context, reminders)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ReminderScheduler.ACTION_DISMISS_NOTIFICATION -> {
                ScreenOverlayManager.removeOverlay()
            }

            ReminderScheduler.ACTION_EXIT_APP -> {
                ReminderScheduler.updatePersistentNotification(context, false)
                KothaPadApp.exitAppCompletely()
            }

            ReminderScheduler.ACTION_TRIGGER_REMINDER -> {
                val pendingResult = goAsync()
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "kothapad:reminder_alarm_wakelock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(15_000L) // 15 seconds max safety timeout
                }

                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: ""
                        val text = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TEXT) ?: "Did I forget to journal today?"
                        val timeStr = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TIME) ?: ""
                        val scheduledTimestamp = intent.getLongExtra(ReminderScheduler.EXTRA_SCHEDULED_TIMESTAMP, 0L)
                        val modeStr = intent.getStringExtra(ReminderScheduler.EXTRA_DISPLAY_MODE) ?: NotificationDisplayMode.NORMAL.name
                        val displayMode = try {
                            NotificationDisplayMode.valueOf(modeStr)
                        } catch (_: Exception) {
                            NotificationDisplayMode.NORMAL
                        }

                        // Re-arm for tomorrow at same scheduled time
                        val prefManager = PreferencesManager(context)
                        val json = prefManager.scheduledRemindersJson.first()
                        val reminders = ScheduledReminder.listFromJson(json)
                        val reminder = reminders.find { it.id == reminderId }
                        if (reminder != null && reminder.isEnabled) {
                            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                            if (am != null) {
                                ReminderScheduler.scheduleSingle(context, am, reminder)
                            }
                        }

                        // Check if user is actively using the app in foreground right now
                        val isActivelyUsingApp = KothaPadApp.isAppActivelyInForeground(context)

                        Log.d("ReminderReceiver", "Alarm fired for $reminderId ($timeStr). isActivelyUsingApp=$isActivelyUsingApp, mode=$displayMode")

                        // Core Requirement:
                        // "When I am actively using the app, notifications should NOT appear.
                        // However, whenever I leave the app, regardless of whether it remains running in the background
                        // or is completely removed from the active app screen, scheduled notifications must be delivered reliably."
                        if (isActivelyUsingApp) {
                            Log.d("ReminderReceiver", "User is actively journaling in KothaPad. Suppressing notification as requested.")
                            return@launch
                        }

                        // User has left the app (minimized, running in background, or completely closed):
                        // Deliver reliably!
                        ReminderScheduler.showSystemNotification(
                            context = context,
                            reminderId = reminderId,
                            text = text,
                            timeStr = timeStr,
                            scheduledTimestamp = scheduledTimestamp
                        )

                        // If user configured floating screen overlay mode, also display the prominent popup
                        if (displayMode == NotificationDisplayMode.FLOATING) {
                            if (XiaomiOverlayHelper.canDrawOverlays(context)) {
                                ScreenOverlayManager.showOverlayDirect(context, reminderId, text, timeStr)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ReminderReceiver", "Error delivering reminder", e)
                    } finally {
                        try {
                            if (wakeLock?.isHeld == true) {
                                wakeLock.release()
                            }
                        } catch (_: Exception) {}
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
