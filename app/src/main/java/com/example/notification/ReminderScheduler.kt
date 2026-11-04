package com.example.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    const val EXTRA_REMINDER_TIME = "extra_reminder_time"
    const val EXTRA_DISPLAY_MODE = "extra_display_mode"

    // Live state flow for in-app floating notification preview / popup when app is open
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

    fun scheduleSingle(context: Context, alarmManager: AlarmManager, reminder: ScheduledReminder) {
        val pi = getAlarmPendingIntent(context, reminder)

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // If time is now or in the past (within 10 seconds leeway), schedule for tomorrow
            if (timeInMillis <= now + 10_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val targetTime = calendar.timeInMillis
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
            Log.w(TAG, "Exact alarm permission restricted, falling back to set()", e)
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                targetTime,
                pi
            )
        }
    }

    fun cancelSingle(context: Context, reminder: ScheduledReminder) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = getAlarmPendingIntent(context, reminder)
        alarmManager.cancel(pi)
    }

    private fun getAlarmPendingIntent(context: Context, reminder: ScheduledReminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
            // Explicit component ensures delivery even if app is stopped or in background
            setPackage(context.packageName)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TEXT, reminder.getEffectiveText())
            putExtra(EXTRA_REMINDER_TIME, reminder.formattedTime())
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
     */
    fun showSystemNotification(context: Context, reminderId: String, text: String, timeStr: String = "") {
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

        val title = if (timeStr.isNotBlank()) "KothaPad Reminder • $timeStr" else "KothaPad Reminder"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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
            .addAction(plusAction)
            .setFullScreenIntent(contentPendingIntent, false) // Enables heads-up banner on devices
            .build()

        nm.notify(reminderId.hashCode(), notification)
    }
}

/**
 * BroadcastReceiver triggered by AlarmManager or Boot.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val prefManager = PreferencesManager(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val json = prefManager.scheduledRemindersJson.first()
                    val reminders = ScheduledReminder.listFromJson(json)
                    ReminderScheduler.rescheduleAll(context, reminders)
                }
            }

            ReminderScheduler.ACTION_TRIGGER_REMINDER -> {
                val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: ""
                val text = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TEXT) ?: "Did I forget to journal today?"
                val timeStr = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_TIME) ?: ""
                val modeStr = intent.getStringExtra(ReminderScheduler.EXTRA_DISPLAY_MODE) ?: NotificationDisplayMode.NORMAL.name
                val displayMode = try {
                    NotificationDisplayMode.valueOf(modeStr)
                } catch (_: Exception) {
                    NotificationDisplayMode.NORMAL
                }

                // Check if reminder is enabled and re-arm for tomorrow at same time
                val prefManager = PreferencesManager(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val json = prefManager.scheduledRemindersJson.first()
                    val reminders = ScheduledReminder.listFromJson(json)
                    val reminder = reminders.find { it.id == reminderId }
                    if (reminder != null && reminder.isEnabled) {
                        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                        if (am != null) {
                            ReminderScheduler.scheduleSingle(context, am, reminder)
                        }
                    }
                }

                val isForeground = com.example.KothaPadApp.isAppInForeground

                if (displayMode == NotificationDisplayMode.FLOATING) {
                    if (isForeground) {
                        // App is actively open and in foreground right now
                        // Show in-app floating banner with the scheduled time preserved
                        val cal = Calendar.getInstance()
                        ReminderScheduler.showFloatingReminder(
                            ScheduledReminder(
                                id = reminderId,
                                hour = cal.get(Calendar.HOUR_OF_DAY),
                                minute = cal.get(Calendar.MINUTE),
                                displayMode = NotificationDisplayMode.FLOATING,
                                customText = text,
                                useCustomText = true
                            )
                        )
                    } else {
                        // App is MINIMIZED, in background, or closed:
                        // Do NOT set in-app state (prevents delayed banner appearing later upon reopen)
                        if (XiaomiOverlayHelper.canDrawOverlays(context)) {
                            // Show true prominent screen overlay over whatever app is currently running
                            ScreenOverlayManager.showOverlay(context, reminderId, text, timeStr)
                        } else {
                            // Fallback to high priority heads-up notification
                            ReminderScheduler.showSystemNotification(context, reminderId, text, timeStr)
                        }
                    }
                } else {
                    // Normal mode: Standard notification + heads-up banner with scheduled time
                    ReminderScheduler.showSystemNotification(context, reminderId, text, timeStr)
                }
            }
        }
    }
}
