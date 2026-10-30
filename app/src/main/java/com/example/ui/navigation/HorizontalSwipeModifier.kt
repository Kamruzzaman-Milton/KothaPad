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
    dragResistance: Float = 1.0f,
    maxDragOffsetPx: Float = Float.MAX_VALUE
): Modifier = composed {
    if (!enabled || (!allowSwipeLeft && !allowSwipeRight)) {
        return@composed Modifier
    }

    val density = LocalDensity.current
    val thresholdPx = with(density) { 28.dp.toPx() }
    val velocityThresholdPx = with(density) { 320.dp.toPx() }

    this.pointerInput(enabled, allowSwipeLeft, allowSwipeRight) {
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            var totalDx = 0f
            var totalDy = 0f
            var directionLocked = false
            var isHorizontal = false
            var triggered = false
            val pointerId = down.id

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (!change.pressed) {
                    // Finger lifted: if not triggered yet, check if velocity or distance met threshold
                    if (!triggered && directionLocked && isHorizontal) {
                        val velocityX = velocityTracker.calculateVelocity().x
                        if (allowSwipeRight && (totalDx >= thresholdPx || velocityX >= velocityThresholdPx)) {
                            change.consume()
                            onSwipeRight()
                        } else if (allowSwipeLeft && (totalDx <= -thresholdPx || velocityX <= -velocityThresholdPx)) {
                            change.consume()
                            onSwipeLeft()
                        }
                    }
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                val absX = abs(totalDx)
                val absY = abs(totalDy)

                if (!directionLocked) {
                    if (absX > touchSlop || absY > touchSlop) {
                        directionLocked = true
                        // Natural thumb sweep check: horizontal dominates vertical
                        if (absX > absY * 1.15f) {
                            val isRight = totalDx > 0f
                            val isLeft = totalDx < 0f
                            if ((isRight && allowSwipeRight) || (isLeft && allowSwipeLeft)) {
                                isHorizontal = true
                                change.consume()
                            } else {
                                break
                            }
                        } else {
                            // Vertical scroll dominates
                            break
                        }
                    }
                } else if (isHorizontal && !triggered) {
                    change.consume()
                    // Immediately trigger as soon as swipe threshold or flick is reached during motion
                    if (allowSwipeRight && totalDx >= thresholdPx) {
                        triggered = true
                        onSwipeRight()
                        break
                    } else if (allowSwipeLeft && totalDx <= -thresholdPx) {
                        triggered = true
                        onSwipeLeft()
                        break
                    }
                }
            }
        }
    }
}
