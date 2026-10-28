package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import com.example.data.model.FloatingLayoutSettings
import com.example.markdown.FormatBarButtonDef
import com.example.markdown.MarkdownSettings
import com.example.markdown.getFormatBarButtonDefinitions

data class RailButtonMeta(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector
)

val ALL_RAIL_BUTTONS_META = listOf(
    RailButtonMeta(
        id = FloatingLayoutSettings.BUTTON_COPY,
        name = "Copy",
        description = "One-tap clipboard copy",
        icon = Icons.Default.ContentCopy
    ),
    RailButtonMeta(
        id = FloatingLayoutSettings.BUTTON_REDO,
        name = "Redo",
        description = "Reapply undone edits",
        icon = Icons.AutoMirrored.Filled.Redo
    ),
    RailButtonMeta(
        id = FloatingLayoutSettings.BUTTON_UNDO,
        name = "Undo",
        description = "Revert recent changes",
        icon = Icons.AutoMirrored.Filled.Undo
    ),
    RailButtonMeta(
        id = FloatingLayoutSettings.BUTTON_MIC,
        name = "Mic (Record)",
        description = "Start/stop speech dictation",
        icon = Icons.Default.Mic
    )
)

@Composable
fun RailButtonOrderReorderControl(
    railButtonOrder: List<String>,
    onMoveItem: (fromIndex: Int, toIndex: Int) -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOrder = remember(railButtonOrder) {
        val list = railButtonOrder.toMutableList()
        for (btnId in FloatingLayoutSettings.ALL_BUTTON_IDS) {
            if (!list.contains(btnId)) list.add(btnId)
        }
        list
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rail_button_order_control")
    ) {
        Text(
            text = "Rail Button Order (Top to Bottom)",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "Arrange buttons from top to bottom as they appear in the side rail column.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            currentOrder.forEachIndexed { index, buttonId ->
                val meta = ALL_RAIL_BUTTONS_META.find { it.id == buttonId }
                    ?: RailButtonMeta(buttonId, buttonId, "", Icons.Default.Mic)

                val positionLabel = when (index) {
                    0 -> "Top"
                    currentOrder.size - 1 -> "Bottom"
                    else -> "#${index + 1}"
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge indicating position
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = meta.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = meta.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "($positionLabel)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                            Text(
                                text = meta.description,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }

                        // Up Button
                        IconButton(
                            onClick = { if (index > 0) onMoveItem(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("move_up_${buttonId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Move Up",
                                tint = if (index > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Down Button
                        IconButton(
                            onClick = { if (index < currentOrder.size - 1) onMoveItem(index, index + 1) },
                            enabled = index < currentOrder.size - 1,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("move_down_${buttonId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Move Down",
                                tint = if (index < currentOrder.size - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onResetOrder,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reset_rail_order_button"),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reset order (default: Mic, Undo, Redo, Copy)", fontSize = 12.sp)
        }
    }
}

@Composable
fun FormatBarButtonsReorderControl(
    formatBarOrder: List<String>,
    formatBarButtons: List<String>,
    enabled: Boolean,
    onReorder: (newOrder: List<String>) -> Unit,
    onToggleCheck: (buttonId: String, isChecked: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onResetOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val allDefMap = remember { getFormatBarButtonDefinitions().associateBy { it.id } }

    val currentOrder = remember(formatBarOrder) {
        val list = formatBarOrder.toMutableList()
        MarkdownSettings.DEFAULT_FORMAT_BAR_ORDER.forEach { id ->
            if (!list.contains(id)) list.add(id)
        }
        list
    }

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetAccumulator by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("format_bar_buttons_reorder_control")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Format Buttons & Order",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onSelectAll,
                    enabled = enabled
                ) {
                    Text("All", fontSize = 12.sp)
                }
                TextButton(
                    onClick = onSelectNone,
                    enabled = enabled
                ) {
                    Text("None", fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Drag the handle (≡) or use arrows to customize their order. Toggle checkboxes to show/hide buttons.",
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            currentOrder.forEachIndexed { index, buttonId ->
                val btn = allDefMap[buttonId] ?: FormatBarButtonDef(
                    id = buttonId,
                    label = buttonId,
                    contentDescription = buttonId,
                    renderIcon = { Text(buttonId.take(1).uppercase()) }
                )
                val isChecked = formatBarButtons.contains(buttonId)
                val isDragging = draggingIndex == index

                val rowElevation = if (isDragging) 8.dp else 0.dp
                val rowBorderColor = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                val rowBgColor = if (isDragging) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = rowBgColor,
                    border = BorderStroke(if (isDragging) 1.5.dp else 1.dp, rowBorderColor),
                    shadowElevation = rowElevation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 10f else 1f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetAccumulator
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .testTag("format_bar_item_$buttonId")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (enabled) {
                                    onToggleCheck(buttonId, checked)
                                }
                            },
                            enabled = enabled,
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("format_bar_check_${btn.id}")
                        )

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            btn.renderIcon()
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = btn.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (enabled && isChecked) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val posLabel = when (index) {
                                    0 -> "1st"
                                    1 -> "2nd"
                                    2 -> "3rd"
                                    else -> "${index + 1}th"
                                }
                                Text(
                                    text = "($posLabel)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        color = if (enabled && isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                )
                            }
                            Text(
                                text = btn.contentDescription,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = if (enabled && isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            )
                        }

                        IconButton(
                            onClick = {
                                if (enabled && index > 0) {
                                    val list = currentOrder.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index - 1, item)
                                    try {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    } catch (_: Exception) {}
                                    onReorder(list)
                                }
                            },
                            enabled = enabled && index > 0,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("move_up_${buttonId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Move Up",
                                tint = if (enabled && index > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (enabled && index < currentOrder.size - 1) {
                                    val list = currentOrder.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index + 1, item)
                                    try {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    } catch (_: Exception) {}
                                    onReorder(list)
                                }
                            },
                            enabled = enabled && index < currentOrder.size - 1,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("move_down_${buttonId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Move Down",
                                tint = if (enabled && index < currentOrder.size - 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .pointerInput(buttonId, enabled) {
                                    if (!enabled) return@pointerInput
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingIndex = index
                                            dragOffsetAccumulator = 0f
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            } catch (_: Exception) {}
                                        },
                                        onDragEnd = {
                                            draggingIndex = null
                                            dragOffsetAccumulator = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = null
                                            dragOffsetAccumulator = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetAccumulator += dragAmount.y
                                            val currentIndex = draggingIndex ?: return@detectDragGestures
                                            val rowHeightPx = 52.dp.toPx()

                                            if (dragOffsetAccumulator > rowHeightPx && currentIndex < currentOrder.size - 1) {
                                                val list = currentOrder.toMutableList()
                                                val item = list.removeAt(currentIndex)
                                                list.add(currentIndex + 1, item)
                                                draggingIndex = currentIndex + 1
                                                dragOffsetAccumulator -= rowHeightPx
                                                try {
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                } catch (_: Exception) {}
                                                onReorder(list)
                                            } else if (dragOffsetAccumulator < -rowHeightPx && currentIndex > 0) {
                                                val list = currentOrder.toMutableList()
                                                val item = list.removeAt(currentIndex)
                                                list.add(currentIndex - 1, item)
                                                draggingIndex = currentIndex - 1
                                                dragOffsetAccumulator += rowHeightPx
                                                try {
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                } catch (_: Exception) {}
                                                onReorder(list)
                                            }
                                        }
                                    )
                                }
                                .testTag("drag_handle_${buttonId}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onResetOrder,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reset_format_bar_order_button"),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reset format buttons & order", fontSize = 12.sp)
        }
    }
}
