package com.example.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * Manages scheduling, canceling, and triggering of reminders.
 */
object ReminderScheduler {

    const val CHANNEL_ID = "kothapad_reminders"
    const val CHANNEL_NAME = "Daily Reminders"

    const val ACTION_TRIGGER_REMINDER = "com.example.notification.ACTION_TRIGGER_REMINDER"
    const val ACTION_PLUS_CLICK = "com.example.notification.ACTION_PLUS_CLICK"
    const val ACTION_DISMISS_NOTIFICATION = "com.example.notification.ACTION_DISMISS_NOTIFICATION"

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TEXT = "extra_reminder_text"
    const val EXTRA_DISPLAY_MODE = "extra_display_mode"

    // Live state flow for active in-app floating notification popup
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

        // First cancel all previous reminder pending intents for this list or general range
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

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pi
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pi
                )
            }
        } catch (_: SecurityException) {
            // In case exact alarm permission is missing on Android 12+, fallback to set()
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
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
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TEXT, reminder.getEffectiveText())
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
     * Posts standard Android system notification with content text and direct (+) Note Action.
     */
    fun showSystemNotification(context: Context, reminderId: String, text: String) {
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Tap content: open today's note in editing mode
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("KothaPad Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(plusAction)
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
                // Reschedule all enabled reminders from DataStore
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
                val modeStr = intent.getStringExtra(ReminderScheduler.EXTRA_DISPLAY_MODE) ?: NotificationDisplayMode.NORMAL.name
                val displayMode = try {
                    NotificationDisplayMode.valueOf(modeStr)
                } catch (_: Exception) {
                    NotificationDisplayMode.NORMAL
                }

                // Check if current reminder is still enabled and reschedule for next day
                val prefManager = PreferencesManager(context)
                CoroutineScope(Dispatchers.IO).launch {
                    val json = prefManager.scheduledRemindersJson.first()
                    val reminders = ScheduledReminder.listFromJson(json)
                    val reminder = reminders.find { it.id == reminderId }
                    if (reminder != null && reminder.isEnabled) {
                        // Re-arm alarm for tomorrow
                        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                        if (am != null) {
                            ReminderScheduler.scheduleSingle(context, am, reminder)
                        }
                    }
                }

                if (displayMode == NotificationDisplayMode.FLOATING) {
                    // Try to trigger live floating popup
                    val reminder = ScheduledReminder(
                        id = reminderId,
                        displayMode = NotificationDisplayMode.FLOATING,
                        customText = text,
                        useCustomText = true
                    )
                    ReminderScheduler.showFloatingReminder(reminder)
                    // Also fire system notification as high priority heads-up fallback
                    ReminderScheduler.showSystemNotification(context, reminderId, text)
                } else {
                    ReminderScheduler.showSystemNotification(context, reminderId, text)
                }
            }
        }
    }
}
