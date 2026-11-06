package io.github.dulestrom.booklub

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.booklub_mobile.Book
import uniffi.booklub_mobile.BooklubException
import uniffi.booklub_mobile.FieldErrors
import uniffi.booklub_mobile.applyCategory
import uniffi.booklub_mobile.applyCurrentPage
import uniffi.booklub_mobile.applyStatus
import uniffi.booklub_mobile.applyTotalPages
import uniffi.booklub_mobile.checkImage
import uniffi.booklub_mobile.completionPercent
import uniffi.booklub_mobile.initials
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

const val NO_CLIPBOARD_IMAGE = "The clipboard has no image. In your browser, long-press the picture and choose Copy Image."

fun BooklubException.text(): String = when (this) {
    is BooklubException.Store -> reason
    is BooklubException.Image -> reason
    is BooklubException.NotFound -> "That item no longer exists."
}

/** Reads a picked image, refusing anything over the desktop's 20 MB limit before reading it all. */
fun readImage(context: Context, uri: Uri): ByteArray? = context.contentResolver.openInputStream(uri)?.use { input ->
    val bytes = input.readNBytes(20 * 1024 * 1024 + 1)
    bytes
}

/** The first image on the clipboard, or null when it holds none (REQ-15a). */
fun clipboardImage(context: Context): ByteArray? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    val description = clip.description
    for (index in 0 until clip.itemCount) {
        val uri = clip.getItemAt(index).uri ?: continue
        val type = context.contentResolver.getType(uri) ?: (0 until description.mimeTypeCount).map(description::getMimeType).firstOrNull()
        if (type?.startsWith("image/") == true) return runCatching { readImage(context, uri) }.getOrNull()
    }
    return null
}

// ---------- Preview ----------

/** Screen B1, Android SRS REQ-10: everything read-only. Editing is the long-press menu's job. */
@Composable
fun PreviewScreen(state: AppState, id: String) {
    val book = remember(state.revision, id) { state.book(id) }
    if (book == null) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    val bitmap = rememberBitmap(book.backgroundFile, state::bookImage)
    val category = remember(state.revision, book.categoryId) { state.library.categoryName(book.categoryId) }
    val sub = remember(state.revision, book.subCategoryId) { state.library.subCategoryName(book.subCategoryId) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 4.dp)) { ToolButton(R.drawable.ic_back, "Back") { state.back() } }
        Box(
            Modifier.align(Alignment.CenterHorizontally).width(176.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(ink.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(initials(book.title), style = Type.initials, color = ink.ink2)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(book.title, style = Type.title1, color = ink.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (book.author.isNotBlank()) {
            Text(book.author, style = Type.body, color = ink.accentInk, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
            InfoChip(statusIcon(book.status), book.status)
            Spacer(Modifier.width(8.dp))
            InfoChip(if (book.priority == "High") R.drawable.ic_priority_high else null, "${book.priority} priority")
        }
        Text(
            when {
                category.isEmpty() -> "Uncategorized"
                sub.isEmpty() -> category
                else -> "$category › $sub"
            },
            style = Type.caption, color = ink.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        val total = book.totalPages
        if (total != null && total > 0u) {
            Spacer(Modifier.height(18.dp))
            Card {
                val percent = completionPercent(book).toInt()
                Row {
                    Text("Page ${book.currentPage} of $total", style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
                    Text("$percent%", style = Type.bodyStrong, color = ink.ink)
                }
                Spacer(Modifier.height(10.dp))
                ProgressLine(percent / 100f, book.status == "Finished", false, Modifier.clip(RoundedCornerShape(3.dp)), 6.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            val rows = buildList {
                book.startDate?.let { add("Started" to it) }
                book.finishDate?.let { add("Finished" to it) }
                book.rating?.let { add("Rating" to "${"★".repeat(it.toInt())}${"☆".repeat(5 - it.toInt())}  ${it} of 5") }
                book.isbn?.let { add("ISBN" to it) }
                val published = listOfNotNull(book.publisher.ifBlank { null }, book.publishedYear?.toString()).joinToString(", ")
                if (published.isNotEmpty()) add("Publisher" to published)
                if (book.language.isNotBlank()) add("Language" to book.language)
            }
            if (rows.isEmpty()) Text("No other details yet.", style = Type.caption, color = ink.ink3)
            rows.forEachIndexed { index, (key, value) ->
                if (index > 0) HorizontalDivider(color = ink.lineSubtle, modifier = Modifier.padding(vertical = 10.dp))
                Row {
                    Text(key, style = Type.body, color = ink.ink2, modifier = Modifier.weight(1f))
                    Text(value, style = Type.body, color = ink.ink, textAlign = TextAlign.End)
                }
            }
        }
        if (book.notes.isNotBlank()) {
            Eyebrow("Notes")
            Text(book.notes, style = Type.body, color = ink.ink)
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun statusIcon(status: String) = when (status) {
    "Reading" -> R.drawable.ic_reading
    "Finished" -> R.drawable.ic_finished
    else -> R.drawable.ic_to_read
}

@Composable
fun InfoChip(icon: Int?, text: String) {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(ink.raised).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Glyph(icon, null, ink.ink2, 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.caption, color = ink.ink2)
    }
}

// ---------- Editor ----------

private sealed interface Pending {
    data object Unchanged : Pending
    data class Picked(val file: String, val small: Boolean) : Pending
    data object Removed : Pending
}

/** Screen B4 to B9. Nothing is stored until Save (REQ-11d, REQ-15e). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(state: AppState, id: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(id) { id?.let { state.book(it) } ?: state.library.newBook() }
    val isNew = id == null
    var draft by remember(id) { mutableStateOf(original) }
    var errors by remember { mutableStateOf<FieldErrors?>(null) }
    var pending by remember { mutableStateOf<Pending>(Pending.Unchanged) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Book?>(null) }
    val categories = remember(state.revision) { state.library.categories() }
    // Typed as text, so an unfinished number can be shown with its error rather than lost.
    var currentText by remember(id) { mutableStateOf(original.currentPage.toString()) }
    var totalText by remember(id) { mutableStateOf(original.totalPages?.toString() ?: "") }
    var yearText by remember(id) { mutableStateOf(original.publishedYear?.toString() ?: "") }

    val shown = when (val p = pending) {
        is Pending.Picked -> p.file
        Pending.Removed -> null
        Pending.Unchanged -> draft.backgroundFile
    }
    val dirty = draft != original || pending != Pending.Unchanged
    val bitmap = rememberBitmap(shown, state::bookImage)

    fun discardPicked() {
        (pending as? Pending.Picked)?.let { state.library.discardImage(it.file) }
    }

    fun close() {
        discardPicked()
        state.back()
    }

    fun accept(bytes: ByteArray?) {
        imageError = null
        if (bytes == null) {
            imageError = NO_CLIPBOARD_IMAGE
            return
        }
        scope.launch {
            try {
                val checked = withContext(Dispatchers.Default) { checkImage(bytes) }
                val file = withContext(Dispatchers.IO) { state.library.storeBookImage(bytes) }
                discardPicked()
                pending = Pending.Picked(file, checked.small)
            } catch (e: BooklubException) {
                imageError = e.text()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) accept(runCatching { readImage(context, uri) }.getOrNull() ?: ByteArray(0))
    }

    fun save(force: Boolean = false) {
        val book = draft.copy(backgroundFile = shown)
        if (isNew && !force) {
            state.library.duplicateOf(book)?.let { duplicate = it; return }
        }
        try {
            // A sync while the editor was open keeps its changes to the fields not edited here (REQ-32b).
            val result = if (id == null) state.library.saveBook(book) else state.library.saveEditedBook(original, book)
            if (result.saved) {
                state.changed()
                state.back()
            } else {
                errors = result.errors
            }
        } catch (e: BooklubException) {
            state.error = e.text()
        }
    }

    BackHandler { if (dirty) leaving = true else close() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(R.drawable.ic_close, "Close") { if (dirty) leaving = true else close() }
            Text(if (isNew) "Add Book" else "Edit Book", style = Type.title2, color = ink.ink, modifier = Modifier.weight(1f))
            CommitButton("Save", Modifier.padding(end = 12.dp)) { save() }
        }
        HorizontalDivider(color = ink.line)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            // ---------- Background ----------
            Row {
                BackgroundPreview(bitmap, shown != null)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background", style = Type.bodyStrong, color = ink.ink)
                    if (pending is Pending.Picked) Text("NOT SAVED YET", style = Type.chip, color = ink.accentInk, modifier = Modifier.background(ink.raised).padding(horizontal = 6.dp, vertical = 2.dp))
                    SecondaryButton("Choose File", R.drawable.ic_background_add) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    SecondaryButton("Paste Image") { accept(clipboardImage(context)) }
                    if (shown != null) SecondaryButton("Remove", R.drawable.ic_background_remove) { discardPicked(); pending = Pending.Removed }
                }
            }
            imageError?.let { Spacer(Modifier.height(12.dp)); Feedback("ERROR", it) }
            (pending as? Pending.Picked)?.let { picked ->
                Spacer(Modifier.height(12.dp))
                if (picked.small) Feedback("WARNING", "This image is small and may look blurry on the tile.")
                Spacer(Modifier.height(8.dp))
                Feedback("PREVIEW", "Choose Save to keep this background, or close to restore the previous one.")
            }

            // ---------- Details ----------
            Eyebrow("Details")
            Field("Title", draft.title, errors?.title) { draft = draft.copy(title = it) }
            Field("Author", draft.author) { draft = draft.copy(author = it) }
            Choice("Category", state.library.categoryName(draft.categoryId).ifEmpty { "None" }, listOf("None") + categories.map { it.name }) { index ->
                draft = applyCategory(draft, if (index == 0) null else categories[index - 1].id)
            }
            val subs = categories.firstOrNull { it.id == draft.categoryId }?.subCategories.orEmpty()
            Choice("Sub-category", state.library.subCategoryName(draft.subCategoryId).ifEmpty { "None" }, listOf("None") + subs.map { it.name }, enabled = subs.isNotEmpty()) { index ->
                draft = draft.copy(subCategoryId = if (index == 0) null else subs[index - 1].id)
            }

            // ---------- Reading ----------
            Eyebrow("Reading")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice("Status", draft.status, listOf("To Read", "Reading", "Finished"), Modifier.weight(1f)) { index ->
                    draft = applyStatus(draft, listOf("To Read", "Reading", "Finished")[index])
                    currentText = draft.currentPage.toString()
                }
                Choice("Priority", draft.priority, listOf("High", "Medium", "Low"), Modifier.weight(1f)) { index ->
                    draft = draft.copy(priority = listOf("High", "Medium", "Low")[index])
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Current page", currentText, errors?.currentPage, KeyboardType.Number, Modifier.weight(1f)) { text ->
                    currentText = text.filter(Char::isDigit)
                    currentText.toUIntOrNull()?.let { draft = applyCurrentPage(draft, it) }
                }
                Field("Total pages", totalText, errors?.totalPages, KeyboardType.Number, Modifier.weight(1f)) { text ->
                    totalText = text.filter(Char::isDigit)
                    draft = applyTotalPages(draft, totalText.toUIntOrNull())
                    currentText = draft.currentPage.toString()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Started", draft.startDate, errors?.startDate, Modifier.weight(1f)) { draft = draft.copy(startDate = it) }
                DateField("Finished", draft.finishDate, errors?.finishDate, Modifier.weight(1f)) { draft = draft.copy(finishDate = it) }
            }

            // ---------- Your take ----------
            Eyebrow("Your take")
            Text("Rating", style = Type.caption, color = ink.ink2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val rating = draft.rating?.toInt() ?: 0
                for (value in 1..5) {
                    ToolButton(if (value <= rating) R.drawable.ic_star_filled else R.drawable.ic_star, "Rate $value of 5", ink.accent) {
                        draft = draft.copy(rating = if (rating == value) null else value.toUByte())
                    }
                }
                Text(if (rating == 0) "No rating" else "$rating of 5", style = Type.caption, color = ink.ink3)
            }
            // Required by REQ-11b (D-25).
            Field("Notes", draft.notes, singleLine = false) { draft = draft.copy(notes = it) }

            // ---------- Catalogue ----------
            Eyebrow("Catalogue")
            Field("ISBN", draft.isbn ?: "", errors?.isbn, KeyboardType.Number) { draft = draft.copy(isbn = it.ifBlank { null }) }
            Field("Publisher", draft.publisher) { draft = draft.copy(publisher = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Year", yearText, errors?.publishedYear, KeyboardType.Number, Modifier.weight(1f)) { text ->
                    yearText = text.filter(Char::isDigit).take(4)
                    draft = draft.copy(publishedYear = yearText.toIntOrNull())
                }
                Field("Language", draft.language, modifier = Modifier.weight(1f)) { draft = draft.copy(language = it) }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    // Screen B8, REQ-11e
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            containerColor = ink.card,
            title = { Text("Save your changes?", style = Type.title2, color = ink.ink) },
            text = { Text("You changed this book. Leaving now loses those changes.", style = Type.body, color = ink.ink2) },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false }) { Text("Cancel", color = ink.accentInk, style = Type.bodyStrong) }
                    TextButton(onClick = { leaving = false; close() }) { Text("Discard", color = ink.dangerInk, style = Type.bodyStrong) }
                }
            },
            confirmButton = { CommitButton("Save") { leaving = false; save() } },
        )
    }
    // Screen B9, REQ-13b
    duplicate?.let { match ->
        ConfirmDialog(
            "This book may already be in your library",
            "“${match.title}” by ${match.author.ifBlank { "an unknown author" }} is already in your library. Add it again anyway?",
            "Add Anyway",
            destructive = false,
            onDismiss = { duplicate = null },
        ) { save(force = true) }
    }
}

@Composable
private fun BackgroundPreview(bitmap: android.graphics.Bitmap?, has: Boolean) {
    val line = ink.lineStrong
    Box(
        Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(ink.raised)
            .then(if (has) Modifier else Modifier.drawBehind {
                drawRoundRect(line, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "Book background", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Glyph(R.drawable.ic_background_add, "No background yet", ink.ink3, 32.dp)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    Column(modifier.padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            colors = fieldColors(),
            textStyle = Type.body,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_error, null, ink.dangerInk, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(error, style = Type.caption, color = ink.dangerInk)
            }
        }
    }
}

/** A read-only field that opens a menu of choices. */
@Composable
private fun Choice(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, enabled: Boolean = true, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Glyph(R.drawable.ic_sort, null, ink.ink2, 18.dp) },
            colors = fieldColors(),
            textStyle = Type.body,
            modifier = Modifier.fillMaxWidth(),
        )
        // The field itself is read-only, so a transparent layer takes the tap.
        Box(Modifier.matchParentSize().clickable(enabled = enabled, onClickLabel = "Choose $label") { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = ink.card) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option, style = Type.body, color = ink.ink) }, onClick = { open = false; onChoose(index) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String?, error: String?, modifier: Modifier = Modifier, onChange: (String?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier.padding(bottom = 10.dp)) {
        Box {
            OutlinedTextField(
                value = value ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                placeholder = { Text("yyyy-mm-dd") },
                isError = error != null,
                trailingIcon = { Glyph(R.drawable.ic_calendar, null, ink.ink2, 18.dp) },
                colors = fieldColors(),
                textStyle = Type.body,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable(onClickLabel = "Choose the ${label.lowercase()} date") { picking = true })
        }
        if (error != null) Text(error, style = Type.caption, color = ink.dangerInk, modifier = Modifier.padding(top = 4.dp))
    }
    if (picking) {
        val initial = value?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                }) { Text("OK", color = ink.accentInk) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { picking = false; onChange(null) }) { Text("Clear", color = ink.dangerInk) }
                    TextButton(onClick = { picking = false }) { Text("Cancel", color = ink.accentInk) }
                }
            },
        ) { DatePicker(state = picker) }
    }
}

// ---------- Category background ----------

/** Screen C2, Android SRS REQ-12a: built-in artwork, a file, or a pasted image, kept until Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryBackgroundSheet(state: AppState, category: uniffi.booklub_mobile.Category, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(category.background?.takeIf { it.startsWith("preset:") }?.removePrefix("preset:")) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var removed by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun accept(data: ByteArray?) {
        problem = null
        if (data == null) { problem = NO_CLIPBOARD_IMAGE; return }
        try {
            checkImage(data)
            bytes = data
            preset = null
            removed = false
        } catch (e: BooklubException) {
            problem = e.text()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) accept(runCatching { readImage(context, uri) }.getOrNull() ?: ByteArray(0))
    }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDone, sheetState = sheet, containerColor = ink.card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Background for ${category.name}", style = Type.title2, color = ink.ink, modifier = Modifier.weight(1f))
                ToolButton(R.drawable.ic_close, "Close", onClick = onDone)
            }
            Eyebrow("Built-in artwork")
            val presets = remember { uniffi.booklub_mobile.presets() }
            presets.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    row.forEach { option ->
                        val art = rememberBitmap("preset:${option.id}", state::categoryImage)
                        val selected = preset == option.id && bytes == null && !removed
                        Box(
                            Modifier.weight(1f).aspectRatio(216f / 122f).clip(RoundedCornerShape(8.dp))
                                .border(if (selected) 3.dp else 0.dp, ink.accent, RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = option.title) { preset = option.id; bytes = null; removed = false },
                        ) {
                            if (art != null) Image(art.asImageBitmap(), option.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(ink.accent).padding(2.dp)) { Glyph(R.drawable.ic_check, null, ink.onAccent, 14.dp) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            SheetRow(R.drawable.ic_background_add, "Choose File") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            SheetRow(R.drawable.ic_background_replace, "Paste Image") { accept(clipboardImage(context)) }
            SheetRow(R.drawable.ic_background_remove, "Remove Background") { removed = true; preset = null; bytes = null }
            if (bytes != null) Feedback("PREVIEW", "Choose Save to use the image you picked.")
            problem?.let { Feedback("ERROR", it) }
            Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel", style = Type.bodyStrong, color = ink.ink) }
                CommitButton("Save", Modifier.weight(1f)) {
                    scope.launch {
                        // Decoding a large image takes a moment, so it happens off the main thread,
                        // and only the state change comes back to it.
                        val picked = bytes
                        val failure = withContext(Dispatchers.IO) {
                            try {
                                when {
                                    picked != null -> state.library.setCategoryImage(category.id, picked)
                                    removed -> state.library.setCategoryPreset(category.id, null)
                                    else -> state.library.setCategoryPreset(category.id, preset)
                                }
                                null
                            } catch (e: BooklubException) {
                                e.text()
                            }
                        }
                        if (failure == null) {
                            state.forgetImages()
                            state.changed()
                            onDone()
                        } else {
                            problem = failure
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = Type.body, color = ink.ink)
    }
}
