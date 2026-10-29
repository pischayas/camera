package com.example.manualcam

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Suppress("DEPRECATION")
class MainActivity : Activity(), SensorEventListener {

    private class Cam(val id: String, val label: String, val back: Boolean, val manual: Boolean, val mm: Int)
    private class Stab(val label: String, val eis: Int, val ois: Int)

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    // ---- views
    private lateinit var st: Settings
    private lateinit var cm: CameraManager
    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var recBtn: Button
    private lateinit var bottom: LinearLayout
    private lateinit var ctlPanel: LinearLayout
    private lateinit var ctlTxt: TextView
    private lateinit var ctlBar: SeekBar
    private lateinit var ctlBar2: SeekBar
    private lateinit var ctlBtn: Button
    private lateinit var pullRow: LinearLayout
    private lateinit var cleanExit: Button
    private val chips = HashMap<String, Button>()

    // ---- threads / helpers
    private val ui = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var anThread: HandlerThread? = null
    private var an: Handler? = null
    private val analyzer = Analyzer()
    private val meter = AudioMeter()

    // ---- camera
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var chars: CameraCharacteristics? = null
    private var streamMap: StreamConfigurationMap? = null
    private var cams = listOf<Cam>()
    private var camIdx = 0
    private var sizes = listOf(Size(1920, 1080))
    private var sizeIdx = 0
    private var fps = 30
    private var fpsRanges = listOf<Range<Int>>()

    // ---- exposure
    private var manualOk = true
    private var aeManual = false
    private var aeLock = false
    private var isoMin = 100
    private var isoMax = 800
    private var isoVal = 400
    private var shutNs = listOf(16_666_666L)
    private var shutLb = listOf("1/60")
    private var shutIdx = 0
    private var evRange = Range(0, 0)
    private var evStep = 0f
    private var evVal = 0

    // ---- white balance
    private val awbNames = mapOf(1 to "WB Auto", 2 to "Tungsten", 3 to "Fluorescent", 4 to "Warm fluor.", 5 to "Daylight", 6 to "Cloudy", 7 to "Twilight", 8 to "Shade")
    private val calK = mapOf(2 to 2856, 3 to 4000, 4 to 3500, 5 to 5500, 6 to 6500, 7 to 7000, 8 to 7500)
    private var wbChoices = listOf("WB Auto" to 1)
    private var wbSel = 0
    private var kelvin = 5600
    private var tint = 0
    private val calGains = HashMap<Int, RggbChannelVector>()
    private var calTransform: ColorSpaceTransform? = null
    @Volatile private var calMode = 0
    private var calDone = false

    // ---- focus / zoom / stabilisation / tone
    private var afOk = false
    private var afCont = 3
    private var focusMode = 0
    private val focusNames = listOf("AF-C", "AF-tap", "MF")
    private var minFocus = 0f
    private var focusVal = 0f
    private var pullA = 0f
    private var pullB = 0f
    private var tapRegion: MeteringRectangle? = null
    private var zoomRange = Range(1f, 1f)
    private var zoomVal = 1f
    private var stabChoices = listOf(Stab("Off", 0, 0))
    private var hasOis = false
    private var toneOk = false

    // ---- ui state
    private var active = ""
    private var clean = false
    private var rollF = 0f
    private var batt = 0
    private var remain = ""
    private var tickN = 0

    // ---- recording
    private var recording = false
    private var recStart = 0L
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var outIsDoc = false

    // ---- option tables
    private val guideNames = listOf("Off", "2.39:1", "2:1", "1.85:1", "16:9", "4:3", "1:1", "9:16")
    private val guideRatios = listOf(0f, 2.39f, 2f, 1.85f, 16f / 9f, 4f / 3f, 1f, 9f / 16f)
    private val gridNames = listOf("Off", "Thirds", "Center cross", "Thirds + cross")
    private val zebraNames = listOf("Off", "90%", "95%", "100%")
    private val zebraLevels = listOf(-1, 230, 242, 252)
    private val peakNames = listOf("Off", "Red", "Green", "Blue", "White")
    private val peakColors = listOf(0, 0xFFFF3030.toInt(), 0xFF30FF30.toInt(), 0xFF3080FF.toInt(), 0xFFFFFFFF.toInt())
    private val scopeNames = listOf("Off", "Histogram", "Waveform", "RGB parade", "Vectorscope")
    private val lookNames = listOf("Standard", "Black & white", "Cinema", "Vivid", "Log view-assist")
    private val anamNames = listOf("Off (1.0x)", "1.25x", "1.33x", "1.5x", "1.8x", "2.0x")
    private val anamVals = listOf(1f, 1.25f, 1.33f, 1.5f, 1.8f, 2f)
    private val codecNames = listOf("H.264 (AVC)", "H.265 (HEVC)")
    private val brNames = listOf("Low", "Medium", "High", "Max")
    private val brBpp = listOf(0.07, 0.12, 0.2, 0.32)
    private val toneNames = listOf("Standard", "Flat (low contrast)", "Contrast")
    private val audSrcNames = listOf("Mic", "Camcorder", "Unprocessed", "Voice recognition")
    private val audSrcVals = listOf(1, 5, 9, 6)

    // =================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        window.setDecorFitsSystemWindows(false)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager
        cams = listCams()
        if (cams.isEmpty()) { toast("ไม่พบกล้อง"); finish(); return }
        camIdx = cams.indexOfFirst { it.back && it.mm in 20..30 }.let { if (it < 0) 0 else it }
        buildUi()
        applyMonitor()
        hideBars()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onResume() {
        super.onResume()
        startThreads()
        (getSystemService(SENSOR_SERVICE) as SensorManager).let {
            it.registerListener(this, it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        }
        if (::tv.isInitialized && tv.isAvailable) openCamera()
        startMeter()
        ui.post(tick)
        an?.post(anRun)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        an?.removeCallbacks(anRun)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        stopRecording(restart = false)
        meter.stop()
        closeCamera()
        bgThread?.quitSafely(); anThread?.quitSafely()
        try { bgThread?.join() } catch (_: Exception) {}
        bgThread = null; bg = null; anThread = null; an = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startThreads() {
        if (bgThread == null) { bgThread = HandlerThread("cam").also { it.start() }; bg = Handler(bgThread!!.looper) }
        if (anThread == null) { anThread = HandlerThread("analysis").also { it.start() }; an = Handler(anThread!!.looper) }
    }

    private fun startMeter() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || recording) return
        meter.start(audSrcVals[st.i("audSrc").coerceIn(0, audSrcVals.size - 1)], audioDevice())
    }

    private fun audioDevice(): AudioDeviceInfo? {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val id = st.i("audDev", -1)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (st.b("volRec", true) && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event?.repeatCount == 0) toggleRecord()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val flip = windowManager.defaultDisplay.rotation == Surface.ROTATION_270
        val x = if (flip) -e.values[0] else e.values[0]
        val y = if (flip) -e.values[1] else e.values[1]
        val r = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()
        rollF += (r - rollF) * 0.15f
        overlay.roll = rollF
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // =================================================================== UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun mkBtn(t: String, click: () -> Unit) = Button(this).apply {
        text = t; textSize = 10f; isAllCaps = false
        setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { click() }
    }

    private fun barListener(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) f(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        overlay = OverlayView(this)
        root.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startThreads(); openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        // pinch = zoom, tap = focus
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (zoomRange.upper > 1f) {
                    zoomVal = (zoomVal * d.scaleFactor).coerceIn(zoomRange.lower, zoomRange.upper)
                    update(); refreshUi(); if (active == "ZOOM") configurePanel()
                }
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tapFocus(e.x, e.y); return true }
        })
        overlay.setOnTouchListener { _, ev -> sg.onTouchEvent(ev); gd.onTouchEvent(ev); true }

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setShadowLayer(4f, 0f, 0f, Color.BLACK); setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

        cleanExit = mkBtn("⛶") { clean = false; applyMonitor() }.apply { visibility = View.GONE }
        root.addView(cleanExit, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(8) })

        // control panel
        ctlTxt = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }
        ctlBar = SeekBar(this).apply { setOnSeekBarChangeListener(barListener { onBar(it) }) }
        ctlBar2 = SeekBar(this).apply { setOnSeekBarChangeListener(barListener { tint = it - 50; refreshUi(); update() }) }
        ctlBtn = mkBtn("") {
            when (active) {
                "SHUTTER", "ISO" -> { if (manualOk) aeManual = !aeManual else toast("กล้องนี้ไม่รองรับ Manual Sensor") }
                "WB" -> { wbSel = (wbSel + 1) % wbChoices.size; if (isKelvin() && !calDone) calibrateWb() }
                "FOCUS" -> { if (afOk) focusMode = (focusMode + 1) % 3 else toast("กล้องนี้โฟกัสคงที่") }
            }
            configurePanel(); update()
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ctlTxt, LinearLayout.LayoutParams(dp(120), WRAP))
            addView(ctlBar, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ctlBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "Tint"; setTextColor(Color.WHITE); textSize = 12f }, LinearLayout.LayoutParams(dp(120), WRAP))
            addView(ctlBar2, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        pullRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((t, f) in listOf<Pair<String, () -> Unit>>(
                "Set A" to { pullA = focusVal; toast("A = ${"%.2f".format(pullA)}") },
                "Set B" to { pullB = focusVal; toast("B = ${"%.2f".format(pullB)}") },
                "Pull A→B" to { runPull(pullA, pullB) },
                "Pull B→A" to { runPull(pullB, pullA) }
            )) addView(mkBtn(t, f), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        ctlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(row1); addView(row2); addView(pullRow)
        }
        ctlPanel.tag = row2

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (k in listOf("FPS", "RES", "SHUTTER", "ISO", "WB", "FOCUS", "EXP", "ZOOM", "LENS", "LOCK", "MON", "SET", "CLEAN")) {
            val b = mkBtn(k) { onChip(k) }
            chips[k] = b
            chipRow.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(ctlPanel); addView(scroll)
        }
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { rightMargin = dp(96) })

        recBtn = Button(this).apply {
            text = "REC"; setTextColor(Color.WHITE); setBackgroundColor(0xCCD32F2F.toInt())
            setOnClickListener { toggleRecord() }
        }
        root.addView(recBtn, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(10) })
        setContentView(root)
    }

    private fun fitPreview() {
        val w = root.width - root.paddingLeft - root.paddingRight
        val h = root.height - root.paddingTop - root.paddingBottom
        if (w <= 0 || h <= 0) return
        val s = sizes[sizeIdx]
        val desq = anamVals[st.i("anam").coerceIn(0, anamVals.size - 1)]
        val ratio = s.width * desq / s.height
        var tw = w; var th = (w / ratio).toInt()
        if (th > h) { th = h; tw = (h * ratio).toInt() }
        val tvW = (tw / desq).toInt()
        val lp = tv.layoutParams
        if (lp.width != tvW || lp.height != th) tv.layoutParams = FrameLayout.LayoutParams(tvW, th, Gravity.CENTER)
        tv.scaleX = desq
        val lo = overlay.layoutParams
        if (lo.width != tw || lo.height != th) overlay.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
    }

    private fun applyMonitor() {
        overlay.guide = guideRatios[st.i("guide").coerceIn(0, guideRatios.size - 1)]
        overlay.grid = st.i("grid")
        overlay.safe = st.b("safe")
        overlay.level = st.b("level", true)
        overlay.showAudio = st.b("audioMeter", true)
        overlay.scopeMode = st.i("scope")
        overlay.clean = clean
        bottom.visibility = if (clean) View.GONE else View.VISIBLE
        status.visibility = if (clean) View.GONE else View.VISIBLE
        cleanExit.visibility = if (clean) View.VISIBLE else View.GONE
        tv.setLayerPaint(lookPaint(st.i("look")))
        if (::root.isInitialized) fitPreview()
        overlay.invalidate()
    }

    private fun lookPaint(i: Int): Paint? {
        if (i == 0) return null
        val m = ColorMatrix()
        fun contrast(c: Float): ColorMatrix {
            val o = 127.5f * (1 - c)
            return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, o, 0f, c, 0f, 0f, o, 0f, 0f, c, 0f, o, 0f, 0f, 0f, 1f, 0f))
        }
        when (i) {
            1 -> m.setSaturation(0f)
            2 -> { m.setSaturation(0.85f); m.postConcat(contrast(1.12f)) }
            3 -> m.setSaturation(1.4f)
            else -> { m.setSaturation(1.3f); m.postConcat(contrast(1.5f)) }
        }
        return Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
    }

    private fun onChip(k: String) {
        when (k) {
            "FPS" -> if (!recording) {
                val a = allowedFps()
                pick("Frame rate", a.map { "$it fps" }, a.indexOf(fps)) {
                    fps = a[it]; st.put("fps", fps); buildShutters(); refreshUi(); startPreview()
                }
            }
            "RES" -> if (!recording) {
                pick("Resolution", sizes.map { "${sizeLabel(it)}  ·  ${it.width}x${it.height}" }, sizeIdx) {
                    sizeIdx = it; st.put("size", "${sizes[it].width}x${sizes[it].height}")
                    fixFps(); buildShutters(); refreshUi(); fitPreview(); startPreview()
                }
            }
            "SHUTTER", "ISO", "WB", "FOCUS", "EXP", "ZOOM" -> {
                if (k == "ZOOM" && zoomRange.upper <= 1f) { toast("กล้องนี้ไม่รองรับซูม"); return }
                active = if (active == k) "" else k
                configurePanel()
            }
            "LENS" -> if (!recording && cams.size > 1) {
                closeCamera()
                camIdx = (camIdx + 1) % cams.size
                ui.postDelayed({ openCamera() }, 350)
            }
            "LOCK" -> { aeLock = !aeLock; refreshUi(); update() }
            "MON" -> monitorMenu()
            "SET" -> settingsMenu()
            "CLEAN" -> { clean = true; applyMonitor() }
        }
    }

    private fun configurePanel() {
        val row2 = ctlPanel.tag as View
        row2.visibility = if (active == "WB") View.VISIBLE else View.GONE
        pullRow.visibility = if (active == "FOCUS") View.VISIBLE else View.GONE
        ctlBtn.visibility = if (active == "EXP" || active == "ZOOM") View.GONE else View.VISIBLE
        when (active) {
            "SHUTTER" -> { ctlBar.max = shutNs.size - 1; ctlBar.progress = shutIdx }
            "ISO" -> { ctlBar.max = 100; ctlBar.progress = (100 * kotlin.math.ln(isoVal.toDouble() / isoMin) / kotlin.math.ln(isoMax.toDouble() / isoMin)).toInt().coerceIn(0, 100) }
            "WB" -> { ctlBar.max = 80; ctlBar.progress = ((kelvin - 2000) / 100).coerceIn(0, 80); ctlBar2.max = 100; ctlBar2.progress = tint + 50 }
            "FOCUS" -> { ctlBar.max = 100; ctlBar.progress = if (minFocus > 0) (100 - focusVal / minFocus * 100).roundToInt().coerceIn(0, 100) else 100 }
            "EXP" -> { ctlBar.max = evRange.upper - evRange.lower; ctlBar.progress = evVal - evRange.lower }
            "ZOOM" -> { ctlBar.max = 100; ctlBar.progress = (100 * kotlin.math.ln(zoomVal / zoomRange.lower) / kotlin.math.ln(zoomRange.upper / zoomRange.lower)).toInt().coerceIn(0, 100) }
        }
        ctlPanel.visibility = if (active.isEmpty()) View.GONE else View.VISIBLE
        refreshUi()
    }

    private fun isKelvin() = wbChoices.getOrNull(wbSel)?.second == -1

    private fun refreshUi() {
        if (!::ctlTxt.isInitialized) return
        fun set(k: String, v: String) { chips[k]?.text = "$k\n$v" }
        val s = sizes[sizeIdx]
        set("FPS", "$fps")
        set("RES", sizeLabel(s))
        set("SHUTTER", if (aeManual) shutLb.getOrElse(shutIdx) { "" } else "AUTO")
        set("ISO", if (aeManual) "$isoVal" else "AUTO")
        set("WB", if (isKelvin()) "${kelvin}K" else wbChoices[wbSel].first.removePrefix("WB "))
        set("FOCUS", focusNames[focusMode])
        set("EXP", "%+.1f".format(evVal * evStep))
        set("ZOOM", "%.1fx".format(zoomVal))
        set("LENS", cams[camIdx].label)
        set("LOCK", if (aeLock) "ON" else "OFF")
        for ((k, b) in chips) b.setBackgroundColor(if (k == active || (k == "LOCK" && aeLock)) 0xCC1565C0.toInt() else 0x99000000.toInt())
        ctlTxt.text = when (active) {
            "SHUTTER" -> "Shutter ${shutLb.getOrElse(shutIdx) { "" }}"
            "ISO" -> "ISO $isoVal"
            "WB" -> if (isKelvin()) "${kelvin}K  tint $tint" else wbChoices[wbSel].first
            "FOCUS" -> if (focusVal <= 0.001f) "Focus ∞" else "Focus %.2f m".format(1f / focusVal)
            "EXP" -> "EV %+.1f".format(evVal * evStep)
            "ZOOM" -> "Zoom %.1fx".format(zoomVal)
            else -> ""
        }
        ctlBtn.text = when (active) {
            "SHUTTER", "ISO" -> if (aeManual) "MANUAL" else "AUTO"
            "WB" -> wbChoices[wbSel].first
            "FOCUS" -> focusNames[focusMode]
            else -> ""
        }
        val wbOn = active != "WB" || isKelvin()
        ctlBar.isEnabled = wbOn; ctlBar.alpha = if (wbOn) 1f else 0.4f
        ctlBar2.isEnabled = isKelvin(); ctlBar2.alpha = if (isKelvin()) 1f else 0.4f
    }

    private fun onBar(p: Int) {
        when (active) {
            "SHUTTER" -> { goManual(); shutIdx = p.coerceIn(0, shutNs.size - 1) }
            "ISO" -> { goManual(); isoVal = (isoMin * (isoMax.toDouble() / isoMin).pow(p / 100.0)).toInt().coerceIn(isoMin, isoMax) }
            "WB" -> kelvin = 2000 + 100 * p
            "FOCUS" -> { focusMode = 2; focusVal = minFocus * (100 - p) / 100f }
            "EXP" -> evVal = evRange.lower + p
            "ZOOM" -> zoomVal = (zoomRange.lower * (zoomRange.upper / zoomRange.lower).pow(p / 100f)).coerceIn(zoomRange.lower, zoomRange.upper)
        }
        refreshUi(); update()
    }

    private fun goManual() { if (manualOk) aeManual = true }

    private fun sizeLabel(s: Size): String = when {
        s.width * 9 == s.height * 16 && s.height == 2160 -> "4K"
        s.width * 9 == s.height * 16 && s.height == 1080 -> "1080p"
        s.width * 9 == s.height * 16 && s.height == 720 -> "720p"
        else -> "${s.width}x${s.height}"
    }

    // ---- dialogs

    private fun pick(title: String, items: List<String>, cur: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), cur) { d, w -> d.dismiss(); onPick(w) }
            .show()
    }

    private fun e(label: String, f: () -> Unit): Pair<String, () -> Unit> = label to f

    private fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    private fun ask(title: String, cur: String, ok: (String) -> Unit) {
        val ed = EditText(this).apply { setText(cur); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(ed)
            .setPositiveButton("OK") { _, _ -> ok(ed.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    private fun sub(title: String, names: List<String>, key: String, reopen: () -> Unit) {
        pick(title, names, st.i(key)) { st.put(key, it); applyMonitor(); reopen() }
    }

    private fun monitorMenu() {
        menu("Monitor / HUD", listOf(
            e("Frame guide: ${guideNames[st.i("guide")]}") { sub("Frame guide", guideNames, "guide") { monitorMenu() } },
            e("Grid: ${gridNames[st.i("grid")]}") { sub("Grid", gridNames, "grid") { monitorMenu() } },
            e("Safe area: ${onOff(st.b("safe"))}") { st.put("safe", !st.b("safe")); applyMonitor(); monitorMenu() },
            e("Zebra: ${zebraNames[st.i("zebra")]}") { sub("Zebra", zebraNames, "zebra") { monitorMenu() } },
            e("Focus peaking: ${peakNames[st.i("peak")]}") { sub("Focus peaking", peakNames, "peak") { monitorMenu() } },
            e("False color: ${onOff(st.b("falseColor"))}") { st.put("falseColor", !st.b("falseColor")); applyMonitor(); monitorMenu() },
            e("Histogram / scope: ${scopeNames[st.i("scope")]}") { sub("Scope", scopeNames, "scope") { monitorMenu() } },
            e("Level (horizon): ${onOff(st.b("level", true))}") { st.put("level", !st.b("level", true)); applyMonitor(); monitorMenu() },
            e("Audio meter: ${onOff(st.b("audioMeter", true))}") { st.put("audioMeter", !st.b("audioMeter", true)); applyMonitor(); monitorMenu() },
            e("Monitor look: ${lookNames[st.i("look")]}") { sub("Monitor look", lookNames, "look") { monitorMenu() } },
            e("Anamorphic desqueeze: ${anamNames[st.i("anam")]}") { sub("Anamorphic desqueeze", anamNames, "anam") { monitorMenu() } }
        ))
    }

    private fun settingsMenu() {
        val ext = st.b("ext") && st.s("extUri").isNotEmpty()
        menu("Settings", listOf(
            e("Codec: ${codecNames[st.i("codec")]}") { pick("Codec", codecNames, st.i("codec")) { st.put("codec", it); settingsMenu() } },
            e("Bitrate: ${brNames[st.i("br", 2)]} (${videoBitrate() / 1_000_000} Mbps)") { pick("Bitrate", brNames, st.i("br", 2)) { st.put("br", it); settingsMenu() } },
            e("Stabilization: ${stabChoices.getOrElse(st.i("stab")) { stabChoices[0] }.label}") {
                pick("Stabilization", stabChoices.map { it.label }, st.i("stab")) { st.put("stab", it); update(); settingsMenu() }
            },
            e("Tone profile: ${toneNames[st.i("tone")]}${if (toneOk) "" else " (ไม่รองรับ)"}") {
                pick("Tone profile", toneNames, st.i("tone")) { st.put("tone", it); update(); settingsMenu() }
            },
            e("Shutter units: ${if (st.b("angle")) "Angle" else "Speed"}") { st.put("angle", !st.b("angle")); buildShutters(); refreshUi(); settingsMenu() },
            e("Audio source: ${audSrcNames[st.i("audSrc")]}") {
                pick("Audio source", audSrcNames, st.i("audSrc")) { st.put("audSrc", it); meter.stop(); startMeter(); settingsMenu() }
            },
            e("Audio input device") { audioDeviceMenu() },
            e("Audio channels: ${if (st.b("stereo", true)) "Stereo" else "Mono"}") { st.put("stereo", !st.b("stereo", true)); settingsMenu() },
            e("Storage: ${if (ext) "EXTERNAL" else "Internal"}") { storageMenu() },
            e("Slate: ${st.s("camId", "A")}${"%03d".format(st.i("reel", 1))} S${st.s("scene", "1")} SH${st.i("shot", 1)} T${st.i("take", 1)}") { slateMenu() },
            e("Timecode: ${if (st.i("tcSrc") == 1) "Time of day" else "Record run"}") { st.put("tcSrc", 1 - st.i("tcSrc")); settingsMenu() },
            e("Pull-focus time: ${st.i("pullSecs", 3)} s") {
                val v = listOf(1, 2, 3, 5, 8, 10)
                pick("Pull-focus time", v.map { "$it s" }, v.indexOf(st.i("pullSecs", 3))) { st.put("pullSecs", v[it]); settingsMenu() }
            },
            e("Volume keys = record: ${onOff(st.b("volRec", true))}") { st.put("volRec", !st.b("volRec", true)); settingsMenu() },
            e("Reset all settings") { st.clear(); toast("รีเซ็ตแล้ว"); applyMonitor() }
        ))
    }

    private fun audioDeviceMenu() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        fun tn(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
            else -> "type $t"
        }
        val names = listOf("Default") + devs.map { "${it.productName} (${tn(it.type)})" }
        val cur = devs.indexOfFirst { it.id == st.i("audDev", -1) } + 1
        pick("Audio input", names, cur) {
            st.put("audDev", if (it == 0) -1 else devs[it - 1].id)
            meter.stop(); startMeter(); settingsMenu()
        }
    }

    private fun storageMenu() {
        val ext = st.b("ext") && st.s("extUri").isNotEmpty()
        pick("Storage (บันทึกวิดีโอไปที่)", listOf("Internal (Movies/ManualCam)", if (ext) "External folder (เปลี่ยนโฟลเดอร์...)" else "External: SD card / USB drive (เลือกโฟลเดอร์...)"), if (ext) 1 else 0) {
            if (it == 0) { st.put("ext", false); toast("บันทึกลงเครื่อง") }
            else {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                startActivityForResult(i, 77)
            }
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77 && res == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            st.put("extUri", u.toString()); st.put("ext", true)
            toast("ตั้งที่เก็บภายนอกแล้ว")
        }
    }

    private fun slateMenu() {
        menu("Slate / ชื่อคลิป", listOf(
            e("Camera ID: ${st.s("camId", "A")}") { ask("Camera ID", st.s("camId", "A")) { v -> st.put("camId", v.ifEmpty { "A" }.take(2)); slateMenu() } },
            e("Reel: ${st.i("reel", 1)}") { ask("Reel", "${st.i("reel", 1)}") { v -> st.put("reel", v.toIntOrNull() ?: 1); slateMenu() } },
            e("Scene: ${st.s("scene", "1")}") { ask("Scene", st.s("scene", "1")) { v -> st.put("scene", v.ifEmpty { "1" }.filter { c -> c.isLetterOrDigit() }); slateMenu() } },
            e("Shot: ${st.i("shot", 1)}") { ask("Shot", "${st.i("shot", 1)}") { v -> st.put("shot", v.toIntOrNull() ?: 1); slateMenu() } },
            e("Take: ${st.i("take", 1)}") { ask("Take", "${st.i("take", 1)}") { v -> st.put("take", v.toIntOrNull() ?: 1); slateMenu() } }
        ))
    }

    // =================================================================== camera

    private fun hasManual(c: CameraCharacteristics) =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    private fun listCams(): List<Cam> {
        val out = ArrayList<Cam>()
        for (id in cm.cameraIdList) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (caps != null && !caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                if (map.getOutputSizes(MediaRecorder::class.java).isNullOrEmpty()) continue
                val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (f != null && sz != null) (f * 43.27f / sqrt(sz.width * sz.width + sz.height * sz.height)).roundToInt() else 0
                out.add(Cam(id, (if (back) "B" else "F") + id + (if (eq > 0) " ${eq}mm" else ""), back, hasManual(c), eq))
            } catch (_: Exception) {}
        }
        return out.sortedWith(compareByDescending<Cam> { it.back }.thenByDescending { it.manual })
    }

    private fun loadChars() {
        val c = cm.getCameraCharacteristics(cams[camIdx].id)
        chars = c
        manualOk = hasManual(c)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        streamMap = map
        sizes = map.getOutputSizes(MediaRecorder::class.java)
            .filter { it.width in 1280..4096 && it.height >= 720 }
            .sortedBy { it.width * it.height }
            .ifEmpty { listOf(Size(1920, 1080)) }
        val want = st.s("size", "1920x1080")
        var si = sizes.indexOfFirst { "${it.width}x${it.height}" == want }
        if (si < 0) si = sizes.indexOfFirst { it.width == 1920 && it.height == 1080 }
        if (si < 0) si = sizes.size - 1
        sizeIdx = si

        fpsRanges = (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()).toList()
        fps = st.i("fps", 30)
        fixFps()

        val ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        isoMin = (ir?.lower ?: 100).coerceAtLeast(1)
        isoMax = minOf(ir?.upper ?: 800, 6400).coerceAtLeast(isoMin + 1)
        isoVal = 400.coerceIn(isoMin, isoMax)

        evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        evVal = 0
        aeManual = false; aeLock = false

        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afm = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: listOf(0)
        afOk = minFocus > 0f && afm.contains(0)
        afCont = if (afm.contains(3)) 3 else if (afm.contains(4)) 4 else 1
        focusMode = 0; focusVal = 0f; tapRegion = null

        zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        zoomVal = 1f

        val avail = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(1)
        val l = ArrayList<Pair<String, Int>>()
        for (m in avail) awbNames[m]?.let { l.add(it to m) }
        if (l.isEmpty()) l.add("WB Auto" to 1)
        l.sortBy { if (it.second == 1) 0 else 1 }
        if (manualOk && avail.contains(0)) l.add("Kelvin" to -1)
        wbChoices = l; wbSel = 0; kelvin = 5600; tint = 0
        calGains.clear(); calDone = false; calMode = 0; calTransform = null

        val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: listOf(0)
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: listOf(0)
        hasOis = ois.contains(1)
        val sl = ArrayList<Stab>()
        sl.add(Stab("Off", 0, 0))
        if (eis.contains(1)) sl.add(Stab("EIS", 1, 0))
        if (hasOis) sl.add(Stab("OIS", 0, 1))
        if (eis.contains(1) && hasOis) sl.add(Stab("EIS + OIS", 1, 1))
        if (eis.contains(2)) sl.add(Stab("Preview stabilization", 2, 0))
        stabChoices = sl
        toneOk = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.contains(0) == true

        buildShutters()
        active = ""
        configurePanel()
        fitPreview()
        if (!manualOk) toast("กล้องนี้ไม่รองรับ Manual Sensor (ลองสลับเลนส์ด้วยปุ่ม LENS)")
    }

    private fun allowedFps(): List<Int> {
        val s = sizes[sizeIdx]
        val dur = streamMap?.getOutputMinFrameDuration(MediaRecorder::class.java, s) ?: 0L
        val maxF = if (dur > 0) 1_000_000_000.0 / dur + 0.5 else 1000.0
        return listOf(24, 25, 30, 50, 60, 120).filter { f -> f <= maxF && fpsRanges.any { it.upper == f } }.ifEmpty { listOf(30) }
    }

    private fun fixFps() {
        val a = allowedFps()
        if (fps !in a) fps = a.lastOrNull { it <= 30 } ?: a.first()
    }

    private fun buildShutters() {
        val er = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ns = ArrayList<Long>(); val lb = ArrayList<String>()
        val frame = 1_000_000_000L / fps
        if (st.b("angle")) {
            for (a in listOf(11.2, 22.5, 45.0, 90.0, 144.0, 172.8, 180.0, 216.0, 270.0, 360.0)) {
                val t = (frame * a / 360.0).toLong()
                if (er == null || t in er.lower..er.upper) { ns.add(t); lb.add(if (a % 1.0 == 0.0) "${a.toInt()}°" else "$a°") }
            }
        } else {
            for (d in listOf(24, 30, 48, 50, 60, 96, 100, 120, 250, 500, 1000, 2000, 4000, 8000)) {
                if (d < fps) continue
                val t = 1_000_000_000L / d
                if (er == null || t in er.lower..er.upper) { ns.add(t); lb.add("1/$d") }
            }
        }
        if (ns.isEmpty()) { ns.add(frame); lb.add("1/$fps") }
        shutNs = ns; shutLb = lb
        shutIdx = ns.indices.minByOrNull { abs(ns[it] - frame / 2) } ?: 0
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!tv.isAvailable || camera != null || cams.isEmpty()) return
        startThreads()
        try {
            loadChars()
            cm.openCamera(cams[camIdx].id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; startPreview() }
                override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
                override fun onError(c: CameraDevice, err: Int) { c.close(); camera = null; toast("เปิดกล้องไม่ได้ (error $err)") }
            }, bg)
        } catch (ex: Exception) {
            toast("เปิดกล้องไม่ได้: ${ex.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        session = null; camera = null
    }

    private fun startPreview() {
        val cam = camera ?: return
        val stex = tv.surfaceTexture ?: return
        try {
            session?.close(); session = null
            val s = sizes[sizeIdx]
            stex.setDefaultBufferSize(s.width, s.height)
            val surface = Surface(stex)
            builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) { session = sess; update() }
                override fun onConfigureFailed(sess: CameraCaptureSession) { toast("ตั้งค่าพรีวิวไม่สำเร็จ") }
            }, bg)
        } catch (ex: Exception) {
            toast("พรีวิวผิดพลาด: ${ex.message}")
        }
    }

    private val capCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val cm0 = calMode
            if (cm0 != 0 && res.get(CaptureResult.CONTROL_AWB_MODE) == cm0) {
                res.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { calGains[cm0] = it }
                if (cm0 == 5) res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { calTransform = it }
            }
        }
    }

    private fun identity() = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))

    private fun kelvinGains(k: Int): RggbChannelVector {
        fun ch(v: RggbChannelVector) = floatArrayOf(v.red, v.greenEven, v.greenOdd, v.blue)
        val pts = calGains.map { (mode, g) -> (1e6f / (calK[mode] ?: 5500)) to ch(g) }.sortedBy { it.first }
        val out = FloatArray(4)
        if (pts.size < 2) {
            val g = pts.firstOrNull()?.second ?: floatArrayOf(2f, 1f, 1f, 1.6f)
            for (j in 0..3) out[j] = g[j]
        } else {
            val m = 1e6f / k
            var a = pts[0]; var b = pts[1]
            for (i in 0 until pts.size - 1) if (m >= pts[i].first) { a = pts[i]; b = pts[i + 1] }
            val t = (m - a.first) / (b.first - a.first)
            for (j in 0..3) out[j] = (a.second[j] + (b.second[j] - a.second[j]) * t).coerceIn(0.5f, 8f)
        }
        val gm = 1f - tint * 0.004f
        return RggbChannelVector(out[0], out[1] * gm, out[2] * gm, out[3])
    }

    private fun calibrateWb() {
        val modes = listOf(2, 5, 8).filter { m -> wbChoices.any { it.second == m } }
        if (modes.size < 2) { toast("กล้องนี้มี preset WB ไม่พอสำหรับ Kelvin"); return }
        calGains.clear(); toast("กำลังคาลิเบรต WB...")
        var t = 0L
        for (m in modes) { bg?.postDelayed({ calMode = m; runOnUiThread { update() } }, t); t += 600 }
        bg?.postDelayed({ calMode = 0; calDone = true; runOnUiThread { refreshUi(); update() } }, t)
    }

    private fun applyControls(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val range = fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower } ?: Range(fps, fps)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        stabChoices.getOrNull(st.i("stab"))?.let {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it.eis)
            if (hasOis) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it.ois)
        }
        b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, aeLock)

        if (aeManual && manualOk) {
            val er = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            var ns = shutNs.getOrElse(shutIdx) { 1_000_000_000L / fps }
            if (er != null) ns = ns.coerceIn(er.lower, er.upper)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evVal)
        }

        val cal = calMode
        if (cal != 0) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, cal)
        } else if (isKelvin() && calDone) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(kelvin))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, calTransform ?: identity())
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, if (isKelvin()) 1 else wbChoices[wbSel].second)
        }

        if (afOk) {
            when (focusMode) {
                0 -> b.set(CaptureRequest.CONTROL_AF_MODE, afCont)
                1 -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    tapRegion?.let {
                        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                        if (!aeManual) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
                    }
                }
                else -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusVal)
                }
            }
        }
        if (zoomRange.upper > 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomVal)
        val tone = st.i("tone")
        if (toneOk && tone > 0) {
            val a = Curves.curve(tone)
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(a, a, a))
        }
    }

    private fun update() {
        val b = builder ?: return
        val s = session ?: return
        try {
            applyControls(b)
            s.setRepeatingRequest(b.build(), capCb, bg)
        } catch (_: Exception) {}
    }

    private fun tapFocus(x: Float, y: Float) {
        overlay.focusX = x; overlay.focusY = y; overlay.focusT = System.currentTimeMillis()
        val c = chars ?: return
        val b = builder ?: return
        val s = session ?: return
        if (!afOk) return
        val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cx = a.left + (x / overlay.width * a.width()).toInt()
        val cy = a.top + (y / overlay.height * a.height()).toInt()
        val hw = a.width() / 16
        val hh = a.height() / 16
        val x0 = (cx - hw).coerceIn(a.left, a.right - hw * 2)
        val y0 = (cy - hh).coerceIn(a.top, a.bottom - hh * 2)
        tapRegion = MeteringRectangle(x0, y0, hw * 2, hh * 2, 1000)
        focusMode = 1
        try {
            applyControls(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), capCb, bg)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), capCb, bg)
        } catch (_: Exception) {}
        refreshUi()
    }

    private fun runPull(from: Float, to: Float) {
        if (!afOk) { toast("กล้องนี้โฟกัสคงที่"); return }
        focusMode = 2
        ValueAnimator.ofFloat(from, to).apply {
            duration = st.i("pullSecs", 3) * 1000L
            addUpdateListener {
                focusVal = it.animatedValue as Float
                update(); refreshUi()
                if (active == "FOCUS" && minFocus > 0) ctlBar.progress = (100 - focusVal / minFocus * 100).roundToInt().coerceIn(0, 100)
            }
            start()
        }
        refreshUi()
    }

    // =================================================================== HUD loops

    private fun lvl(a: Int): Float = ((20 * log10(max(a, 1) / 32768.0) + 60) / 60).toFloat().coerceIn(0f, 1f)

    private val tick = object : Runnable {
        override fun run() {
            val a = if (recording) (try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }) else meter.peak
            overlay.audio = lvl(a)
            overlay.recording = recording
            if (tickN++ % 50 == 0) updateBatteryAndSpace()
            status.text = statusText()
            overlay.invalidate()
            ui.postDelayed(this, 100)
        }
    }

    private val anRun = object : Runnable {
        override fun run() {
            val z = zebraLevels[st.i("zebra").coerceIn(0, 3)]
            val pk = peakColors[st.i("peak").coerceIn(0, 4)]
            val fc = st.b("falseColor")
            val sm = st.i("scope")
            val need = !clean && (z >= 0 || pk != 0 || fc || sm > 0)
            if (need && tv.isAvailable && camera != null) {
                try {
                    val r = analyzer.run(tv, z, pk, fc, sm)
                    overlay.fx = r.fx; overlay.scope = r.scope; overlay.hist = r.hist
                    overlay.postInvalidate()
                } catch (_: Exception) {}
            } else if (overlay.fx != null || overlay.scope != null || overlay.hist != null) {
                overlay.fx = null; overlay.scope = null; overlay.hist = null
                overlay.postInvalidate()
            }
            an?.postDelayed(this, 120)
        }
    }

    private fun updateBatteryAndSpace() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) batt = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val fb = freeBytes()
        remain = if (fb < 0) "" else {
            val mins = fb / ((videoBitrate() + 256_000) / 8L) / 60
            "REM ${mins / 60}h${"%02d".format(mins % 60)}m"
        }
    }

    private fun freeBytes(): Long {
        return try {
            if (st.b("ext") && st.s("extUri").isNotEmpty()) {
                val vol = DocumentsContract.getTreeDocumentId(Uri.parse(st.s("extUri"))).substringBefore(":")
                val path = if (vol == "primary") Environment.getExternalStorageDirectory().path else "/storage/$vol"
                StatFs(path).availableBytes
            } else StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (_: Exception) { -1L }
    }

    private fun tc(): String {
        val f: Int
        val sec: Long
        if (st.i("tcSrc") == 1) {
            val now = System.currentTimeMillis()
            val ms = now + TimeZone.getDefault().getOffset(now)
            sec = (ms / 1000) % 86400; f = ((ms % 1000) * fps / 1000).toInt()
        } else {
            val el = if (recording) SystemClock.elapsedRealtime() - recStart else 0L
            sec = el / 1000; f = ((el % 1000) * fps / 1000).toInt()
        }
        return "%02d:%02d:%02d:%02d".format((sec / 3600) % 24, (sec / 60) % 60, sec % 60, f)
    }

    private fun statusText(): String {
        val s = sizes.getOrNull(sizeIdx) ?: return ""
        val ext = st.b("ext") && st.s("extUri").isNotEmpty()
        return (if (recording) "● REC " else "STBY ") + tc() + "   ${sizeLabel(s)} ${fps}p ${if (st.i("codec") == 1) "H.265" else "H.264"}" +
            "   ${if (ext) "EXT" else "INT"} $remain   BAT $batt%"
    }

    // =================================================================== recording

    private fun videoBitrate(): Int {
        val s = sizes.getOrNull(sizeIdx) ?: return 16_000_000
        val f = if (st.i("codec") == 1) 0.6 else 1.0
        return (s.width.toDouble() * s.height * fps * brBpp[st.i("br", 2).coerceIn(0, 3)] * f).toLong().coerceIn(2_000_000L, 150_000_000L).toInt()
    }

    private fun clipName(): String {
        val ts = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
        return "%s%03d_S%s_SH%d_T%d_%s.mp4".format(st.s("camId", "A"), st.i("reel", 1), st.s("scene", "1"), st.i("shot", 1), st.i("take", 1), ts)
    }

    private fun openOutput(name: String) {
        outIsDoc = false
        if (st.b("ext") && st.s("extUri").isNotEmpty()) {
            try {
                val tree = Uri.parse(st.s("extUri"))
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(contentResolver, parent, "video/mp4", name) ?: throw Exception("create failed")
                pfd = contentResolver.openFileDescriptor(doc, "rw")
                outUri = doc; outIsDoc = true
                return
            } catch (_: Exception) {
                toast("เขียนลงที่เก็บภายนอกไม่ได้ (ตรวจสอบการ์ด/โฟลเดอร์) จึงบันทึกลงเครื่องแทน")
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ManualCam")
        }
        outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        pfd = contentResolver.openFileDescriptor(outUri!!, "rw")
    }

    private fun setupRecorder() {
        val s = sizes[sizeIdx]
        openOutput(clipName())
        val so = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rot = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val front = !cams[camIdx].back
        val hint = if (front) (so + rot) % 360 else (so - rot + 360) % 360
        val dev = audioDevice()
        recorder = MediaRecorder().apply {
            setAudioSource(audSrcVals[st.i("audSrc").coerceIn(0, audSrcVals.size - 1)])
            if (dev != null) setPreferredDevice(dev)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd!!.fileDescriptor)
            setVideoEncodingBitRate(videoBitrate())
            setVideoFrameRate(fps)
            setVideoSize(s.width, s.height)
            setVideoEncoder(if (st.i("codec") == 1) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(256_000)
            setAudioSamplingRate(48_000)
            setAudioChannels(if (st.b("stereo", true)) 2 else 1)
            setOrientationHint(hint)
            prepare()
        }
    }

    private fun releaseRecorder(delete: Boolean) {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        if (delete) outUri?.let {
            try { if (outIsDoc) DocumentsContract.deleteDocument(contentResolver, it) else contentResolver.delete(it, null, null) } catch (_: Exception) {}
        }
    }

    private fun toggleRecord() { if (recording) stopRecording() else startRecording() }

    private fun startRecording() {
        val cam = camera ?: return
        val stex = tv.surfaceTexture ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { toast("ต้องอนุญาตไมโครโฟนก่อน"); return }
        try {
            meter.stop()
            setupRecorder()
            session?.close(); session = null
            val s = sizes[sizeIdx]
            stex.setDefaultBufferSize(s.width, s.height)
            val ps = Surface(stex)
            val rs = recorder!!.surface
            builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(ps); addTarget(rs) }
            cam.createCaptureSession(listOf(ps, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    update()
                    try {
                        recorder?.start()
                        recStart = SystemClock.elapsedRealtime()
                        recording = true
                        runOnUiThread { recBtn.text = "STOP" }
                    } catch (ex: Exception) {
                        toast("เริ่มอัดไม่ได้: ${ex.message}")
                        releaseRecorder(true); startPreview(); runOnUiThread { startMeter() }
                    }
                }
                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    toast("ความละเอียด/เฟรมเรตนี้ไม่รองรับ")
                    releaseRecorder(true); startPreview(); runOnUiThread { startMeter() }
                }
            }, bg)
        } catch (ex: Exception) {
            toast("เริ่มอัดไม่ได้: ${ex.message}")
            releaseRecorder(true); startPreview(); startMeter()
        }
    }

    private fun stopRecording(restart: Boolean = true) {
        if (!recording) return
        recording = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        releaseRecorder(false)
        st.put("take", st.i("take", 1) + 1)
        runOnUiThread { recBtn.text = "REC" }
        toast(if (outIsDoc) "บันทึกแล้วที่เก็บภายนอก" else "บันทึกแล้วที่ Movies/ManualCam")
        if (restart) { startPreview(); startMeter() }
    }
}
