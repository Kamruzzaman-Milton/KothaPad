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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Note
import com.example.ui.navigation.horizontalSwipeNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses date from note title (YYYY-MM-DD...), falling back to createdAt date for notes in Daily Journal folder.
 */
fun extractNoteJournalDate(note: Note): LocalDate? {
    val title = note.title.trim()
    if (title.length >= 10 && title[4] == '-' && title[7] == '-') {
        val parsed = runCatching { LocalDate.parse(title.substring(0, 10)) }.getOrNull()
        if (parsed != null) return parsed
    }
    val f = note.folder.trim()
    if (f.equals("Daily Journal", ignoreCase = true) || f.equals("Daily Journals", ignoreCase = true)) {
        return try {
            if (note.createdAt > 0L) {
                Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
            } else null
        } catch (_: Exception) {
            null
        }
    }
    return null
}

fun isDailyJournalNote(note: Note): Boolean {
    val f = note.folder.trim()
    if (f.equals("Daily Journals", ignoreCase = true) || f.equals("Daily Journal", ignoreCase = true)) {
        return true
    }
    val title = note.title.trim()
    return title.length >= 10 && title[4] == '-' && title[7] == '-'
}

private val START_YEAR_MONTH: YearMonth = YearMonth.of(2010, 1)

@Immutable
data class FastMonthData(
    val index: Int,
    val yearMonth: YearMonth,
    val ymString: String,
    val title: String,
    val startOffset: Int,
    val daysInMonth: Int
)

@Immutable
data class MonthCalendarData(
    val yearMonth: YearMonth,
    val title: String,
    val weeks: List<List<LocalDate?>>
)

private val fastMonthCache = ConcurrentHashMap<Int, FastMonthData>()
private val monthTitleFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

fun getFastMonthData(index: Int): FastMonthData {
    return fastMonthCache.getOrPut(index) {
        val ym = START_YEAR_MONTH.plusMonths(index.toLong())
        val title = ym.format(monthTitleFormatter)
        val firstDay = ym.atDay(1)
        val startOffset = (firstDay.dayOfWeek.value + 1) % 7 // Saturday is 0
        val totalDays = ym.lengthOfMonth()
        val ymStr = ym.toString()

        FastMonthData(
            index = index,
            yearMonth = ym,
            ymString = ymStr,
            title = title,
            startOffset = startOffset,
            daysInMonth = totalDays
        )
    }
}

fun getCachedMonthData(ym: YearMonth): MonthCalendarData {
    val index = ChronoUnit.MONTHS.between(START_YEAR_MONTH, ym).toInt()
    val fast = getFastMonthData(index)
    val cells = ArrayList<LocalDate?>(fast.startOffset + fast.daysInMonth + 7)
    for (i in 0 until fast.startOffset) {
        cells.add(null)
    }
    for (day in 1..fast.daysInMonth) {
        cells.add(fast.yearMonth.atDay(day))
    }
    val remainder = cells.size % 7
    if (remainder != 0) {
        for (i in 0 until (7 - remainder)) {
            cells.add(null)
        }
    }
    return MonthCalendarData(
        yearMonth = ym,
        title = fast.title,
        weeks = cells.chunked(7)
    )
}

// Pre-defined reusable styles and shapes to eliminate per-cell allocations
private val DayCellShape = RoundedCornerShape(8.dp)
private val WeekDayHeaderStyle = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 11.5.sp
)
private val DayNormalStyle = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 13.5.sp
)
private val DayBoldStyle = TextStyle(
    fontWeight = FontWeight.Bold,
    fontSize = 13.5.sp
)
private val MonthTitleStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyJournalManagerScreen(
    allNotes: List<Note>,
    onDateClick: (LocalDate) -> Unit,
    onTodayPlusClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Fast O(1) bitmask map: YearMonth -> 32-bit Int (bit 'day' is 1 if that day has a note)
    val notesMaskMap = remember(allNotes) {
        val map = HashMap<YearMonth, Int>()
        for (note in allNotes) {
            if (!note.isDeleted && isDailyJournalNote(note)) {
                val d = extractNoteJournalDate(note)
                if (d != null) {
                    val ym = YearMonth.of(d.year, d.monthValue)
                    val currentMask = map[ym] ?: 0
                    map[ym] = currentMask or (1 shl d.dayOfMonth)
                }
            }
        }
        map
    }

    val today = remember { LocalDate.now() }
    val currentMonth = remember { YearMonth.now() }
    val todayDay = today.dayOfMonth

    val totalMonths = remember(currentMonth) {
        (ChronoUnit.MONTHS.between(START_YEAR_MONTH, currentMonth) + 1).toInt()
    }

    // Pre-warm month cache in background coroutine so scrolling backward is instantly smooth
    LaunchedEffect(totalMonths) {
        withContext(Dispatchers.Default) {
            for (i in 0 until totalMonths) {
                getFastMonthData(i)
            }
        }
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (totalMonths - 1).coerceAtLeast(0))
    val coroutineScope = rememberCoroutineScope()
    val weekDays = remember { listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri") }

    var showMonthYearDialog by remember { mutableStateOf(false) }

    val isAwayFromCurrentMonth by remember(totalMonths) {
        derivedStateOf {
            listState.firstVisibleItemIndex < (totalMonths - 1)
        }
    }

    // Theme colors cached for day cell drawing
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val futureColor = onSurfaceColor.copy(alpha = 0.28f)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .horizontalSwipeNavigation(
                enabled = !showMonthYearDialog,
                allowSwipeLeft = true,  // right-to-left: closes Calendar and returns to note editor
                allowSwipeRight = false,
                onSwipeLeft = onBackClick
            )
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
                            contentDescription = "Back to Journal Editor"
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
                    count = totalMonths,
                    key = { it }
                ) { index ->
                    val monthData = getFastMonthData(index)
                    val ym = monthData.yearMonth
                    val isCurrentMonth = (ym == currentMonth)
                    val isFutureMonth = ym.isAfter(currentMonth)
                    val mask = notesMaskMap[ym] ?: 0

                    OptimizedMonthBlock(
                        data = monthData,
                        isCurrentMonth = isCurrentMonth,
                        isFutureMonth = isFutureMonth,
                        todayDay = todayDay,
                        notesMask = mask,
                        primaryColor = primaryColor,
                        onPrimaryColor = onPrimaryColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariantColor = onSurfaceVariantColor,
                        futureColor = futureColor,
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
                                    listState.scrollToItem(totalMonths - 1)
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
            val visibleIndex = listState.firstVisibleItemIndex.coerceIn(0, totalMonths - 1)
            START_YEAR_MONTH.plusMonths(visibleIndex.toLong())
        }

        MonthYearPickerDialog(
            initialYearMonth = currentVisibleMonth,
            startYear = START_YEAR_MONTH.year,
            currentMonth = currentMonth,
            onDismiss = { showMonthYearDialog = false },
            onSelect = { selectedYm ->
                showMonthYearDialog = false
                val targetIndex = ChronoUnit.MONTHS.between(START_YEAR_MONTH, selectedYm).toInt()
                    .coerceIn(0, totalMonths - 1)
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
                            val monthName = Month.of(mValue).getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
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
    data: FastMonthData,
    isCurrentMonth: Boolean,
    isFutureMonth: Boolean,
    todayDay: Int,
    notesMask: Int,
    primaryColor: Color,
    onPrimaryColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariantColor: Color,
    futureColor: Color,
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
                style = MonthTitleStyle,
                color = primaryColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            )

            // Day of week column headers with single-pass measurement (zero weight overhead)
            CalendarWeekHeaderRow(
                weekDays = weekDays,
                color = onSurfaceVariantColor,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // High-performance single-pass grid (zero nested Rows, zero empty placeholder boxes, zero 2-pass weights)
            CalendarMonthGrid(
                data = data,
                isCurrentMonth = isCurrentMonth,
                isFutureMonth = isFutureMonth,
                todayDay = todayDay,
                notesMask = notesMask,
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor,
                onSurfaceColor = onSurfaceColor,
                futureColor = futureColor,
                onDateClick = onDateClick
            )
        }
    }
}

@Composable
private fun CalendarWeekHeaderRow(
    weekDays: List<String>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Layout(
        content = {
            weekDays.forEach { dayName ->
                Text(
                    text = dayName,
                    textAlign = TextAlign.Center,
                    style = WeekDayHeaderStyle,
                    color = color
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    ) { measurables, constraints ->
        val colWidth = constraints.maxWidth / 7
        val childConstraints = Constraints(maxWidth = colWidth)
        val placeables = measurables.map { it.measure(childConstraints) }
        val maxHeight = placeables.maxOfOrNull { it.height } ?: 0

        layout(constraints.maxWidth, maxHeight) {
            placeables.forEachIndexed { col, placeable ->
                val x = col * colWidth + (colWidth - placeable.width) / 2
                placeable.place(x, 0)
            }
        }
    }
}

@Composable
private fun CalendarMonthGrid(
    data: FastMonthData,
    isCurrentMonth: Boolean,
    isFutureMonth: Boolean,
    todayDay: Int,
    notesMask: Int,
    primaryColor: Color,
    onPrimaryColor: Color,
    onSurfaceColor: Color,
    futureColor: Color,
    onDateClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(
        content = {
            for (day in 1..data.daysInMonth) {
                val isToday = isCurrentMonth && (day == todayDay)
                val isFuture = isFutureMonth || (isCurrentMonth && day > todayDay)
                val hasNote = (notesMask and (1 shl day)) != 0

                CalendarDayCell(
                    dayNumber = day,
                    isToday = isToday,
                    isFuture = isFuture,
                    hasNote = hasNote,
                    primaryColor = primaryColor,
                    onPrimaryColor = onPrimaryColor,
                    onSurfaceColor = onSurfaceColor,
                    futureColor = futureColor,
                    onClick = { onDateClick(data.yearMonth.atDay(day)) },
                    testTagDate = "${data.ymString}-${if (day < 10) "0$day" else day.toString()}"
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    ) { measurables, constraints ->
        val colWidth = constraints.maxWidth / 7
        val cellHeight = colWidth
        val childConstraints = Constraints.fixed(colWidth, cellHeight)
        val placeables = measurables.map { it.measure(childConstraints) }

        val totalSlots = data.startOffset + data.daysInMonth
        val numRows = (totalSlots + 6) / 7
        val totalHeight = numRows * cellHeight

        layout(constraints.maxWidth, totalHeight) {
            var slot = data.startOffset
            placeables.forEach { placeable ->
                val col = slot % 7
                val row = slot / 7
                placeable.place(col * colWidth, row * cellHeight)
                slot++
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
    primaryColor: Color,
    onPrimaryColor: Color,
    onSurfaceColor: Color,
    futureColor: Color,
    onClick: () -> Unit,
    testTagDate: String
) {
    val backgroundColor = if (hasNote) primaryColor else Color.Transparent
    val contentColor = when {
        hasNote -> onPrimaryColor
        isFuture -> futureColor
        else -> onSurfaceColor
    }

    val borderModifier = when {
        isToday && !hasNote -> Modifier.border(1.5.dp, primaryColor.copy(alpha = 0.75f), DayCellShape)
        isToday && hasNote -> Modifier.border(1.5.dp, onPrimaryColor, DayCellShape)
        else -> Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(DayCellShape)
            .background(backgroundColor)
            .then(borderModifier)
            .clickable(onClick = onClick)
            .testTag("calendar_day_$testTagDate"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayNumber.toString(),
            style = if (hasNote || isToday) DayBoldStyle else DayNormalStyle,
            color = contentColor
        )
    }
}
