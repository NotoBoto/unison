package io.github.notoboto.unison.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** «Наложение» of Studio chapters on a plugin book (SOURCES_DESIGN.md §12). */
class StudioOverlayTest {
    private val unison = Source("https://notoboto.github.io/unison/", "Unison")
    private val other = Source("https://example.org/books/", "Другой")
    private val tolstoy = BookMeta(id = "tolstyy-i-tonkiy", title = "Толстый и тонкий", data = "https://unison-tolstyy-i-tonkiy.pages.dev/",
        origin = BookOrigin("wikisource-ru", "Толстый и тонкий (Чехов)"))
    private val catalogs = mapOf(
        unison.url to listOf(BookMeta(id = "hpmor", title = "ГПиМРМ"), tolstoy),
        other.url to listOf(tolstoy.copy(id = "copy")),
    )

    @Test fun matchesByPluginAndBook() {
        val (src, m) = StudioOverlay.match("wikisource-ru", "Толстый и тонкий (Чехов)", listOf(unison, other), catalogs)!!
        assertEquals(unison, src); assertEquals("tolstyy-i-tonkiy", m.id)
        assertEquals("copy", StudioOverlay.match("wikisource-ru", "Толстый и тонкий (Чехов)", listOf(other, unison), catalogs)!!.second.id)
        assertNull(StudioOverlay.match("ranobelib", "Толстый и тонкий (Чехов)", listOf(unison), catalogs), "another plugin")
        assertNull(StudioOverlay.match("wikisource-ru", "Толстый и тонкий", listOf(unison), catalogs), "the book id must be the same")
    }

    // the phone: chapters a (n 1), x (n 4, inserted later on the site), c (n 3); Studio has voiced a and c as its 1 and 2
    private val phone = listOf(
        ChapterMeta(1, "Глава a", 400.0, v = null),
        ChapterMeta(4, "Глава x", 0.0),
        ChapterMeta(3, "Глава c", 500.0),
    )
    private val ids = mapOf(1 to "a", 4 to "x", 3 to "c")
    private val link = StudioOverlay.Link(unison.url, "book", "https://unison-book.pages.dev/", listOf(
        ChapterMeta(1, "A", 612.5, 123, "v1", id = "a", hash = "h1"),
        ChapterMeta(2, "C", 700.0, 456, "v2", id = "c", hash = "h2", parts = listOf(10, 20),
            images = listOf(ChapterImage(3, "img/p.jpg"), ChapterImage(5, "https://cdn.example/q.png"))),
    ))

    @Test fun studioChaptersArePutInByChapterId() {
        val r = StudioOverlay.apply(phone, ids, link, phoneVoiced = emptySet())
        assertEquals(listOf(1, 4, 3), r.map { it.n }, "order and n stay the phone's")
        assertEquals(listOf("Глава a", "Глава x", "Глава c"), r.map { it.title })
        val a = r[0]; val x = r[1]; val c = r[2]
        assertEquals("https://unison-book.pages.dev/ch001", a.studio)
        assertEquals("https://unison-book.pages.dev/ch002", c.studio, "Studio n, not the phone's")
        assertEquals(612.5, a.duration); assertEquals("s:v1", a.v); assertEquals("h1", a.hash)
        assertEquals(listOf(10L, 20L), c.parts)
        assertEquals(listOf("https://unison-book.pages.dev/img/p.jpg", "https://cdn.example/q.png"), c.images.map { it.src })
        assertSame(phone[1], x, "a chapter Studio has not voiced is the phone's")
    }

    @Test fun phoneVoicedChapterKeepsItsAudio() {
        val r = StudioOverlay.apply(phone, ids, link, phoneVoiced = setOf(1))
        assertNull(r[0].studio); assertEquals(400.0, r[0].duration)
        assertEquals("https://unison-book.pages.dev/ch002", r[2].studio)
    }

    @Test fun nothingWithoutALink() {
        assertSame(phone, StudioOverlay.apply(phone, ids, null, emptySet()))
        assertEquals(phone, StudioOverlay.apply(phone, emptyMap(), link, emptySet()), "no chapterIds: nothing matches")
    }
}
