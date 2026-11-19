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
 *  ROLE_COLORS_GROOM.md §5 A.4): invariants in three reading orders plus golden values of the Python model. */
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

    /** Reads the book in [order] the way the reader does (learn, then show) and checks the per-chapter invariants. */
    private fun read(name: String, order: List<Int>): Read {
        var br = BookRoles(); var collisions = 0; var changes = 0
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
            if (speakers <= RoleColors.SIZE) assertEquals("$name ch$n: collision", speakers, after.drop(1).distinct().size)  // invariant 1
            for (i in 1 until r.size) { prev.put(r[i], after[i])?.let { if (it != after[i]) changes++ } }
        }
        return Read(br, collisions, changes)
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
        val golden = mapOf("seq" to (18 to 50), "reversed" to (18 to 61), "stride" to (18 to 57))
        for ((name, order) in orders) {
            val a = read(name, order)
            assertEquals("$name: chapters with a collision, colour changes", golden[name], a.collisions to a.changes)
        }
        val br = read("seq", seq).br
        assertEquals(mapOf("Гарри" to 2, "Квиррелл" to 0, "Дамблдор" to 6, "Гермиона" to 7, "МакГонагалл" to 4, "Драко" to 9,
            "Безымянный_м" to 11, "Толпа" to 10, "Снейп" to 3, "Безымянный_ж" to 1),
            br.seen.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(10).associate { it.key to br.color.getValue(it.key) })
        val ch1 = book.getValue(1)                         // Рассказчик, Майкл, Петуния, Гарри, Фигг: as the old (role - 1) % n
        assertEquals(listOf(-1, 0, 1, 2, 3), RoleColors.colorsFor(RoleColors.learn(BookRoles(), ch1, 1), ch1).toList())
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
}
