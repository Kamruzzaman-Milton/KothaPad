package com.example.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Note
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses date from note filename (YYYY-MM-DD...), falling back to createdAt date.
 */
fun extractNoteJournalDate(note: Note): LocalDate? {
    val title = note.title.trim()
    val match = Regex("""^(\d{4}-\d{2}-\d{2})""").find(title)
    if (match != null) {
        try {
            return LocalDate.parse(match.groupValues[1])
        } catch (_: Exception) {}
    }
    return try {
        if (note.createdAt > 0L) {
            Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        } else null
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyJournalManagerScreen(
    allNotes: List<Note>,
    onDateClick: (LocalDate) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only notes in "Daily Journals" folder that are not trashed
    val journalNotes = remember(allNotes) {
        allNotes.filter {
            it.folder.trim().equals("Daily Journals", ignoreCase = true) && !it.isDeleted
        }
    }

    val notesByDate = remember(journalNotes) {
        journalNotes.mapNotNull { note ->
            extractNoteJournalDate(note)?.let { it to note }
        }.groupBy({ it.first }, { it.second })
    }

    val today = remember { LocalDate.now() }
    val currentMonth = remember { YearMonth.now() }
    var pastMonthsCount by remember { mutableIntStateOf(24) }

    val earliestNoteDate = remember(notesByDate) {
        notesByDate.keys.minOrNull()
    }
    val earliestMonth = earliestNoteDate?.let { YearMonth.from(it) }
    val minMonthsNeeded = remember(earliestMonth, currentMonth) {
        if (earliestMonth != null && earliestMonth.isBefore(currentMonth)) {
            val diff = (currentMonth.year - earliestMonth.year) * 12 + (currentMonth.monthValue - earliestMonth.monthValue)
            maxOf(diff + 1, 24)
        } else 24
    }
    val effectiveMonthsCount = maxOf(pastMonthsCount, minMonthsNeeded)

    // Month list from past months down to currentMonth
    val monthsList = remember(effectiveMonthsCount, currentMonth) {
        (effectiveMonthsCount downTo 0).map { offset ->
            currentMonth.minusMonths(offset.toLong())
        }
    }

    val listState = rememberLazyListState()

    // Scroll to latest (current) month at the bottom on initial display
    LaunchedEffect(Unit) {
        if (monthsList.isNotEmpty()) {
            listState.scrollToItem(monthsList.size - 1)
        }
    }

    // Lazy load older past months when scrolling to the top
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIndex ->
                if (firstIndex == 0 && pastMonthsCount < 120) {
                    val prevCount = pastMonthsCount
                    pastMonthsCount += 12
                    // Smoothly preserve scroll offset
                    listState.scrollToItem(12, listState.firstVisibleItemScrollOffset)
                }
            }
    }

    val weekDays = remember { listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri") }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("daily_journal_manager_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Daily Journal Manager",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("daily_journal_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Notes Manager"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            items(monthsList, key = { it.toString() }) { ym ->
                MonthBlock(
                    yearMonth = ym,
                    today = today,
                    notesByDate = notesByDate,
                    weekDays = weekDays,
                    onDateClick = onDateClick
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MonthBlock(
    yearMonth: YearMonth,
    today: LocalDate,
    notesByDate: Map<LocalDate, List<Note>>,
    weekDays: List<String>,
    onDateClick: (LocalDate) -> Unit
) {
    val monthTitle = remember(yearMonth) {
        yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Month Header
            Text(
                text = monthTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            )

            // Day of week column headers (Sat, Sun, Mon, Tue, Wed, Thu, Fri)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                weekDays.forEach { dayName ->
                    Text(
                        text = dayName,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Grid calculation: Saturday starts at index 0
            val firstDay = yearMonth.atDay(1)
            val startOffset = (firstDay.dayOfWeek.value + 1) % 7
            val totalDays = yearMonth.lengthOfMonth()

            val calendarCells = remember(yearMonth) {
                val cells = mutableListOf<LocalDate?>()
                // Leading empty days
                for (i in 0 until startOffset) {
                    cells.add(null)
                }
                // Days of month
                for (day in 1..totalDays) {
                    cells.add(yearMonth.atDay(day))
                }
                // Trailing empty days to complete row
                val remainder = cells.size % 7
                if (remainder != 0) {
                    for (i in 0 until (7 - remainder)) {
                        cells.add(null)
                    }
                }
                cells
            }

            val weeks = remember(calendarCells) {
                calendarCells.chunked(7)
            }

            // Render week rows
            weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    week.forEach { date ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (date != null) {
                                val isToday = (date == today)
                                val dayNotes = notesByDate[date] ?: emptyList()
                                val hasNotes = dayNotes.isNotEmpty()
                                val noteCount = dayNotes.size

                                CalendarDayCell(
                                    date = date,
                                    isToday = isToday,
                                    hasNotes = hasNotes,
                                    noteCount = noteCount,
                                    onClick = { onDateClick(date) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    hasNotes: Boolean,
    noteCount: Int,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)

    val backgroundColor = when {
        hasNotes -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    val contentColor = when {
        hasNotes -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val border = when {
        isToday && !hasNotes -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
        isToday && hasNotes -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.onPrimary)
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(backgroundColor)
            .then(if (border != null) Modifier.background(backgroundColor, shape).padding(1.dp) else Modifier)
            .clickable(onClick = onClick)
            .testTag("calendar_day_${date}")
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = backgroundColor,
            border = border
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (hasNotes || isToday) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.5.sp
                    ),
                    color = contentColor
                )

                // Small badge if date has more than 1 note
                if (hasNotes && noteCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(14.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (noteCount > 9) "9+" else noteCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
