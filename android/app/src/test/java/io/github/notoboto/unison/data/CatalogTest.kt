package io.github.notoboto.unison.data

import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalog fields of stage 7 (SOURCES_DESIGN.md §12, §19.7, decision 15): origin and nsfw in books.json. */
class CatalogTest {
    // the shape Unison Studio publishes (unison-catalog-7q7.pages.dev/books.json, 20.11.2026)
    private val catalog = """[
      {"id": "hpmor", "title": "ГПиМРМ", "data": "https://unison-hpmor.pages.dev/", "total": 122},
      {"id": "tolstyy-i-tonkiy", "title": "Толстый и тонкий", "data": "https://unison-tolstyy-i-tonkiy.pages.dev/", "total": 1,
       "origin": {"plugin": "wikisource-ru", "book": "Толстый и тонкий (Чехов)"}},
      {"id": "adult", "title": "Взрослая", "data": "https://unison-adult.pages.dev/",
       "origin": {"plugin": "ranobelib", "book": "123--adult"}, "nsfw": true, "someFutureField": 1}
    ]"""

    @Test fun originAndNsfwAreRead() {
        val list = json.decodeFromString(ListSerializer(BookMeta.serializer()), catalog)
        assertNull(list[0].origin)
        assertFalse(list[0].nsfw)
        assertEquals(BookOrigin("wikisource-ru", "Толстый и тонкий (Чехов)"), list[1].origin)
        assertTrue(list[2].nsfw)
    }

    @Test fun adultBooksAreHiddenUntilTheSwitchIsOn() {
        val list = json.decodeFromString(ListSerializer(BookMeta.serializer()), catalog)
        assertEquals(listOf("hpmor", "tolstyy-i-tonkiy"), list.shown(showNsfw = false).map { it.id })
        assertEquals(listOf("hpmor", "tolstyy-i-tonkiy", "adult"), list.shown(showNsfw = true).map { it.id })
    }

    @Test fun oldMetaFilesStillLoad() {
        // meta.json of books saved before stage 7 has neither field
        val m = json.decodeFromString(BookMeta.serializer(), """{"id": "s0123", "title": "Книга", "data": ""}""")
        assertNull(m.origin); assertFalse(m.nsfw)
    }
}
