package com.example.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

object XiaomiOverlayHelper {

    fun isXiaomiOrMiui(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        return manufacturer.contains("xiaomi") ||
                brand.contains("xiaomi") ||
                brand.contains("redmi") ||
                brand.contains("poco")
    }

    /**
     * Checks if standard SYSTEM_ALERT_WINDOW permission is granted.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Checks if MIUI / HyperOS background pop-up window permission is granted via AppOps.
     */
    fun isMiuiPopupGranted(context: Context): Boolean {
        if (!isXiaomiOrMiui()) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            if (appOps != null) {
                val method = appOps.javaClass.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                // 10021 is MIUI OP_BACKGROUND_START_ACTIVITY
                val mode = method.invoke(appOps, 10021, android.os.Process.myUid(), context.packageName) as? Int
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } else {
                canDrawOverlays(context)
            }
        } catch (_: Exception) {
            canDrawOverlays(context)
        }
    }

    /**
     * Opens standard Android overlay settings.
     */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Attempts to open MIUI/HyperOS "Other Permissions" screen where "Display pop-up windows while running in the background" resides.
     */
    fun openMiuiPopupPermissions(context: Context): Boolean {
        val intents = listOf(
            // MIUI / HyperOS App Permissions Editor
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            // Alternative MIUI Permission Details
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            // Standard App Details
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try next
            }
        }
        return false
    }

    /**
     * Attempts to open MIUI Autostart screen to prevent background termination.
     */
    fun openMiuiAutostart(context: Context): Boolean {
        val intents = listOf(
            Intent().apply {
                setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent().apply {
                setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }
}
