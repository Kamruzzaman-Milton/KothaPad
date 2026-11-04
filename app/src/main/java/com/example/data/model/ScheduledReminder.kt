package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Display modes for reminders.
 */
enum class NotificationDisplayMode {
    NORMAL,    // Standard Android system notification
    FLOATING   // In-app or overlay floating popup at the top of the screen
}

/**
 * Visual styling preferences for floating notifications.
 */
data class FloatingNotificationConfig(
    val popupSize: String = "MEDIUM", // "COMPACT", "MEDIUM", "LARGE"
    val visualStyle: String = "ELEVATED", // "ELEVATED", "MINIMAL", "ACCENT_BORDER"
    val backgroundOpacity: Float = 0.95f, // 0.7f to 1.0f
    val autoDismissSeconds: Int = 12 // 0 for persistent until tapped/dismissed, or 5-30s
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("popupSize", popupSize)
        put("visualStyle", visualStyle)
        put("backgroundOpacity", backgroundOpacity.toDouble())
        put("autoDismissSeconds", autoDismissSeconds)
    }

    companion object {
        fun fromJson(jsonStr: String?): FloatingNotificationConfig {
            if (jsonStr.isNullOrBlank()) return FloatingNotificationConfig()
            return try {
                val obj = JSONObject(jsonStr)
                FloatingNotificationConfig(
                    popupSize = obj.optString("popupSize", "MEDIUM"),
                    visualStyle = obj.optString("visualStyle", "ELEVATED"),
                    backgroundOpacity = obj.optDouble("backgroundOpacity", 0.95).toFloat(),
                    autoDismissSeconds = obj.optInt("autoDismissSeconds", 12)
                )
            } catch (_: Exception) {
                FloatingNotificationConfig()
            }
        }
    }
}

/**
 * Represents an individual scheduled reminder/notification.
 * Unlimited custom notifications can be created.
 */
data class ScheduledReminder(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int = 9,               // 0..23
    val minute: Int = 0,             // 0..59
    val isEnabled: Boolean = true,
    val displayMode: NotificationDisplayMode = NotificationDisplayMode.NORMAL,
    val useCustomText: Boolean = false,
    val customText: String = "",
    val defaultText: String = "Did I forget to journal today?"
) {
    /**
     * Resolves the actual text to display based on the toggle.
     */
    fun getEffectiveText(): String {
        return if (useCustomText && customText.isNotBlank()) {
            customText.trim()
        } else {
            defaultText.ifBlank { "Did I forget to journal today?" }
        }
    }

    /**
     * Formats 24h hour and minute to friendly 12h AM/PM string, e.g. "07:00 AM".
     */
    fun formattedTime(): String {
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val amPm = if (hour < 12) "AM" else "PM"
        return "%02d:%02d %s".format(h12, minute, amPm)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("isEnabled", isEnabled)
        put("displayMode", displayMode.name)
        put("useCustomText", useCustomText)
        put("customText", customText)
        put("defaultText", defaultText)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduledReminder {
            return ScheduledReminder(
                id = obj.optString("id", UUID.randomUUID().toString()),
                hour = obj.optInt("hour", 9),
                minute = obj.optInt("minute", 0),
                isEnabled = obj.optBoolean("isEnabled", true),
                displayMode = try {
                    NotificationDisplayMode.valueOf(obj.optString("displayMode", NotificationDisplayMode.NORMAL.name))
                } catch (_: Exception) {
                    NotificationDisplayMode.NORMAL
                },
                useCustomText = obj.optBoolean("useCustomText", false),
                customText = obj.optString("customText", ""),
                defaultText = obj.optString("defaultText", "Did I forget to journal today?")
            )
        }

        fun listToJson(list: List<ScheduledReminder>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String?): List<ScheduledReminder> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val list = ArrayList<ScheduledReminder>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(fromJson(obj))
                }
                list.sortedWith(compareBy({ it.hour }, { it.minute }))
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
