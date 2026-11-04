package com.example.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 12-Hour Analog Clock Picker with true 1-minute precision (00 to 59).
 * Features an intuitive 2-step selection:
 * Step 1: Hour selection (1..12) with AM/PM toggle.
 * Step 2: Minute selection (00..59) with individual minute resolution and 5-min major markers.
 */
@Composable
fun AnalogClockPickerDialog(
    initialHour: Int,    // 0..23
    initialMinute: Int,  // 0..59
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 12-hour breakdown
    var isPm by remember { mutableStateOf(initialHour >= 12) }
    var hour12 by remember {
        val h = when {
            initialHour == 0 -> 12
            initialHour > 12 -> initialHour - 12
            else -> initialHour
        }
        mutableIntStateOf(h)
    }
    var minute by remember { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }

    // Mode: "HOUR" or "MINUTE"
    var pickerMode by remember { mutableStateOf("HOUR") }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Reminder Time",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Display: [HH] : [MM] [AM/PM]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour selector button
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { pickerMode = "HOUR" },
                        color = if (pickerMode == "HOUR") primaryColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = if (pickerMode == "HOUR") androidx.compose.foundation.BorderStroke(1.5.dp, primaryColor) else null,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "%02d".format(hour12),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (pickerMode == "HOUR") primaryColor else onSurfaceColor
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    // Minute selector button
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { pickerMode = "MINUTE" },
                        color = if (pickerMode == "MINUTE") primaryColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = if (pickerMode == "MINUTE") androidx.compose.foundation.BorderStroke(1.5.dp, primaryColor) else null,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "%02d".format(minute),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (pickerMode == "MINUTE") primaryColor else onSurfaceColor
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // AM / PM Toggle Column
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { isPm = false },
                            color = if (!isPm) primaryColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "AM",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isPm) Color.White else onSurfaceVariant
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { isPm = true },
                            color = if (isPm) primaryColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "PM",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPm) Color.White else onSurfaceVariant
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Subtitle indicating what is being picked
                Text(
                    text = if (pickerMode == "HOUR") "Touch or drag to select Hour (1 - 12)" else "Touch or drag to select Minute (00 - 59)",
                    style = MaterialTheme.typography.labelMedium,
                    color = primaryColor
                )

                // Circular Analog Clock Face
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .pointerInput(pickerMode) {
                            fun updateFromOffset(offset: Offset) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val dx = offset.x - center.x
                                val dy = offset.y - center.y
                                // angle in radians from top (12 o'clock) clockwise:
                                // atan2(dy, dx) returns angle from +X (3 o'clock).
                                var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                if (angleDeg < 0f) angleDeg += 360f

                                if (pickerMode == "HOUR") {
                                    // 360 deg / 12 = 30 deg per hour
                                    val rawHour = (angleDeg / 30f).roundToInt() % 12
                                    hour12 = if (rawHour == 0) 12 else rawHour
                                } else {
                                    // 360 deg / 60 = 6 deg per minute (1-minute precision!)
                                    val rawMin = (angleDeg / 6f).roundToInt() % 60
                                    minute = rawMin
                                }
                            }

                            detectTapGestures(
                                onPress = { offset ->
                                    updateFromOffset(offset)
                                    // Auto advance to minute after picking hour
                                    if (pickerMode == "HOUR") {
                                        pickerMode = "MINUTE"
                                    }
                                }
                            )
                        }
                        .pointerInput(pickerMode) {
                            fun updateFromOffset(offset: Offset) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val dx = offset.x - center.x
                                val dy = offset.y - center.y
                                var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                if (angleDeg < 0f) angleDeg += 360f

                                if (pickerMode == "HOUR") {
                                    val rawHour = (angleDeg / 30f).roundToInt() % 12
                                    hour12 = if (rawHour == 0) 12 else rawHour
                                } else {
                                    val rawMin = (angleDeg / 6f).roundToInt() % 60
                                    minute = rawMin
                                }
                            }

                            detectDragGestures { change, _ ->
                                change.consume()
                                updateFromOffset(change.position)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f - 24.dp.toPx()

                        // Draw center dot
                        drawCircle(
                            color = primaryColor,
                            radius = 4.dp.toPx(),
                            center = center
                        )

                        if (pickerMode == "HOUR") {
                            // Selected Hour Hand
                            val hourAngleDeg = (hour12 % 12) * 30f - 90f
                            val hourAngleRad = Math.toRadians(hourAngleDeg.toDouble())
                            val handEnd = Offset(
                                (center.x + radius * 0.72f * cos(hourAngleRad)).toFloat(),
                                (center.y + radius * 0.72f * sin(hourAngleRad)).toFloat()
                            )

                            // Hand line
                            drawLine(
                                color = primaryColor,
                                start = center,
                                end = handEnd,
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )

                            // Selected indicator circle at number
                            drawCircle(
                                color = primaryColor,
                                radius = 16.dp.toPx(),
                                center = handEnd
                            )

                            // Draw 12 Numbers
                            val textPaint = android.graphics.Paint().apply {
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 15.sp.toPx()
                                isAntiAlias = true
                            }

                            for (h in 1..12) {
                                val angle = Math.toRadians((h * 30f - 90f).toDouble())
                                val nx = (center.x + radius * 0.72f * cos(angle)).toFloat()
                                val ny = (center.y + radius * 0.72f * sin(angle)).toFloat() + (textPaint.textSize / 3f)

                                textPaint.color = if (h == hour12) {
                                    android.graphics.Color.WHITE
                                } else {
                                    android.graphics.Color.argb(
                                        220,
                                        (onSurfaceColor.red * 255).toInt(),
                                        (onSurfaceColor.green * 255).toInt(),
                                        (onSurfaceColor.blue * 255).toInt()
                                    )
                                }
                                textPaint.isFakeBoldText = (h == hour12)

                                drawContext.canvas.nativeCanvas.drawText(
                                    h.toString(),
                                    nx,
                                    ny,
                                    textPaint
                                )
                            }
                        } else {
                            // Selected Minute Hand
                            val minAngleDeg = minute * 6f - 90f
                            val minAngleRad = Math.toRadians(minAngleDeg.toDouble())
                            val handEnd = Offset(
                                (center.x + radius * 0.78f * cos(minAngleRad)).toFloat(),
                                (center.y + radius * 0.78f * sin(minAngleRad)).toFloat()
                            )

                            drawLine(
                                color = primaryColor,
                                start = center,
                                end = handEnd,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )

                            drawCircle(
                                color = primaryColor,
                                radius = 14.dp.toPx(),
                                center = handEnd
                            )

                            // Draw minute numbers every 5 mins (00, 05, 10 ... 55)
                            val textPaint = android.graphics.Paint().apply {
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 13.5.sp.toPx()
                                isAntiAlias = true
                            }

                            for (m in 0 until 60 step 5) {
                                val angle = Math.toRadians((m * 6f - 90f).toDouble())
                                val nx = (center.x + radius * 0.78f * cos(angle)).toFloat()
                                val ny = (center.y + radius * 0.78f * sin(angle)).toFloat() + (textPaint.textSize / 3f)

                                textPaint.color = if (m == minute) {
                                    android.graphics.Color.WHITE
                                } else {
                                    android.graphics.Color.argb(
                                        220,
                                        (onSurfaceColor.red * 255).toInt(),
                                        (onSurfaceColor.green * 255).toInt(),
                                        (onSurfaceColor.blue * 255).toInt()
                                    )
                                }
                                textPaint.isFakeBoldText = (m == minute)

                                drawContext.canvas.nativeCanvas.drawText(
                                    "%02d".format(m),
                                    nx,
                                    ny,
                                    textPaint
                                )
                            }

                            // If selected minute is not on a 5-minute mark, draw selected minute value in center bubble
                            if (minute % 5 != 0) {
                                textPaint.color = android.graphics.Color.WHITE
                                textPaint.isFakeBoldText = true
                                drawContext.canvas.nativeCanvas.drawText(
                                    "%02d".format(minute),
                                    handEnd.x,
                                    handEnd.y + (textPaint.textSize / 3f),
                                    textPaint
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalHour24 = when {
                        !isPm && hour12 == 12 -> 0
                        !isPm -> hour12
                        isPm && hour12 == 12 -> 12
                        else -> hour12 + 12
                    }
                    onConfirm(finalHour24, minute)
                }
            ) {
                Text("Set Time")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
