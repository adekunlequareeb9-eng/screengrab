package com.example.screengrab

/**
 * Smart resume logic. Every screen snapshot is a list of text lines (top to bottom).
 * add() works out which lines are already saved (the screen overlaps the end of what
 * we have, because the user scrolled a little) and appends only the new ones.
 *
 * 1. Longest "end of saved text == start of this screen" overlap wins (normal scrolling).
 * 2. Otherwise find an anchor line (fixed headers, partial overlaps) and append what follows it.
 * 3. No overlap at all (fast fling, jumped content): append everything after a [...] gap marker.
 */
class Merger(private val window: Int = 400) {

    companion object {
        const val GAP = "[...]"
    }

    val lines = ArrayList<String>()

    // Ads and buttons to ignore, and long lines already saved (prose never repeats a 60+ character line).
    private val junk = Junk.words()
    private val seen = HashSet<String>()

    private fun isLong(s: String) = s.length >= 60

    private fun append(rest: List<String>): Int {
        var r = rest
        var added = 0
        // A line that was cut off at the edge last time and is complete now: replace it.
        if (r.isNotEmpty() && lines.isNotEmpty()) {
            val last = lines[lines.size - 1]
            val first = r[0]
            if (last.length >= 20 && first.length > last.length && first.startsWith(last)) {
                lines[lines.size - 1] = first
                if (isLong(first)) seen.add(first)
                r = r.drop(1)
                added++
            }
        }
        val fresh = ArrayList<String>()
        for (s in r) {
            if (isLong(s) && !seen.add(s)) continue
            fresh.add(s)
        }
        lines.addAll(fresh)
        return added + fresh.size
    }

    private fun tailEquals(cur: List<String>, k: Int): Boolean {
        val n = lines.size
        for (i in 0 until k) {
            if (lines[n - k + i] != cur[i]) return false
        }
        return true
    }

    private fun anchorOk(cur: List<String>, a: Int, p: Int, from: Int): Boolean {
        var r = 0
        while (a - r >= 0 && p - r >= from && cur[a - r] == lines[p - r]) r++
        if (r >= 2) return true
        val s = cur[a]
        if (s.length < 25) return false
        var count = 0
        for (i in from until lines.size) {
            if (lines[i] == s) count++
        }
        return count == 1
    }

    /** Returns how many lines were added. */
    fun add(raw: List<String>): Int {
        val cur = raw.filter { !Junk.isJunk(it, junk) }
        if (cur.isEmpty()) return 0
        if (lines.isEmpty()) {
            return append(cur)
        }
        val n = lines.size

        // 1) overlap between the end of saved text and the start of the screen
        var k = minOf(cur.size, n)
        while (k >= 1) {
            if (tailEquals(cur, k) && (k >= 2 || k == cur.size || cur[0].length >= 12)) {
                return append(cur.subList(k, cur.size))
            }
            k--
        }

        // 2) anchor search, from the bottom of the screen upwards
        val from = maxOf(0, n - window)
        var a = cur.size - 1
        while (a >= 0) {
            var p = n - 1
            while (p >= from) {
                if (lines[p] == cur[a] && anchorOk(cur, a, p, from)) {
                    var j = 0
                    while (a + 1 + j < cur.size && p + 1 + j < n && cur[a + 1 + j] == lines[p + 1 + j]) j++
                    return append(cur.subList(a + 1 + j, cur.size))
                }
                p--
            }
            a--
        }

        // 3) nothing lines up. Long lines we already have are skipped (re-scrolled or an ad broke the overlap).
        val fresh = cur.filter { !(isLong(it) && it in seen) }
        if (fresh.isEmpty()) return 0
        // Only mark a gap when none of this screen was seen before.
        if (fresh.size == cur.size) lines.add(GAP)
        return append(fresh) + 1
    }
}
