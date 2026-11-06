package io.github.dulestrom.booklub

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uniffi.booklub_mobile.Book
import uniffi.booklub_mobile.BookList
import uniffi.booklub_mobile.Filters
import uniffi.booklub_mobile.Scope
import uniffi.booklub_mobile.SubScope
import uniffi.booklub_mobile.sortKeys

private val STATUSES = listOf("Reading", "To Read", "Finished")
private val PRIORITIES = listOf("High", "Medium", "Low")

/** The large title row at the top of each tab, with its tools on the right. */
@Composable
fun TitleBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, tools: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) ToolButton(R.drawable.ic_back, "Back", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = if (onBack == null) Type.largeTitle else Type.title2, color = ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Type.caption, color = ink.ink3)
        }
        tools()
    }
}

// ---------- Home ----------

@Composable
fun HomeScreen(state: AppState) {
    val home = remember(state.revision) { state.library.home() }
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    var categoryFor by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            TitleBar("Home") {
                ToolButton(R.drawable.ic_search, "Search") { state.go(Screen.Search) }
                ToolButton(R.drawable.ic_add, "Add book") { state.go(Screen.Editor(null)) }
            }
        }
        // REQ-01e
        if (home.reading.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Continue Reading", style = Type.heading, color = ink.ink, modifier = Modifier.weight(1f))
                        Text(booksText(home.reading.size), style = Type.caption, color = ink.ink3)
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(home.reading, key = { it.id }) { book ->
                            BookTile(state, book, Modifier.width(150.dp), onClick = { state.go(Screen.Preview(book.id)) }, onLongClick = { actionsFor = book })
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Browse Categories", style = Type.heading, color = ink.ink, modifier = Modifier.padding(top = 8.dp))
        }
        items(home.categories, key = { it.category.id }) { summary ->
            CategoryTile(state, summary, onClick = { state.go(Screen.Category(summary.category.id)) }, onLongClick = { categoryFor = summary.category.id })
        }
        if (home.uncategorizedBooks > 0u) {
            item {
                CategoryFrame(
                    "Uncategorized", home.uncategorizedBooks.toInt(), home.uncategorizedFinished.toInt(), null, false,
                    Modifier.clickable { state.go(Screen.Category(null)) }, glyph = R.drawable.ic_uncategorized,
                )
            }
        }
        // REQ-12h
        item { NewTile("New Category", Modifier.fillMaxWidth().aspectRatio(216f / 122f)) { creating = true } }
    }
    actionsFor?.let { book -> BookActions(state, book) { actionsFor = null } }
    categoryFor?.let { id -> CategoryActions(state, id) { categoryFor = null } }
    if (creating) {
        NameDialog("New category", "", "Create", onDismiss = { creating = false }) { name ->
            state.write { state.library.addCategory(name) }
        }
    }
}

// ---------- Book grid with headings ----------

/**
 * Books in two or more columns, with a full-width heading above each group. A full-width item
 * always starts a new line, so each group's books are the only ones beneath its heading (REQ-08l).
 */
fun LazyGridScope.bookGrid(state: AppState, list: BookList, onLongClick: (Book) -> Unit) {
    var heading = 0
    list.books.forEachIndexed { position, book ->
        while (heading < list.headings.size && list.headings[heading].firstBook.toInt() == position) {
            val shown = list.headings[heading]
            item(key = "h:$heading:${shown.title}", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading(shown.title, shown.count.toInt(), shown.major)
            }
            heading++
        }
        item(key = book.id) {
            BookTile(state, book, onClick = { state.go(Screen.Preview(book.id)) }, onLongClick = { onLongClick(book) })
        }
    }
}

@Composable
fun BookGridScreen(state: AppState, list: BookList, header: LazyGridScope.() -> Unit, empty: @Composable () -> Unit) {
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        header()
        if (list.books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { empty() }
        } else {
            bookGrid(state, list) { actionsFor = it }
        }
    }
    actionsFor?.let { book -> BookActions(state, book) { actionsFor = null } }
}

// ---------- Library ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(state: AppState) {
    val facets = remember(state.revision) { state.library.facets() }
    val list = remember(state.revision, state.statusFilter, state.sort, state.filters) { state.library.view(state.libraryScope(), state.filters, state.sort) }
    var sorting by remember { mutableStateOf(false) }
    val active = state.filters.categoryIds.size + state.filters.subCategoryIds.size + state.filters.statuses.size + state.filters.priorities.size
    BookGridScreen(state, list, header = {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                TitleBar("Library") {
                    ToolButton(R.drawable.ic_search, "Search") { state.go(Screen.Search) }
                    ToolButton(R.drawable.ic_sort, "Sort and filter") { sorting = true }
                    Box {
                        ToolButton(R.drawable.ic_filter, if (active > 0) "Filters, $active active" else "Filters") { sorting = true }
                        if (active > 0) {
                            Text(active.toString(), style = Type.chip, color = ink.onAccent, modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).clip(CircleShape).background(ink.accent).padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                    ToolButton(R.drawable.ic_add, "Add book") { state.go(Screen.Editor(null)) }
                }
                // REQ-01d
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All", facets.total.toInt(), state.statusFilter == null) { state.statusFilter = null }
                    for (status in STATUSES) {
                        val count = facets.statuses.firstOrNull { it.key == status }?.count?.toInt() ?: 0
                        Chip(status, count, state.statusFilter == status) { state.statusFilter = status }
                    }
                }
                if (state.sort != "Manual order" || active > 0) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(R.drawable.ic_sort, null, ink.ink3, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(summaryText(state, list), style = Type.caption, color = ink.ink3)
                    }
                }
            }
        }
    }, empty = { NoMatches(state, active > 0) })
    if (sorting) SortFilterSheet(state) { sorting = false }
}

private fun summaryText(state: AppState, list: BookList): String {
    val parts = mutableListOf<String>()
    if (state.sort != "Manual order") parts += "Sorted by ${state.sort}"
    val names = state.filters.categoryIds.map { state.library.categoryName(it) } +
        state.filters.subCategoryIds.map { state.library.subCategoryName(it) } + state.filters.statuses + state.filters.priorities.map { "$it priority" }
    if (names.isNotEmpty()) parts += "Filters: ${names.joinToString(", ")}"
    if (names.isNotEmpty()) parts += "Showing ${list.books.size} of ${list.scopeTotal}"
    return parts.joinToString(" · ")
}

@Composable
fun NoMatches(state: AppState, filtering: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(R.drawable.ic_search, null, ink.ink3, 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(if (filtering || state.search.isNotBlank()) "No books match" else "No books here yet", style = Type.title2, color = ink.ink)
        Spacer(Modifier.height(6.dp))
        Text(if (filtering || state.search.isNotBlank()) "No books match the active criteria." else "Add a book with the + button.", style = Type.body, color = ink.ink2)
        if (filtering || state.search.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (filtering) SecondaryButton("Clear Filters") { state.filters = Filters("", emptyList(), emptyList(), emptyList(), emptyList()) }
                if (state.search.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text("Clear Search", style = Type.bodyStrong, color = ink.accentInk, modifier = Modifier.clickable { state.search = "" }.padding(12.dp))
                }
            }
        }
    }
}

/** Screen A4: sort keys, then each filter facet, with Clear All and Show N Books. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SortFilterSheet(state: AppState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val facets = remember(state.revision) { state.library.facets() }
    val categories = remember(state.revision) { state.library.categories() }
    var sort by remember { mutableStateOf(state.sort) }
    var filters by remember { mutableStateOf(state.filters) }
    val shown = remember(sort, filters, state.statusFilter, state.revision) { state.library.view(state.libraryScope(), filters, sort).books.size }
    fun count(list: List<uniffi.booklub_mobile.Count>, key: String) = list.firstOrNull { it.key == key }?.count?.toInt() ?: 0
    fun toggle(list: List<String>, value: String) = if (value in list) list - value else list + value
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = ink.card) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort and filter", style = Type.title2, color = ink.ink, modifier = Modifier.weight(1f))
                ToolButton(R.drawable.ic_close, "Close", onClick = onDismiss)
            }
            Eyebrow("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in sortKeys()) Chip(key, null, sort == key) { sort = key }
            }
            Eyebrow("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (category in categories) {
                    Chip(category.name, count(facets.categories, category.id), category.id in filters.categoryIds) { filters = filters.copy(categoryIds = toggle(filters.categoryIds, category.id)) }
                }
            }
            // REQ-03c
            val subs = categories.flatMap { it.subCategories }
            if (subs.isNotEmpty()) {
                Eyebrow("Sub-category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sub in subs) {
                        Chip(sub.name, count(facets.subCategories, sub.id), sub.id in filters.subCategoryIds) { filters = filters.copy(subCategoryIds = toggle(filters.subCategoryIds, sub.id)) }
                    }
                }
            }
            Eyebrow("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (status in STATUSES) Chip(status, count(facets.statuses, status), status in filters.statuses) { filters = filters.copy(statuses = toggle(filters.statuses, status)) }
            }
            Eyebrow("Priority")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (priority in PRIORITIES) Chip(priority, count(facets.priorities, priority), priority in filters.priorities) { filters = filters.copy(priorities = toggle(filters.priorities, priority)) }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { filters = Filters("", emptyList(), emptyList(), emptyList(), emptyList()); sort = "Manual order" }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Clear All", style = Type.bodyStrong, color = ink.ink)
                }
                CommitButton("Show ${booksText(shown).replaceFirstChar { it.uppercase() }}", Modifier.weight(1f)) {
                    state.sort = sort
                    state.filters = filters
                    onDismiss()
                }
            }
        }
    }
}

// ---------- Category ----------

@Composable
fun CategoryScreen(state: AppState, id: String?) {
    val category = remember(state.revision, id) { state.library.categories().firstOrNull { it.id == id } }
    var sub by remember(id) { mutableStateOf<SubScope>(SubScope.All) }
    val all = remember(state.revision, id) { state.library.view(Scope.Category(id, SubScope.All), state.filters.copy(search = ""), "Manual order") }
    val list = remember(state.revision, id, sub, state.sort) { state.library.view(Scope.Category(id, sub), Filters("", emptyList(), emptyList(), emptyList(), emptyList()), state.sort) }
    var subActions by remember { mutableStateOf<uniffi.booklub_mobile.SubCategory?>(null) }
    var creating by remember { mutableStateOf(false) }
    if (id != null && category == null) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    val finished = all.books.count { it.status == "Finished" }
    BookGridScreen(state, list, header = {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                TitleBar(category?.name ?: "Uncategorized", "${booksText(all.books.size)} · $finished finished", onBack = { state.back() }) {
                    ToolButton(R.drawable.ic_search, "Search") { state.go(Screen.Search) }
                    ToolButton(R.drawable.ic_add, "Add book") { state.go(Screen.Editor(null)) }
                }
                if (category != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("All", all.books.size, sub == SubScope.All) { sub = SubScope.All }
                        for (entry in category.subCategories) {
                            val count = all.books.count { it.subCategoryId == entry.id }
                            Chip(entry.name, count, sub == SubScope.Sub(entry.id), onLongClick = { subActions = entry }) { sub = SubScope.Sub(entry.id) }
                        }
                        val without = all.books.count { it.subCategoryId == null }
                        if (category.subCategories.isNotEmpty() && without > 0) {
                            Chip("No Sub-category", without, sub == SubScope.NoSub) { sub = SubScope.NoSub }
                        }
                        // REQ-12i
                        NewTile("New Sub-category", Modifier.height(36.dp)) { creating = true }
                    }
                }
            }
        }
    }, empty = { NoMatches(state, false) })
    subActions?.let { entry ->
        SubCategoryActions(state, category!!.id, entry) { subActions = null; if (sub == SubScope.Sub(entry.id)) sub = SubScope.All }
    }
    if (creating && category != null) {
        NameDialog("New sub-category", "", "Create", onDismiss = { creating = false }) { name ->
            state.write { state.library.addSubCategory(category.id, name) }
        }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(state: AppState) {
    val list = remember(state.revision, state.search, state.filters, state.sort) { state.library.view(Scope.All, state.filters.copy(search = state.search), state.sort) }
    val focus = remember { FocusRequester() }
    BookGridScreen(state, list, header = {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(R.drawable.ic_back, "Back") { state.back() }
                    TextField(
                        value = state.search,
                        onValueChange = { state.search = it },
                        placeholder = { Text("Search title, author, ISBN", color = ink.ink3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = { if (state.search.isNotEmpty()) ToolButton(R.drawable.ic_close, "Clear search") { state.search = "" } },
                        colors = TextFieldDefaults.colors(focusedContainerColor = ink.raised, unfocusedContainerColor = ink.raised, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = ink.ink, unfocusedTextColor = ink.ink, cursorColor = ink.accent),
                        shape = CircleShape,
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                if (state.search.isNotBlank() && list.books.isNotEmpty()) {
                    Text("${booksText(list.books.size)} · matches title, author or ISBN, ignoring case and accents", style = Type.caption, color = ink.ink3, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }, empty = { if (state.search.isNotBlank()) NoMatches(state, false) })
}

// ---------- Actions ----------

/** Screen B2 and B3: long-press a book for Edit or Delete. */
@Composable
fun BookActions(state: AppState, book: Book, onDone: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        ActionSheet(book.title, book.author.ifBlank { null }, listOf(
            SheetAction(R.drawable.ic_edit, "Edit") { onDone(); state.go(Screen.Editor(book.id)) },
            SheetAction(R.drawable.ic_delete, "Delete", destructive = true) { confirming = true },
        )) { if (!confirming) onDone() }
    }
    if (confirming) {
        ConfirmDialog(
            "Delete “${book.title}”?",
            "The book and its background are removed from this phone.",
            "Delete",
            onDismiss = { confirming = false; onDone() },
        ) {
            if (state.write { state.library.deleteBook(book.id) } && state.screen == Screen.Preview(book.id)) state.back()
        }
    }
}

/** Screen C1 to C4: long-press a Category tile. */
@Composable
fun CategoryActions(state: AppState, id: String, onDone: () -> Unit) {
    val category = remember(state.revision, id) { state.library.categories().firstOrNull { it.id == id } } ?: run { onDone(); return }
    var step by remember { mutableStateOf("menu") }
    val books = remember(state.revision, id) { state.library.booksInCategory(id).toInt() }
    when (step) {
        "menu" -> ActionSheet(category.name, "${booksText(books)} · ${category.subCategories.size} sub-categories", listOf(
            SheetAction(R.drawable.ic_background_replace, "Choose Background") { step = "background" },
            SheetAction(R.drawable.ic_edit, "Rename") { step = "rename" },
            SheetAction(R.drawable.ic_delete, "Delete", destructive = true) { step = "delete" },
        )) { if (step == "menu") onDone() }
        "rename" -> NameDialog("Rename category", category.name, "Rename", onDismiss = onDone) { name -> state.write { state.library.renameCategory(id, name) } }
        "delete" -> ConfirmDialog(
            "Delete “${category.name}”?",
            if (books == 0) "No books carry this category." else "${booksText(books).replaceFirstChar { it.uppercase() }} carry this category. They stay in your library, without a category.",
            "Delete",
            onDismiss = onDone,
        ) { state.write { state.library.deleteCategory(id) } }
        "background" -> CategoryBackgroundSheet(state, category, onDone)
    }
}

/** Screen C6 and C7: long-press a Sub-category chip. */
@Composable
fun SubCategoryActions(state: AppState, categoryId: String, sub: uniffi.booklub_mobile.SubCategory, onDone: () -> Unit) {
    var step by remember { mutableStateOf("menu") }
    val books = remember(state.revision, sub.id) { state.library.booksInSubCategory(sub.id).toInt() }
    val categoryName = remember(state.revision) { state.library.categoryName(categoryId) }
    when (step) {
        "menu" -> ActionSheet(sub.name, "Sub-category of $categoryName · ${booksText(books)}", listOf(
            SheetAction(R.drawable.ic_edit, "Rename") { step = "rename" },
            SheetAction(R.drawable.ic_delete, "Delete", destructive = true) { step = "delete" },
        )) { if (step == "menu") onDone() }
        "rename" -> NameDialog("Rename sub-category", sub.name, "Rename", onDismiss = onDone) { name -> state.write { state.library.renameSubCategory(categoryId, sub.id, name) } }
        "delete" -> ConfirmDialog(
            "Delete “${sub.name}”?",
            if (books == 0) "No books carry this sub-category." else "${booksText(books).replaceFirstChar { it.uppercase() }} carry this sub-category. They keep the category $categoryName.",
            "Delete",
            onDismiss = onDone,
        ) { state.write { state.library.deleteSubCategory(categoryId, sub.id) } }
    }
}
