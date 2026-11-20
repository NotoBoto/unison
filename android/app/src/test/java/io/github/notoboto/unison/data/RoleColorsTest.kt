package io.github.notoboto.unison.data

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** data/RoleColors.kt on the roles lists of the 122 chapters of HPMOR (resource made by scripts/role_colors_resource.py,
 *  ROLE_COLORS_GROOM.md §5 A.4): invariants in three reading orders plus golden values of the Python model
 *  (scripts/role_colors_check.py for step 1; scripts/role_colors_pin_model.py for the user's colours, §6 C.5). */
class RoleColorsTest {
    /** chapter -> roles list (index 0 = narrator), as the server sends it. */
    private val book: Map<Int, List<String>> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("role-colors-hpmor.json")!!.bufferedReader().use { it.readText() }
        json.parseToJsonElement(text).jsonObject["chapters"]!!.jsonArray.associate { c ->
            c.jsonObject["n"]!!.jsonPrimitive.int to c.jsonObject["roles"]!!.jsonArray.map { it.jsonPrimitive.content } }
    }
    private val seq get() = book.keys.sorted()
    /** Reading orders; the same in scripts/role_colors_resource.py. */
    private val orders get() = mapOf("seq" to seq, "reversed" to seq.reversed(), "stride" to seq.sortedBy { it * 37 % 127 })

    private class Read(val br: BookRoles, val collisions: Int, val changes: Int)

    /** Reads the book in [order] the way the reader does (learn, then show) and checks the per-chapter invariants;
     *  starts from [start], or from nothing with the user's colours [pins]. */
    private fun read(name: String, order: List<Int>, pins: Map<String, Int> = emptyMap(), start: BookRoles? = null): Read {
        var br = start ?: BookRoles(pinned = pins); var collisions = 0; var changes = 0
        val prev = HashMap<String, Int>()
        for (n in order) {
            val r = book.getValue(n)
            val before = RoleColors.colorsFor(br, r)
            br = RoleColors.learn(br, r, n)
            val after = RoleColors.colorsFor(br, r)
            assertArrayEquals("$name ch$n: colours before and after learn", before, after)            // invariant 2
            assertSame("$name ch$n: learn again", br, RoleColors.learn(br, r, n))                     // invariant 3
            val speakers = r.drop(1).distinct().size
            if (after.drop(1).distinct().size < speakers) collisions++
            inv("$name ch$n", br, r, after)                                                         // invariant 1
            for (i in 1 until r.size) { prev.put(r[i], after[i])?.let { if (it != after[i]) changes++ } }
        }
        return Read(br, collisions, changes)
    }

    /** Invariant 1: with <= 12 speakers only the ones the user pinned to one colour share it; a pinned one shows its colour. */
    private fun inv(what: String, br: BookRoles, r: List<String>, a: IntArray = RoleColors.colorsFor(br, r)) {
        val present = r.drop(1).distinct()
        val pp = present.filter { it in br.pinned }
        if (present.size <= RoleColors.SIZE)
            assertEquals("$what: collision", present.size - (pp.size - pp.map { br.pinned[it] }.distinct().size), a.drop(1).distinct().size)
        for (i in 1 until r.size) br.pinned[r[i]]?.let { assertEquals("$what: ${r[i]} pinned", it, a[i]) }
    }

    @Test fun invariants() {
        val appearances = book.values.sumOf { it.drop(1).distinct().size }
        assertEquals(122, book.size); assertEquals(919, appearances)
        for ((name, order) in orders) {
            val a = read(name, order)
            assertEquals("$name: deterministic", a.br, read(name, order).br)                          // invariant 4
            assertEquals("$name: sum of seen", appearances, a.br.seen.values.sum())                  // invariant 5
            assertEquals(153, a.br.color.size)
            assertEquals(book.filterValues { it.size > 1 }.keys, a.br.chapters)                     // ch 99: narrator only, not counted
            for ((n, r) in book) {                                                                   // re-read: invariant 1 again
                val speakers = r.drop(1).distinct().size
                if (speakers <= RoleColors.SIZE) assertEquals("$name reread ch$n", speakers, RoleColors.colorsFor(a.br, r).drop(1).distinct().size)
            }
        }
    }

    /** Numbers of the Python model (scripts/role_colors_check.py) for the same orders. */
    @Test fun sameAsModel() {
        val golden = mapOf("seq" to (18 to 50), "reversed" to (18 to 61), "stride" to (18 to 60))
        for ((name, order) in orders) {
            val a = read(name, order)
            assertEquals("$name: chapters with a collision, colour changes", golden[name], a.collisions to a.changes)
        }
        val br = read("seq", seq).br
        assertEquals(mapOf("Гарри" to 2, "Квиррелл" to 0, "Дамблдор" to 6, "Гермиона" to 7, "МакГонагалл" to 4, "Драко" to 9,
            "Безымянный_м" to 10, "Толпа" to 8, "Снейп" to 11, "Безымянный_ж" to 1),
            br.seen.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10).associate { it.key to br.color.getValue(it.key) })
        val ch1 = book.getValue(1)                         // Рассказчик, Майкл, Петуния, Гарри, Фигг: as the old (role - 1) % n
        assertEquals(listOf(-1, 0, 1, 2, 3), RoleColors.colorsFor(RoleColors.learn(BookRoles(), ch1, 1), ch1).toList())
    }

    /** Speakers of one chapter per colour. */
    private fun crowd(br: BookRoles, r: List<String>): Int =
        RoleColors.colorsFor(br, r).drop(1).zip(r.drop(1)).distinct().groupingBy { it.first }.eachCount().values.max()

    /** More speakers than colours: newcomers share the colours evenly, none piles on one (the bug: ch 63 opened first,
     *  37 speakers, all seen 0 -> 26 of them on colour 0). */
    @Test fun crowdedChapterSharesColours() {
        val r = book.getValue(63)
        val speakers = r.drop(1).distinct().size
        assertEquals(37, speakers)
        val br = RoleColors.learn(BookRoles(), r, 63)
        val perColour = br.color.values.groupingBy { it }.eachCount()
        assertEquals((0 until RoleColors.SIZE).toSet(), perColour.keys)
        assertTrue("ch63 first: $perColour", perColour.values.max() <= (speakers + RoleColors.SIZE - 1) / RoleColors.SIZE)
        assertEquals(4, crowd(br, r))
        // chapters 1..63 in order: no worse than before the fix (old: 19 colour changes, up to 12 speakers on one colour)
        var b = BookRoles(); var worst = 0
        for (n in seq.filter { it <= 63 }) { b = RoleColors.learn(b, book.getValue(n), n); worst = maxOf(worst, crowd(b, book.getValue(n))) }
        assertEquals(5, worst)
        assertEquals(5 to 18, read("1..63", seq.filter { it <= 63 }).let { it.collisions to it.changes })
    }

    @Test fun piperAndNothingToWrite() {
        val piper = listOf("Рассказчик", "Мужской голос", "Женский голос")
        val br = RoleColors.learn(BookRoles(), piper, 1)
        assertEquals(listOf(-1, 0, 1), RoleColors.colorsFor(br, piper).toList())
        assertSame(br, RoleColors.learn(br, piper, 1))                         // the same chapter again
        assertSame(br, RoleColors.learn(br, listOf("Рассказчик"), 2))         // narrator only
        assertEquals(listOf(-1), RoleColors.colorsFor(null, listOf("Рассказчик")).toList())
    }

    @Test fun oldStateWithoutRoleColors() {
        val st = json.decodeFromString(AppState.serializer(), """{"library":[],"settings":{"fontSize":21}}""")
        assertTrue(st.roleColors.isEmpty()); assertEquals(21, st.settings.fontSize)
        val br = BookRoles(mapOf("Гарри" to 2), mapOf("Гарри" to 5), setOf(1, 3))
        val back = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st.copy(roleColors = mapOf("k" to br))))
        assertEquals(br, back.roleColors["k"])
    }

    // ---- the user's colours (ROLE_COLORS_GROOM.md §6 C.5); numbers of scripts/role_colors_pin_model.py ----

    private fun readTo(k: Int, from: BookRoles = BookRoles()): BookRoles =
        seq.filter { it <= k }.fold(from) { b, n -> RoleColors.learn(b, book.getValue(n), n) }
    /** Colours shown in chapter [n] for those of [names] who speak there. */
    private fun show(br: BookRoles, n: Int, vararg names: String): Map<String, Int> {
        val r = book.getValue(n); val a = RoleColors.colorsFor(br, r)
        return names.filter { it in r }.associateWith { a[r.indexOf(it)] }
    }
    /** colour -> chapters [name] is shown in it, over the whole book. */
    private fun shownAll(br: BookRoles, name: String): Map<Int, Int> =
        seq.filter { name in book.getValue(it) }.groupingBy { show(br, it, name).getValue(name) }.eachCount()
    /** Stored colours that changed from [a] to [b]. */
    private fun diff(a: BookRoles, b: BookRoles): Map<String, Int> = b.color.filter { (nm, c) -> a.color[nm] != c }

    @Test fun pinsKeepTheirColour() {
        val pins = mapOf("Гарри" to 0, "Драко" to 2, "Гермиона" to 2)
        val golden = mapOf("seq" to (30 to 50), "reversed" to (30 to 63), "stride" to (30 to 53))
        for ((name, order) in orders) {
            val a = read(name, order, pins)
            assertEquals("$name: chapters with a collision, colour changes", golden[name], a.collisions to a.changes)
            assertEquals("$name: pinned kept", pins, a.br.pinned)                                    // learn keeps pinned
            assertTrue("$name: pinned not in color", pins.keys.none { it in a.br.color })
            for (n in seq) {
                val r = book.getValue(n)
                if ("Квиррелл" in r && "Гарри" in r) assertTrue("$name ch$n: Квиррелл", show(a.br, n, "Квиррелл").getValue("Квиррелл") != 0)
            }
        }
        val base = read("seq", seq).br
        val br = read("seq", seq, pins).br
        assertEquals(mapOf("Гарри" to 0, "Квиррелл" to 1, "Дамблдор" to 5, "Гермиона" to 2, "МакГонагалл" to 4, "Драко" to 2,
            "Безымянный_м" to 7, "Толпа" to 9, "Снейп" to 11, "Безымянный_ж" to 6),
            base.seen.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10)
                .associate { it.key to (br.pinned[it.key] ?: br.color.getValue(it.key)) })
    }

    @Test fun pinMidwayAndAutomatic() {
        val br = readTo(19); val r19 = book.getValue(19)
        assertEquals(mapOf("Драко" to 9, "Гойл" to 1, "Крэбб" to 5), show(br, 19, "Драко", "Гойл", "Крэбб"))
        val b2 = RoleColors.withPinned(br, mapOf("Драко" to 1), r19, 19)
        assertEquals(mapOf("Драко" to 1, "Гойл" to 11, "Крэбб" to 5), show(b2, 19, "Драко", "Гойл", "Крэбб"))
        assertArrayEquals("on the fly = written", RoleColors.colorsFor(br.copy(pinned = mapOf("Драко" to 1)), r19), RoleColors.colorsFor(b2, r19))
        assertEquals(9, b2.color["Драко"])
        assertEquals(mapOf("Гойл" to 11, "Петуния" to 3, "Спраут" to 3, "Забини" to 3, "Ханна" to 3), diff(br, b2))
        assertEquals(br.seen, b2.seen); assertEquals(br.chapters, b2.chapters)
        for (n in seq.filter { it <= 19 }) inv("ch$n after the pin", b2, book.getValue(n))
        val b3 = RoleColors.withPinned(b2, emptyMap(), r19, 19)                                       // «automatic»
        assertEquals(mapOf("Драко" to 9, "Гойл" to 11), show(b3, 19, "Драко", "Гойл"))
        assertEquals(b2.color, b3.color)
        val a = read("20..122", seq.filter { it >= 20 }, start = b3)
        assertEquals(17 to 47, a.collisions to a.changes)
    }

    /** Review Б1: «automatic» gives the pinned one its own colour back, not the one of who gave way to it. */
    @Test fun automaticBringsColourBack() {
        val br = readTo(7); val r7 = book.getValue(7)
        assertEquals(mapOf("Драко" to 9, "Петуния" to 1), show(br, 7, "Драко", "Петуния"))
        val b2 = RoleColors.withPinned(br, mapOf("Драко" to 1), r7, 7)
        assertEquals(mapOf("Драко" to 1, "Петуния" to 6), show(b2, 7, "Драко", "Петуния"))
        assertEquals(mapOf("Петуния" to 6), diff(br, b2))
        val b3 = RoleColors.withPinned(b2, emptyMap(), r7, 7)
        assertEquals(mapOf("Драко" to 9, "Петуния" to 6), show(b3, 7, "Драко", "Петуния"))
        assertEquals(b2.color, b3.color)
        for (n in listOf(1, 2)) assertEquals("ch$n", mapOf("Петуния" to 6), show(b3, n, "Петуния"))
        for (k in listOf(3, 4, 6)) {                       // pinned for the rest of the book, then «automatic»
            val b = RoleColors.withPinned(readTo(19), mapOf("Драко" to k), book.getValue(19), 19)
            val end = read("Драко -> $k", seq.filter { it >= 20 }, start = b).br
            assertEquals("Драко -> $k", mapOf(9 to 34), shownAll(RoleColors.withPinned(end, emptyMap()), "Драко"))
        }
    }

    @Test fun pinnedSharedInCrowd() {
        val r = book.getValue(63)
        val b = RoleColors.learn(BookRoles(pinned = mapOf("Гарри" to 0)), r, 63)
        assertEquals(mapOf("Гарри" to 0), show(b, 63, "Гарри"))
        assertEquals(4, crowd(b, r))                       // as without the user's colours
        assertEquals((0 until RoleColors.SIZE).toSet(), RoleColors.colorsFor(b, r).drop(1).toSet())
    }

    /** 6.9.9: the automatic owners of a chosen colour give it up in the whole book at once. */
    @Test fun pinRecoloursOwnersBookWide() {
        val full = read("seq", seq).br
        assertEquals(2, full.color["Гарри"])
        assertEquals(listOf("Гарри", "Жасмин"), RoleColors.owners(full, "Шляпа")[2])
        val r64 = book.getValue(64)
        assertTrue("Гарри" !in r64)
        val b = RoleColors.withPinned(full, mapOf("Шляпа" to 2), r64, 64)
        assertEquals(mapOf("Гарри" to 3, "Жасмин" to 5, "Помфри" to 5, "Сумеречная Искорка" to 5), diff(full, b))
        assertEquals(mapOf(3 to 118), shownAll(b, "Гарри"))
        for ((n, r) in book) {
            val before = RoleColors.colorsFor(b, r)
            val again = RoleColors.learn(b, r, n)
            assertSame("ch$n: learn after the pin", b, again)
            assertArrayEquals("ch$n", before, RoleColors.colorsFor(again, r))
        }
        val bE = RoleColors.withPinned(full, mapOf("Шляпа" to 2))   // no chapter on screen («Действующие лица»)
        assertEquals(mapOf(3 to 118), shownAll(bE, "Гарри"))
    }

    @Test fun pinNothingToWrite() {
        val b = readTo(5); val r5 = book.getValue(5)
        assertSame(b, RoleColors.withPinned(b, b.pinned, r5, 5))
        assertSame(b, RoleColors.withPinned(b, b.pinned - "Гарри", r5, 5))    // «automatic» for one not pinned
        assertEquals(mapOf("Гарри" to 1), RoleColors.withPinned(b, mapOf("Гарри" to 13)).pinned)
        val nb = RoleColors.withPinned(b, mapOf("Гарри" to 1), book.getValue(6), 6)   // ch 6 not counted yet
        assertEquals(b.seen, nb.seen); assertEquals(b.chapters, nb.chapters)
        assertEquals(mapOf("Петуния" to 7), diff(b, nb))
        val g = readTo(5, RoleColors.withPinned(BookRoles(), mapOf("Гарри" to 5)))    // pinned before the first count
        assertTrue("Гарри" !in g.color)
        val g2 = RoleColors.withPinned(g, emptyMap(), r5, 5)
        assertEquals(5, g2.color["Гарри"]); assertEquals(mapOf("Гарри" to 5), show(g2, 5, "Гарри"))
    }

    @Test fun oldBookRolesWithoutPinned() {
        val old = json.decodeFromString(BookRoles.serializer(), """{"color":{"Гарри":2},"seen":{"Гарри":5},"chapters":[1]}""")
        assertTrue(old.pinned.isEmpty()); assertEquals(mapOf("Гарри" to 2), old.color)
        val st = AppState(roleColors = mapOf("k" to old.copy(pinned = mapOf("Драко" to 3))))
        assertEquals(st, json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st)))
    }
}
