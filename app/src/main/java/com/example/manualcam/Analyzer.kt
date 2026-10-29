package com.example.manualcam

import android.graphics.Bitmap
import android.view.TextureView
import kotlin.math.abs

/** Reads a small copy of the preview and builds zebra / peaking / false colour / scopes. */
class Analyzer {
    private val w = 320
    private val h = 180
    private val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val fxBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val scBmp = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
    private val px = IntArray(w * h)
    private val ly = IntArray(w * h)
    private val fxPx = IntArray(w * h)
    private val scPx = IntArray(256 * 128)
    private val cnt = IntArray(256 * 128)

    class Result(val fx: Bitmap?, val scope: Bitmap?, val hist: IntArray?)

    private fun gray(g: Int) = (0xFF shl 24) or (g shl 16) or (g shl 8) or g

    private fun falseColor(y: Int): Int = when {
        y < 12 -> 0xFF7A1FA2.toInt()
        y < 40 -> 0xFF1565C0.toInt()
        y < 104 -> gray(y)
        y < 124 -> 0xFF2E9E4F.toInt()
        y < 180 -> gray(y)
        y < 200 -> 0xFFFF8FB1.toInt()
        y < 240 -> 0xFFFFEB3B.toInt()
        else -> 0xFFFF1744.toInt()
    }

    fun run(tv: TextureView, zebra: Int, peak: Int, fcolor: Boolean, scopeMode: Int): Result {
        tv.getBitmap(src)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            ly[i] = (((c shr 16) and 255) * 54 + ((c shr 8) and 255) * 183 + (c and 255) * 19) shr 8
        }

        var fx: Bitmap? = null
        if (zebra >= 0 || peak != 0 || fcolor) {
            java.util.Arrays.fill(fxPx, 0)
            if (fcolor) for (i in ly.indices) fxPx[i] = falseColor(ly[i])
            if (zebra >= 0) {
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (ly[i] >= zebra && ((x + y) and 7) < 4) fxPx[i] = 0xCCFF2020.toInt()
                }
            }
            if (peak != 0) {
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    val g = abs(ly[i + 1] - ly[i - 1]) + abs(ly[i + w] - ly[i - w])
                    if (g > 40) fxPx[i] = peak
                }
            }
            fxBmp.setPixels(fxPx, 0, w, 0, 0, w, h)
            fx = fxBmp
        }

        var sc: Bitmap? = null
        var hist: IntArray? = null
        if (scopeMode == 1) {
            val hh = IntArray(64)
            for (v in ly) hh[v shr 2]++
            hist = hh
        } else if (scopeMode in 2..4) {
            java.util.Arrays.fill(cnt, 0)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                when (scopeMode) {
                    2 -> cnt[(127 - ly[i] * 127 / 255) * 256 + x * 256 / w]++
                    3 -> {
                        val sx = x * 85 / w
                        cnt[(127 - r * 127 / 255) * 256 + sx]++
                        cnt[(127 - g * 127 / 255) * 256 + 85 + sx]++
                        cnt[(127 - b * 127 / 255) * 256 + 170 + sx]++
                    }
                    else -> {
                        val cb = (128 + ((-43 * r - 85 * g + 128 * b) shr 8)).coerceIn(0, 255)
                        val cr = (128 + ((128 * r - 107 * g - 21 * b) shr 8)).coerceIn(0, 255)
                        cnt[(127 - cr * 127 / 255) * 256 + 64 + cb * 127 / 255]++
                    }
                }
            }
            for (i in scPx.indices) {
                val n = cnt[i]
                if (n == 0) { scPx[i] = 0x88000000.toInt(); continue }
                val v = minOf(255, 60 + n * 60)
                val col = i % 256
                scPx[i] = when (scopeMode) {
                    2 -> (0xFF shl 24) or (v shl 8)
                    3 -> (0xFF shl 24) or when { col < 85 -> v shl 16; col < 170 -> v shl 8; else -> v }
                    else -> gray(v)
                }
            }
            scBmp.setPixels(scPx, 0, 256, 0, 0, 256, 128)
            sc = scBmp
        }
        return Result(fx, sc, hist)
    }
}
