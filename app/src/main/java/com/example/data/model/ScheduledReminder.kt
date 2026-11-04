package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Display modes for reminders:
 * NORMAL: Conventional Android heads-up popup and notification shade.
 * FLOATING: Prominent full screen overlay popup (SYSTEM_ALERT_WINDOW) over any application.
 */
enum class NotificationDisplayMode {
    NORMAL,
    FLOATING
}

/**
 * Advanced Visual Styling & Geometry Configuration for Floating Popup Overlay.
 */
data class FloatingNotificationConfig(
    val popupWidthDp: Int = 320,              // 240..420 dp
    val minHeightDp: Int = 160,               // 120..300 dp
    val verticalPosition: String = "CENTER",  // "TOP", "CENTER", "BOTTOM"
    val horizontalOffsetDp: Int = 0,          // -60..60 dp
    val verticalOffsetDp: Int = 0,            // -150..150 dp
    val backgroundColorHex: String = "#1E1E2E", // Hex color
    val backgroundOpacity: Float = 0.96f,     // 0.60f..1.0f
    val cornerRadiusDp: Int = 20,             // 0..32 dp
    val borderWidthDp: Int = 1,               // 0..4 dp
    val borderColorHex: String = "#7C4DFF",   // Border color
    val textSizeSp: Int = 16,                 // 12..24 sp
    val textFont: String = "DEFAULT",         // "DEFAULT", "SERIF", "MONOSPACE"
    val fontWeight: String = "BOLD",          // "NORMAL", "MEDIUM", "BOLD"
    val textColorHex: String = "#FFFFFF",     // Text color
    val textAlignment: String = "CENTER",     // "START", "CENTER", "END"
    val paddingDp: Int = 18,                  // 8..32 dp
    val animationEffect: String = "SCALE",    // "FADE", "SCALE", "SLIDE"
    val autoDismissSeconds: Int = 15          // 0 (persistent until tapped) or 5..60s
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("popupWidthDp", popupWidthDp)
        put("minHeightDp", minHeightDp)
        put("verticalPosition", verticalPosition)
        put("horizontalOffsetDp", horizontalOffsetDp)
        put("verticalOffsetDp", verticalOffsetDp)
        put("backgroundColorHex", backgroundColorHex)
        put("backgroundOpacity", backgroundOpacity.toDouble())
        put("cornerRadiusDp", cornerRadiusDp)
        put("borderWidthDp", borderWidthDp)
        put("borderColorHex", borderColorHex)
        put("textSizeSp", textSizeSp)
        put("textFont", textFont)
        put("fontWeight", fontWeight)
        put("textColorHex", textColorHex)
        put("textAlignment", textAlignment)
        put("paddingDp", paddingDp)
        put("animationEffect", animationEffect)
        put("autoDismissSeconds", autoDismissSeconds)
    }

    companion object {
        fun fromJson(jsonStr: String?): FloatingNotificationConfig {
            if (jsonStr.isNullOrBlank()) return FloatingNotificationConfig()
            return try {
                val obj = JSONObject(jsonStr)
                FloatingNotificationConfig(
                    popupWidthDp = obj.optInt("popupWidthDp", 320),
                    minHeightDp = obj.optInt("minHeightDp", 160),
                    verticalPosition = obj.optString("verticalPosition", "CENTER"),
                    horizontalOffsetDp = obj.optInt("horizontalOffsetDp", 0),
                    verticalOffsetDp = obj.optInt("verticalOffsetDp", 0),
                    backgroundColorHex = obj.optString("backgroundColorHex", "#1E1E2E"),
                    backgroundOpacity = obj.optDouble("backgroundOpacity", 0.96).toFloat(),
                    cornerRadiusDp = obj.optInt("cornerRadiusDp", 20),
                    borderWidthDp = obj.optInt("borderWidthDp", 1),
                    borderColorHex = obj.optString("borderColorHex", "#7C4DFF"),
                    textSizeSp = obj.optInt("textSizeSp", 16),
                    textFont = obj.optString("textFont", "DEFAULT"),
                    fontWeight = obj.optString("fontWeight", "BOLD"),
                    textColorHex = obj.optString("textColorHex", "#FFFFFF"),
                    textAlignment = obj.optString("textAlignment", "CENTER"),
                    paddingDp = obj.optInt("paddingDp", 18),
                    animationEffect = obj.optString("animationEffect", "SCALE"),
                    autoDismissSeconds = obj.optInt("autoDismissSeconds", 15)
                )
            } catch (_: Exception) {
                FloatingNotificationConfig()
            }
        }
    }
}

/**
 * Scheduled Reminder Entity
 */
data class ScheduledReminder(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int = 9,               // 0..23
    val minute: Int = 0,             // 0..59 (exact 1-minute precision)
    val isEnabled: Boolean = true,
    val displayMode: NotificationDisplayMode = NotificationDisplayMode.NORMAL,
    val useCustomText: Boolean = false,
    val customText: String = "",
    val defaultText: String = "Did I forget to journal today?"
) {
    fun getEffectiveText(): String {
        return if (useCustomText && customText.isNotBlank()) {
            customText.trim()
        } else {
            defaultText.ifBlank { "Did I forget to journal today?" }
        }
    }

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
