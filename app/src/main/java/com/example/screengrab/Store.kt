package com.example.screengrab

import android.content.Context
import android.graphics.Rect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("screengrab", Context.MODE_PRIVATE)

    fun isMd(c: Context): Boolean = p(c).getString("fmt", "md") == "md"
    fun setMd(c: Context, v: Boolean) {
        p(c).edit().putString("fmt", if (v) "md" else "txt").apply()
    }

    /** true = the user scrolls UP (older content appears on top), e.g. chat history. */
    fun isUp(c: Context): Boolean = p(c).getBoolean("up", false)
    fun setUp(c: Context, v: Boolean) {
        p(c).edit().putBoolean("up", v).apply()
    }

    fun controls(c: Context): Boolean = p(c).getBoolean("controls", true)
    fun setControls(c: Context, v: Boolean) {
        p(c).edit().putBoolean("controls", v).apply()
    }

    fun loadRegion(c: Context): Rect? {
        val s = p(c)
        if (!s.contains("rl")) return null
        return Rect(s.getInt("rl", 0), s.getInt("rt", 0), s.getInt("rr", 0), s.getInt("rb", 0))
    }

    fun saveRegion(c: Context, r: Rect?) {
        val e = p(c).edit()
        if (r == null) {
            e.remove("rl").remove("rt").remove("rr").remove("rb")
        } else {
            e.putInt("rl", r.left).putInt("rt", r.top).putInt("rr", r.right).putInt("rb", r.bottom)
        }
        e.apply()
    }
}

object SessionStore {

    fun dir(c: Context): File {
        val d = File(c.filesDir, "sessions")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(c: Context): List<File> =
        (dir(c).listFiles() ?: emptyArray<File>())
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }

    /** Writes one finished session. Returns the file, or null if nothing was captured. */
    fun save(
        c: Context,
        lines: List<String>,
        apps: Collection<String>,
        startedAt: Long,
        md: Boolean
    ): File? {
        if (lines.isEmpty()) return null
        val ext = if (md) "md" else "txt"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        var f = File(dir(c), "capture_$stamp.$ext")
        var i = 2
        while (f.exists()) {
            f = File(dir(c), "capture_${stamp}_$i.$ext")
            i++
        }
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val sb = StringBuilder()
        if (md) {
            sb.append("# Screen capture\n\n")
            sb.append("- Started: ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAt)))
                .append('\n')
            sb.append("- Duration: ").append(secs / 60).append("m ").append(secs % 60).append("s\n")
            if (apps.isNotEmpty()) sb.append("- Apps: ").append(apps.joinToString(", ")).append('\n')
            sb.append("- Lines: ").append(lines.size).append("\n\n---\n\n")
            sb.append(lines.joinToString("\n\n"))
            sb.append('\n')
        } else {
            sb.append(lines.joinToString("\n"))
            sb.append('\n')
        }
        f.writeText(sb.toString())
        return f
    }
}
