package com.example.manualcam

import android.content.Context

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("manualcam", Context.MODE_PRIVATE)
    fun i(k: String, d: Int = 0) = p.getInt(k, d)
    fun s(k: String, d: String = "") = p.getString(k, d) ?: d
    fun b(k: String, d: Boolean = false) = p.getBoolean(k, d)
    fun put(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    fun put(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    fun clear() = p.edit().clear().apply()
}
