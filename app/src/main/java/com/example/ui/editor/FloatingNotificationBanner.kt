package com.example.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FloatingNotificationConfig
import com.example.data.model.ScheduledReminder
import kotlinx.coroutines.delay

/**
 * Top floating notification popup overlay with reminder message and direct (+) button.
 * Tapping (+) opens the current date's note in editing mode immediately.
 */
@Composable
fun FloatingNotificationBanner(
    reminder: ScheduledReminder?,
    config: FloatingNotificationConfig,
    onTodayPlusClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (reminder == null) return

    // Auto-dismiss countdown if configured
    if (config.autoDismissSeconds > 0) {
        LaunchedEffect(reminder.id) {
            delay(config.autoDismissSeconds * 1000L)
            onDismiss()
        }
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Sizing customization
    val (horizontalPadding, verticalPadding, textFontSize, iconSize) = when (config.popupSize) {
        "COMPACT" -> listOf(10.dp, 8.dp, 12.sp, 20.dp)
        "LARGE" -> listOf(20.dp, 16.dp, 16.sp, 28.dp)
        else -> listOf(14.dp, 12.dp, 14.sp, 24.dp) // "MEDIUM"
    }

    // Styling customization
    val shape = RoundedCornerShape(16.dp)
    val containerColor = when (config.visualStyle) {
        "MINIMAL" -> MaterialTheme.colorScheme.surface.copy(alpha = config.backgroundOpacity)
        "ACCENT_BORDER" -> MaterialTheme.colorScheme.surface.copy(alpha = config.backgroundOpacity)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = config.backgroundOpacity) // "ELEVATED"
    }

    val borderStroke = when (config.visualStyle) {
        "ACCENT_BORDER" -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }

    val elevation = if (config.visualStyle == "MINIMAL") 2.dp else 10.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topInset + 12.dp, start = 16.dp, end = 16.dp)
            .testTag("floating_notification_overlay"),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .shadow(elevation, shape),
            shape = shape,
            color = containerColor,
            border = borderStroke
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding as androidx.compose.ui.unit.Dp, vertical = verticalPadding as androidx.compose.ui.unit.Dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Leading notification icon + reminder message
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Reminder Notification",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(iconSize as androidx.compose.ui.unit.Dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily Reminder",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = reminder.getEffectiveText(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = textFontSize as androidx.compose.ui.unit.TextUnit,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Action area: PLUS (+) Button & Dismiss button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Dedicated PLUS (+) button matching the Calendar FAB action
                    FloatingActionButton(
                        onClick = {
                            onDismiss()
                            onTodayPlusClick()
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("floating_notification_plus_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Open Today's Note in Editing Mode",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("floating_notification_dismiss_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
