package io.github.dulestrom.booklub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.booklub_mobile.Book
import uniffi.booklub_mobile.Booklub
import uniffi.booklub_mobile.BooklubException
import uniffi.booklub_mobile.Filters
import uniffi.booklub_mobile.Scope

/** Where the Reader is. The back gesture pops one screen off the stack (REQ-10d). */
sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Analytics : Screen
    data object Settings : Screen
    data class Category(val id: String?) : Screen
    data class Preview(val bookId: String) : Screen
    /** No id means a new book. */
    data class Editor(val bookId: String?) : Screen
    data object Search : Screen
    /** Pairing with a computer (screens E1 to E8). */
    data object Pair : Screen
}

enum class Tab(val label: String, val icon: Int, val root: Screen) {
    Home("Home", R.drawable.ic_home, Screen.Home),
    Library("Library", R.drawable.ic_all_books, Screen.Library),
    Analytics("Analytics", R.drawable.ic_progress, Screen.Analytics),
    Settings("Settings", R.drawable.ic_settings, Screen.Settings),
}

/**
 * Everything the screens share. Every write goes through the Rust core, which persists it
 * before memory changes, and then bumps [revision] so the screens read the new state.
 */
class AppState(private val context: Context) {
    private val prefs = context.getSharedPreferences("booklub", Context.MODE_PRIVATE)
    private val assets = context.assets

    /** Where images are kept (REQ-42, P13). Booklub's own storage unless the Reader chose a card. */
    var imagesDir: String = prefs.getString("images_dir", null)?.takeIf { usable(it) } ?: context.filesDir.resolve("images").path
        private set

    var library: Booklub = open(imagesDir)
        private set

    private fun open(images: String) = Booklub.open(context.filesDir.path, images, context.cacheDir.path)

    private fun usable(path: String): Boolean = java.io.File(path).let { it.mkdirs(); it.isDirectory && it.canWrite() }

    val sync = SyncController(this, context)

    var revision by mutableIntStateOf(0)
        private set
    var appearance by mutableStateOf(Appearance.entries.firstOrNull { it.name == prefs.getString("appearance", null) } ?: Appearance.System)
        private set

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    var tab by mutableStateOf(Tab.Home)
        private set

    // Library view state, kept while the Reader moves between screens.
    var statusFilter by mutableStateOf<String?>(null)
    var sort by mutableStateOf("Manual order")
    var filters by mutableStateOf(Filters("", emptyList(), emptyList(), emptyList(), emptyList()))
    var search by mutableStateOf("")

    /** The most recent storage error, shown as a banner (desktop REQ-31f). */
    var error by mutableStateOf<String?>(null)

    init {
        // REQ-42d: a memory card that has gone leaves images in Booklub's own storage until the
        // Reader chooses again.
        if (prefs.getString("images_dir", null)?.let { !usable(it) } == true) {
            error = "Booklub's storage folder is not available. Choose it again in Settings."
        }
    }

    fun changed() {
        revision++
        sync.nudge()
    }

    /** A session changed the library, so the screens and their images read it again. */
    fun reloadedBySync() {
        forgetImages()
        revision++
    }

    /**
     * REQ-42a: moves every stored image to [to] and reopens the library there. Sync waits, so no
     * session writes an image halfway through the move.
     */
    suspend fun moveImages(to: StorageOption): Boolean = sync.paused {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (from, target) = java.io.File(imagesDir) to java.io.File(to.path)
            if (from.canonicalPath == target.canonicalPath) return@withContext true
            if (!usable(to.path)) return@withContext false
            val files = from.listFiles()?.filter { it.isFile }.orEmpty()
            // Copies first, and switches only once every copy is complete.
            val copied = runCatching { files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) } }.isSuccess
            if (!copied) {
                files.forEach { target.resolve(it.name).delete() }
                return@withContext false
            }
            library.close()
            library = open(to.path)
            imagesDir = to.path
            prefs.edit().putString("images_dir", to.path).apply()
            files.forEach { it.delete() }
            true
        }.also { moved -> if (moved) reloadedBySync() }
    }

    fun go(next: Screen) {
        stack.add(next)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun select(next: Tab) {
        tab = next
        stack.clear()
        stack.add(next.root)
    }

    fun chooseAppearance(value: Appearance) {
        appearance = value
        prefs.edit().putString("appearance", value.name).apply()
    }

    fun libraryScope(): Scope = statusFilter?.let { Scope.Status(it) } ?: Scope.All

    /** Runs a write, reporting a store failure instead of crashing. */
    fun write(action: () -> Unit): Boolean = try {
        action()
        error = null
        changed()
        true
    } catch (e: BooklubException) {
        error = e.text()
        false
    }

    fun book(id: String): Book? = library.book(id)

    // ---------- Images ----------

    private val bitmaps = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A book's thumbnail, decoded once and kept while memory allows. Call off the main thread. */
    fun bookImage(file: String): Bitmap? = bitmaps.get(file) ?: library.thumbnailPath(file)?.let { path ->
        BitmapFactory.decodeFile(path)?.also { bitmaps.put(file, it) }
    }

    /** A Category tile's art: built-in artwork from the app, or a stored image path. */
    fun categoryImage(art: String): Bitmap? = bitmaps.get(art) ?: run {
        val bitmap = if (art.startsWith("preset:")) {
            assets.open("category-art/${art.removePrefix("preset:")}.jpg").use(BitmapFactory::decodeStream)
        } else {
            BitmapFactory.decodeFile(art)
        }
        bitmap?.also { bitmaps.put(art, it) }
    }

    fun forgetImages() = bitmaps.evictAll()
}
