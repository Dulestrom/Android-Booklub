package io.github.dulestrom.booklub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uniffi.booklub_mobile.Goal
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

// ---------- Analytics ----------

/** Screen D1 and D2: the desktop Analytics screen, in one column (Android SRS REQ-45). */
@Composable
fun AnalyticsScreen(state: AppState) {
    val goals = remember(state.revision) { state.library.goals() }
    val stats = remember(state.revision) { state.library.stats() }
    var editing by remember { mutableStateOf<Goal?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TitleBar("Analytics")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (goal in goals) GoalCard(goal) { editing = goal }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Total books", stats.total.toString(), Modifier.weight(1f))
                StatTile("Reading", stats.reading.toString(), Modifier.weight(1f))
                StatTile("To Read", stats.toRead.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Finished", stats.finished.toString(), Modifier.weight(1f))
                StatTile("Finished in ${LocalDate.now().year}", stats.finishedThisYear.toString(), Modifier.weight(1f))
                StatTile("Avg days to finish", stats.meanDaysToFinish?.let { Math.round(it).toString() } ?: "—", Modifier.weight(1f))
            }

            Card {
                Text("Finished per month · ${LocalDate.now().year}", style = Type.bodyStrong, color = ink.ink)
                Spacer(Modifier.height(12.dp))
                MonthBars(stats.finishedByMonth.map { it.toInt() })
            }

            Card {
                Text("Category distribution", style = Type.bodyStrong, color = ink.ink)
                val largest = (stats.byCategory.maxOfOrNull { it.count.toInt() } ?: 0).coerceAtLeast(1)
                stats.byCategory.forEachIndexed { index, entry ->
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text(entry.name, style = Type.caption, color = ink.ink2, modifier = Modifier.weight(1f))
                        Text(entry.count.toString(), style = Type.caption, color = ink.ink)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(entry.count.toInt() / largest.toFloat()).height(10.dp).clip(RoundedCornerShape(2.dp)).background(ink.chart[index % ink.chart.size]))
                }
                if (stats.uncategorized > 0u) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text("No category", style = Type.caption, color = ink.ink3, modifier = Modifier.weight(1f))
                        Text(booksText(stats.uncategorized.toInt()), style = Type.caption, color = ink.ink3)
                    }
                }
                if (stats.thinnest.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(R.drawable.ic_warning, null, ink.warningInk, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Fewest books: ${stats.thinnest.joinToString(", ")}", style = Type.caption, color = ink.ink2)
                    }
                }
            }

            Card {
                Text("Priority by status", style = Type.bodyStrong, color = ink.ink)
                Spacer(Modifier.height(10.dp))
                TableRow("", "Reading", "To Read", "Finished", header = true)
                for (row in stats.priorityByStatus) TableRow(row.priority, row.reading.toString(), row.toRead.toString(), row.finished.toString())
            }

            Card {
                Text("Average rating", style = Type.caption, color = ink.ink2)
                Text(stats.meanRating?.let { String.format(Locale.US, "%.1f of 5", it) } ?: "—", style = Type.title1, color = ink.ink)
                if (stats.meanRating == null) Text("No books have a rating yet.", style = Type.caption, color = ink.ink3)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    editing?.let { goal -> GoalSheet(state, goal) { editing = null } }
}

@Composable
private fun GoalCard(goal: Goal, onEdit: () -> Unit) {
    val now = LocalDate.now()
    val period = if (goal.id == "booksPerYear") "${now.year} · Books per year" else "${now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} · Books per month"
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(period, style = Type.bodyStrong, color = ink.ink, modifier = Modifier.weight(1f))
            if (goal.active) PaceChip(goal.pacing)
            ToolButton(R.drawable.ic_edit, "Edit ${period.substringAfter("· ").lowercase()} goal", onClick = onEdit)
        }
        if (!goal.active) {
            Text("Goal off. Edit it to switch it back on.", style = Type.caption, color = ink.ink3)
            return@Card
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(goal.achieved.toString(), style = Type.stat, color = ink.ink)
            Spacer(Modifier.width(8.dp))
            Text("of ${booksText(goal.target.toInt())} · ${goal.percent}%", style = Type.caption, color = ink.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        ProgressLine(goal.percent.toInt() / 100f, goal.pacing != "Behind", false, Modifier.clip(RoundedCornerShape(4.dp)), 8.dp)
        Spacer(Modifier.height(8.dp))
        Text("${goal.remaining} remaining · ${String.format(Locale.US, "%.1f", goal.perWeekNeeded)} books a week needed", style = Type.caption, color = ink.ink3)
    }
}

/** Pace by glyph and word as well as colour (desktop QA-35). */
@Composable
private fun PaceChip(pacing: String) {
    val (icon, color) = when (pacing) {
        "Ahead" -> R.drawable.ic_pace_ahead to ink.successInk
        "Behind" -> R.drawable.ic_pace_behind to ink.warningInk
        else -> R.drawable.ic_pace_on_track to ink.successInk
    }
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(ink.raised).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, null, color, 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(pacing, style = Type.caption, color = color)
    }
}

@Composable
private fun StatTile(caption: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(ink.card).border(1.dp, ink.line, RoundedCornerShape(10.dp)).padding(10.dp).heightIn(min = 64.dp)) {
        Text(caption, style = Type.caption, color = ink.ink2, minLines = 1, maxLines = 2)
        Text(value, style = Type.title2, color = ink.ink)
    }
}

@Composable
private fun MonthBars(counts: List<Int>) {
    val largest = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val bar = ink.chart[0]
    val empty = ink.line
    Row(Modifier.fillMaxWidth().height(140.dp), verticalAlignment = Alignment.Bottom) {
        counts.forEachIndexed { month, count ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (count > 0) Text(count.toString(), style = Type.chip, color = ink.ink2)
                Canvas(
                    Modifier.fillMaxWidth().padding(horizontal = 3.dp).height((100f * count / largest).coerceAtLeast(1f).dp)
                        .semantics { contentDescription = "${java.time.Month.of(month + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH)}: $count finished" },
                ) {
                    drawRoundRect(if (count > 0) bar else empty, Offset.Zero, Size(size.width, size.height), CornerRadius(3.dp.toPx()))
                }
                Text("JFMAMJJASOND"[month].toString(), style = Type.chip, color = ink.ink3, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun TableRow(first: String, a: String, b: String, c: String, header: Boolean = false) {
    val style = if (header) Type.caption else Type.body
    val color = if (header) ink.ink2 else ink.ink
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(first, style = Type.bodyStrong, color = ink.ink, modifier = Modifier.weight(1.2f))
        for (cell in listOf(a, b, c)) Text(cell, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** Screen D3, Android SRS REQ-45a. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(state: AppState, goal: Goal, onDone: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var target by remember { mutableIntStateOf(goal.target.toInt()) }
    var active by remember { mutableStateOf(goal.active) }
    val yearly = goal.id == "booksPerYear"
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = ink.card) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (yearly) "Books per year" else "Books per month", style = Type.title2, color = ink.ink, modifier = Modifier.weight(1f))
                ToolButton(R.drawable.ic_close, "Close", onClick = onDone)
            }
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Goal on", style = Type.bodyStrong, color = ink.ink)
                    Text("Show this goal and its pace on Analytics", style = Type.caption, color = ink.ink3)
                }
                Switch(checked = active, onCheckedChange = { active = it }, colors = SwitchDefaults.colors(checkedTrackColor = ink.accent))
            }
            Text(if (yearly) "Target for ${LocalDate.now().year}" else "Target for this month", style = Type.caption, color = ink.ink2)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", "Lower the target", enabled = target > 1) { target-- }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(target.toString(), style = Type.stat, color = ink.ink)
                    Text("books", style = Type.caption, color = ink.ink3)
                }
                StepButton("+", "Raise the target", enabled = true) { target++ }
            }
            Text("${goal.achieved} finished so far.", style = Type.caption, color = ink.ink2)
            Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel", style = Type.bodyStrong, color = ink.ink) }
                CommitButton("Save", Modifier.weight(1f)) {
                    if (state.write { state.library.saveGoal(goal.id, target.toUInt(), active) }) onDone()
                }
            }
        }
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(ink.raised).clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.title2, color = if (enabled) ink.ink else ink.ink4) }
}

// ---------- Settings ----------

/** Screen D4, with Devices (REQ-41, REQ-41a) and the image folder (REQ-42a). */
@Composable
fun SettingsScreen(state: AppState) {
    val context = LocalContext.current
    val used = remember(state.revision, state.imagesDir) {
        java.io.File(state.imagesDir).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024.0 * 1024.0)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        TitleBar("Settings")
        Eyebrow("Appearance")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(ink.raised).padding(4.dp)) {
            for (option in Appearance.entries) {
                val selected = state.appearance == option
                Row(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) ink.card else ink.raised)
                        .clickable(onClickLabel = "Appearance ${option.label}") { state.chooseAppearance(option) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) { Glyph(R.drawable.ic_check, null, ink.ink, 16.dp); Spacer(Modifier.width(6.dp)) }
                    Text(option.label, style = if (selected) Type.bodyStrong else Type.body, color = ink.ink)
                }
            }
        }
        Eyebrow("Devices")
        DevicesSection(state)
        Eyebrow("Storage")
        ImageFolderRow(state, used)
        Eyebrow("About")
        Text("Booklub for Android · ${BuildInfo.VERSION}", style = Type.body, color = ink.ink2)
        Text("Released under the ISC licence. Manrope is under the SIL Open Font License.", style = Type.caption, color = ink.ink3, modifier = Modifier.padding(top = 4.dp))
        var notices by remember { mutableStateOf<List<String>?>(null) }
        Text(
            "Third-party notices", style = Type.bodyStrong, color = ink.accentInk,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp).clickable(onClickLabel = "Show third-party notices") {
                notices = runCatching { context.assets.open("THIRD-PARTY-NOTICES.txt").bufferedReader().readLines() }.getOrElse { listOf("The notices are missing from this build.") }
            },
        )
        notices?.let { lines ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { notices = null },
                containerColor = ink.card,
                title = { Text("Third-party notices", style = Type.title2, color = ink.ink) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(lines.size) { index -> Text(lines[index], style = Type.caption, color = ink.ink2) }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { notices = null }) { Text("Close", color = ink.accentInk, style = Type.bodyStrong) } },
            )
        }
    }
}

object BuildInfo {
    const val VERSION = "1.0.0"
}

/** REQ-41, REQ-41a, REQ-34, REQ-21: the paired computer, or the way to pair one. */
@Composable
private fun DevicesSection(state: AppState) {
    val sync = state.sync
    val scope = rememberCoroutineScope()
    val computer = sync.computer
    var removing by remember { mutableStateOf(false) }
    if (computer == null) {
        Card(Modifier.clickable(onClickLabel = "Pair with Computer") { state.go(Screen.Pair) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_backup, null, ink.ink2)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Pair with Computer", style = Type.bodyStrong, color = ink.ink)
                    Text("Keep this phone in step with Booklub on your computer, across your home network.", style = Type.caption, color = ink.ink3)
                }
            }
        }
        return
    }
    // Re-reads the time each minute, so "just now" moves on.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); tick++ } }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_backup, null, ink.ink2)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(computer.name, style = Type.bodyStrong, color = ink.ink)
                val detail = remember(computer, tick) { lastSyncedText(computer.lastSession) + if (computer.pending) " \u00b7 Sync pending" else "" }
                Text(detail, style = Type.caption, color = ink.ink3)
            }
        }
        sync.problem?.let { Text(it, style = Type.caption, color = ink.dangerInk, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Sync Now") { scope.launch { sync.syncOnce() } }
            SecondaryButton("Remove") { removing = true }
        }
    }
    if (removing) {
        ConfirmDialog(
            title = "Remove ${computer.name}?",
            body = "This phone stops syncing with it. Your books stay on this phone.",
            confirm = "Remove",
            onDismiss = { removing = false },
            onConfirm = { removing = false; scope.launch { sync.forget() } },
        )
    }
}

/** REQ-42a: Booklub's own storage, or its folder on a memory card. */
@Composable
private fun ImageFolderRow(state: AppState, used: Double) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val options = remember(choosing) { storageOptions(context) }
    val current = options.firstOrNull { it.path == state.imagesDir }?.label ?: "Booklub's own storage"
    Card(Modifier.clickable(onClickLabel = "Choose the image folder") { choosing = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_category, null, ink.ink2)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Image folder", style = Type.bodyStrong, color = ink.ink)
                Text(if (moving) "Moving images\u2026" else "$current \u00b7 ${String.format(Locale.US, "%.1f", used)} MB used", style = Type.caption, color = ink.ink3)
            }
        }
    }
    if (choosing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = ink.card,
            title = { Text("Image folder", style = Type.title2, color = ink.ink) },
            text = {
                Column {
                    if (options.size == 1) {
                        Text("Book and category images are kept in Booklub's own storage on this phone. Insert a memory card to keep them there instead.", style = Type.body, color = ink.ink2)
                    }
                    for (option in options.takeIf { it.size > 1 }.orEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Keep images on ${option.label}") {
                                choosing = false
                                if (option.path != state.imagesDir) {
                                    moving = true
                                    scope.launch {
                                        if (!state.moveImages(option)) state.error = "Booklub couldn't move the images there. They are still in the old folder."
                                        moving = false
                                    }
                                }
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.label, style = Type.bodyStrong, color = ink.ink)
                                Text("${megabytes(option.freeBytes)} free", style = Type.caption, color = ink.ink3)
                            }
                            if (option.path == state.imagesDir) Glyph(R.drawable.ic_check, "Current", ink.accentInk)
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { choosing = false }) { Text(if (options.size > 1) "Cancel" else "OK", color = ink.accentInk, style = Type.bodyStrong) } },
        )
    }
}
