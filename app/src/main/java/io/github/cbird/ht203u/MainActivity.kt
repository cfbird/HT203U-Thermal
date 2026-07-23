package io.github.cbird.ht203u

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 1
        private const val TAG = "ht203u"
    }

    private val traceBuf = ArrayDeque<String>()

    private fun dlog(m: String) {
        android.util.Log.i(TAG, m)
        synchronized(traceBuf) {
            traceBuf.addLast(m)
            while (traceBuf.size > 200) traceBuf.removeFirst()
        }
    }

    private var cameraHelper: ICameraHelper? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var thermalView: ImageView
    private lateinit var statusText: TextView
    private lateinit var tempText: TextView
    private lateinit var btnRange: Button
    private lateinit var btnUnit: Button
    private lateinit var sinkView: TextureView
    @Volatile private var sinkTexture: SurfaceTexture? = null
    @Volatile private var sinkAttached = false

    private val bitmap: Bitmap =
        Bitmap.createBitmap(Xtherm.WIDTH, Xtherm.HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(Xtherm.PIXELS)
    private val palette = Xtherm.ironPalette()
    private var frameU16 = ShortArray(256 * 520)

    // Negotiation fallback: candidate uncompressed modes, tried in order until frames flow
    private var candidates: List<Size> = emptyList()
    private var candIdx = 0
    @Volatile private var curW = Xtherm.WIDTH
    @Volatile private var curH = Xtherm.FRAME_HEIGHT
    @Volatile private var radiometricSeen = false
    private var triedTallSwitch = false

    @Volatile private var highRange = false
    @Volatile private var fahrenheit = false
    @Volatile private var rawModeRequested = false
    @Volatile private var framesReceived = 0L
    @Volatile private var lastFrameBytes = 0

    private var tempTable: FloatArray? = null
    private var frameCount = 0L

    private var previewRestarts = 0
    @Volatile private var permissionRetried = false
    @Volatile private var requestingDevice = false

    private val watchdog = object : Runnable {
        override fun run() {
            val helper = cameraHelper
            if (helper != null && rawModeRequested && helper.isCameraOpened) {
                if (framesReceived == 0L) {
                    // negotiation likely failed (libuvc INVALID_MODE) — try the next mode
                    if (candIdx + 1 < candidates.size) {
                        candIdx++
                        applySize(candidates[candIdx], "no frames in previous mode")
                    } else {
                        statusText.text =
                            "No mode delivered frames (last buffer: $lastFrameBytes B) — tap Log and share it"
                    }
                } else if (!radiometricSeen && !triedTallSwitch && curH < 384) {
                    // frames flow but no thermal data found; try a stacked (tall) mode once
                    triedTallSwitch = true
                    candidates.firstOrNull { it.height >= 384 }?.let {
                        applySize(it, "video-only frames, trying stacked mode")
                    }
                }
            }
            mainHandler.postDelayed(this, 3000)
        }
    }

    private fun applySize(size: Size, reason: String) {
        val helper = cameraHelper ?: return
        dlog("applySize ${size.width}x${size.height}@${size.fps} ($reason)")
        statusText.text = "Trying mode ${size.width}x${size.height}…"
        try {
            helper.stopPreview()
            helper.previewSize = size
            curW = size.width
            curH = size.height
            framesReceived = 0
            frameCount = 0
            tempTable = null
            helper.startPreview()
        } catch (t: Throwable) {
            dlog("applySize failed: $t")
            statusText.text = "Mode switch failed: ${t.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        thermalView = findViewById(R.id.thermalView)
        statusText = findViewById(R.id.statusText)
        tempText = findViewById(R.id.tempText)
        btnRange = findViewById(R.id.btnRange)
        btnUnit = findViewById(R.id.btnUnit)
        statusText.setOnClickListener { retryDevice() }
        sinkView = findViewById(R.id.sinkView)
        sinkView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                sinkTexture = st
                attachSinkIfReady()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                sinkTexture = null; sinkAttached = false; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            cameraHelper?.getUVCControl()?.setZoomAbsolute(Xtherm.CMD_CALIBRATE)
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        btnRange.setOnClickListener {
            highRange = !highRange
            cameraHelper?.getUVCControl()?.setZoomAbsolute(
                if (highRange) Xtherm.CMD_RANGE_HIGH else Xtherm.CMD_RANGE_NORMAL
            )
            btnRange.setText(if (highRange) R.string.range_high else R.string.range_normal)
        }
        btnUnit.setOnClickListener {
            fahrenheit = !fahrenheit
            btnUnit.setText(if (fahrenheit) R.string.unit_f else R.string.unit_c)
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        if (!hasCameraPermission()) {
            status("Camera permission needed (Android requires it for USB cameras)…")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            initCameraHelper()
        }
        mainHandler.postDelayed(watchdog, 3000)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                status(getString(R.string.waiting))
                initCameraHelper()
            } else {
                status("Camera permission denied — USB cameras can't work without it. Grant it in App info → Permissions.")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(watchdog)
        cameraHelper?.release()
        cameraHelper = null
    }

    private fun initCameraHelper() {
        if (cameraHelper != null) return
        cameraHelper = CameraHelper().apply {
            setStateCallback(stateCallback)
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            dlog("onAttach vid=%04x pid=%04x name=%s".format(
                device.vendorId, device.productId, device.productName))
            if (requestingDevice) return
            requestingDevice = true
            status("Camera attached — requesting permission…")
            // Triggers the system USB permission dialog (required on GrapheneOS)
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            requestingDevice = false
            val param = com.serenegiant.usb.UVCParam()
            // Thermal cams routinely misreport isochronous bandwidth; always fix it up
            param.quirks = UVCCamera.UVC_QUIRK_FIX_BANDWIDTH
            cameraHelper?.openCamera(param)
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            val sizes = helper.supportedSizeList
            dlog("onCameraOpen; supported sizes: " + sizes.joinToString {
                "type=${it.type} ${it.width}x${it.height}@${it.fps}(${it.fpsList})"
            })
            // Uncompressed (thermal) modes only; prefer stacked 2x196, then single blocks
            val priority = mapOf(392 to 0, 196 to 1, 400 to 2, 200 to 3, 192 to 4, 384 to 5)
            candidates = sizes
                .filter { it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED && it.width == Xtherm.WIDTH }
                .sortedBy { priority[it.height] ?: 9 }
                .distinctBy { it.height }
            if (candidates.isEmpty()) {
                status("No ${Xtherm.WIDTH}-wide YUYV modes found — is this an HT-203U? (tap Log)")
                return
            }
            dlog("candidates: " + candidates.joinToString { "${it.width}x${it.height}" })
            candIdx = 0
            triedTallSwitch = false
            radiometricSeen = false
            helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RAW)
            attachSinkIfReady()
            applySize(candidates[0], "initial")
            rawModeRequested = true
            // Xtherm-style raw command is a no-op on HIKMICRO firmware but harmless
            mainHandler.postDelayed({ enterRawMode() }, 700)
        }

        override fun onCameraClose(device: UsbDevice) {
            sinkAttached = false
            status("Camera closed")
        }

        override fun onDeviceClose(device: UsbDevice) {}

        override fun onDetach(device: UsbDevice) {
            requestingDevice = false
            permissionRetried = false
            status(getString(R.string.waiting))
            runOnUiThread { tempText.text = "" }
        }

        override fun onCancel(device: UsbDevice) {
            requestingDevice = false
            if (!permissionRetried) {
                permissionRetried = true
                status("USB permission denied — retrying in 2s…")
                mainHandler.postDelayed({ retryDevice() }, 2000)
            } else {
                status("USB permission denied — tap this text to retry, or replug the camera")
            }
        }
    }

    private fun retryDevice() {
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        if (cameraHelper == null) initCameraHelper()
        val helper = cameraHelper ?: return
        val device = helper.deviceList?.firstOrNull()
        if (device == null) {
            status("No USB camera found — check GrapheneOS Settings → USB peripherals, then replug")
            return
        }
        requestingDevice = true
        status("Requesting USB permission…")
        helper.selectDevice(device)
    }

    private fun attachSinkIfReady() {
        val helper = cameraHelper ?: return
        val st = sinkTexture ?: return
        if (!sinkAttached && helper.isCameraOpened) {
            st.setDefaultBufferSize(Xtherm.WIDTH, Xtherm.FRAME_HEIGHT)
            helper.addSurface(st, false)
            sinkAttached = true
        }
    }

    private fun enterRawMode() {
        val control = cameraHelper?.getUVCControl() ?: return
        try {
            val limits = control.updateZoomAbsoluteLimit()
            dlog("zoom limits: ${limits?.joinToString()}; sending 0x8004")
        } catch (t: Throwable) {
            dlog("zoom limit query failed: $t")
        }
        control.setZoomAbsolute(Xtherm.CMD_RAW_MODE)
        dlog("readback zoom=0x%x".format(runCatching { control.zoomAbsolute }.getOrDefault(-1)))
        rawModeRequested = true
        // Shutter calibration shortly after entering raw mode
        mainHandler.postDelayed({
            cameraHelper?.getUVCControl()?.setZoomAbsolute(Xtherm.CMD_CALIBRATE)
        }, 400)
        status("raw-mode command sent — waiting for radiometric frames…")
    }

    private fun pickThermalSize(sizes: List<Size>): Size? =
        sizes.firstOrNull {
            it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED &&
                    it.width == Xtherm.WIDTH && it.height == Xtherm.FRAME_HEIGHT
        }

    private val frameCallback = com.serenegiant.usb.IFrameCallback { frame: ByteBuffer ->
        try {
            processFrame(frame)
        } catch (t: Throwable) {
            status("Frame error: ${t.message}")
        }
    }

    private fun processFrame(frame: ByteBuffer) {
        lastFrameBytes = frame.remaining()
        val nU16 = frame.remaining() / 2
        if (framesReceived == 0L) dlog("first frame: ${frame.remaining()} B in mode ${curW}x${curH}")
        if (nU16 < Xtherm.PIXELS) return
        framesReceived++
        frameCount++
        if (frameU16.size < nU16) frameU16 = ShortArray(nU16)
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, nU16)

        val w = Xtherm.WIDTH
        val h = if (nU16 % w == 0) nU16 / w else curH

        // 1) Xtherm-style: look for a 4-row meta block after each 192-row image block
        var k = 1
        while (k * 196 <= h) {
            val base = (k - 1) * 196
            val meta = Xtherm.parseMeta(frameU16, w * (base + Xtherm.HEIGHT))
            if (meta != null) {
                if (!radiometricSeen) { radiometricSeen = true; dlog("xtherm meta found in block $k (row $base)") }
                renderXtherm(meta, w * base)
                return
            }
            k++
        }

        // 2) HIKMICRO/TC001-style stacked frame: bottom half = raw Kelvin*64, no meta
        if (h >= 384) {
            val bottom = w * (h / 2)
            var mn = 65535; var mx = 0
            for (i in bottom until bottom + Xtherm.PIXELS) {
                val v = frameU16[i].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            // plausible temps (-40..500 °C => ~14900..49500 in K*64), non-flat field
            if (mn in 14000..50000 && mx in 14000..50000 && mx > mn + 16) {
                if (!radiometricSeen) { radiometricSeen = true; dlog("K/64 radiometric in bottom half (min=$mn max=$mx)") }
                renderK64(bottom, mn, mx)
                return
            }
        }

        // 3) nothing recognized — show luma of the top block, log stats periodically
        if (frameCount % 150L == 1L) logFrameStats(w, h)
        for (i in 0 until Xtherm.PIXELS) {
            val y = frameU16[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        show("video ${w}x$h · frame $framesReceived · no thermal data recognized yet", "")
    }

    private fun renderXtherm(meta: Xtherm.Meta, imageOff: Int) {
        if (tempTable == null || frameCount % 8 == 0L) {
            tempTable = Xtherm.tempTable(meta, highRange)
        }
        val table = tempTable ?: return
        val minRaw = meta.minRaw
        val span = (meta.maxRaw - minRaw).coerceAtLeast(1)
        for (i in 0 until Xtherm.PIXELS) {
            val v = frameU16[imageOff + i].toInt() and 0xFFFF
            pixels[i] = palette[((v - minRaw) * 255 / span).coerceIn(0, 255)]
        }
        drawMarker(meta.minX, meta.minY, 0xFF40A0FF.toInt())
        drawMarker(meta.maxX, meta.maxY, 0xFFFF4040.toInt())
        drawMarker(Xtherm.WIDTH / 2, Xtherm.HEIGHT / 2, 0xFFFFFFFF.toInt())
        show(
            "radiometric (xtherm) · frame %d · shutter %.1f°C".format(framesReceived, meta.tempShutter),
            "▼%s  ⌖%s  ▲%s".format(fmt(table[meta.minRaw]), fmt(table[meta.centerRaw]), fmt(table[meta.maxRaw]))
        )
    }

    private fun renderK64(offset: Int, mn: Int, mx: Int) {
        val span = (mx - mn).coerceAtLeast(1)
        var minI = 0; var maxI = 0
        for (i in 0 until Xtherm.PIXELS) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            if (v == mn) minI = i
            if (v == mx) maxI = i
            pixels[i] = palette[((v - mn) * 255 / span).coerceIn(0, 255)]
        }
        val center = frameU16[offset + (Xtherm.HEIGHT / 2) * Xtherm.WIDTH + Xtherm.WIDTH / 2].toInt() and 0xFFFF
        drawMarker(minI % Xtherm.WIDTH, minI / Xtherm.WIDTH, 0xFF40A0FF.toInt())
        drawMarker(maxI % Xtherm.WIDTH, maxI / Xtherm.WIDTH, 0xFFFF4040.toInt())
        drawMarker(Xtherm.WIDTH / 2, Xtherm.HEIGHT / 2, 0xFFFFFFFF.toInt())
        fun k64(v: Int) = (v / 64.0 - 273.15).toFloat()
        show(
            "radiometric (K/64) · frame $framesReceived",
            "▼%s  ⌖%s  ▲%s".format(fmt(k64(mn)), fmt(k64(center)), fmt(k64(mx)))
        )
    }

    private fun logFrameStats(w: Int, h: Int) {
        fun stats(rowStart: Int, rows: Int): String {
            var mn = 65535; var mx = 0; var sum = 0L
            val from = rowStart * w
            val to = (rowStart + rows) * w
            for (i in from until to) {
                val v = frameU16[i].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
                sum += v
            }
            return "rows $rowStart..${rowStart + rows - 1}: min=$mn max=$mx mean=${sum / (to - from)}"
        }
        dlog("frame ${w}x$h stats: " + stats(0, minOf(192, h)) +
                (if (h >= 384) " | " + stats(h / 2, minOf(192, h - h / 2)) else ""))
    }

    private fun show(statusMsg: String, temps: String) {
        runOnUiThread {
            bitmap.setPixels(pixels, 0, Xtherm.WIDTH, 0, 0, Xtherm.WIDTH, Xtherm.HEIGHT)
            thermalView.setImageBitmap(bitmap)
            thermalView.invalidate()
            tempText.text = temps
            statusText.text = statusMsg
        }
    }

    private fun fmt(celsius: Float): String =
        if (fahrenheit) "%.1f°F".format(celsius * 9f / 5f + 32f)
        else "%.1f°C".format(celsius)

    private fun drawMarker(x: Int, y: Int, color: Int) {
        for (d in -3..3) {
            val px = (x + d).coerceIn(0, Xtherm.WIDTH - 1)
            val py = (y + d).coerceIn(0, Xtherm.HEIGHT - 1)
            pixels[y.coerceIn(0, Xtherm.HEIGHT - 1) * Xtherm.WIDTH + px] = color
            pixels[py * Xtherm.WIDTH + x.coerceIn(0, Xtherm.WIDTH - 1)] = color
        }
    }

    private fun status(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    private fun showLog() {
        val sb = StringBuilder()
        sb.append("=== app trace ===\n")
        synchronized(traceBuf) { traceBuf.forEach { sb.append(it).append('\n') } }
        sb.append("\n=== logcat (native/UVC) ===\n")
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400"))
            val keys = listOf("UVC", "uvc", "libusb", "usb", "Camera", TAG, "AndroidRuntime", "libjpeg")
            proc.inputStream.bufferedReader().readLines()
                .filter { line -> keys.any { line.contains(it) } }
                .takeLast(150)
                .forEach { sb.append(it).append('\n') }
        } catch (t: Throwable) {
            sb.append("logcat read failed: $t\n")
        }
        val text = sb.toString()
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle("Debug log")
                .setMessage(text)
                .setPositiveButton("Share") { _, _ ->
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    startActivity(android.content.Intent.createChooser(i, "Share log"))
                }
                .setNeutralButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ht203u log", text))
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
}
