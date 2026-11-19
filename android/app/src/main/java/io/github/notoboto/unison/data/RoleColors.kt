package io.github.notoboto.unison.data

/**
 * A character keeps one reader colour for the whole book (ROLE_COLORS_GROOM.md §1, «Решение»):
 * - a newcomer gets a colour nobody present in the chapter shows; among those, the one whose most frequent owner was met
 *   least often (then the smaller sum of its owners' meetings, then the smaller number);
 * - two present with one colour: the one met more often in the book keeps it, the other takes a free colour for good;
 * - no free colour (more speakers than colours): the shared colour stays.
 * roles = the chapter's "roles" list, index 0 = narrator (never coloured).
 */
object RoleColors {
    /** Colours per theme in ui/Theme.kt. */
    const val SIZE = 12

    /** Colour number for each role index of the chapter, -1 for the narrator. Writes nothing: newcomers and collisions
     *  are settled on the fly, exactly as [learn] would settle them. */
    fun colorsFor(br: BookRoles?, roles: List<String>, size: Int = SIZE): IntArray {
        val c = resolve(br ?: BookRoles(), roles, size)
        return IntArray(roles.size) { i -> if (i == 0) -1 else c.getValue(roles[i]) }
    }

    /** [br] after chapter [n] was opened. The same instance when nothing changes, so nothing is written. */
    fun learn(br: BookRoles, roles: List<String>, n: Int, size: Int = SIZE): BookRoles {
        if (roles.size <= 1) return br                       // narrator only: local and not-yet-voiced chapters
        val names = roles.drop(1)
        val first = n !in br.chapters
        val newcomers = names.filter { it !in br.color }.distinct()
        if (!first && newcomers.isEmpty()) return br
        val c = resolve(br, roles, size)
        return if (first) BookRoles(
            color = br.color + c,                            // newcomers and the ones that gave way, for good
            seen = br.seen + names.distinct().associateWith { (br.seen[it] ?: 0) + 1 },
            chapters = br.chapters + n,
        ) else br.copy(color = br.color + newcomers.associateWith { c.getValue(it) })   // re-read chapter (re-voiced): newcomers only
    }

    /** name -> colour for the chapter's speakers: newcomers placed, collisions settled. */
    private fun resolve(br: BookRoles, roles: List<String>, size: Int): Map<String, Int> {
        val present = roles.drop(1).distinct()               // in the order they first speak
        val c = HashMap<String, Int>()
        for (nm in present) br.color[nm]?.let { c[nm] = Math.floorMod(it, size) }
        // 1) newcomers; the colours of those present count as taken
        val taken = c.values.toHashSet()
        for (nm in present) if (nm !in c) pick(br, c, taken, size).let { c[nm] = it; taken += it }
        // 2) collisions: seniority = chapters met in, then who spoke first in this chapter
        val order = present.withIndex().sortedWith(compareBy({ -(br.seen[it.value] ?: 0) }, { it.index })).map { it.value }
        val used = HashSet<Int>()
        order.forEachIndexed { i, nm ->
            var col = c.getValue(nm)
            if (col in used) {
                // free = shown by nobody present: neither the ones placed nor the ones still to come
                val busy = used + order.subList(i + 1, order.size).map { c.getValue(it) }
                if (busy.size < size) { col = pick(br, c, busy, size); c[nm] = col }
            }
            used += col
        }
        return c
    }

    /** The least «owned» colour outside [taken] (all colours when none is free). Owners = the whole book, with this
     *  chapter's placements [now] over the stored ones. */
    private fun pick(br: BookRoles, now: Map<String, Int>, taken: Set<Int>, size: Int): Int {
        val maxSeen = IntArray(size); val sumSeen = IntArray(size)
        fun own(nm: String, col: Int) { val s = br.seen[nm] ?: 0; val k = Math.floorMod(col, size); if (s > maxSeen[k]) maxSeen[k] = s; sumSeen[k] += s }
        br.color.forEach { (nm, col) -> if (nm !in now) own(nm, col) }
        now.forEach { (nm, col) -> own(nm, col) }
        val free = (0 until size).filter { it !in taken }.ifEmpty { (0 until size).toList() }
        return free.minWith(compareBy({ maxSeen[it] }, { sumSeen[it] }, { it }))
    }
}
