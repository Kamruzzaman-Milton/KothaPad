package com.example.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Note
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyJournalDayScreen(
    selectedDate: LocalDate,
    allNotes: List<Note>,
    currentNoteId: Long,
    showNoteMetrics: Boolean = true,
    noteMetricsStyle: String = "COMPACT",
    noteMetricsOrder: String = NoteMetricsConfig.DEFAULT_ORDER_STRING,
    noteMetricsHiddenItems: Set<String> = emptySet(),
    onNoteClick: (Note) -> Unit,
    onCreateNoteForDate: (LocalDate) -> Unit,
    onRenameNote: (Note, String) -> Unit,
    onDeleteNote: (Note) -> Unit,
    onPinToggle: (Note) -> Unit,
    onDuplicateNote: (Note) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Title formatted e.g. "28 Oct 2026"
    val formattedTitle = remember(selectedDate) {
        selectedDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }

    // Filter notes in "Daily Journals" folder matching selectedDate
    val dayNotes = remember(allNotes, selectedDate) {
        allNotes.filter { note ->
            note.folder.trim().equals("Daily Journals", ignoreCase = true) &&
                    !note.isDeleted &&
                    extractNoteJournalDate(note) == selectedDate
        }.sortedWith(
            compareByDescending<Note> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
    }

    var noteToRename by remember { mutableStateOf<Note?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var noteToDeleteConfirm by remember { mutableStateOf<Note?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("daily_journal_day_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = formattedTitle,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("day_notes_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Calendar"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onCreateNoteForDate(selectedDate)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.testTag("day_notes_plus_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create new note for this date"
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (dayNotes.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No notes for this day",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the plus button below to create a journal entry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dayNotes, key = { it.id }) { note ->
                        NoteTreeItem(
                            note = note,
                            isCurrentlyOpen = (note.id == currentNoteId),
                            isMultiSelectMode = false,
                            isSelected = false,
                            showMetrics = showNoteMetrics,
                            metricsStyle = noteMetricsStyle,
                            noteMetricsOrder = noteMetricsOrder,
                            noteMetricsHiddenItems = noteMetricsHiddenItems,
                            isTrash = false,
                            isArchive = note.isArchived,
                            onNoteClick = { onNoteClick(note) },
                            onToggleSelect = {},
                            onRename = {
                                noteToRename = note
                                renameInput = note.title
                            },
                            onPinToggle = { onPinToggle(note) },
                            onDuplicate = { onDuplicateNote(note) },
                            onDelete = { noteToDeleteConfirm = note }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }
    }

    // Rename Dialog
    noteToRename?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToRename = null },
            title = { Text("Rename Note") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Note Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = renameInput.trim()
                        if (clean.isNotBlank()) {
                            onRenameNote(note, clean)
                        }
                        noteToRename = null
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    noteToDeleteConfirm?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDeleteConfirm = null },
            title = { Text("Delete Note") },
            text = { Text("Move \"${note.title}\" to Trash?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteNote(note)
                        noteToDeleteConfirm = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
