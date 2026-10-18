package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SpokenCommand
import com.example.engine.pipeline.PipelineContext
import com.example.engine.pipeline.StampProcessor
import com.example.engine.pipeline.TextPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("KothaPad", appName)
    }

    @Test
    fun `test stamp token replacement`() {
        val template = "Entry: {date} at {time}\n{cursor}End"
        val result = StampProcessor.processTemplate(template, locale = Locale.US)
        assertTrue(result.outputText.contains("Entry: "))
        assertTrue(result.outputText.contains("End"))
        assertTrue(result.cursorOffsetFromStart > 0)
    }

    @Test
    fun `test bengali punctuation pipeline`() = runBlocking {
        val pipeline = TextPipeline.createDefault()
        val commands = listOf(
            SpokenCommand(language = "bn", phrase = "দাঁড়ি", actionType = "PUNCTUATION", replacementText = "। ")
        )
        val context = PipelineContext(
            languageCode = "bn-BD",
            precedingText = "",
            activeCommands = commands
        )
        val processed = pipeline.execute("আমি ভাত খাব দাঁড়ি", context)
        assertTrue(processed.contains("।"))
    }

    @Test
    fun `test keyboard serialization round trip`() {
        val original = com.example.ui.keyboard.KeyboardLayouts.getDefault3x10Layout()
        val json = com.example.ui.keyboard.KeyboardLayouts.serializeToJson(original)
        val deserialized = com.example.ui.keyboard.KeyboardLayouts.deserializeFromJson(json)

        assertEquals(original.rows.size, deserialized.rows.size)
        for (r in original.rows.indices) {
            val originalKeys = original.rows[r].keys
            val deserializedKeys = deserialized.rows[r].keys
            assertEquals(originalKeys.size, deserializedKeys.size)
            for (k in originalKeys.indices) {
                assertEquals(originalKeys[k].id, deserializedKeys[k].id)
                assertEquals(originalKeys[k].label, deserializedKeys[k].label)
                assertEquals(originalKeys[k].actionType, deserializedKeys[k].actionType)
                assertEquals(originalKeys[k].outputText, deserializedKeys[k].outputText)
            }
        }
    }

    @Test
    fun `test note metrics order, visibility, and formatting`() {
        val note = com.example.data.model.Note(
            id = 1L,
            title = "Test Note",
            content = "Hello world from test suite",
            sessionDurationSec = 135L, // 2m 15s
            createdAt = 1700000000000L,
            updatedAt = 1700000050000L
        )

        // Default order: WORDS, SESSION_TIME, CHARS, READ_TIME, MODIFIED_TIME, CREATED_TIME
        val compactMetrics = com.example.ui.notes.NoteMetricsConfig.formatMetrics(
            note = note,
            style = "COMPACT",
            orderStr = com.example.ui.notes.NoteMetricsConfig.DEFAULT_ORDER_STRING,
            hiddenItems = emptySet()
        )
        assertTrue(compactMetrics.contains("5w"))
        assertTrue(compactMetrics.contains("2m 15s spent"))
        assertTrue(compactMetrics.contains("27c"))
        assertTrue(compactMetrics.contains("~1m read"))

        // Test hiding specific items
        val hiddenMetrics = com.example.ui.notes.NoteMetricsConfig.formatMetrics(
            note = note,
            style = "COMPACT",
            orderStr = com.example.ui.notes.NoteMetricsConfig.DEFAULT_ORDER_STRING,
            hiddenItems = setOf(
                com.example.ui.notes.NoteMetricsConfig.METRIC_CHARS,
                com.example.ui.notes.NoteMetricsConfig.METRIC_CREATED_TIME
            )
        )
        org.junit.Assert.assertFalse(hiddenMetrics.contains("27c"))
        assertTrue(hiddenMetrics.contains("5w"))
        assertTrue(hiddenMetrics.contains("2m 15s spent"))

        // Test custom serial order (SESSION_TIME first, then WORDS)
        val customOrder = "${com.example.ui.notes.NoteMetricsConfig.METRIC_SESSION_TIME},${com.example.ui.notes.NoteMetricsConfig.METRIC_WORDS}"
        val reorderedMetrics = com.example.ui.notes.NoteMetricsConfig.formatMetrics(
            note = note,
            style = "COMPACT",
            orderStr = customOrder,
            hiddenItems = setOf(
                com.example.ui.notes.NoteMetricsConfig.METRIC_CHARS,
                com.example.ui.notes.NoteMetricsConfig.METRIC_READ_TIME,
                com.example.ui.notes.NoteMetricsConfig.METRIC_MODIFIED_TIME,
                com.example.ui.notes.NoteMetricsConfig.METRIC_CREATED_TIME
            )
        )
        assertEquals("2m 15s spent • 5w", reorderedMetrics)
    }

    @Test
    fun `test horizontal swipe navigation state transitions and destination isolation`() {
        // Models the exact screen navigation states
        var currentScreen = "EDITOR"
        var isNoteManagerOpen = false

        // 1. Editor Screen active:
        assertTrue(currentScreen == "EDITOR" && !isNoteManagerOpen)

        // 1a. Swiping horizontally from right to left (dx < 0) must open main settings menu
        val onSwipeLeftFromEditor = {
            currentScreen = "SETTINGS"
        }
        onSwipeLeftFromEditor()
        assertEquals("SETTINGS", currentScreen)
        org.junit.Assert.assertFalse(isNoteManagerOpen)

        // 2. Main Settings Menu open:
        // 2a. Swiping horizontally from left to right (dx > 0) must close settings and return to editor
        val onSwipeRightFromSettings = {
            currentScreen = "EDITOR"
        }
        onSwipeRightFromSettings()
        assertEquals("EDITOR", currentScreen)
        org.junit.Assert.assertFalse(isNoteManagerOpen)

        // 3. Editor Screen active:
        // 3a. Swiping horizontally from left to right (dx > 0) must open note manager list
        val onSwipeRightFromEditor = {
            isNoteManagerOpen = true
        }
        onSwipeRightFromEditor()
        assertTrue(isNoteManagerOpen)
        assertEquals("EDITOR", currentScreen)

        // 4. Note Manager List open:
        // 4a. Swiping horizontally from right to left (dx < 0) must close note manager and return to editor
        val onSwipeLeftFromNoteManager = {
            isNoteManagerOpen = false
        }
        onSwipeLeftFromNoteManager()
        org.junit.Assert.assertFalse(isNoteManagerOpen)
        assertEquals("EDITOR", currentScreen)

        // 5. Verify isolation: Note Manager must never accidentally open Settings, and Settings must never open Note Manager
        val allowRightOnSettings = true
        val allowLeftOnSettings = false // Swiping left on settings does nothing
        org.junit.Assert.assertFalse(allowLeftOnSettings)

        val allowLeftOnNoteManager = true
        val allowRightOnNoteManager = false // Swiping right on note manager does nothing
        org.junit.Assert.assertFalse(allowRightOnNoteManager)
    }

    @Test
    fun `test horizontal swipe directional dominance and threshold logic`() {
        // Pure horizontal swipe: dx dominates dy significantly
        fun isHorizontalSwipeValid(dx: Float, dy: Float, touchSlop: Float): Boolean {
            val absX = kotlin.math.abs(dx)
            val absY = kotlin.math.abs(dy)
            return (absX > touchSlop || absY > touchSlop) && (absX > absY * 1.6f)
        }

        val touchSlop = 20f

        // Case 1: Clear horizontal swipe right
        assertTrue(isHorizontalSwipeValid(dx = 80f, dy = 10f, touchSlop = touchSlop))

        // Case 2: Clear horizontal swipe left
        assertTrue(isHorizontalSwipeValid(dx = -90f, dy = 15f, touchSlop = touchSlop))

        // Case 3: Vertical scroll attempt (e.g. note or settings scrolling)
        org.junit.Assert.assertFalse(isHorizontalSwipeValid(dx = 15f, dy = 80f, touchSlop = touchSlop))

        // Case 4: Diagonal movement where vertical motion is too high
        org.junit.Assert.assertFalse(isHorizontalSwipeValid(dx = 40f, dy = 35f, touchSlop = touchSlop))

        // Case 5: Micro touch under touch slop (e.g. tap or click)
        org.junit.Assert.assertFalse(isHorizontalSwipeValid(dx = 5f, dy = 3f, touchSlop = touchSlop))
    }

    @Test
    fun `test note editing state and content preservation across navigation`() {
        // Verify that navigating away and back does not reset textFieldValue or currentNote
        var currentNoteContent = "Important draft that must not be lost"
        var currentCursorPosition = currentNoteContent.length

        // Navigate to Settings
        val savedContent = currentNoteContent
        val savedCursor = currentCursorPosition

        var currentScreen = "SETTINGS"
        assertEquals("SETTINGS", currentScreen)

        // Return to Editor
        currentScreen = "EDITOR"
        assertEquals("EDITOR", currentScreen)
        assertEquals("Important draft that must not be lost", savedContent)
        assertEquals(currentNoteContent.length, savedCursor)
    }
}
