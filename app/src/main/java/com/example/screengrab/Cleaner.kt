package com.example.screengrab

/** Lines (ads, buttons) that are never worth saving. Matching is case-insensitive. */
object Junk {

    fun parse(s: String): List<String> =
        s.split('\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** The user's list while the service is running, otherwise the defaults. */
    fun words(): List<String> {
        val c = CaptureService.instance
        return parse(if (c != null) Prefs.junk(c) else Prefs.DEFAULT_JUNK)
    }

    // Gift buttons and prices that float over the story: "Flower", "Like", "Free", "₦200.00".
    private val builtin = setOf("flower", "like", "free", "gift", "gifts", "coffee", "champagne", "luxury car")
    private val price = Regex("""^[₦$€£]\s?[\d,.]+$""")

    /** A line is junk if it equals a phrase, or (for phrases of 12+ characters) contains it. */
    fun isJunk(line: String, words: List<String>): Boolean {
        val t = line.trim().lowercase()
        if (t.isEmpty()) return true
        if (t in builtin || price.matches(t)) return true
        for (w in words) {
            if (t == w) return true
            if (w.length >= 12 && t.contains(w)) return true
        }
        return false
    }
}

/**
 * Turns raw captured chunks into readable paragraphs:
 *  - " , " (a line wrap some sites insert) becomes a plain space
 *  - ".," "!," "?," and a closing quote followed by a comma mark a paragraph break
 *  - a chunk that stops in the middle of a sentence is joined to the next one
 *  - [...] gap markers are dropped (they are mostly ad breaks); the count goes in the md header
 */
object Cleaner {

    const val CUT = "[possible cut: the end of the chapter above may be missing]"

    private val wrap = Regex(" , ")
    private val para = Regex("""([.!?]["”’]?), """)
    private val chapter = Regex("""^Chapter \d+\b""")

    private fun endsSentence(s: String): Boolean {
        val t = s.trimEnd()
        if (t.isEmpty()) return true
        val c = t[t.length - 1]
        return c == '.' || c == '!' || c == '?' || c == '"' || c == '”' ||
            c == '’' || c == '…' || c == ':' || c == ']' || c == '*'
    }

    fun clean(chunks: List<String>): List<String> {
        val out = ArrayList<String>()
        var open = false
        for (chunk in chunks) {
            if (chunk == Merger.GAP) continue
            var s = wrap.replace(chunk, " ")
            s = para.replace(s, "\$1\n")
            val parts = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            for ((i, p) in parts.withIndex()) {
                if (i == 0 && open && out.isNotEmpty() && !chapter.containsMatchIn(p)) {
                    out[out.size - 1] = out[out.size - 1] + " " + p
                } else {
                    // A new chapter starts while the last sentence was unfinished: text was missed.
                    if (i == 0 && open && out.isNotEmpty()) out.add(CUT)
                    out.add(p)
                }
            }
            if (parts.isNotEmpty()) open = !endsSentence(out[out.size - 1])
        }
        return out
    }
}
