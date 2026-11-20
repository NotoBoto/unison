package io.github.notoboto.unison.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notoboto.unison.UnisonApp
import io.github.notoboto.unison.sources.BookDetails
import io.github.notoboto.unison.sources.ChapterStub
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Наложение» (SOURCES_DESIGN.md §12) against the live data: the Unison catalog has «Толстый и тонкий» with
 * origin {wikisource-ru, «Толстый и тонкий (Чехов)»}, published by Unison Studio. Needs internet.
 */
@RunWith(AndroidJUnit4::class)
class StudioOverlayLiveTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = ctx.applicationContext as UnisonApp
    private val src = pluginSourceUrl("wikisource-ru")
    private val made = mutableListOf<String>()

    @After fun clean() { made.forEach { TextBooks.delete(ctx, src, it) }; app.repo.reloadLocal() }

    @Test fun pluginBookGetsTheStudioChapters() = runBlocking<Unit> {
        app.sources.repos.ensureBuiltIns()
        assertTrue(app.repo.refreshSource(DEFAULT_SOURCE).isSuccess)
        val bookId = "Толстый и тонкий (Чехов)"
        // the chapter ids come from the plugin itself: they must be the ones Studio published
        val chapters = app.sources.chapters("wikisource-ru", bookId)
        val id = app.sources.books.add("wikisource-ru", app.sources.details("wikisource-ru", bookId), chapters).also { made += it }
        assertTrue("matched by the catalog's origin", app.repo.refreshStudio(src, id))

        val link = StudioOverlay.load(ctx, src, id)!!
        assertEquals("tolstyy-i-tonkiy", link.book)
        assertEquals("https://unison-tolstyy-i-tonkiy.pages.dev/", link.data)
        val b = app.repo.book(bookKey(src, id))!!
        val ch = b.chapters.single()
        assertEquals("the phone's chapterId is Studio's", link.chapters.single().id, chapters.single().id)
        assertEquals("https://unison-tolstyy-i-tonkiy.pages.dev/ch001", ch.studio)
        assertTrue("Studio length", ch.duration > 100)
        assertTrue(ch.v!!.startsWith(StudioOverlay.STUDIO_V))
        assertNotNull(ch.hash)
    }

    @Test fun bookWithoutStudioVersionHasNoOverlay() = runBlocking<Unit> {
        app.sources.repos.ensureBuiltIns()
        assertTrue(app.repo.refreshSource(DEFAULT_SOURCE).isSuccess)
        val id = app.sources.books.add("wikisource-ru", BookDetails(id = "Нет такой книги в Studio", title = "Нет", url = "https://ru.wikisource.org/wiki/x"),
            listOf(ChapterStub("Нет такой книги в Studio", "Нет", "https://ru.wikisource.org/wiki/x"))).also { made += it }
        // a stale copy (the book was taken out of the catalog): dropped once every catalog is known
        StudioOverlay.save(ctx, src, id, StudioOverlay.Link(DEFAULT_SOURCE.url, "old", "https://old.example/",
            listOf(ChapterMeta(1, "x", 10.0, id = "Нет такой книги в Studio"))))
        app.repo.reloadLocal()
        assertNotNull(app.repo.book(bookKey(src, id))!!.chapters.single().studio)
        assertFalse(app.repo.refreshStudio(src, id))
        assertNull(StudioOverlay.load(ctx, src, id))
        assertNull(app.repo.book(bookKey(src, id))!!.chapters.single().studio)
    }
}
