package io.github.dulestrom.booklub

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.booklub_mobile.Booklub
import uniffi.booklub_mobile.Filters
import uniffi.booklub_mobile.Scope
import uniffi.booklub_mobile.applyStatus

/**
 * Runs the generated Kotlin bindings against the Rust library built for this computer, so a
 * mismatch between the two shows up without a phone.
 */
class BridgeTest {
    private fun open(): Booklub {
        val root = Files.createTempDirectory("booklub-bridge")
        return Booklub.open(root.resolve("data").toString(), root.resolve("images").toString(), root.resolve("cache").toString())
    }

    @Test
    fun aNewLibraryHasTheTwoDefaultCategories() {
        assertEquals(listOf("Fiction", "Non-fiction"), open().categories().map { it.name })
    }

    @Test
    fun booksRoundTripThroughTheBindings() {
        val library = open()
        var book = library.newBook()
        book = book.copy(title = "Ѓердан од ќерамиди", priority = "High", totalPages = 300u)
        book = applyStatus(book, "Reading")
        assertTrue(library.saveBook(book).saved)
        val list = library.view(Scope.All, Filters("ќерам", emptyList(), emptyList(), emptyList(), emptyList()), "Priority")
        assertEquals(1, list.books.size)
        assertEquals("High Priority", list.headings.single().title)
        assertEquals("Reading", library.book(book.id)!!.status)
    }

    @Test
    fun anInvalidBookComesBackWithItsMessage() {
        val result = open().let { it.saveBook(it.newBook()) }
        assertFalse(result.saved)
        assertEquals("Title is required.", result.errors.title)
    }

    @Test
    fun goalsAndStatsCrossTheBridge() {
        val library = open()
        library.saveGoal("booksPerMonth", 5u, true)
        assertEquals(5u, library.goals().first { it.id == "booksPerMonth" }.target)
        assertEquals(12, library.stats().finishedByMonth.size)
    }
}
