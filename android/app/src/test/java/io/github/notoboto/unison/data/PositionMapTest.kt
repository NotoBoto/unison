package io.github.notoboto.unison.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Moving the listening position between the phone's and Studio's voicing of a chapter (SOURCES_DESIGN.md §12). */
class PositionMapTest {
    private fun para(vararg w: Pair<String, Double?>) = w.map { (word, t) -> Tok(word, t, 0) }
    private fun chapter(duration: Double, vararg paras: List<Tok>) = ChapterText(1, "Толстый и тонкий", listOf("Рассказчик"), paras.toList(), duration)

    // the phone (TtsWorker): its own dash token before a line of dialogue, even timings
    private val phone = chapter(100.0,
        para("Толстый" to 0.0, "и" to 0.5, "тонкий" to 0.7),
        para("На" to 2.0, "вокзале" to 2.4, "встретились" to 3.0, "два" to 3.6, "приятеля:" to 3.9),
        para("—" to 10.0, "Порфирий!" to 10.2, "—" to 11.0, "воскликнул" to 11.3, "толстый," to 12.0, "увидев" to 12.6, "тонкого." to 13.0),
        para("Флёр-д’оранжем" to 20.0, "пахло." to 21.0),
    )
    // Studio (chNNN.json): the same paragraphs, the dash glued to the word, its own timings
    private val studio = chapter(262.6,
        para("Толстый" to 0.0, "и" to 0.48, "тонкий" to 0.62),
        para("На" to 2.96, "вокзале" to 3.04, "встретились" to 3.46, "два" to 4.12, "приятеля:" to 4.56),
        para("—Порфирий!" to 36.72, "—" to 37.88, "воскликнул" to 37.88, "толстый," to 38.5, "увидев" to 39.22, "тонкого." to 39.6),
        para("Флер-д'оранжем" to 50.0, "пахло." to 51.4),
    )

    @Test fun wordInParagraphMovesToTheSameWord() {
        assertEquals(4.12, PositionMap.map(phone, 3.7, studio, sameText = true), "«два»")
        assertEquals(3.46, PositionMap.map(studio, 3.5, phone, sameText = true).let { PositionMap.map(phone, it, studio, true) })
    }

    @Test fun dashesSplitDifferentlyDoNotShiftTheWord() {
        assertEquals(36.72, PositionMap.map(phone, 10.0, studio, sameText = true), "the phone's «—» belongs to «Порфирий»")
        assertEquals(37.88, PositionMap.map(phone, 11.5, studio, sameText = true), "«воскликнул»")
        assertEquals(39.22, PositionMap.map(phone, 12.7, studio, sameText = true), "«увидев»")
        assertEquals(11.3, PositionMap.map(studio, 38.0, phone, sameText = true), "and back")
    }

    @Test fun caseYoAndApostrophesAreTheSameWord() {
        assertEquals(51.4, PositionMap.map(phone, 21.5, studio, sameText = true), "«пахло»")
        assertEquals(50.0, PositionMap.map(phone, 20.2, studio, sameText = true), "«Флёр-д’оранжем» = «Флер-д'оранжем»")
    }

    @Test fun otherTextMovesByShareOfTime() {
        assertEquals(262.6 * 0.25, PositionMap.map(phone, 25.0, studio, sameText = false), 1e-9)
        val shorter = chapter(200.0, *studio.paras.dropLast(1).toTypedArray())
        assertEquals(50.0, PositionMap.map(phone, 25.0, shorter, sameText = true), 1e-9, "another paragraph count")
    }

    @Test fun edges() {
        assertEquals(0.0, PositionMap.map(phone, 0.0, studio, sameText = true))
        assertEquals(262.6, PositionMap.map(phone, 500.0, studio, sameText = false), 1e-9, "past the end")
        assertEquals(51.4, PositionMap.map(phone, 99.0, studio, sameText = true), "after the last word: the last word")
    }

    @Test fun normalization() {
        assertEquals(null, PositionMap.norm("—")); assertEquals(null, PositionMap.norm("…"))
        assertEquals("ее", PositionMap.norm("«Её»"))
    }
}
