package io.github.notoboto.unison.data

/**
 * A character keeps one reader colour for the whole book (ROLE_COLORS_GROOM.md §1, «Решение»):
 * - a newcomer gets a colour nobody present in the chapter shows; among those, the one whose most frequent owner was met
 *   least often (then the smaller sum of its owners' meetings, then the smaller number);
 * - two present with one colour: the one met more often in the book keeps it, the other takes a free colour for good;
 * - no free colour (more speakers than colours): the shared colour stays; a newcomer then takes the colour fewest of the
 *   chapter's speakers show (then the same order as above, then fewer owners in the book), so they share evenly;
 * - a colour the user chose (BookRoles.pinned, §6) is shown in every chapter and never gives way; the others avoid it.
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
        val newcomers = names.filter { it !in br.color && it !in br.pinned }.distinct()
        if (!first && newcomers.isEmpty()) return br
        val c = resolve(br, roles, size)
        return if (first) br.copy(                           // copy, not BookRoles(...): keeps pinned
            color = br.color + c.filterKeys { it !in br.pinned },   // newcomers and the ones that gave way, for good; the user's colours stay in pinned only
            seen = br.seen + names.distinct().associateWith { (br.seen[it] ?: 0) + 1 },
            chapters = br.chapters + n,
        ) else br.copy(color = br.color + newcomers.associateWith { c.getValue(it) })   // re-read chapter (re-voiced): newcomers only
    }

    /** [br] with the user's colours [pinned] (name -> colour; a name left out = automatic). The automatic owners of a newly
     *  chosen colour give it up in the whole book at once, seniors first (one shown in the chapter on screen [roles] keeps
     *  the colour it shows there); when that chapter [n] is already counted, the ones giving way in it are written too, as
     *  [learn] does on a first opening. seen/chapters are never touched. The same instance when nothing changes. */
    fun withPinned(br: BookRoles, pinned: Map<String, Int>, roles: List<String> = emptyList(), n: Int = -1, size: Int = SIZE): BookRoles {
        val p = pinned.mapValues { Math.floorMod(it.value, size) }
        if (p == br.pinned) return br
        var b = br.copy(pinned = p)
        if (roles.size > 1 && n in b.chapters) {            // the chapter on screen: who gives way there, for good
            val moved = resolve(b, roles, size).filter { (nm, col) -> nm !in p && b.color[nm] != col }
            if (moved.isNotEmpty()) b = b.copy(color = b.color + moved)
        }
        // the automatic owners of a newly chosen colour give way to it in the whole book now (§6, 6.9.9)
        val chosen = p.filter { (nm, k) -> br.pinned[nm] != k }.values.toSet()
        val victims = b.color.filter { (nm, col) -> nm !in p && Math.floorMod(col, size) in chosen }.keys
            .sortedWith(compareBy({ -(b.seen[it] ?: 0) }, { it }))
        if (victims.isEmpty()) return b
        val shown = if (roles.size > 1) resolve(b, roles, size) else emptyMap()
        // not the colours shown in the chapter on screen, unless it shows them all (a crowd)
        val taken = (p.values + shown.values).toHashSet().let { if (it.size >= size) p.values.toHashSet() else it }
        val color = b.color.toMutableMap()                  // LinkedHashMap: the order is kept
        for (v in victims) color[v] = shown[v]?.takeIf { it !in chosen } ?: pick(b.copy(color = color), emptyMap(), taken, size)
        return b.copy(color = color)
    }

    /** Who shows colour k in the book, for the dialog: pinned to k, then automatic with k (a pinned one not by its old
     *  colour), most chapters first, without [but]; at most [top] per colour. */
    fun owners(br: BookRoles, but: String, top: Int = 2, size: Int = SIZE): Map<Int, List<String>> =
        (br.pinned.entries + br.color.entries.filter { it.key !in br.pinned }).filter { it.key != but }
            .groupBy({ Math.floorMod(it.value, size) }, { it.key })
            .mapValues { (_, v) -> v.sortedWith(compareBy({ -(br.seen[it] ?: 0) }, { it })).take(top) }

    /** name -> colour for the chapter's speakers: newcomers placed, collisions settled. */
    private fun resolve(br: BookRoles, roles: List<String>, size: Int): Map<String, Int> {
        val present = roles.drop(1).distinct()               // in the order they first speak
        val c = HashMap<String, Int>()
        val pin = br.pinned
        for (nm in present) (pin[nm] ?: br.color[nm])?.let { c[nm] = Math.floorMod(it, size) }
        // 1) newcomers; the colours of those present (the user's included) count as taken
        val taken = c.values.toHashSet()
        for (nm in present) if (nm !in c) pick(br, c, taken, size).let { c[nm] = it; taken += it }
        // 2) collisions: the user's colours first, they never give way (two pinned alike both keep it: the user's choice);
        //    then seniority = chapters met in, then who spoke first in this chapter
        val order = present.withIndex().sortedWith(compareBy({ it.value !in pin }, { -(br.seen[it.value] ?: 0) }, { it.index })).map { it.value }
        val used = HashSet<Int>()
        order.forEachIndexed { i, nm ->
            var col = c.getValue(nm)
            if (col in used && nm !in pin) {
                // free = shown by nobody present: neither the ones placed nor the ones still to come
                val busy = used + order.subList(i + 1, order.size).map { c.getValue(it) }
                if (busy.size < size) { col = pick(br, c, busy, size); c[nm] = col }
            }
            used += col
        }
        return c
    }

    /** The least «owned» colour outside [taken]. Owners = the whole book, with this chapter's placements [now] over the
     *  stored ones. When none is free (more speakers than colours) the least crowded colour in this chapter first, so a
     *  crowd of newcomers spreads over all colours instead of piling on one (a book's first chapter: everyone seen 0). */
    private fun pick(br: BookRoles, now: Map<String, Int>, taken: Set<Int>, size: Int): Int {
        val maxSeen = IntArray(size); val sumSeen = IntArray(size); val owners = IntArray(size)
        fun own(nm: String, col: Int) { val s = br.seen[nm] ?: 0; val k = Math.floorMod(col, size); if (s > maxSeen[k]) maxSeen[k] = s; sumSeen[k] += s; owners[k]++ }
        // a pinned character owns its chosen colour and keeps its automatic one reserved, so «automatic» brings it back
        br.color.forEach { (nm, col) -> if (nm !in now || (nm in br.pinned && Math.floorMod(col, size) != now[nm])) own(nm, col) }
        br.pinned.forEach { (nm, col) -> if (nm !in now) own(nm, col) }
        now.forEach { (nm, col) -> own(nm, col) }
        val free = (0 until size).filter { it !in taken }
        if (free.isNotEmpty()) return free.minWith(compareBy({ maxSeen[it] }, { sumSeen[it] }, { it }))
        val here = IntArray(size).also { h -> now.values.forEach { h[Math.floorMod(it, size)]++ } }
        return (0 until size).minWith(compareBy({ here[it] }, { maxSeen[it] }, { sumSeen[it] }, { owners[it] }, { it }))
    }
}
