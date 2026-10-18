package com.example.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * High-performance horizontal swipe navigation modifier.
 *
 * Performance optimizations:
 * 1. Zero recomposition during dragging: `translationX` is driven entirely inside
 *    the `graphicsLayer { ... }` draw lambda so movement runs on the GPU RenderNode
 *    without recomposing or re-laying-out heavy screen contents.
 * 2. Gesture isolation: continuous drag movement is decoupled from ViewModel/global
 *    state. Screen navigation and state changes only dispatch upon release when
 *    thresholds are met.
 * 3. Directional slope locking: verifies horizontal motion dominance (`abs(dx) > abs(dy) * 1.6f`)
 *    before locking into a swipe. If vertical movement dominates, the detector yields
 *    immediately so `LazyColumn` and `verticalScroll` scroll smoothly with zero lag.
 * 4. Interaction safety: respects child touch consumption (such as text selection handles,
 *    sliders, and button clicks) before touch slop, preventing conflicting gesture handlers.
 * 5. I/O isolation: keeps database queries, disk access, and auto-saves out of gesture paths.
 */
fun Modifier.horizontalSwipeNavigation(
    enabled: Boolean = true,
    allowSwipeLeft: Boolean = false,   // dx < 0 (drag towards left)
    allowSwipeRight: Boolean = false,  // dx > 0 (drag towards right)
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    dragResistance: Float = 1.0f,      // 1.0f for 1:1 panel tracking, <1.0f for elastic resistance
    maxDragOffsetPx: Float = Float.MAX_VALUE
): Modifier = composed {
    if (!enabled || (!allowSwipeLeft && !allowSwipeRight)) {
        return@composed Modifier
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val thresholdPx = with(density) { 56.dp.toPx() }
    val velocityThresholdPx = with(density) { 600.dp.toPx() }

    val animOffset = remember { Animatable(0f) }

    this
        .graphicsLayer {
            translationX = animOffset.value
        }
        .pointerInput(enabled, allowSwipeLeft, allowSwipeRight, screenWidthPx) {
            val touchSlop = viewConfiguration.touchSlop

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                var totalDx = 0f
                var totalDy = 0f
                var directionLocked = false
                var isHorizontal = false
                val pointerId = down.id

                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                    if (!change.pressed) {
                        // Pointer released (finger lifted)
                        if (directionLocked && isHorizontal) {
                            val velocity = velocityTracker.calculateVelocity().x
                            val currentVal = animOffset.value
                            val effectiveDx = totalDx

                            val shouldTriggerRight = allowSwipeRight && (
                                currentVal >= thresholdPx ||
                                effectiveDx >= thresholdPx ||
                                velocity >= velocityThresholdPx
                            )

                            val shouldTriggerLeft = allowSwipeLeft && (
                                currentVal <= -thresholdPx ||
                                effectiveDx <= -thresholdPx ||
                                velocity <= -velocityThresholdPx
                            )

                            if (shouldTriggerRight) {
                                scope.launch {
                                    if (dragResistance >= 0.8f) {
                                        animOffset.animateTo(screenWidthPx, tween(180))
                                    } else {
                                        animOffset.animateTo(0f, tween(150))
                                    }
                                    onSwipeRight()
                                    animOffset.snapTo(0f)
                                }
                            } else if (shouldTriggerLeft) {
                                scope.launch {
                                    if (dragResistance >= 0.8f) {
                                        animOffset.animateTo(-screenWidthPx, tween(180))
                                    } else {
                                        animOffset.animateTo(0f, tween(150))
                                    }
                                    onSwipeLeft()
                                    animOffset.snapTo(0f)
                                }
                            } else {
                                // Threshold not reached: snap back smoothly
                                scope.launch {
                                    animOffset.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        }
                        break
                    }

                    // If a child (button, slider, text selection) consumed the event before direction lock, abort
                    if (!directionLocked && change.isConsumed) {
                        break
                    }

                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val delta = change.position - change.previousPosition
                    totalDx += delta.x
                    totalDy += delta.y

                    if (!directionLocked) {
                        val absX = abs(totalDx)
                        val absY = abs(totalDy)

                        if (absX > touchSlop || absY > touchSlop) {
                            directionLocked = true
                            // Require horizontal movement to clearly dominate vertical movement
                            if (absX > absY * 1.6f) {
                                val isRight = totalDx > 0
                                val isLeft = totalDx < 0
                                if ((isRight && allowSwipeRight) || (isLeft && allowSwipeLeft)) {
                                    isHorizontal = true
                                    change.consume()
                                } else {
                                    // Moving in disallowed direction for active screen
                                    isHorizontal = false
                                    break
                                }
                            } else {
                                // Vertical scroll dominates, let children scroll smoothly
                                isHorizontal = false
                                break
                            }
                        }
                    } else if (isHorizontal) {
                        change.consume()
                        val incrementalDelta = delta.x * dragResistance
                        val raw = animOffset.value + incrementalDelta
                        val clamped = when {
                            !allowSwipeLeft -> raw.coerceIn(0f, maxDragOffsetPx)
                            !allowSwipeRight -> raw.coerceIn(-maxDragOffsetPx, 0f)
                            else -> raw.coerceIn(-maxDragOffsetPx, maxDragOffsetPx)
                        }
                        scope.launch {
                            animOffset.snapTo(clamped)
                        }
                    }
                }
            }
        }
}
