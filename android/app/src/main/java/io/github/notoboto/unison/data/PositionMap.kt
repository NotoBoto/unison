package io.github.notoboto.unison.data

/**
 * Moves a listening position between two voicings of the same chapter (SOURCES_DESIGN.md §12): the phone's and Unison
 * Studio's. Both chapter files keep the title as paras[0], then the paragraphs of `core` in the same order.
 *
 * The word is found by its number among the paragraph's normalized words (lower case, ё = е, no tokens made of punctuation
 * only): Studio and the phone split dashes into tokens differently, so token indexes do not match. When the texts differ
 * (another chapter hash, another paragraph count) the position moves by the share of the chapter listened to.
 * Phone timings are estimates, so the result is exact to the word, not to the millisecond.
 */
object PositionMap {
    /** A normalized word, or null for a token of punctuation only ("—", "…"). */
    fun norm(token: String): String? =
        token.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }.ifEmpty { null }

    /** [pos] seconds in [from] → seconds in [to]. [sameText]: the chapter hashes of both voicings are equal. */
    fun map(from: ChapterText, pos: Double, to: ChapterText, sameText: Boolean): Double {
        if (pos <= 0.0) return 0.0
        val byShare = byShare(from, pos, to)
        if (!sameText || from.paras.size != to.paras.size) return byShare
        val w = from.wordAt(pos).takeIf { it >= 0 } ?: return 0.0       // before the first word: the start
        val (_, p, ti) = from.timeline[w]
        val src = words(from.paras[p])
        val dst = words(to.paras[p])
        if (src.isEmpty() || dst.isEmpty()) return byShare
        // the word the token belongs to: a punctuation token ("—") counts as the word after it
        val k = src.indexOfFirst { it.token >= ti }.let { if (it < 0) src.size - 1 else it }
        val word = src[k].word
        // the same word near the same place (a stray token on one side only shifts the count by one or two)
        val j = listOf(0, 1, -1, 2, -2, 3, -3).map { k + it }.firstOrNull { it in dst.indices && dst[it].word == word }
            ?: return byShare
        return dst[j].time ?: byShare
    }

    private fun byShare(from: ChapterText, pos: Double, to: ChapterText): Double =
        if (from.duration <= 0.0) 0.0 else (pos / from.duration).coerceIn(0.0, 1.0) * to.duration

    private class W(val word: String, val token: Int, val time: Double?)

    private fun words(p: List<Tok>): List<W> = p.mapIndexedNotNull { i, t -> norm(t.word)?.let { W(it, i, t.t) } }
}
