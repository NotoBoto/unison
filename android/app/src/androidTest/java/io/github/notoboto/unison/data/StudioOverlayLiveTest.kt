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

    @Test fun studioChapterIsReadPlayedAndDownloadedFromStudio() = runBlocking<Unit> {
        app.sources.repos.ensureBuiltIns()
        assertTrue(app.repo.refreshSource(DEFAULT_SOURCE).isSuccess)
        val bookId = "Толстый и тонкий (Чехов)"
        val id = app.sources.books.add("wikisource-ru", app.sources.details("wikisource-ru", bookId), app.sources.chapters("wikisource-ru", bookId)).also { made += it }
        assertTrue(app.repo.refreshStudio(src, id))
        val key = bookKey(src, id)
        val b = app.repo.book(key)!!
        val n = b.chapters.single().n

        // played and read from Studio, no voicing on the phone
        assertTrue(app.player.hasAudio(b, n))
        assertEquals("https://unison-tolstyy-i-tonkiy.pages.dev/ch001.ogg", app.repo.audioUri(b, n))
        val t = app.repo.chapterText(b, n)
        assertTrue("Studio roles", t.roles.size > 1)
        assertTrue("word timings", t.paras.drop(1).all { p -> p.all { it.t != null } })
        assertEquals("paras[0] is the title", "Толстый и тонкий", t.paras[0].joinToString(" ") { it.word })

        // «Скачать» downloads the Studio files instead of voicing
        app.downloads.enqueue(b, listOf(n))
        val until = System.currentTimeMillis() + 90_000
        while (!app.store.isDownloaded(key, n) && System.currentTimeMillis() < until) kotlinx.coroutines.delay(500)
        assertEquals(b.chapters.single().v, app.store.s.downloaded[key]?.get(n))
        assertEquals(io.github.notoboto.unison.download.DlState.Done, app.downloads.state(b, n))
        assertTrue(app.repo.audioUri(b, n).startsWith("file:"))
        assertTrue("the downloaded copy is read", app.repo.chapterText(b, n).roles.size > 1)
        app.repo.deleteLocal(key, n)
        assertFalse(app.store.isDownloaded(key, n))
    }

    @Test fun phoneVoicedChapterKeepsItsVoicingUntilDeleted() = runBlocking<Unit> {
        app.sources.repos.ensureBuiltIns()
        assertTrue(app.repo.refreshSource(DEFAULT_SOURCE).isSuccess)
        val bookId = "Толстый и тонкий (Чехов)"
        val id = app.sources.books.add("wikisource-ru", app.sources.details("wikisource-ru", bookId), app.sources.chapters("wikisource-ru", bookId)).also { made += it }
        val key = bookKey(src, id)
        app.repo.reloadLocal()
        val n = app.repo.book(key)!!.chapters.single().n
        app.store.markDownloaded(key, n, "tts")      // voiced on the phone before Studio published the chapter
        assertTrue(app.repo.refreshStudio(src, id))
        assertNull("the phone's voicing stays", app.repo.book(key)!!.chapters.single().studio)
        app.repo.deleteLocal(key, n)
        assertNotNull("deleted: the Studio chapter takes its place", app.repo.book(key)!!.chapters.single().studio)
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
