package com.example.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.R
import com.example.data.model.FloatingNotificationConfig
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * True system window overlay service (SYSTEM_ALERT_WINDOW) that displays a prominent,
 * customizable floating reminder popup over any running application or home screen.
 */
class ScreenOverlayReminderService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        if (intent.action == ACTION_DISMISS) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: ""
        val reminderText = intent.getStringExtra(EXTRA_REMINDER_TEXT) ?: "Did I forget to journal today?"

        CoroutineScope(Dispatchers.Main).launch {
            val prefManager = PreferencesManager(applicationContext)
            val configJson = prefManager.floatingNotificationConfigJson.first()
            val config = FloatingNotificationConfig.fromJson(configJson)
            displayOverlay(reminderId, reminderText, config)
        }

        return START_NOT_STICKY
    }

    private fun displayOverlay(reminderId: String, text: String, config: FloatingNotificationConfig) {
        removeOverlay()

        if (!XiaomiOverlayHelper.canDrawOverlays(this)) {
            // Cannot draw overlay, fallback to system notification
            ReminderScheduler.showSystemNotification(this, reminderId, text)
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density
        val widthPx = (config.popupWidthDp * density).toInt()
        val minHeightPx = (config.minHeightDp * density).toInt()
        val paddingPx = (config.paddingDp * density).toInt()
        val cornerRadiusPx = config.cornerRadiusDp * density
        val borderWidthPx = (config.borderWidthDp * density).toInt()

        val gravityVal = when (config.verticalPosition) {
            "TOP" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "BOTTOM" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityVal
            x = (config.horizontalOffsetDp * density).toInt()
            y = (config.verticalOffsetDp * density).toInt()
        }

        // Root container
        val rootContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Card Container using GradientDrawable (eliminating external cardview dependency)
        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = minHeightPx
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            elevation = 16f * density

            val parsedBg = runCatching { Color.parseColor(config.backgroundColorHex) }.getOrDefault(Color.parseColor("#1E1E2E"))
            val alphaVal = (config.backgroundOpacity.coerceIn(0f, 1f) * 255).toInt()
            val bgWithAlpha = Color.argb(alphaVal, Color.red(parsedBg), Color.green(parsedBg), Color.blue(parsedBg))

            val parsedBorder = runCatching { Color.parseColor(config.borderColorHex) }.getOrDefault(Color.parseColor("#7C4DFF"))

            background = GradientDrawable().apply {
                setColor(bgWithAlpha)
                cornerRadius = cornerRadiusPx
                if (borderWidthPx > 0) {
                    setStroke(borderWidthPx, parsedBorder)
                }
            }
        }

        // Top Row: Title + Close (X) button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleTv = TextView(this).apply {
            this.text = "KOTHAPAD REMINDER"
            textSize = 11f
            setTextColor(Color.parseColor("#BB86FC"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#B0BEC5"))
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val btnSize = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener {
                removeOverlay()
                stopSelf()
            }
        }

        topRow.addView(titleTv)
        topRow.addView(closeBtn)
        cardContainer.addView(topRow)

        // Middle: Reminder Message Text
        val messageTv = TextView(this).apply {
            this.text = text
            this.textSize = config.textSizeSp.toFloat()
            val textCol = runCatching { Color.parseColor(config.textColorHex) }.getOrDefault(Color.WHITE)
            setTextColor(textCol)

            val baseTypeface = when (config.textFont) {
                "SERIF" -> Typeface.SERIF
                "MONOSPACE" -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
            val weightStyle = when (config.fontWeight) {
                "BOLD" -> Typeface.BOLD
                "MEDIUM" -> Typeface.NORMAL
                else -> Typeface.NORMAL
            }
            setTypeface(baseTypeface, weightStyle)

            gravity = when (config.textAlignment) {
                "START" -> Gravity.START
                "END" -> Gravity.END
                else -> Gravity.CENTER
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
        }
        cardContainer.addView(messageTv)

        // Bottom: Dedicated PLUS (+) Button
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnSize = (52 * density).toInt()
        val plusBtnLayout = FrameLayout(this).apply {
            val btnRadius = 26f * density
            val parsedBorderColor = runCatching { Color.parseColor(config.borderColorHex) }.getOrDefault(Color.parseColor("#7C4DFF"))
            background = GradientDrawable().apply {
                setColor(parsedBorderColor)
                cornerRadius = btnRadius
            }
            elevation = 6f * density
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
        }

        val plusIv = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        plusBtnLayout.addView(plusIv)
        plusBtnLayout.setOnClickListener {
            // Immediately open today's note in editing mode
            val launchIntent = Intent(this@ScreenOverlayReminderService, MainActivity::class.java).apply {
                action = ReminderScheduler.ACTION_PLUS_CLICK
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(launchIntent)
            removeOverlay()
            stopSelf()
        }

        bottomRow.addView(plusBtnLayout)
        cardContainer.addView(bottomRow)
        rootContainer.addView(cardContainer)

        // Appearance Animation
        val animSet = AnimationSet(true)
        when (config.animationEffect) {
            "SCALE" -> {
                animSet.addAnimation(ScaleAnimation(0.7f, 1f, 0.7f, 1f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f).apply { duration = 250 })
                animSet.addAnimation(AlphaAnimation(0f, 1f).apply { duration = 200 })
            }
            "SLIDE" -> {
                animSet.addAnimation(AlphaAnimation(0f, 1f).apply { duration = 200 })
            }
            else -> { // "FADE"
                animSet.addAnimation(AlphaAnimation(0f, 1f).apply { duration = 300 })
            }
        }
        rootContainer.startAnimation(animSet)

        try {
            windowManager?.addView(rootContainer, params)
            overlayView = rootContainer
        } catch (_: Exception) {
            // Fallback to system notification
            ReminderScheduler.showSystemNotification(this, reminderId, text)
            stopSelf()
            return
        }

        // Auto-dismiss handling
        if (config.autoDismissSeconds > 0) {
            autoDismissRunnable = Runnable {
                removeOverlay()
                stopSelf()
            }
            handler.postDelayed(autoDismissRunnable!!, config.autoDismissSeconds * 1000L)
        }
    }

    private fun removeOverlay() {
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        autoDismissRunnable = null
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    companion object {
        const val ACTION_SHOW = "com.example.notification.ACTION_SHOW_OVERLAY"
        const val ACTION_DISMISS = "com.example.notification.ACTION_DISMISS_OVERLAY"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_REMINDER_TEXT = "extra_reminder_text"

        fun showOverlay(context: Context, reminderId: String, text: String) {
            val intent = Intent(context, ScreenOverlayReminderService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_REMINDER_TEXT, text)
            }
            context.startService(intent)
        }
    }
}
