package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.Note
import com.example.data.model.SpokenCommand
import com.example.data.model.Stamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Note::class, Stamp::class, SpokenCommand::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun stampDao(): StampDao
    abstract fun spokenCommandDao(): SpokenCommandDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN folder TEXT NOT NULL DEFAULT 'General'")
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN sessionDurationSec INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stamps ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN sessionCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN wordCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN charCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN statsStale INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kothapad.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        scope.launch(Dispatchers.IO) {
                            seedInitialData(getInstance(context, scope))
                        }
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        scope.launch(Dispatchers.IO) {
                            ensureDefaultNotes(getInstance(context, scope))
                        }
                    }
                }).build().also { INSTANCE = it }
            }
        }

        suspend fun seedInitialData(database: AppDatabase) {
            val stampDao = database.stampDao()
            val noteDao = database.noteDao()
            val commandDao = database.spokenCommandDao()

            // Seed default templates
            if (stampDao.getCount() == 0) {
                val defaultTemplates = listOf(
                    Stamp(
                        setName = "Journal",
                        label = "Daily journal",
                        templateText = """═══ Daily Journal: {date} ({weekday}) ═══
Today:
{cursor}

Gratitude:
- 

Tomorrow:
- """,
                        voiceTriggerPhrase = "daily journal",
                        orderIndex = 0,
                        isPinned = true
                    ),
                    Stamp(
                        setName = "Study",
                        label = "Study log",
                        templateText = """═══ Study Log: {datetime} ═══
Subject: {cursor}
Key Concepts:
- 

Questions & Follow-ups:
- """,
                        voiceTriggerPhrase = "study log",
                        orderIndex = 1,
                        isPinned = false
                    ),
                    Stamp(
                        setName = "General",
                        label = "Quick note",
                        templateText = "[{date} {time}] {cursor}",
                        voiceTriggerPhrase = "quick note",
                        orderIndex = 2,
                        isPinned = false
                    )
                )
                stampDao.insertAll(defaultTemplates)
            }

            // Seed spoken commands
            if (commandDao.getCount() == 0) {
                val defaultCommands = listOf(
                    // English Punctuation
                    SpokenCommand(language = "en", phrase = "full stop", actionType = "PUNCTUATION", replacementText = ". "),
                    SpokenCommand(language = "en", phrase = "period", actionType = "PUNCTUATION", replacementText = ". "),
                    SpokenCommand(language = "en", phrase = "comma", actionType = "PUNCTUATION", replacementText = ", "),
                    SpokenCommand(language = "en", phrase = "question mark", actionType = "PUNCTUATION", replacementText = "? "),
                    SpokenCommand(language = "en", phrase = "exclamation mark", actionType = "PUNCTUATION", replacementText = "! "),
                    SpokenCommand(language = "en", phrase = "new line", actionType = "PUNCTUATION", replacementText = "\n"),
                    SpokenCommand(language = "en", phrase = "new paragraph", actionType = "PUNCTUATION", replacementText = "\n\n"),
                    SpokenCommand(language = "en", phrase = "colon", actionType = "PUNCTUATION", replacementText = ": "),
                    SpokenCommand(language = "en", phrase = "dash", actionType = "PUNCTUATION", replacementText = " — "),

                    // Bangla Punctuation
                    SpokenCommand(language = "bn", phrase = "দাঁড়ি", actionType = "PUNCTUATION", replacementText = "। "),
                    SpokenCommand(language = "bn", phrase = "দাড়ি", actionType = "PUNCTUATION", replacementText = "। "),
                    SpokenCommand(language = "bn", phrase = "কমা", actionType = "PUNCTUATION", replacementText = ", "),
                    SpokenCommand(language = "bn", phrase = "প্রশ্নবোধক চিহ্ন", actionType = "PUNCTUATION", replacementText = "? "),
                    SpokenCommand(language = "bn", phrase = "প্রশ্নচিহ্ন", actionType = "PUNCTUATION", replacementText = "? "),
                    SpokenCommand(language = "bn", phrase = "আশ্চর্যবোধক চিহ্ন", actionType = "PUNCTUATION", replacementText = "! "),
                    SpokenCommand(language = "bn", phrase = "নতুন লাইন", actionType = "PUNCTUATION", replacementText = "\n"),
                    SpokenCommand(language = "bn", phrase = "নতুন প্যারাগ্রাফ", actionType = "PUNCTUATION", replacementText = "\n\n"),
                    SpokenCommand(language = "bn", phrase = "কোলন", actionType = "PUNCTUATION", replacementText = ": "),

                    // Voice Actions
                    SpokenCommand(language = "en", phrase = "delete last word", actionType = "ACTION", commandCode = "DELETE_LAST_WORD"),
                    SpokenCommand(language = "en", phrase = "undo that", actionType = "ACTION", commandCode = "UNDO"),
                    SpokenCommand(language = "en", phrase = "stop dictation", actionType = "ACTION", commandCode = "STOP_DICTATION"),
                    SpokenCommand(language = "bn", phrase = "শেষ শব্দ মুছুন", actionType = "ACTION", commandCode = "DELETE_LAST_WORD"),
                    SpokenCommand(language = "bn", phrase = "শব্দ কাটো", actionType = "ACTION", commandCode = "DELETE_LAST_WORD"),
                    SpokenCommand(language = "bn", phrase = "আনডু করো", actionType = "ACTION", commandCode = "UNDO"),
                    SpokenCommand(language = "bn", phrase = "থামো", actionType = "ACTION", commandCode = "STOP_DICTATION"),
                    SpokenCommand(language = "bn", phrase = "বন্ধ করো", actionType = "ACTION", commandCode = "STOP_DICTATION")
                )
                commandDao.insertAll(defaultCommands)
            }

            // Seed and ensure default notes
            ensureDefaultNotes(database)
        }

        suspend fun ensureDefaultNotes(database: AppDatabase) {
            val noteDao = database.noteDao()
            val allNotes = noteDao.getAllNotesList()
            val currentTime = System.currentTimeMillis()

            // 1. Rename and upgrade existing "⚡ KothaPad Quick Tips" or create "⚡KothaPad Tips"
            val oldQuickTips = allNotes.find { it.title == "⚡ KothaPad Quick Tips" }
            if (oldQuickTips != null) {
                noteDao.update(
                    oldQuickTips.copy(
                        title = "⚡KothaPad Tips",
                        content = KOTHAPAD_TIPS_CONTENT,
                        folder = "General",
                        updatedAt = currentTime
                    )
                )
            } else if (allNotes.none { it.title == "⚡KothaPad Tips" }) {
                val tipsNote = Note(
                    title = "⚡KothaPad Tips",
                    content = KOTHAPAD_TIPS_CONTENT,
                    isPinned = true,
                    languageCode = "bn-BD",
                    folder = "General",
                    tags = "tips,guide,shortcuts,scenarios",
                    createdAt = currentTime,
                    updatedAt = currentTime
                )
                noteDao.insert(tipsNote)
            }

            // 2. Ensure "⚡KothaPad features" exists
            if (allNotes.none { it.title == "⚡KothaPad features" }) {
                val featuresNote = Note(
                    title = "⚡KothaPad features",
                    content = KOTHAPAD_FEATURES_CONTENT,
                    isPinned = false,
                    languageCode = "bn-BD",
                    folder = "General",
                    tags = "features,guide,docs,reference",
                    createdAt = currentTime + 1,
                    updatedAt = currentTime + 1
                )
                noteDao.insert(featuresNote)
            }
        }

        val KOTHAPAD_TIPS_CONTENT = """
# ⚡ KothaPad Tips & Productivity Masterclass

Welcome to **KothaPad**! This guide is crafted to help you dictate, format, and organize your thoughts with maximum speed, clarity, and efficacy.

---

## 🚀 The Core Mindset: Think in Voice, Polish with Touch

Traditional note-taking forces your brain to slow down to typing speed (30–45 WPM). With KothaPad, you can comfortably speak at **140–180 words per minute**:
- **Continuous Voice Stream:** Speak in whole sentences and paragraphs without worrying about system timeouts.
- **Micro-Edits on the Fly:** Use the floating side rail, in-app keyboard, and quick format bar to structure your text without halting your dictation flow.

---

## 🎙️ 1. Mastering Continuous Hands-Free Dictation

1. **Tap the Glowing Mic:** Tap the floating mic button to start recording. The live waveform visualizer confirms audio capture.
2. **Never Worry About Pauses:** Unlike default speech recognizers that abort after 3 seconds of silence, KothaPad's **Speech Supervisor** stays active in the background. Stop to sip water, consult notes, or formulate ideas—dictation resumes seamlessly when you speak again.
3. **Timed Dictation Sprints:** Use Timed Dictation (set minutes in secondary bar or side rail) for 2 to 5-minute brainstorm sprints.
4. **Offline Voice Packs for Zero Latency:** In Android System Settings > Google > Voice / Speech Services, download offline speech recognition packs for Bengali (বাংলা) and English. This enables lightning-fast transcription with zero internet requirement.

---

## 🗣️ 2. Voice Punctuation & Smart Commands

KothaPad's intelligent pipeline converts spoken phrases into exact typographical symbols automatically:

### Bengali Commands (বাংলা ভয়েস কমান্ড)
- **"দাঁড়ি"** or **"দাড়ি"** ➔ `।` (Bangla Dā̃ṛi)
- **"কমা"** ➔ `,`
- **"প্রশ্নবোধক চিহ্ন"** or **"প্রশ্নচিহ্ন"** ➔ `?`
- **"আশ্চর্যবোধক চিহ্ন"** ➔ `!`
- **"নতুন লাইন"** ➔ Inserts a single line break
- **"নতুন প্যারাগ্রাফ"** ➔ Inserts a double paragraph break
- **"কোলন"** ➔ `:`
- **"শব্দ কাটো"** or **"শেষ শব্দ মুছুন"** ➔ Erases the last spoken word
- **"আনডু করো"** ➔ Reverts the last voice insertion
- **"থামো"** or **"বন্ধ করো"** ➔ Halts voice dictation

### English Commands
- **"full stop"** or **"period"** ➔ `.`
- **"comma"** ➔ `,`
- **"question mark"** ➔ `?`
- **"exclamation mark"** ➔ `!`
- **"new line"** ➔ Line break
- **"new paragraph"** ➔ Paragraph break
- **"colon"** ➔ `:`
- **"dash"** ➔ ` — `
- **"delete last word"** ➔ Deletes previous word
- **"undo that"** ➔ Reverts previous insertion
- **"stop dictation"** ➔ Halts dictation

---

## ⌨️ 3. Hybrid In-App Keyboard & Cursor Flow

- **Type While Listening:** When you switch to KothaPad's In-App Keyboard, voice recognition remains fully active!
- **Cursor Navigation (◀ ▶):** Relocate your cursor anywhere in the text while speaking. Dictated words drop exactly where the cursor is placed.
- **Hold-to-Insert:** Long press keyboard keys for quick punctuation, numbers, and custom macros.

---

## 📑 4. Obsidian-Grade Markdown & Reading View

- **Live Editor Styling:** As you type `# Heading`, `**bold**`, or `==highlight==`, text styles visually right inside the editor. Syntax markers dim gracefully to reduce visual clutter.
- **Tappable Checkboxes in Reading Mode:** Switch to Reading Mode (tap the Book icon in the top bar). Tap task checkboxes (`- [ ]`) directly on screen to mark items complete—it auto-saves instantly!
- **Auto-Continuing Lists:** Hit Enter on any bullet, number, or task item to automatically continue the list. Press Enter twice to finish the list.

---

## 🏷️ 5. One-Tap Text Stamps & Templates

- **Instant Timestamps:** Tap the Stamp button above the keyboard or toolbar to insert `[{date} {time}]` at your cursor.
- **Pre-Built Logs:** Access Daily Journal and Study Log templates with placeholders ready for your dictation.
- **Custom Templates:** Create custom stamps under Settings > Text Stamps for your personal repetitive forms.

---

## 🌍 Real-Life Productivity Scenarios

### Scenario 1: University Lectures & Academic Seminars
- **Workflow:** Sit near the speaker or speakerphone. Set language to Bangla or English. Keep dictation running continuously.
- **Efficacy Strategy:** Tap `H` (Heading) on the Format Bar before every new lecture slide, and bullet points (`-`) for sub-points. Use `==highlight==` on exam-critical concepts.

### Scenario 2: Journalism & Field Interviews
- **Workflow:** Connect a wired lapel mic or Bluetooth headset. KothaPad automatically detects the external audio input.
- **Efficacy Strategy:** Speak conversational punctuation commands ("new paragraph", "কমা", "দাঁড়ি") to produce ready-to-publish interview drafts without manual cleanup.

### Scenario 3: Daily Standup & Meeting Minutes
- **Workflow:** Open a note in the "General" folder. Tap the Stamp icon for `[Date Time]` timestamping.
- **Efficacy Strategy:** Dictate action items as task lists (`- [ ] Prepare pitch deck`). In the afternoon review, switch to Reading Mode and tap checkboxes as tasks get accomplished.

### Scenario 4: Creative Writers & Novelists
- **Workflow:** Defeat blank page syndrome by pacing your room and speaking scenes aloud.
- **Efficacy Strategy:** Let your raw thoughts stream without judging grammar. Use spoken commands for paragraphs and dialogues. Refine formatting later using the quick format bar.

### Scenario 5: Medical, Field & Inspection Logs
- **Workflow:** Dictate hands-free while inspecting machinery, inventory, or clinical reports.
- **Efficacy Strategy:** Enable "Offline Preferred" mode in Settings for complete zero-connectivity operation in basements, warehouses, or rural zones.

---

## ⚙️ Pro Customization Checklist
- [x] Rearrange your Format Bar buttons (Settings > Markdown Settings > Format Bar) by dragging Heading, Bold, Highlight, Numbered List, and Checkbox to the front.
- [x] Configure Floating Side Rail on your dominant thumb side for easy single-handed control.
- [x] Enable AMOLED theme for deep battery savings during long dictation sessions.
""".trimIndent()

        val KOTHAPAD_FEATURES_CONTENT = """
# ⚡ KothaPad Complete Feature Tour

Welcome to the definitive feature guide for **KothaPad**—the ultra-fast, local-first voice dictation pad and Markdown editor engineered specifically for Bengali and English power writers.

---

## 🎙️ 1. Advanced Voice Dictation Engine

- **Continuous Non-Stop Dictation:** KothaPad uses a proprietary **Speech Supervisor** loop. Unlike default Android speech recognizers that terminate on 3 seconds of silence, KothaPad remains vigilant, automatically restarting without dropping spoken words.
- **Custom Pause Tolerance:** Tailor how long the supervisor waits during silence (from 5 seconds up to 60 seconds) before initiating a seamless refresh.
- **Timed Dictation Mode:** Set focused dictation sprints (1 to 60 minutes) with a real-time countdown timer and clean automatic completion.
- **Multi-Source Audio Detector:** Automatically detects and switches between Built-in Microphone, Wired Headset Mic, and Bluetooth Audio Devices with live visual status chips.
- **Crash-Safety Audio Mute Recovery:** Automatically restores system media and notification volumes if an unexpected interruption occurs during recording.
- **Offline-Preferred Mode:** Force local on-device speech transcription, guaranteeing zero internet data transmission and minimal audio latency.

---

## 🗣️ 2. Intelligent Spoken Commands & Punctuation Pipeline

- **Bilingual Spoken Punctuation:** Real-time typographic conversion for both Bengali ("দাঁড়ি", "কমা", "প্রশ্নবোধক চিহ্ন", "আশ্চর্যবোধক চিহ্ন", "নতুন লাইন", "নতুন প্যারাগ্রাফ", "কোলন") and English ("full stop", "comma", "question mark", "exclamation mark", "new line", "new paragraph", "colon", "dash").
- **Voice Editing Actions:** Speak "শব্দ কাটো" / "delete last word" to delete mistakes without lifting your hands, or "আনডু করো" / "undo that" to revert.
- **Smart Spacing Pipeline:** Enforces typographic rules around Bengali and English punctuation symbols (prevents rogue leading spaces and guarantees single trailing space).
- **Auto-Capitalization Pipeline:** Intelligently capitalizes sentence starts in English dictation.
- **Customizable Command Dictionary:** Add, edit, or delete spoken phrases and their action mappings under Settings > Spoken Commands.

---

## ✍️ 3. Obsidian-Style Markdown Engine

- **Live Syntax Styling in Editor Mode:** Live bolding, italicization, highlights, code blocks, blockquotes, and headings right inside the editing field while typing.
- **Dimmed Syntax Markers:** Markers such as `#`, `**`, `==`, `>`, and ```` are visually dimmed so reading while editing is natural and distraction-free.
- **Proportional Heading Scaling:** Configurable heading scale (0.8x to 1.5x) visually enlarges `# Heading 1` down to `###### Heading 6` in real-time.
- **Monospace Code Blocks:** Inline code `` `code` `` and multi-line fenced code blocks ```` ``` ```` render in JetBrains Mono font.
- **Auto-Continuing Lists:** Hitting Enter on a bullet (`-`), numbered (`1.`), or task (`- [ ]`) list automatically generates the next list line.
- **Rich Reading Mode:** Clean rendered view with markdown tables, horizontal dividers, blockquotes, and customizable reading font scale.
- **Interactive Checkboxes in Reading Mode:** Tap checkboxes directly while reading to toggle completed status and auto-save the note.

---

## 🎛️ 4. Customizable Quick Format Bar

- **Drag-and-Drop Reordering:** In Settings > Markdown Settings > Format Bar, simply drag and drop the handle (≡) or use arrows to arrange components into your preferred sequence.
- **Component Visibility Toggles:** Individually toggle buttons on or off (Heading, Bold, Highlight, Numbered List, Checkbox, Divider, Italic, Strikethrough, Bullet List, Quote, Inline Code, Code Block, Link).
- **Default Optimized Sequence:** Starts with Heading, Bold, Highlight, Numbered List, Checkbox, Divider, followed by remaining tools.
- **One-Tap Wrapping:** Wraps selected text in markdown syntax, or inserts empty template syntax with cursor inside if no text is highlighted.

---

## 📱 5. Floating Controls & Ergonomic Side Rail

- **Side Rail Layout:** Positions primary controls (Mic, Timer, Keyboard, Expand, Format Bar) along the left or right edge for natural one-handed thumb ergonomics.
- **Customizable Rail Button Order:** Reorder the buttons within the side rail in Settings > Floating Controls.
- **Floating Action Cluster:** Alternatively, dock floating controls in a compact cluster at the bottom with adjustable scale and offsets.
- **Live Waveform Visualizer:** Displays live audio amplitude so you always know when speech is being captured.

---

## ⌨️ 6. Built-In In-App Keyboard

- **Dual-Language Soft Keyboard:** Custom soft keyboard with dedicated Bengali and English layouts designed specifically for voice notes.
- **Concurrent Voice + Typing:** Move cursors (◀ ▶) or type words without interrupting active voice dictation.
- **Custom Ergonomics:** Adjust key heights (30dp–60dp), font size (12sp–20sp), and haptic vibration feedback.
- **Hold-to-Insert Shortcuts:** Long-press keys to trigger numbers, symbols, or custom text stamps.

---

## 📂 7. Vault Architecture & Notes Management

- **Folder Hierarchy:** Organize notes into General, Templates, Trash, or user-created custom folders.
- **Multi-Metric Note Tracker:** Live tracking of word count, character count, and estimated reading time with customizable visibility and ordering.
- **Deep Search:** Instant full-text search across titles, contents, tags, and folders.
- **Auto-Empty Trash:** Configurable automatic trash cleanup (e.g. 7 days retention) with one-tap undo recovery.
- **Full Vault Import & Export:** Export your entire notes database, templates, and settings to standard JSON or folder backups for complete data sovereignty.

---

## 🔒 8. Zero-Tracking, 100% Local-First Privacy

- **Zero Accounts & Zero Cloud Dependency:** No login, no telemetry, no third-party tracking, and no external servers.
- **Your Notes Remain on Your Device:** All data is safely stored in local SQLite / Room storage and can be backed up to your local storage vault.
""".trimIndent()
    }
}
