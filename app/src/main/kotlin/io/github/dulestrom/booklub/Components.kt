package io.github.dulestrom.booklub

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.booklub_mobile.Book
import uniffi.booklub_mobile.CategorySummary
import uniffi.booklub_mobile.completionPercent
import uniffi.booklub_mobile.initials

val ink: Ink @Composable get() = LocalInk.current

@Composable
fun Glyph(id: Int, description: String?, tint: Color = ink.ink, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = description, tint = tint, modifier = modifier.size(size))
}

/** An icon-only control, at least 48 dp square (Android SRS QA-32). */
@Composable
fun ToolButton(id: Int, description: String, tint: Color = ink.ink, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Glyph(id, description, tint) }
}

/** Decodes off the main thread, so a long grid scrolls without waiting for images. */
@Composable
fun rememberBitmap(key: String?, load: (String) -> Bitmap?): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, key) {
        value = key?.let { withContext(Dispatchers.IO) { runCatching { load(it) }.getOrNull() } }
    }
    return bitmap
}

/** The long-press actions as TalkBack custom actions (Android SRS QA-31). */
fun Modifier.tileActions(label: String, onClick: () -> Unit, onLongClick: () -> Unit, longLabel: String): Modifier =
    this
        .semantics {
            contentDescription = label
            customActions = listOf(CustomAccessibilityAction(longLabel) { onLongClick(); true })
        }
        .let { modifier ->
            @OptIn(ExperimentalFoundationApi::class)
            modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longLabel)
        }

@Composable
fun StatusChip(text: String, onImage: Boolean) {
    val background = if (onImage) ink.scrimSolid else ink.card
    val foreground = if (onImage) ink.tileInk else ink.ink2
    Text(
        text.uppercase(),
        style = Type.chip,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .then(if (onImage) Modifier else Modifier.border(1.dp, ink.line, RoundedCornerShape(6.dp)))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun ProgressLine(fraction: Float, finished: Boolean, onImage: Boolean, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).background(if (onImage) ink.scrimSolid else ink.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(if (finished) ink.success else ink.accent))
    }
}

/** A book as a 2:3 tile: background or initials, status, priority, title, author, progress. */
@Composable
fun BookTile(state: AppState, book: Book, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap = rememberBitmap(book.backgroundFile, state::bookImage)
    val hasImage = book.backgroundFile != null
    val shape = RoundedCornerShape(10.dp)
    val percent = remember(book) { completionPercent(book).toInt() }
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(if (hasImage) ink.scrimSolid else ink.raised)
            .border(1.dp, if (hasImage) Color.Transparent else ink.line, shape)
            .tileActions(
                label = "${book.title} by ${book.author.ifBlank { "unknown author" }}, ${book.status}" + (if (book.priority == "High") ", high priority" else ""),
                onClick = onClick,
                onLongClick = onLongClick,
                longLabel = "Book actions",
            ),
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (!hasImage) {
            Text(remember(book.title) { initials(book.title) }, style = Type.initials, color = ink.ink2, modifier = Modifier.align(Alignment.Center).padding(bottom = 56.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusChip(book.status, hasImage)
            if (book.priority == "High") {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(if (hasImage) ink.scrimSolid else ink.card)
                        .then(if (hasImage) Modifier else Modifier.border(1.dp, ink.line, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { Glyph(R.drawable.ic_priority_high, null, if (hasImage) ink.tileInk else ink.warningInk, 16.dp) }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().background(if (hasImage) ink.scrim else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(book.title, style = Type.tileTitle, color = if (hasImage) ink.tileInk else ink.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(book.author, style = Type.tileAuthor, color = if (hasImage) ink.tileInk else ink.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (book.totalPages != null && book.totalPages!! > 0u) {
                ProgressLine(percent / 100f, book.status == "Finished", hasImage)
            }
        }
    }
}

/** A Category as a landscape tile: artwork under a scrim, name, counts, finished share. */
@Composable
fun CategoryTile(state: AppState, summary: CategorySummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap = rememberBitmap(summary.art, state::categoryImage)
    CategoryFrame(
        name = summary.category.name,
        books = summary.books.toInt(),
        finished = summary.finished.toInt(),
        art = bitmap,
        hasArt = summary.art != null,
        modifier = Modifier.tileActions(
            "${summary.category.name}, ${booksText(summary.books.toInt())}, ${summary.finished} finished",
            onClick, onLongClick, "Category actions",
        ),
    )
}

@Composable
fun CategoryFrame(name: String, books: Int, finished: Int, art: Bitmap?, hasArt: Boolean, modifier: Modifier = Modifier, glyph: Int? = null) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().aspectRatio(216f / 122f).clip(shape)
            .background(if (hasArt) ink.scrimSolid else ink.raised)
            .border(1.dp, if (hasArt) Color.Transparent else ink.line, shape),
    ) {
        if (art != null) {
            Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.1f to Color.Transparent, 0.5f to ink.scrim, 1f to Color(0xD1120E0A))))
        }
        if (glyph != null) {
            Glyph(glyph, null, ink.ink2, 24.dp, Modifier.padding(14.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
            Text(name, style = Type.bodyStrong, color = if (hasArt) ink.tileInk else ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${booksText(books)} · $finished finished", style = Type.tileAuthor, color = if (hasArt) ink.tileInk else ink.ink3)
        }
        ProgressLine(if (books > 0) finished / books.toFloat() else 0f, true, hasArt, Modifier.align(Alignment.BottomStart))
    }
}

/** The dashed tile that creates a Category or a Sub-category. */
@Composable
fun NewTile(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val line = ink.lineStrong
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(line, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_add, null, ink.accentInk, 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.bodyStrong, color = ink.accentInk)
    }
}

/** A selectable chip. Selection adds a check glyph, so it never relies on colour alone. */
@Composable
fun Chip(text: String, count: Int?, selected: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val clickable = if (onLongClick != null) {
        Modifier.tileActions(text, onClick, onLongClick, "Sub-category actions")
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) ink.commit else Color.Transparent)
            .border(1.dp, if (selected) ink.commit else ink.line, shape)
            .then(clickable)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Glyph(R.drawable.ic_check, null, ink.onCommit, 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.caption, color = if (selected) ink.onCommit else ink.ink)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = Type.caption, color = if (selected) ink.onCommit else ink.ink3)
        }
    }
}

/** A heading with a rule beneath it (desktop REQ-08j). */
@Composable
fun SectionHeading(title: String, count: Int, major: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (major) 12.dp else 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = if (major) Type.heading else Type.caption.copy(color = ink.ink2), color = if (major) ink.ink else ink.ink2, modifier = Modifier.weight(1f))
            Text(booksText(count), style = Type.caption, color = ink.ink3)
        }
        HorizontalDivider(color = if (major) ink.line else ink.lineSubtle)
    }
}

fun booksText(count: Int) = if (count == 1) "1 book" else "$count books"

data class SheetAction(val icon: Int, val label: String, val destructive: Boolean = false, val run: () -> Unit)

/** The long-press menu, as a bottom sheet (screens B2, C1, C6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, subtitle: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = ink.card) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(title, style = Type.heading, color = ink.ink, modifier = Modifier.padding(horizontal = 24.dp))
            if (subtitle != null) Text(subtitle, style = Type.caption, color = ink.ink3, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            for (action in actions) {
                Row(
                    // The action decides what comes next: closing here as well would remove a dialog
                    // or sheet that the action has just asked for.
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { action.run() }.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val color = if (action.destructive) ink.dangerInk else ink.ink
                    Glyph(action.icon, null, color)
                    Spacer(Modifier.width(18.dp))
                    Text(action.label, style = Type.body, color = color)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ink.card,
        title = { Text(title, style = Type.title2, color = ink.ink) },
        text = { Text(body, style = Type.body, color = ink.ink2) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ink.accentInk, style = Type.bodyStrong) } },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = ButtonDefaults.buttonColors(containerColor = if (destructive) ink.danger else ink.commit, contentColor = if (destructive) ink.onDanger else ink.onCommit),
            ) { Text(confirm, style = Type.bodyStrong) }
        },
    )
}

/** Asks for a Category or Sub-category name (screens C3, C5). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ink.card,
        title = { Text(title, style = Type.title2, color = ink.ink) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = ink.accentInk, style = Type.bodyStrong) } },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onDone(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ink.commit, contentColor = ink.onCommit),
            ) { Text(confirm, style = Type.bodyStrong) }
        },
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ink.accent,
    unfocusedBorderColor = ink.line,
    errorBorderColor = ink.dangerInk,
    focusedLabelColor = ink.accentInk,
    unfocusedLabelColor = ink.ink3,
    focusedTextColor = ink.ink,
    unfocusedTextColor = ink.ink,
    cursorColor = ink.accent,
    focusedContainerColor = ink.card,
    unfocusedContainerColor = ink.card,
    errorContainerColor = ink.card,
)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.eyebrow, color = ink.ink3, modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ink.card).border(1.dp, ink.line, RoundedCornerShape(14.dp)).padding(16.dp),
        content = content,
    )
}

/** The commit button: dark ink on paper, the desktop's Save. */
@Composable
fun CommitButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).widthIn(min = 64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ink.commit, contentColor = ink.onCommit),
    ) { Text(text, style = Type.bodyStrong) }
}

@Composable
fun SecondaryButton(text: String, icon: Int? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(ink.raised).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, null, ink.ink, 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.bodyStrong, color = ink.ink)
    }
}

/** The Feedback component: a glyph and a label, so kinds differ by more than colour. */
@Composable
fun Feedback(kind: String, text: String) {
    val (color, icon) = when (kind) {
        "ERROR" -> ink.dangerInk to R.drawable.ic_error
        "WARNING" -> ink.warningInk to R.drawable.ic_warning
        "SUCCESS" -> ink.successInk to R.drawable.ic_success
        else -> ink.accentInk to R.drawable.ic_success
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.card).border(1.dp, color, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Glyph(icon, null, color, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(kind, style = Type.chip, color = color)
            Text(text, style = Type.caption, color = ink.ink)
        }
    }
}
