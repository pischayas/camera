package com.example.manualcam

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import kotlin.math.abs
import kotlin.math.max

/** Level meter used while idle (during recording the recorder's own amplitude is used). */
class AudioMeter {
    @Volatile var peak = 0
    @Volatile private var running = false
    private var th: Thread? = null

    @Suppress("MissingPermission")
    fun start(source: Int, dev: AudioDeviceInfo?) {
        if (th != null) return
        running = true
        th = Thread {
            var ar: AudioRecord? = null
            try {
                val sr = 48000
                val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
                ar = r
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    if (dev != null) r.setPreferredDevice(dev)
                    r.startRecording()
                    val buf = ShortArray(1024)
                    while (running) {
                        val n = r.read(buf, 0, buf.size)
                        var m = 0
                        for (i in 0 until n) m = max(m, abs(buf[i].toInt()))
                        peak = m
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { ar?.stop() } catch (_: Exception) {}
                try { ar?.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        try { th?.join(600) } catch (_: Exception) {}
        th = null
        peak = 0
    }
}
