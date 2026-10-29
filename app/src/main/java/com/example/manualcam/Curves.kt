package com.example.manualcam

import kotlin.math.pow

/** Tone-curve profiles (applied through Camera2 TONEMAP_CURVE). */
object Curves {
    private fun srgb(x: Float) = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    /** idx 1 = Flat (low contrast, wide latitude), idx 2 = Contrast (S-curve) */
    fun curve(idx: Int): FloatArray {
        val n = 64
        val a = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i / (n - 1f)
            val s = srgb(x)
            val y = when (idx) {
                1 -> 0.06f + 0.88f * s.pow(0.85f)
                else -> s + 0.6f * (s * s * (3 - 2 * s) - s)
            }
            a[i * 2] = x
            a[i * 2 + 1] = y.coerceIn(0f, 1f)
        }
        return a
    }
}
