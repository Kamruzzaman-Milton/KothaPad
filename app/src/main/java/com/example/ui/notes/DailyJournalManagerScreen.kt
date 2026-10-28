package com.example.ui.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Parses date from note title (YYYY-MM-DD...), falling back to createdAt date.
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

private val START_YEAR_MONTH: YearMonth = YearMonth.of(2010, 1)

@Immutable
data class MonthCalendarData(
    val yearMonth: YearMonth,
    val title: String,
    val weeks: List<List<LocalDate?>>
)

private fun precomputeMonthData(ym: YearMonth): MonthCalendarData {
    val title = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    val firstDay = ym.atDay(1)
    val startOffset = (firstDay.dayOfWeek.value + 1) % 7 // Saturday is 0
    val totalDays = ym.lengthOfMonth()

    val cells = ArrayList<LocalDate?>(startOffset + totalDays + 7)
    for (i in 0 until startOffset) {
        cells.add(null)
    }
    for (day in 1..totalDays) {
        cells.add(ym.atDay(day))
    }
    val remainder = cells.size % 7
    if (remainder != 0) {
        for (i in 0 until (7 - remainder)) {
            cells.add(null)
        }
    }

    return MonthCalendarData(
        yearMonth = ym,
        title = title,
        weeks = cells.chunked(7)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyJournalManagerScreen(
    allNotes: List<Note>,
    onDateClick: (LocalDate) -> Unit,
    onTodayPlusClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Only notes in "Daily Journals" folder that are not trashed
    val journalNotes = remember(allNotes) {
        allNotes.filter {
            it.folder.trim().equals("Daily Journals", ignoreCase = true) && !it.isDeleted
        }
    }

    // High performance O(1) date lookup for notes
    val datesWithNotes = remember(journalNotes) {
        journalNotes.mapNotNull { extractNoteJournalDate(it) }.toSet()
    }

    val today = remember { LocalDate.now() }
    val currentMonth = remember { YearMonth.now() }

    // Calendar fixed start at Jan 2010 and dynamically ends at current month
    val monthDataList = remember(currentMonth) {
        val totalMonths = ChronoUnit.MONTHS.between(START_YEAR_MONTH, currentMonth)
        (0L..totalMonths).map { offset ->
            precomputeMonthData(START_YEAR_MONTH.plusMonths(offset))
        }
    }

    // Initial scroll position: immediately display the current month (last item)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = monthDataList.lastIndex)
    val coroutineScope = rememberCoroutineScope()
    val weekDays = remember { listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri") }

    var showMonthYearDialog by remember { mutableStateOf(false) }

    // Derived state to determine if user has scrolled away from the current month
    val isAwayFromCurrentMonth by remember(monthDataList) {
        derivedStateOf {
            listState.firstVisibleItemIndex < monthDataList.lastIndex
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("daily_journal_manager_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Daily Journal",
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
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomInset = maxOf(innerPadding.calculateBottomPadding(), navBarBottom)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                items(
                    items = monthDataList,
                    key = { it.yearMonth.toString() }
                ) { monthData ->
                    OptimizedMonthBlock(
                        data = monthData,
                        today = today,
                        datesWithNotes = datesWithNotes,
                        weekDays = weekDays,
                        onDateClick = onDateClick
                    )
                }

                // Generous bottom spacing: last row of calendar dates will always be
                // scrolled completely above the floating action buttons and system nav bar.
                item {
                    Spacer(modifier = Modifier.height(bottomInset + 104.dp))
                }
            }

            // Bottom Floating Buttons Row
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = bottomInset + 16.dp
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-Left Button Group: Month/Year Selector and Return-to-Current-Month FAB
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Month and Year Selector (Bottom Left)
                    FloatingActionButton(
                        onClick = { showMonthYearDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag("month_year_selector_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Select Month and Year"
                        )
                    }

                    // Return to Current Month FAB: appears immediately when scrolled away
                    AnimatedVisibility(
                        visible = isAwayFromCurrentMonth,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.scrollToItem(monthDataList.lastIndex)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.testTag("daily_journal_return_today_fab")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Today,
                                contentDescription = "Return to current month"
                            )
                        }
                    }
                }

                // Plus Button (Bottom Right)
                FloatingActionButton(
                    onClick = onTodayPlusClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("daily_journal_today_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create or open today's note"
                    )
                }
            }
        }
    }

    if (showMonthYearDialog) {
        val currentVisibleMonth = remember {
            val visibleIndex = listState.firstVisibleItemIndex.coerceIn(0, monthDataList.lastIndex)
            monthDataList[visibleIndex].yearMonth
        }

        MonthYearPickerDialog(
            initialYearMonth = currentVisibleMonth,
            startYear = START_YEAR_MONTH.year,
            currentMonth = currentMonth,
            onDismiss = { showMonthYearDialog = false },
            onSelect = { selectedYm ->
                showMonthYearDialog = false
                val targetIndex = ChronoUnit.MONTHS.between(START_YEAR_MONTH, selectedYm).toInt()
                    .coerceIn(0, monthDataList.lastIndex)
                coroutineScope.launch {
                    listState.scrollToItem(targetIndex)
                }
            }
        )
    }
}

@Composable
private fun MonthYearPickerDialog(
    initialYearMonth: YearMonth,
    startYear: Int,
    currentMonth: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit
) {
    var selectedYear by remember { mutableIntStateOf(initialYearMonth.year.coerceIn(startYear, currentMonth.year)) }
    var selectedMonth by remember {
        val maxMonthForYear = if (selectedYear == currentMonth.year) currentMonth.monthValue else 12
        mutableIntStateOf(initialYearMonth.monthValue.coerceIn(1, maxMonthForYear))
    }

    val years = remember(startYear, currentMonth.year) {
        (startYear..currentMonth.year).toList()
    }
    val yearIndex = remember(years, selectedYear) {
        years.indexOf(selectedYear).coerceAtLeast(0)
    }
    val yearListState = rememberLazyListState(initialFirstVisibleItemIndex = (yearIndex - 2).coerceAtLeast(0))
    val monthListState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedMonth - 3).coerceAtLeast(0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Month & Year",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Scrollable Month Column
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxSize()
                ) {
                    Text(
                        text = "Month",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    val maxMonth = if (selectedYear == currentMonth.year) currentMonth.monthValue else 12
                    LazyColumn(
                        state = monthListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items((1..maxMonth).toList()) { mValue ->
                            val isSelected = mValue == selectedMonth
                            val monthName = Month.of(mValue).getDisplayName(TextStyle.FULL, Locale.getDefault())
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedMonth = mValue }
                            ) {
                                Text(
                                    text = monthName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Scrollable Year Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    Text(
                        text = "Year",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    LazyColumn(
                        state = yearListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(years) { y ->
                            val isSelected = y == selectedYear
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedYear = y
                                        val maxM = if (y == currentMonth.year) currentMonth.monthValue else 12
                                        if (selectedMonth > maxM) {
                                            selectedMonth = maxM
                                        }
                                    }
                            ) {
                                Text(
                                    text = y.toString(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
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
                    onSelect(YearMonth.of(selectedYear, selectedMonth))
                }
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun OptimizedMonthBlock(
    data: MonthCalendarData,
    today: LocalDate,
    datesWithNotes: Set<LocalDate>,
    weekDays: List<String>,
    onDateClick: (LocalDate) -> Unit
) {
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
                text = data.title,
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

            // Render precomputed week rows
            data.weeks.forEach { week ->
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
                                val isFuture = date.isAfter(today)
                                val hasNote = datesWithNotes.contains(date)

                                CalendarDayCell(
                                    dayNumber = date.dayOfMonth,
                                    isToday = isToday,
                                    isFuture = isFuture,
                                    hasNote = hasNote,
                                    onClick = if (isFuture) null else { { onDateClick(date) } },
                                    testTagDate = date.toString()
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
    dayNumber: Int,
    isToday: Boolean,
    isFuture: Boolean,
    hasNote: Boolean,
    onClick: (() -> Unit)?,
    testTagDate: String
) {
    val shape = RoundedCornerShape(8.dp)

    val backgroundColor = when {
        hasNote -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    val contentColor = when {
        hasNote -> MaterialTheme.colorScheme.onPrimary
        isFuture -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderModifier = when {
        isToday && !hasNote -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f), shape)
        isToday && hasNote -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.onPrimary, shape)
        else -> Modifier
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(backgroundColor)
            .then(borderModifier)
            .then(clickModifier)
            .testTag("calendar_day_$testTagDate"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (hasNote || isToday) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.5.sp
            ),
            color = contentColor
        )
    }
}
