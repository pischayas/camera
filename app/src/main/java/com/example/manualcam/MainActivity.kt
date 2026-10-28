package com.example.manualcam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var cm: CameraManager
    private lateinit var recBtn: Button
    private lateinit var manualBtn: Button
    private lateinit var lensBtn: Button
    private lateinit var resBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var wbBtn: Button
    private lateinit var isoTxt: TextView
    private lateinit var shutTxt: TextView
    private lateinit var focTxt: TextView
    private lateinit var isoBar: SeekBar
    private lateinit var shutBar: SeekBar
    private lateinit var focBar: SeekBar

    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outUri: Uri? = null
    private var recording = false
    private var chars: CameraCharacteristics? = null

    private var backIds = listOf<String>()
    private var camIdx = 0
    private var sizes = listOf(Size(1920, 1080))
    private var sizeIdx = 0
    private val fpsList = listOf(24, 30, 60)
    private var fpsIdx = 1

    private var manual = false
    private var manualOk = true
    private var isoMin = 100
    private var isoMax = 800
    private var isoVal = 400
    private var shutters = listOf(60)
    private var shutIdx = 0
    private var minFocus = 0f
    private var focusVal = 0f
    private val awbNames = mapOf(
        1 to "WB Auto", 2 to "Tungsten", 3 to "Fluorescent", 4 to "Warm fluor.",
        5 to "Daylight", 6 to "Cloudy", 7 to "Twilight", 8 to "Shade"
    )
    private var awbModes = listOf(1)
    private var awbIdx = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager
        backIds = cm.cameraIdList
            .filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            .sortedByDescending { hasManual(cm.getCameraCharacteristics(it)) }
        if (backIds.isEmpty()) {
            toast("ไม่พบกล้องหลัง"); finish(); return
        }
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        startBg()
        if (::tv.isInitialized && tv.isAvailable) openCamera()
    }

    override fun onPause() {
        stopRecording(restart = false)
        closeCamera()
        bgThread?.quitSafely()
        try { bgThread?.join() } catch (_: Exception) {}
        bgThread = null; bg = null
        super.onPause()
    }

    private fun startBg() {
        if (bgThread == null) {
            bgThread = HandlerThread("cam").also { it.start() }
            bg = Handler(bgThread!!.looper)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun mkBtn(t: String, click: () -> Unit) = Button(this).apply {
        text = t; textSize = 11f; isAllCaps = false
        setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt())
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { click() }
    }

    private fun mkTxt() = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }

    private fun listener(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) f(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun row(label: TextView, bar: SeekBar) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        root.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startBg(); openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }

        lensBtn = mkBtn("Cam 0") {
            if (!recording && backIds.size > 1) {
                closeCamera()
                camIdx = (camIdx + 1) % backIds.size
                manual = false
                root.postDelayed({ openCamera() }, 300)
            }
        }
        resBtn = mkBtn("1080p") {
            if (!recording) { sizeIdx = (sizeIdx + 1) % sizes.size; refreshUi(); fitPreview(); startPreview() }
        }
        fpsBtn = mkBtn("30fps") {
            if (!recording) {
                fpsIdx = (fpsIdx + 1) % fpsList.size
                recomputeShutters(); refreshUi(); startPreview()
            }
        }
        wbBtn = mkBtn("WB Auto") {
            awbIdx = (awbIdx + 1) % awbModes.size; refreshUi(); update()
        }
        manualBtn = mkBtn("AUTO") {
            if (!manual && !manualOk) toast("กล้องนี้ไม่รองรับ Manual Sensor")
            else { manual = !manual; refreshUi(); update() }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in listOf(lensBtn, resBtn, fpsBtn, wbBtn, manualBtn)) {
                addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
        }

        isoTxt = mkTxt(); shutTxt = mkTxt(); focTxt = mkTxt()
        isoBar = SeekBar(this).apply { max = 100 }
        shutBar = SeekBar(this)
        focBar = SeekBar(this).apply { max = 100; progress = 100 }

        isoBar.setOnSeekBarChangeListener(listener { p ->
            isoVal = (isoMin * (isoMax.toDouble() / isoMin).pow(p / 100.0)).toInt().coerceIn(isoMin, isoMax)
            updateLabels(); update()
        })
        shutBar.setOnSeekBarChangeListener(listener { p -> shutIdx = p; updateLabels(); update() })
        focBar.setOnSeekBarChangeListener(listener { p ->
            focusVal = minFocus * (100 - p) / 100f
            updateLabels(); update()
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(btnRow)
            addView(row(isoTxt, isoBar))
            addView(row(shutTxt, shutBar))
            addView(row(focTxt, focBar))
        }
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { rightMargin = dp(96) })

        recBtn = Button(this).apply {
            text = "REC"; setTextColor(Color.WHITE); setBackgroundColor(0xCCD32F2F.toInt())
            setOnClickListener { if (recording) stopRecording() else startRecording() }
        }
        root.addView(recBtn, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(10) })

        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
    }

    private fun fitPreview() {
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val s = sizes[sizeIdx]
        var tw = w; var th = w * s.height / s.width
        if (th > h) { th = h; tw = h * s.width / s.height }
        val lp = tv.layoutParams
        if (lp.width != tw || lp.height != th) {
            tv.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
        }
    }

    private fun updateLabels() {
        isoTxt.text = "ISO $isoVal"
        shutTxt.text = "Shutter 1/${shutters.getOrElse(shutIdx) { 60 }}"
        focTxt.text = if (focusVal <= 0.001f) "Focus ∞" else "Focus %.2f m".format(1f / focusVal)
    }

    private fun refreshUi() {
        val s = sizes[sizeIdx]
        resBtn.text = "${s.height}p"
        fpsBtn.text = "${fpsList[fpsIdx]}fps"
        lensBtn.text = "Cam ${backIds[camIdx]}"
        wbBtn.text = awbNames[awbModes[awbIdx]] ?: "WB"
        manualBtn.text = if (manual) "MANUAL" else "AUTO"
        shutBar.max = (shutters.size - 1).coerceAtLeast(0)
        shutBar.progress = shutIdx
        isoBar.progress = (100 * ln(isoVal.toDouble() / isoMin) / ln(isoMax.toDouble() / isoMin)).toInt().coerceIn(0, 100)
        val a = if (manual) 1f else 0.35f
        for (b in listOf(isoBar, shutBar, focBar)) { b.alpha = a; b.isEnabled = manual }
        updateLabels()
    }

    // ---------------------------------------------------------------- camera

    private fun hasManual(c: CameraCharacteristics): Boolean =
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    private fun loadChars() {
        val c = cm.getCameraCharacteristics(backIds[camIdx])
        chars = c
        manualOk = hasManual(c)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        sizes = map.getOutputSizes(MediaRecorder::class.java)
            .filter { it.width * 9 == it.height * 16 && it.height in listOf(720, 1080, 2160) }
            .sortedBy { it.width }
        if (sizes.isEmpty()) sizes = listOf(Size(1920, 1080))
        sizeIdx = sizes.indexOfFirst { it.height == 1080 }.let { if (it < 0) sizes.size - 1 else it }

        val ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        isoMin = (ir?.lower ?: 100).coerceAtLeast(1)
        isoMax = minOf(ir?.upper ?: 800, 6400).coerceAtLeast(isoMin + 1)
        isoVal = 400.coerceIn(isoMin, isoMax)

        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        focusVal = 0f

        val avail = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(1)
        awbModes = avail.filter { awbNames.containsKey(it) }.ifEmpty { listOf(1) }
        awbIdx = 0

        recomputeShutters()
        focBar.progress = 100
        refreshUi()
        fitPreview()
    }

    private fun recomputeShutters() {
        val fps = fpsList[fpsIdx]
        val r = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val all = listOf(24, 30, 48, 60, 100, 120, 250, 500, 1000, 2000, 4000, 8000)
        shutters = all.filter { it >= fps && (r == null || (1_000_000_000L / it) in r.lower..r.upper) }
        if (shutters.isEmpty()) shutters = listOf(fps)
        shutIdx = shutters.indexOfFirst { it >= fps * 2 }.let { if (it < 0) shutters.size - 1 else it }
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!tv.isAvailable || camera != null) return
        startBg()
        try {
            loadChars()
            cm.openCamera(backIds[camIdx], object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camera = c; startPreview() }
                override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
                override fun onError(c: CameraDevice, e: Int) { c.close(); camera = null; toast("เปิดกล้องไม่ได้ (error $e)") }
            }, bg)
        } catch (e: Exception) {
            toast("เปิดกล้องไม่ได้: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        session = null; camera = null
    }

    @Suppress("DEPRECATION")
    private fun startPreview() {
        val cam = camera ?: return
        val st = tv.surfaceTexture ?: return
        try {
            session?.close(); session = null
            val s = sizes[sizeIdx]
            st.setDefaultBufferSize(s.width, s.height)
            val surface = Surface(st)
            builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; update() }
                override fun onConfigureFailed(s: CameraCaptureSession) { toast("ตั้งค่าพรีวิวไม่สำเร็จ") }
            }, bg)
        } catch (e: Exception) {
            toast("พรีวิวผิดพลาด: ${e.message}")
        }
    }

    private fun applyControls(b: CaptureRequest.Builder) {
        val fps = fpsList[fpsIdx]
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val range = ranges?.filter { it.upper == fps }?.maxByOrNull { it.lower } ?: Range(fps, fps)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbModes[awbIdx])
        if (manual) {
            val er = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            var ns = 1_000_000_000L / shutters.getOrElse(shutIdx) { fps }
            if (er != null) ns = ns.coerceIn(er.lower, er.upper)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusVal)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

    private fun update() {
        val b = builder ?: return
        val s = session ?: return
        try {
            applyControls(b)
            s.setRepeatingRequest(b.build(), null, bg)
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- recording

    private fun setupRecorder() {
        val s = sizes[sizeIdx]
        val fps = fpsList[fpsIdx]
        val name = "MANUAL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ManualCam")
        }
        outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        pfd = contentResolver.openFileDescriptor(outUri!!, "w")

        val so = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rot = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd!!.fileDescriptor)
            setVideoEncodingBitRate((s.width.toLong() * s.height * fps * 0.2).toInt())
            setVideoFrameRate(fps)
            setVideoSize(s.width, s.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(192_000)
            setAudioSamplingRate(48_000)
            setOrientationHint((so - rot + 360) % 360)
            prepare()
        }
    }

    private fun releaseRecorder(delete: Boolean) {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        if (delete) outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val cam = camera ?: return
        val st = tv.surfaceTexture ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("ต้องอนุญาตไมโครโฟนก่อน"); return
        }
        try {
            setupRecorder()
            session?.close(); session = null
            val s = sizes[sizeIdx]
            st.setDefaultBufferSize(s.width, s.height)
            val ps = Surface(st)
            val rs = recorder!!.surface
            builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(ps); addTarget(rs) }
            cam.createCaptureSession(listOf(ps, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    update()
                    try {
                        recorder?.start()
                        recording = true
                        runOnUiThread { recBtn.text = "STOP" }
                    } catch (e: Exception) {
                        toast("เริ่มอัดไม่ได้: ${e.message}")
                        releaseRecorder(true); startPreview()
                    }
                }
                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    toast("ความละเอียด/เฟรมเรตนี้ไม่รองรับ")
                    releaseRecorder(true); startPreview()
                }
            }, bg)
        } catch (e: Exception) {
            toast("เริ่มอัดไม่ได้: ${e.message}")
            releaseRecorder(true); startPreview()
        }
    }

    private fun stopRecording(restart: Boolean = true) {
        if (!recording) return
        recording = false
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        releaseRecorder(false)
        runOnUiThread { recBtn.text = "REC" }
        toast("บันทึกแล้วที่ Movies/ManualCam")
        if (restart) startPreview()
    }
}
