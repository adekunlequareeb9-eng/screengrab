package com.example.screengrab

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the text of whatever app is on screen straight from the accessibility tree
 * (no screenshots, no OCR), so a full screen takes a few tens of milliseconds.
 */
class CaptureService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureService? = null

        private const val GREEN = 0xFF2E7D32.toInt()
        private const val RED = 0xFFE53935.toInt()
        private const val GRAY = 0xFF555555.toInt()
    }

    private class Item(val r: Rect, val s: String)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val scheduled = AtomicBoolean(false)

    private lateinit var wm: WindowManager
    private var controls: View? = null
    private var recBtn: TextView? = null
    private var border: BorderView? = null
    private var picker: View? = null

    @Volatile private var recording = false
    @Volatile private var region: Rect? = null
    @Volatile private var up = false
    private var merger = Merger()
    @Volatile private var junkWords: List<String> = emptyList()
    private val apps = LinkedHashSet<String>()
    private var startedAt = 0L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        region = Prefs.loadRegion(this)
        applyControlsVisibility()
        refreshBorder()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen rotated: saved pixel coordinates no longer fit, so reset the area.
        main.post {
            if (region != null) {
                region = null
                Prefs.saveRegion(this, null)
                refreshBorder()
            }
            removeControls()
            if (Prefs.controls(this)) showControls()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (recording) {
            recording = false
            val lines = synchronized(lock) { ArrayList(merger.lines) }
            SessionStore.save(
                this,
                if (up) lines.reversed() else lines,
                synchronized(lock) { ArrayList(apps) },
                startedAt,
                Prefs.isMd(this)
            )
        }
        closePicker()
        removeControls()
        removeBorder()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!recording || e == null) return
        if (e.packageName?.toString() == packageName) return
        schedule()
    }

    /** At most one capture waits at a time, so a fling can't pile up work. */
    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) {
            main.postDelayed({ worker.execute { captureNow(false) } }, 40)
        }
    }

    // ---------------------------------------------------------------- capture

    private fun captureNow(force: Boolean) {
        scheduled.set(false)
        if (!recording && !force) return
        val root: AccessibilityNodeInfo = (try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }) ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return

        val items = ArrayList<Item>(64)
        collect(root, region, items, 0)
        if (items.isEmpty()) return

        var lines = buildLines(items)
        if (up) lines = lines.reversed()
        synchronized(lock) {
            merger.add(lines)
            if (pkg.isNotEmpty()) apps.add(pkg)
        }
        main.post { updateButton() }
    }

    private fun collect(n: AccessibilityNodeInfo?, reg: Rect?, out: MutableList<Item>, depth: Int) {
        if (n == null || depth > 60) return
        if (!n.isVisibleToUser) return
        val b = Rect()
        n.getBoundsInScreen(b)
        val hasSize = b.width() > 0 && b.height() > 0
        // Skip whole branches that sit completely outside the chosen area (big speed-up).
        if (reg != null && hasSize && !Rect.intersects(b, reg)) return

        if (!n.isPassword && hasSize) {
            var s = n.text?.toString()?.trim() ?: ""
            if (s.isEmpty() && n.childCount == 0) {
                s = n.contentDescription?.toString()?.trim() ?: ""
            }
            // Ads and buttons are dropped one node at a time, BEFORE nodes on the same row are joined,
            // so a floating ad can never take a story line down with it.
            if (s.isNotEmpty() && inRegion(b, reg) && !Junk.isJunk(s, junkWords)) out.add(Item(b, s))
        }
        for (i in 0 until n.childCount) {
            collect(n.getChild(i), reg, out, depth + 1)
        }
    }

    private fun inRegion(b: Rect, reg: Rect?): Boolean {
        if (reg == null) return true
        val i = Rect()
        if (!i.setIntersect(b, reg)) return false
        val inter = i.width().toDouble() * i.height()
        val area = b.width().toDouble() * b.height()
        return inter / area >= 0.3 || reg.contains(b.centerX(), b.centerY())
    }

    /** Sorts nodes top-to-bottom and joins the ones on the same row left-to-right. */
    private fun buildLines(items: List<Item>): List<String> {
        val sorted = items.sortedWith(compareBy({ it.r.top }, { it.r.left }))
        val rows = ArrayList<MutableList<Item>>()
        var rowCy = 0
        var rowH = 0
        for (item in sorted) {
            val cy = item.r.centerY()
            if (rows.isNotEmpty() && abs(cy - rowCy) <= min(rowH, item.r.height()) / 2) {
                rows[rows.size - 1].add(item)
            } else {
                rows.add(mutableListOf(item))
                rowCy = cy
                rowH = item.r.height()
            }
        }
        return rows.map { row ->
            row.sortedBy { it.r.left }.joinToString(" ") { it.s }
        }
    }

    // ---------------------------------------------------------------- session

    private fun toggle() {
        if (recording) stopSession() else startSession()
    }

    private fun startSession() {
        up = Prefs.isUp(this)
        junkWords = Junk.parse(Prefs.junk(this))
        synchronized(lock) {
            merger = Merger()
            apps.clear()
        }
        startedAt = System.currentTimeMillis()
        recording = true
        updateButton()
        // Grab what is on screen right now, no waiting for a scroll event.
        worker.execute { captureNow(false) }
    }

    private fun stopSession() {
        recording = false
        updateButton()
        worker.execute {
            captureNow(true) // one last snapshot
            val lines = synchronized(lock) { ArrayList(merger.lines) }
            val used = synchronized(lock) { ArrayList(apps) }
            val f = SessionStore.save(
                this,
                if (up) lines.reversed() else lines,
                used,
                startedAt,
                Prefs.isMd(this)
            )
            main.post {
                updateButton()
                val msg = if (f == null) "Nothing captured" else "Saved ${lines.size} lines: ${f.name}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- floating controls

    fun applyControlsVisibility() {
        if (Prefs.controls(this)) showControls() else removeControls()
    }

    private fun chip(text: String, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 14f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(18).toFloat()
        t.background = bg
        return t
    }

    private fun showControls() {
        if (controls != null) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6), dp(6), dp(6), dp(6))
        val rowBg = GradientDrawable()
        rowBg.setColor(0xE6222222.toInt())
        rowBg.cornerRadius = dp(24).toFloat()
        row.background = rowBg

        val handle = TextView(this)
        handle.text = "⋮⋮"
        handle.setTextColor(0xFFBBBBBB.toInt())
        handle.textSize = 16f
        handle.gravity = Gravity.CENTER
        handle.setPadding(dp(10), dp(8), dp(10), dp(8))

        val rec = chip("● REC", GREEN)
        rec.setOnClickListener { toggle() }
        val area = chip("▭ AREA", GRAY)
        area.setOnClickListener { openPicker() }

        row.addView(handle)
        row.addView(rec, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(area, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(220)

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    try {
                        wm.updateViewLayout(row, lp)
                    } catch (ex: Exception) {
                    }
                }
            }
            true
        }

        try {
            wm.addView(row, lp)
            controls = row
            recBtn = rec
            updateButton()
        } catch (ex: Exception) {
            Toast.makeText(this, "Could not show controls", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeControls() {
        controls?.let {
            try {
                wm.removeView(it)
            } catch (ex: Exception) {
            }
        }
        controls = null
        recBtn = null
    }

    private fun updateButton() {
        val b = recBtn ?: return
        val bg = b.background as GradientDrawable
        if (recording) {
            val n = synchronized(lock) { merger.lines.size }
            b.text = "■ STOP · $n"
            bg.setColor(RED)
        } else {
            b.text = "● REC"
            bg.setColor(GREEN)
        }
        border?.invalidate()
    }

    // ---------------------------------------------------------------- area picker

    private fun setRegion(r: Rect?) {
        region = r
        Prefs.saveRegion(this, r)
        refreshBorder()
        val msg = if (r == null) "Recording the full screen" else "Area set"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun openPicker() {
        if (picker != null) return
        val root = FrameLayout(this)
        val pv = PickerView(this) { r ->
            setRegion(r)
            closePicker()
        }
        root.addView(pv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val full = chip("FULL SCREEN", GRAY)
        full.setOnClickListener {
            setRegion(null)
            closePicker()
        }
        val cancel = chip("CANCEL", RED)
        cancel.setOnClickListener { closePicker() }
        bar.addView(full)
        bar.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        val barLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        barLp.topMargin = dp(100)
        root.addView(bar, barLp)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, lp)
            picker = root
        } catch (ex: Exception) {
            Toast.makeText(this, "Could not open the area picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePicker() {
        picker?.let {
            try {
                wm.removeView(it)
            } catch (ex: Exception) {
            }
        }
        picker = null
    }

    private fun refreshBorder() {
        removeBorder()
        if (region == null) return
        val v = BorderView(this)
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(v, lp)
            border = v
        } catch (ex: Exception) {
        }
        // keep the control bar above the border
        if (controls != null) {
            removeControls()
            showControls()
        }
    }

    private fun removeBorder() {
        border?.let {
            try {
                wm.removeView(it)
            } catch (ex: Exception) {
            }
        }
        border = null
    }

    /** Thin outline showing which part of the screen is being recorded. Touches pass through. */
    private inner class BorderView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2).toFloat()
        }

        override fun onDraw(c: Canvas) {
            val r = region ?: return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            p.color = if (recording) RED else GREEN
            c.drawRect(
                (r.left - loc[0]).toFloat(),
                (r.top - loc[1]).toFloat(),
                (r.right - loc[0]).toFloat(),
                (r.bottom - loc[1]).toFloat(),
                p
            )
        }
    }

    /** Full-screen layer where the user drags a box over the part of the screen to record. */
    private inner class PickerView(ctx: Context, val onDone: (Rect) -> Unit) : View(ctx) {
        private var x0 = 0f
        private var y0 = 0f
        private var x1 = 0f
        private var y1 = 0f
        private var has = false
        private val dim = Paint()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            dim.color = 0x99000000.toInt()
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = dp(2).toFloat()
            stroke.color = GREEN
            txt.color = 0xFFFFFFFF.toInt()
            txt.textSize = dp(16).toFloat()
            txt.textAlign = Paint.Align.CENTER
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX
                    y0 = e.rawY
                    x1 = x0
                    y1 = y0
                    has = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    x1 = e.rawX
                    y1 = e.rawY
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    x1 = e.rawX
                    y1 = e.rawY
                    val r = Rect(
                        min(x0, x1).toInt(), min(y0, y1).toInt(),
                        max(x0, x1).toInt(), max(y0, y1).toInt()
                    )
                    if (r.width() < dp(40) || r.height() < dp(40)) {
                        has = false
                        invalidate()
                        Toast.makeText(context, "Too small, drag a bigger box", Toast.LENGTH_SHORT).show()
                    } else {
                        onDone(r)
                    }
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (!has) {
                c.drawRect(0f, 0f, w, h, dim)
            } else {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val l = min(x0, x1) - loc[0]
                val t = min(y0, y1) - loc[1]
                val r = max(x0, x1) - loc[0]
                val b = max(y0, y1) - loc[1]
                c.drawRect(0f, 0f, w, t, dim)
                c.drawRect(0f, b, w, h, dim)
                c.drawRect(0f, t, l, b, dim)
                c.drawRect(r, t, w, b, dim)
                c.drawRect(l, t, r, b, stroke)
            }
            c.drawText("Drag a box over the text you want to record", w / 2f, dp(70).toFloat(), txt)
        }
    }
}
