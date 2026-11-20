package io.github.notoboto.unison.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File

/**
 * «Наложение» (SOURCES_DESIGN.md §12, decision 12): a book added through a source plugin plays the chapters Unison Studio
 * has voiced, the rest are voiced by the phone as before.
 *
 * The catalog book whose `origin` is this plugin book (§19.7) is the Studio side. Its index.json lists the chapters in the
 * site's order with `id` (the plugin's chapterId) and `hash`; it is kept next to the book as studio.json, so the overlay
 * works offline. The book key stays `src://…`: progress, bookmarks and categories do not move.
 */
object StudioOverlay {
    /** The Studio side of a plugin book: the catalog it was found in, the catalog book, its data folder and index.json. */
    @Serializable
    data class Link(val source: String, val book: String, val data: String, val chapters: List<ChapterMeta>, val checked: Long = 0)

    fun file(ctx: Context, sourceUrl: String, id: String) = File(TextBooks.dir(ctx, sourceUrl, id), "studio.json")

    fun load(ctx: Context, sourceUrl: String, id: String): Link? =
        file(ctx, sourceUrl, id).takeIf { it.exists() }?.let { f -> runCatching { json.decodeFromString(Link.serializer(), f.readText()) }.getOrNull() }

    fun save(ctx: Context, sourceUrl: String, id: String, link: Link) =
        TextBooks.atomicWrite(file(ctx, sourceUrl, id), json.encodeToString(Link.serializer(), link))

    fun delete(ctx: Context, sourceUrl: String, id: String) { file(ctx, sourceUrl, id).delete() }

    /** The catalog book made from book [book] of plugin [plugin]; the first one in the order of [sources]. */
    fun match(plugin: String, book: String, sources: List<Source>, catalogs: Map<String, List<BookMeta>>): Pair<Source, BookMeta>? =
        sources.firstNotNullOfOrNull { s -> catalogs[s.url]?.firstOrNull { it.origin?.plugin == plugin && it.origin.book == book }?.let { s to it } }

    /** n → chapterId of a plugin book (its chapters.json, §19.5). */
    fun chapterIds(ctx: Context, sourceUrl: String, id: String): Map<Int, String> = runCatching {
        json.decodeFromString(io.github.notoboto.unison.sources.SrcBooks.Table.serializer(), File(TextBooks.dir(ctx, sourceUrl, id), "chapters.json").readText())
            .chapters.associate { it.n to it.id }
    }.getOrDefault(emptyMap())

    /** The Studio chapter of chapter [n], matched by chapterId. */
    fun studioChapter(link: Link?, ids: Map<Int, String>, n: Int): ChapterMeta? {
        val id = ids[n] ?: return null
        return link?.chapters?.firstOrNull { it.id == id }
    }

    /**
     * The chapter list of a plugin book with the Studio chapters put in (matched by chapterId; the order, n and titles stay
     * the phone's). A chapter the phone has voiced itself ([phoneVoiced]) keeps its own audio until it is switched.
     * A Studio chapter gets [ChapterMeta.studio] (where its files are), the Studio length, version, pieces and pictures.
     */
    fun apply(chapters: List<ChapterMeta>, ids: Map<Int, String>, link: Link?, phoneVoiced: Set<Int>): List<ChapterMeta> {
        if (link == null || link.chapters.isEmpty()) return chapters
        val byId = link.chapters.filter { it.id != null }.associateBy { it.id!! }
        return chapters.map { c ->
            if (c.n in phoneVoiced) return@map c
            val s = ids[c.n]?.let { byId[it] } ?: return@map c
            c.copy(duration = s.duration, bytes = s.bytes, v = STUDIO_V + (s.v ?: "1"), parts = s.parts, hash = s.hash,
                images = s.images.map { im -> if (im.src.contains("://")) im else im.copy(src = link.data + im.src) },
                studio = link.data + "ch%03d".format(s.n))
        }
    }

    /** Version prefix of Studio chapters in [AppState.downloaded] and [ChapterMeta.v]; the phone's own voicing is "tts". */
    const val STUDIO_V = "s:"
}
