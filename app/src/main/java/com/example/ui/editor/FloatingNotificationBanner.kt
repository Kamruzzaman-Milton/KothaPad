package com.example.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FloatingNotificationConfig
import com.example.data.model.ScheduledReminder
import kotlinx.coroutines.delay

/**
 * Prominent screen overlay popup component.
 * Used both for in-app floating reminder popups and for the live preview in Settings.
 */
@Composable
fun FloatingNotificationBanner(
    reminder: ScheduledReminder?,
    config: FloatingNotificationConfig,
    onTodayPlusClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isPreview: Boolean = false
) {
    if (reminder == null) return

    // Auto-dismiss countdown if configured and not preview
    if (!isPreview && config.autoDismissSeconds > 0) {
        LaunchedEffect(reminder.id) {
            delay(config.autoDismissSeconds * 1000L)
            onDismiss()
        }
    }

    // Geometry & position alignment
    val boxAlignment = when (config.verticalPosition) {
        "TOP" -> Alignment.TopCenter
        "BOTTOM" -> Alignment.BottomCenter
        else -> Alignment.Center
    }

    // Colors
    val parsedBg = runCatching {
        Color(android.graphics.Color.parseColor(config.backgroundColorHex))
    }.getOrDefault(Color(0xFF1E1E2E))
    val containerColor = parsedBg.copy(alpha = config.backgroundOpacity)

    val parsedBorder = runCatching {
        Color(android.graphics.Color.parseColor(config.borderColorHex))
    }.getOrDefault(Color(0xFF7C4DFF))
    val borderStroke = if (config.borderWidthDp > 0) {
        BorderStroke(config.borderWidthDp.dp, parsedBorder)
    } else null

    val parsedTextCol = runCatching {
        Color(android.graphics.Color.parseColor(config.textColorHex))
    }.getOrDefault(Color.White)

    val textFamily = when (config.textFont) {
        "SERIF" -> FontFamily.Serif
        "MONOSPACE" -> FontFamily.Monospace
        else -> FontFamily.Default
    }

    val textWeight = when (config.fontWeight) {
        "BOLD" -> FontWeight.Bold
        "MEDIUM" -> FontWeight.Medium
        else -> FontWeight.Normal
    }

    val textAlign = when (config.textAlignment) {
        "START" -> TextAlign.Start
        "END" -> TextAlign.End
        else -> TextAlign.Center
    }

    val shape = RoundedCornerShape(config.cornerRadiusDp.dp)

    Box(
        modifier = if (isPreview) {
            modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        } else {
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)) // Dim background for prominence
                .clickable(enabled = false) {}
                .padding(16.dp)
        },
        contentAlignment = boxAlignment
    ) {
        Surface(
            modifier = Modifier
                .width(config.popupWidthDp.dp)
                .defaultMinSize(minHeight = config.minHeightDp.dp)
                .offset(x = config.horizontalOffsetDp.dp, y = config.verticalOffsetDp.dp)
                .shadow(16.dp, shape)
                .testTag("floating_screen_overlay_card"),
            shape = shape,
            color = containerColor,
            border = borderStroke
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(config.paddingDp.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top row: App branding + Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(parsedBorder.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = parsedBorder,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "KOTHAPAD REMINDER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = parsedBorder
                            )
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = parsedTextCol.copy(alpha = 0.65f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Middle: Reminder Text
                Text(
                    text = reminder.getEffectiveText(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = config.textSizeSp.sp,
                        fontFamily = textFamily,
                        fontWeight = textWeight,
                        color = parsedTextCol,
                        textAlign = textAlign,
                        lineHeight = (config.textSizeSp * 1.35f).sp
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom: Dedicated PLUS (+) Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingActionButton(
                        onClick = {
                            onDismiss()
                            onTodayPlusClick()
                        },
                        containerColor = parsedBorder,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("floating_notification_plus_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create or open today's note",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
    }
}
