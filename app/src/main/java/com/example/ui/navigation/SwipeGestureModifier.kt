package com.example.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

enum class SwipeDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT
}

/**
 * Detects an edge swipe gesture to open a navigation panel.
 *
 * Performance highlights:
 * - Only starts tracking if touch originates within [edgeThresholdDp] of the screen edge.
 * - Enforces directional arbitration: if vertical movement exceeds horizontal movement,
 *   the gesture is immediately aborted without consuming pointer events, allowing
 *   underlying vertical scroll (LazyColumn, ScrollableColumn) and text editing to function smoothly.
 * - Does not trigger any recomposition of the host screen during dragging.
 * - Invokes [onOpen] only upon release when either the distance threshold or a flick velocity is met.
 */
fun Modifier.swipeToOpen(
    direction: SwipeDirection,
    edgeThresholdDp: Dp = 44.dp,
    triggerDistanceDp: Dp = 64.dp,
    enabled: Boolean = true,
    onOpen: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val density = LocalDensity.current
    val triggerDistancePx = with(density) { triggerDistanceDp.toPx() }
    val edgeThresholdPx = with(density) { edgeThresholdDp.toPx() }

    this.pointerInput(direction, triggerDistancePx, edgeThresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val startX = down.position.x
            val screenWidth = size.width.toFloat()

            val inEdge = when (direction) {
                SwipeDirection.LEFT_TO_RIGHT -> startX <= edgeThresholdPx
                SwipeDirection.RIGHT_TO_LEFT -> startX >= (screenWidth - edgeThresholdPx)
            }
            if (!inEdge) return@awaitEachGesture

            var totalDx = 0f
            var totalDy = 0f
            var isDragging = false
            val touchSlop = viewConfiguration.touchSlop
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!pointer.pressed) break

                velocityTracker.addPosition(pointer.uptimeMillis, pointer.position)
                val dx = pointer.position.x - pointer.previousPosition.x
                val dy = pointer.position.y - pointer.previousPosition.y
                totalDx += dx
                totalDy += dy

                if (!isDragging) {
                    // Directional arbitration before slop
                    if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx) * 1.25f) {
                        // Dominant vertical motion: user is scrolling or selecting text vertically
                        return@awaitEachGesture
                    }
                    if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy) * 1.25f) {
                        val matchesDirection = when (direction) {
                            SwipeDirection.LEFT_TO_RIGHT -> totalDx > 0f
                            SwipeDirection.RIGHT_TO_LEFT -> totalDx < 0f
                        }
                        if (matchesDirection) {
                            isDragging = true
                            pointer.consume()
                        } else {
                            return@awaitEachGesture
                        }
                    }
                } else {
                    pointer.consume()
                }
            } while (event.changes.any { it.pressed })

            if (isDragging) {
                val velocityX = velocityTracker.calculateVelocity().x
                val isFlick = when (direction) {
                    SwipeDirection.LEFT_TO_RIGHT -> velocityX > 1000f
                    SwipeDirection.RIGHT_TO_LEFT -> velocityX < -1000f
                }
                val isDistanceMet = when (direction) {
                    SwipeDirection.LEFT_TO_RIGHT -> totalDx >= triggerDistancePx
                    SwipeDirection.RIGHT_TO_LEFT -> totalDx <= -triggerDistancePx
                }

                if (isDistanceMet || isFlick) {
                    onOpen()
                }
            }
        }
    }
}

/**
 * Enables smooth swipe-to-dismiss for overlay and secondary panels.
 *
 * Performance highlights:
 * - Uses [Modifier.graphicsLayer] lambda for hardware translation during the drag,
 *   which runs entirely in the Draw phase and completely avoids recomposing the
 *   panel's inner UI tree or large lists.
 * - Directional arbitration ensures vertical list scrolling within the panel
 *   takes priority over horizontal closing gestures.
 * - Snaps smoothly back to 0 if the gesture is cancelled or does not meet threshold.
 */
fun Modifier.swipeToDismiss(
    direction: SwipeDirection = SwipeDirection.LEFT_TO_RIGHT,
    triggerDistanceDp: Dp = 80.dp,
    requireEdgeStart: Boolean = true,
    edgeThresholdDp: Dp = 48.dp,
    enabled: Boolean = true,
    onDismiss: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val triggerDistancePx = with(density) { triggerDistanceDp.toPx() }
    val edgeThresholdPx = with(density) { edgeThresholdDp.toPx() }
    val offsetX = remember { Animatable(0f) }

    this
        .graphicsLayer {
            translationX = offsetX.value
        }
        .pointerInput(direction, triggerDistancePx, edgeThresholdPx, requireEdgeStart) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val startX = down.position.x
                val screenWidth = size.width.toFloat()

                if (requireEdgeStart) {
                    val inEdge = when (direction) {
                        SwipeDirection.LEFT_TO_RIGHT -> startX <= edgeThresholdPx
                        SwipeDirection.RIGHT_TO_LEFT -> startX >= (screenWidth - edgeThresholdPx)
                    }
                    if (!inEdge) return@awaitEachGesture
                }

                var totalDx = 0f
                var totalDy = 0f
                var isDragging = false
                val touchSlop = viewConfiguration.touchSlop
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!pointer.pressed) break

                    velocityTracker.addPosition(pointer.uptimeMillis, pointer.position)
                    val dx = pointer.position.x - pointer.previousPosition.x
                    val dy = pointer.position.y - pointer.previousPosition.y
                    totalDx += dx
                    totalDy += dy

                    if (!isDragging) {
                        if (abs(totalDy) > touchSlop && abs(totalDy) > abs(totalDx) * 1.25f) {
                            // Dominant vertical motion: user is scrolling panel content
                            return@awaitEachGesture
                        }
                        if (abs(totalDx) > touchSlop && abs(totalDx) > abs(totalDy) * 1.25f) {
                            val matchesDirection = when (direction) {
                                SwipeDirection.LEFT_TO_RIGHT -> totalDx > 0f
                                SwipeDirection.RIGHT_TO_LEFT -> totalDx < 0f
                            }
                            if (matchesDirection) {
                                isDragging = true
                                pointer.consume()
                            } else {
                                return@awaitEachGesture
                            }
                        }
                    } else {
                        pointer.consume()
                        scope.launch {
                            val newX = when (direction) {
                                SwipeDirection.LEFT_TO_RIGHT -> totalDx.coerceAtLeast(0f)
                                SwipeDirection.RIGHT_TO_LEFT -> totalDx.coerceAtMost(0f)
                            }
                            offsetX.snapTo(newX)
                        }
                    }
                } while (event.changes.any { it.pressed })

                if (isDragging) {
                    val velocityX = velocityTracker.calculateVelocity().x
                    val isFlick = when (direction) {
                        SwipeDirection.LEFT_TO_RIGHT -> velocityX > 1000f
                        SwipeDirection.RIGHT_TO_LEFT -> velocityX < -1000f
                    }
                    val currentVal = offsetX.value
                    val isDistanceMet = when (direction) {
                        SwipeDirection.LEFT_TO_RIGHT -> currentVal >= triggerDistancePx
                        SwipeDirection.RIGHT_TO_LEFT -> currentVal <= -triggerDistancePx
                    }

                    if (isDistanceMet || isFlick) {
                        scope.launch {
                            val target = when (direction) {
                                SwipeDirection.LEFT_TO_RIGHT -> screenWidth
                                SwipeDirection.RIGHT_TO_LEFT -> -screenWidth
                            }
                            offsetX.animateTo(
                                targetValue = target,
                                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                            )
                            offsetX.snapTo(0f)
                            onDismiss()
                        }
                    } else {
                        scope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                            )
                        }
                    }
                }
            }
        }
}
