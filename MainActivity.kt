package com.example.screengrab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var fmtBtn: Button
    private lateinit var dirBtn: Button
    private lateinit var ctlBtn: Button
    private lateinit var list: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setAllCaps(false)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun label(text: String, size: Float, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        root.addView(label("Screen Grab", 26f, true))

        status = label("", 15f)
        status.setPadding(0, dp(8), 0, dp(8))
        root.addView(status)

        root.addView(btn("1. Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("If the switch is greyed out: App info") {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        })

        val help = label(
            "Turn the service on, go to any app, tap REC on the floating bar. " +
                "Drag the ⋮⋮ handle to move the bar. AREA lets you draw the box to record. " +
                "Scroll as you like; tap STOP when done and the session is saved below.",
            13f
        )
        help.setPadding(0, dp(8), 0, dp(8))
        root.addView(help)

        fmtBtn = btn("") {
            Prefs.setMd(this, !Prefs.isMd(this))
            refresh()
        }
        dirBtn = btn("") {
            Prefs.setUp(this, !Prefs.isUp(this))
            refresh()
        }
        ctlBtn = btn("") {
            Prefs.setControls(this, !Prefs.controls(this))
            CaptureService.instance?.applyControlsVisibility()
            refresh()
        }
        root.addView(fmtBtn)
        root.addView(dirBtn)
        root.addView(ctlBtn)

        val h = label("Saved sessions", 18f, true)
        h.setPadding(0, dp(16), 0, dp(4))
        root.addView(h)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == packageName && c.className.endsWith("CaptureService")
        }
    }

    private fun refresh() {
        status.text = if (serviceOn())
            "● Service is ON. Use the floating REC button over any app."
        else
            "○ Service is OFF. In Accessibility settings open Installed apps, tap Screen Grab, switch it on."
        fmtBtn.text = "File format: ." + (if (Prefs.isMd(this)) "md" else "txt") + "  (tap to switch)"
        dirBtn.text = "Scroll direction: " +
            (if (Prefs.isUp(this)) "UP (older content on top)" else "DOWN (normal reading)") +
            "  (tap to switch)"
        ctlBtn.text = "Floating controls: " + (if (Prefs.controls(this)) "shown" else "hidden") +
            "  (tap to switch)"

        list.removeAllViews()
        val files = SessionStore.list(this)
        if (files.isEmpty()) {
            val t = label("Nothing yet. Your captures will appear here.", 14f)
            t.setPadding(0, dp(8), 0, 0)
            list.addView(t)
        }
        for (f in files) {
            val row = TextView(this)
            row.text = f.name + "\n" + sizeText(f)
            row.textSize = 15f
            row.setPadding(dp(8), dp(12), dp(8), dp(12))
            row.setBackgroundColor(0x11000000)
            row.setOnClickListener { openSession(f) }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            list.addView(row, lp)
        }
    }

    private fun sizeText(f: File): String {
        val kb = f.length() / 1024.0
        return if (kb < 1) "${f.length()} bytes" else String.format("%.1f KB", kb)
    }

    private fun openSession(f: File) {
        val text = try {
            f.readText()
        } catch (e: Exception) {
            ""
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(8), dp(12), 0)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val dlg = AlertDialog.Builder(this).setTitle(f.name).setView(box)
            .setPositiveButton("Close", null).create()

        fun small(l: String, click: () -> Unit): Button {
            val b = btn(l, click)
            b.textSize = 12f
            b.minWidth = 0
            b.minimumWidth = 0
            return b
        }
        actions.addView(small("Copy") { copy(f.name, text) })
        actions.addView(small("Share") { share(f.name, text) })
        actions.addView(small("Export") { export(f) })
        actions.addView(small("Delete") {
            AlertDialog.Builder(this).setTitle("Delete this session?")
                .setPositiveButton("Delete") { _, _ ->
                    f.delete()
                    dlg.dismiss()
                    refresh()
                }
                .setNegativeButton("Cancel", null).show()
        })
        box.addView(actions)

        val shown = if (text.length > 60000) text.substring(0, 60000) + "\n\n[... preview cut, use Copy for everything]" else text
        val tv = TextView(this)
        tv.text = shown
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        val sv = ScrollView(this)
        sv.addView(tv)
        box.addView(
            sv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.5).toInt()
            )
        )
        dlg.show()
    }

    private fun copy(name: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(name, text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Too big to copy, use Export", Toast.LENGTH_LONG).show()
        }
    }

    private fun share(name: String, text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_SUBJECT, name)
            i.putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "Too big to share, use Export", Toast.LENGTH_LONG).show()
        }
    }

    /** Copies the file to Downloads/ScreenGrab so other apps and your file manager can see it. */
    private fun export(f: File) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Export needs Android 10 or newer. Use Copy or Share.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            v.put(MediaStore.Downloads.MIME_TYPE, if (f.extension == "md") "text/markdown" else "text/plain")
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScreenGrab")
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            if (uri == null) throw IllegalStateException("no uri")
            contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
            Toast.makeText(this, "Saved to Downloads/ScreenGrab", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: " + (e.message ?: "unknown"), Toast.LENGTH_LONG).show()
        }
    }
}
