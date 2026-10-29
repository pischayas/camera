package com.example.manualcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs

/** Viewfinder HUD: guides, grid, safe area, level, audio meter, histogram / scopes, effects layer. */
class OverlayView(ctx: Context) : View(ctx) {
    var guide = 0f
    var grid = 0
    var safe = false
    var level = true
    var roll = 0f
    var audio = 0f
    var showAudio = true
    var scopeMode = 0
    var hist: IntArray? = null
    var fx: Bitmap? = null
    var scope: Bitmap? = null
    var clean = false
    var recording = false
    var focusX = -1f
    var focusY = -1f
    var focusT = 0L

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)

    override fun onDraw(c: Canvas) {
        if (clean) return
        val w = width.toFloat()
        val h = height.toFloat()
        fx?.let { r.set(0f, 0f, w, h); c.drawBitmap(it, null, r, null) }

        var l = 0f; var t = 0f; var rt = w; var b = h
        if (guide > 0f) {
            var gw = w; var gh = h
            if (guide > w / h) gh = w / guide else gw = h * guide
            l = (w - gw) / 2; t = (h - gh) / 2; rt = w - l; b = h - t
            p.style = Paint.Style.FILL; p.color = 0xAA000000.toInt()
            c.drawRect(0f, 0f, w, t, p); c.drawRect(0f, b, w, h, p)
            c.drawRect(0f, t, l, b, p); c.drawRect(rt, t, w, b, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 1f; p.color = 0x99FFFFFF.toInt()
        if (grid == 1 || grid == 3) {
            for (i in 1..2) {
                val x = l + (rt - l) * i / 3; val y = t + (b - t) * i / 3
                line(c, x, t, x, b); line(c, l, y, rt, y)
            }
        }
        if (grid == 2 || grid == 3) {
            val cx = (l + rt) / 2; val cy = (t + b) / 2; val k = d * 12
            line(c, cx - k, cy, cx + k, cy); line(c, cx, cy - k, cx, cy + k)
        }
        if (safe) {
            for (f in listOf(0.93f, 0.8f)) {
                val iw = (rt - l) * (1 - f) / 2; val ih = (b - t) * (1 - f) / 2
                r.set(l + iw, t + ih, rt - iw, b - ih); c.drawRect(r, p)
            }
        }
        if (level) {
            c.save(); c.translate(w / 2, h / 2); c.rotate(-roll)
            p.strokeWidth = d * 2f
            p.color = if (abs(roll) < 1f) 0xFF4CAF50.toInt() else 0xCCFFEB3B.toInt()
            line(c, -w * 0.14f, 0f, -w * 0.05f, 0f); line(c, w * 0.05f, 0f, w * 0.14f, 0f)
            c.restore()
            p.strokeWidth = d * 1f; p.color = 0x66FFFFFF.toInt()
            line(c, w / 2 - w * 0.16f, h / 2, w / 2 - w * 0.14f, h / 2)
            line(c, w / 2 + w * 0.14f, h / 2, w / 2 + w * 0.16f, h / 2)
        }
        if (showAudio) {
            val bw = d * 8; val bh = h * 0.4f; val x = d * 6; val y = h * 0.3f
            p.style = Paint.Style.FILL; p.color = 0x88000000.toInt()
            c.drawRect(x, y, x + bw, y + bh, p)
            val lv = audio.coerceIn(0f, 1f)
            var s = 0f
            for ((to, col) in listOf(0.7f to 0xFF4CAF50.toInt(), 0.9f to 0xFFFFEB3B.toInt(), 1f to 0xFFF44336.toInt())) {
                if (lv > s) {
                    p.color = col
                    c.drawRect(x, y + bh * (1 - minOf(lv, to)), x + bw, y + bh * (1 - s), p)
                }
                s = to
            }
        }
        val hh = hist
        if (scopeMode == 1 && hh != null) {
            val bx = w - d * 140; val by = d * 8; val bw = d * 130; val bh = d * 64
            p.style = Paint.Style.FILL; p.color = 0x88000000.toInt(); c.drawRect(bx, by, bx + bw, by + bh, p)
            val mx = hh.max().coerceAtLeast(1).toFloat()
            p.color = 0xCCFFFFFF.toInt()
            for (i in 0 until 64) {
                val v = kotlin.math.sqrt(hh[i] / mx) * bh
                c.drawRect(bx + bw * i / 64, by + bh - v, bx + bw * (i + 1) / 64, by + bh, p)
            }
        }
        scope?.let { if (scopeMode in 2..4) { r.set(w - d * 230, d * 8, w - d * 10, d * 8 + d * 110); c.drawBitmap(it, null, r, null) } }
        if (System.currentTimeMillis() - focusT < 1200 && focusX >= 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = d * 2f; p.color = 0xFFFFEB3B.toInt()
            val k = d * 28; c.drawRect(focusX - k, focusY - k, focusX + k, focusY + k, p)
        }
        if (recording) {
            p.style = Paint.Style.STROKE; p.strokeWidth = d * 3f; p.color = 0xFFE53935.toInt()
            c.drawRect(0f, 0f, w, h, p)
        }
    }
}
