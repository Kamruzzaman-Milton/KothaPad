package com.example.ui.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.KeyActionType
import com.example.data.model.KeyboardKey
import com.example.data.model.KeyboardLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class KeyboardDragSession(
    val fromKbd: Int,
    val fromRow: Int,
    val fromCol: Int,
    val key: KeyboardKey,
    val currentOffset: Offset,
    val hoverRow: Int,
    val hoverCol: Int
)

@Composable
fun InAppKeyboard(
    layout: KeyboardLayout,
    activeKeyboardIndex: Int = 0,
    onSwitchKeyboard: () -> Unit = {},
    onSwapKeysInLayout: (fromRow: Int, fromCol: Int, toRow: Int, toCol: Int) -> Unit = { _, _, _, _ -> },
    onSwapKeysBetweenLayouts: (fromKbd: Int, fromRow: Int, fromCol: Int, toKbd: Int, toRow: Int, toCol: Int) -> Unit = { _, _, _, _, _, _ -> },
    onToggleEditMode: () -> Unit = {},
    keyHeightDp: Int = 40,
    fontSizeSp: Int = 15,
    hapticEnabled: Boolean = true,
    holdBehavior: String = "CUSTOMIZE",
    isEditMode: Boolean = false,
    onKeyAction: (KeyboardKey) -> Unit,
    onKeyEditRequest: (KeyboardKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dragSession by remember { mutableStateOf<KeyboardDragSession?>(null) }
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("in_app_keyboard_3x10"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Subtle 2-dot indicator showing active keyboard without wasting editing space
            Row(
                modifier = Modifier
                    .padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSwitchKeyboard()
                    }
                    .padding(horizontal = 8.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = if (activeKeyboardIndex == 0) 14.dp else 5.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (activeKeyboardIndex == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                )
                Box(
                    modifier = Modifier
                        .size(width = if (activeKeyboardIndex == 1) 14.dp else 5.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (activeKeyboardIndex == 1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { gridCoordinates = it }
                    .pointerInput(layout, isEditMode, activeKeyboardIndex, keyHeightDp) {
                        val touchSlop = viewConfiguration.touchSlop
                        val edgeThresholdPx = with(density) { 34.dp.toPx() }
                        val swipeThresholdPx = with(density) { 30.dp.toPx() }
                        val swipeVelocityPx = with(density) { 300.dp.toPx() }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val downTime = System.currentTimeMillis()
                            val downPos = down.position
                            val pointerId = down.id

                            val gridW = size.width.toFloat().coerceAtLeast(1f)
                            val gridH = size.height.toFloat().coerceAtLeast(1f)
                            val colW = gridW / 10f
                            val rowH = gridH / 3f

                            val downCol = (downPos.x / colW).toInt().coerceIn(0, 9)
                            val downRow = (downPos.y / rowH).toInt().coerceIn(0, 2)
                            val touchedKey = layout.rows.getOrNull(downRow)?.keys?.getOrNull(downCol)

                            var isDragging = false
                            var isSwiping = false
                            var totalDx = 0f
                            var totalDy = 0f
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var edgeTimerJob: Job? = null
                            var edgeCooldown = false

                            // Coroutine to trigger drag mode after long-press threshold (~320ms)
                            val longPressJob = scope.launch {
                                delay(320)
                                if (touchedKey != null) {
                                    isDragging = true
                                    if (hapticEnabled) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }
                                    dragSession = KeyboardDragSession(
                                        fromKbd = activeKeyboardIndex,
                                        fromRow = downRow,
                                        fromCol = downCol,
                                        key = touchedKey,
                                        currentOffset = downPos,
                                        hoverRow = downRow,
                                        hoverCol = downCol
                                    )
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                                if (!change.pressed) {
                                    // Pointer UP or cancelled
                                    longPressJob.cancel()
                                    edgeTimerJob?.cancel()

                                    if (isDragging) {
                                        change.consume()
                                        val session = dragSession
                                        if (session != null) {
                                            if (session.fromKbd == activeKeyboardIndex) {
                                                if (session.fromRow != session.hoverRow || session.fromCol != session.hoverCol) {
                                                    onSwapKeysInLayout(session.fromRow, session.fromCol, session.hoverRow, session.hoverCol)
                                                    if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                }
                                            } else {
                                                onSwapKeysBetweenLayouts(
                                                    session.fromKbd,
                                                    session.fromRow,
                                                    session.fromCol,
                                                    activeKeyboardIndex,
                                                    session.hoverRow,
                                                    session.hoverCol
                                                )
                                                if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            }
                                        }
                                        dragSession = null
                                    } else if (isSwiping) {
                                        change.consume()
                                    } else {
                                        // Quick Tap
                                        val elapsed = System.currentTimeMillis() - downTime
                                        if (elapsed < 320 && touchedKey != null) {
                                            change.consume()
                                            if (hapticEnabled) {
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            }
                                            if (touchedKey.actionType == KeyActionType.TOGGLE_EDIT_MODE) {
                                                onToggleEditMode()
                                            } else if (isEditMode) {
                                                onKeyEditRequest(touchedKey)
                                            } else {
                                                onKeyAction(touchedKey)
                                            }
                                        }
                                    }
                                    break
                                }

                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val delta = change.position - change.previousPosition
                                totalDx += delta.x
                                totalDy += delta.y

                                val distFromDown = (change.position - downPos).getDistance()

                                if (!isDragging && !isSwiping) {
                                    if (distFromDown > touchSlop) {
                                        // Movement detected before long-press timer
                                        longPressJob.cancel()
                                        if (abs(totalDx) > abs(totalDy) * 1.25f) {
                                            isSwiping = true
                                            change.consume()
                                        }
                                    }
                                } else if (isSwiping) {
                                    change.consume()
                                    if (abs(totalDx) >= swipeThresholdPx) {
                                        if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onSwitchKeyboard()
                                        break
                                    }
                                } else if (isDragging) {
                                    change.consume()
                                    val curX = change.position.x
                                    val curY = change.position.y

                                    val curCol = (curX / colW).toInt().coerceIn(0, 9)
                                    val curRow = (curY / rowH).toInt().coerceIn(0, 2)

                                    dragSession = dragSession?.copy(
                                        currentOffset = change.position,
                                        hoverRow = curRow,
                                        hoverCol = curCol
                                    )

                                    // Edge hover detection for cross-keyboard switching
                                    val isAtLeftEdge = curX <= edgeThresholdPx
                                    val isAtRightEdge = curX >= (gridW - edgeThresholdPx)

                                    if ((isAtLeftEdge || isAtRightEdge) && !edgeCooldown) {
                                        if (edgeTimerJob == null || !edgeTimerJob!!.isActive) {
                                            edgeTimerJob = scope.launch {
                                                delay(450)
                                                if (isActive) {
                                                    if (hapticEnabled) {
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                    }
                                                    onSwitchKeyboard()
                                                    edgeCooldown = true
                                                    delay(700)
                                                    edgeCooldown = false
                                                }
                                            }
                                        }
                                    } else {
                                        edgeTimerJob?.cancel()
                                        edgeTimerJob = null
                                    }
                                }
                            }
                        }
                    }
            ) {
                // 3 rows x 10 columns grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (rowIndex in 0 until minOf(3, layout.rows.size)) {
                        val row = layout.rows[rowIndex]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            for (colIndex in 0 until minOf(10, row.keys.size)) {
                                val key = row.keys[colIndex]
                                val session = dragSession
                                val isDraggedSource = session != null &&
                                        session.fromKbd == activeKeyboardIndex &&
                                        session.fromRow == rowIndex &&
                                        session.fromCol == colIndex

                                val isHoverTarget = session != null &&
                                        session.hoverRow == rowIndex &&
                                        session.hoverCol == colIndex

                                InAppKeyView(
                                    key = key,
                                    keyHeightDp = keyHeightDp,
                                    fontSizeSp = fontSizeSp,
                                    isEditMode = isEditMode,
                                    isDraggedSource = isDraggedSource,
                                    isHoverTarget = isHoverTarget,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Dragging floating representation
                dragSession?.let { session ->
                    val keyW = this.maxWidth / 10f
                    val keyH = keyHeightDp.dp
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = (session.currentOffset.x - (keyW.toPx() / 2f)).roundToInt(),
                                    y = (session.currentOffset.y - (keyH.toPx() / 2f)).roundToInt()
                                )
                            }
                            .size(width = keyW, height = keyH)
                            .shadow(10.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = session.key.label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = if (session.key.label.length > 2) (fontSizeSp - 2).coerceAtLeast(10).sp else fontSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InAppKeyView(
    key: KeyboardKey,
    keyHeightDp: Int,
    fontSizeSp: Int,
    isEditMode: Boolean,
    isDraggedSource: Boolean,
    isHoverTarget: Boolean,
    modifier: Modifier = Modifier
) {
    val isEditToggleKey = key.actionType == KeyActionType.TOGGLE_EDIT_MODE
    val isControlKey = key.actionType != KeyActionType.INSERT && key.actionType != KeyActionType.SPACE

    val keyBgColor = when {
        isDraggedSource -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        isHoverTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        isEditToggleKey && isEditMode -> MaterialTheme.colorScheme.primary
        isEditToggleKey && !isEditMode -> MaterialTheme.colorScheme.secondaryContainer
        isEditMode -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        isControlKey -> MaterialTheme.colorScheme.surfaceVariant
        key.actionType == KeyActionType.SPACE -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.background
    }

    val textColor = when {
        isDraggedSource -> Color.Transparent
        isEditToggleKey && isEditMode -> MaterialTheme.colorScheme.onPrimary
        isEditToggleKey && !isEditMode -> MaterialTheme.colorScheme.onSecondaryContainer
        key.actionType == KeyActionType.CHANGE_LANGUAGE -> MaterialTheme.colorScheme.primary
        key.actionType == KeyActionType.OPEN_TEMPLATES -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderModifier = when {
        isHoverTarget -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
        isDraggedSource -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
        isEditToggleKey && isEditMode -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.inversePrimary, RoundedCornerShape(6.dp))
        isEditMode -> Modifier.border(0.7.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
        else -> Modifier
    }

    Box(
        modifier = modifier
            .height(keyHeightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(keyBgColor)
            .then(borderModifier)
            .testTag("key_${key.id.ifEmpty { key.label }}"),
        contentAlignment = Alignment.Center
    ) {
        if (!isDraggedSource) {
            if (key.actionType == KeyActionType.EXIT && (key.label == "Exit" || key.label.isBlank())) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = "Exit",
                    tint = textColor,
                    modifier = Modifier.size((fontSizeSp + 3).coerceIn(16, 24).dp)
                )
            } else {
                Text(
                    text = key.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (key.label.length > 2) (fontSizeSp - 3).coerceAtLeast(10).sp else fontSizeSp.sp,
                        fontWeight = if (isControlKey || isEditMode || isEditToggleKey) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            // In Edit Mode, display small badge indicator on customizable keys
            if (isEditMode && !isEditToggleKey) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                )
            }
        }
    }
}
