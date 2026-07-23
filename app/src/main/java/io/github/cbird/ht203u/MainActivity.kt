package io.github.cbird.ht203u

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            while (traceBuf.size > 250) traceBuf.removeFirst()
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

    private var bitmap: Bitmap = Bitmap.createBitmap(Xtherm.WIDTH, Xtherm.HEIGHT, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(Xtherm.PIXELS)
    private val palette = Xtherm.ironPalette()
    private var frameU16 = ShortArray(256 * 520)

    // Mode negotiation fallback
    private var candidates: List<Size> = emptyList()
    private var candIdx = 0
    @Volatile private var curW = Xtherm.WIDTH
    @Volatile private var curH = Xtherm.FRAME_HEIGHT
    @Volatile private var curType = UVCCamera.UVC_VS_FRAME_UNCOMPRESSED
    @Volatile private var radiometricSeen = false

    @Volatile private var highRange = false
    @Volatile private var fahrenheit = false
    @Volatile private var rawModeRequested = false
    @Volatile private var framesReceived = 0L
    @Volatile private var lastFrameBytes = 0
    @Volatile private var permissionRetried = false
    @Volatile private var requestingDevice = false

    private var tempTable: FloatArray? = null
    private var frameCount = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            val helper = cameraHelper
            if (helper != null && rawModeRequested && helper.isCameraOpened) {
                if (framesReceived == 0L) {
                    if (candIdx + 1 < candidates.size) {
                        candIdx++
                        applySize(candidates[candIdx], "no frames in previous mode")
                    } else {
                        statusText.text =
                            "No mode delivered frames (last buffer: $lastFrameBytes B) — tap Log and share it"
                    }
                }
            }
            mainHandler.postDelayed(this, 3000)
        }
    }

    private fun applySize(size: Size, reason: String) {
        val helper = cameraHelper ?: return
        dlog("applySize type=${size.type} ${size.width}x${size.height}@${size.fps} ($reason)")
        statusText.text = "Trying mode ${size.width}x${size.height} (type ${size.type})…"
        try {
            helper.stopPreview()
            helper.previewSize = size
            curW = size.width
            curH = size.height
            curType = size.type
            framesReceived = 0
            frameCount = 0
            tempTable = null
            // MJPEG frames are decoded to NV21 by the lib; uncompressed passes through raw
            helper.setFrameCallback(
                frameCallback,
                if (size.type == UVCCamera.UVC_VS_FRAME_MJPEG) UVCCamera.PIXEL_FORMAT_NV21
                else UVCCamera.PIXEL_FORMAT_RAW
            )
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
        sinkAttached = false
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
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            requestingDevice = false
            dumpDescriptors(device)
            val param = com.serenegiant.usb.UVCParam()
            param.quirks = UVCCamera.UVC_QUIRK_FIX_BANDWIDTH
            cameraHelper?.openCamera(param)
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            val sizes = helper.supportedSizeList
            dlog("onCameraOpen; supported sizes: " + sizes.joinToString {
                "type=${it.type} ${it.width}x${it.height}@${it.fps}"
            })
            // Uncompressed thermal modes first (any width), then MJPEG as video fallback
            val uncompPrio = mapOf(392 to 0, 196 to 1, 400 to 2, 200 to 3, 192 to 4)
            val uncomp = sizes
                .filter { it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED }
                .sortedBy { (if (it.width == 256) 0 else 10) + (uncompPrio[it.height] ?: 9) }
                .distinctBy { it.width to it.height }
            val mjpegPrio = mapOf(320 to 0, 160 to 1, 360 to 2)
            val mjpeg = sizes
                .filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
                .sortedBy { mjpegPrio[it.height] ?: 9 }
                .distinctBy { it.width to it.height }
            candidates = uncomp + mjpeg
            if (candidates.isEmpty()) {
                status("No video modes found at all — tap Log")
                return
            }
            dlog("candidates: " + candidates.joinToString { "t${it.type} ${it.width}x${it.height}" })
            candIdx = 0
            radiometricSeen = false
            attachSinkIfReady()
            applySize(candidates[0], "initial")
            rawModeRequested = true
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

    private fun dumpDescriptors(device: UsbDevice) {
        try {
            val um = getSystemService(USB_SERVICE) as UsbManager
            val conn = um.openDevice(device)
            if (conn == null) {
                dlog("descriptor dump: openDevice returned null")
                return
            }
            val raw = conn.rawDescriptors
            conn.close()
            if (raw != null) dlog(UsbDesc.summarize(raw)) else dlog("descriptor dump: null")
        } catch (t: Throwable) {
            dlog("descriptor dump failed: $t")
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
        mainHandler.postDelayed({
            cameraHelper?.getUVCControl()?.setZoomAbsolute(Xtherm.CMD_CALIBRATE)
        }, 400)
    }

    private val frameCallback = com.serenegiant.usb.IFrameCallback { frame: ByteBuffer ->
        try {
            processFrame(frame)
        } catch (t: Throwable) {
            dlog("frame error: $t")
            status("Frame error: ${t.message}")
        }
    }

    private fun ensurePixels(n: Int) {
        if (pixels.size < n) pixels = IntArray(n)
    }

    private fun processFrame(frame: ByteBuffer) {
        lastFrameBytes = frame.remaining()
        if (framesReceived == 0L) {
            dlog("first frame: ${frame.remaining()} B in mode t$curType ${curW}x${curH}")
        }
        framesReceived++
        frameCount++

        if (curType == UVCCamera.UVC_VS_FRAME_MJPEG) {
            renderMjpegLuma(frame)
            return
        }

        val nU16 = frame.remaining() / 2
        if (nU16 < curW * 100) return
        if (frameU16.size < nU16) frameU16 = ShortArray(nU16)
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, nU16)

        val w = curW
        val h = if (nU16 % w == 0) nU16 / w else curH

        // 1) Xtherm-style meta blocks (256-wide layouts only)
        if (w == 256) {
            var k = 1
            while (k * 196 <= h) {
                val base = (k - 1) * 196
                val meta = Xtherm.parseMeta(frameU16, w * (base + Xtherm.HEIGHT))
                if (meta != null) {
                    if (!radiometricSeen) {
                        radiometricSeen = true; dlog("xtherm meta found in block $k (row $base)")
                    }
                    renderXtherm(meta, w * base)
                    return
                }
                k++
            }
        }

        // 2) Stacked frame: bottom half raw Kelvin*64 (TC001/HIKMICRO style)
        if (h >= 2 * 190) {
            val rows = h / 2
            val bottom = w * rows
            val count = w * minOf(rows, h - rows)
            var mn = 65535; var mx = 0
            for (i in bottom until bottom + count) {
                val v = frameU16[i].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mn in 14000..50000 && mx in 14000..50000 && mx > mn + 16) {
                if (!radiometricSeen) {
                    radiometricSeen = true; dlog("K/64 radiometric in bottom half (min=$mn max=$mx)")
                }
                renderK64(bottom, mn, mx, w, minOf(rows, h - rows))
                return
            }
        }

        // 3) unknown — luma display + periodic stats
        if (frameCount % 150L == 1L) logFrameStats(w, h)
        val rows = minOf(h, 192)
        ensurePixels(w * rows)
        for (i in 0 until w * rows) {
            val y = frameU16[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        show("video t$curType ${w}x$h · frame $framesReceived · no thermal data recognized yet", "", w, rows)
    }

    private fun renderMjpegLuma(frame: ByteBuffer) {
        val w = curW
        val h = curH
        val n = w * h
        if (frame.remaining() < n) return
        val bytes = ByteArray(n)
        frame.get(bytes, 0, n)
        ensurePixels(n)
        for (i in 0 until n) {
            val y = bytes[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        show("MJPEG ${w}x$h · frame $framesReceived (video only — no radiometry in this mode)", "", w, h)
    }

    private fun renderXtherm(meta: Xtherm.Meta, imageOff: Int) {
        if (tempTable == null || frameCount % 8 == 0L) {
            tempTable = Xtherm.tempTable(meta, highRange)
        }
        val table = tempTable ?: return
        val minRaw = meta.minRaw
        val span = (meta.maxRaw - minRaw).coerceAtLeast(1)
        ensurePixels(Xtherm.PIXELS)
        for (i in 0 until Xtherm.PIXELS) {
            val v = frameU16[imageOff + i].toInt() and 0xFFFF
            pixels[i] = palette[((v - minRaw) * 255 / span).coerceIn(0, 255)]
        }
        drawMarker(meta.minX, meta.minY, Xtherm.WIDTH, Xtherm.HEIGHT, 0xFF40A0FF.toInt())
        drawMarker(meta.maxX, meta.maxY, Xtherm.WIDTH, Xtherm.HEIGHT, 0xFFFF4040.toInt())
        drawMarker(Xtherm.WIDTH / 2, Xtherm.HEIGHT / 2, Xtherm.WIDTH, Xtherm.HEIGHT, 0xFFFFFFFF.toInt())
        show(
            "radiometric (xtherm) · frame %d · shutter %.1f°C".format(framesReceived, meta.tempShutter),
            "▼%s  ⌖%s  ▲%s".format(fmt(table[meta.minRaw]), fmt(table[meta.centerRaw]), fmt(table[meta.maxRaw])),
            Xtherm.WIDTH, Xtherm.HEIGHT
        )
    }

    private fun renderK64(offset: Int, mn: Int, mx: Int, w: Int, rows: Int) {
        val span = (mx - mn).coerceAtLeast(1)
        val n = w * rows
        var minI = 0; var maxI = 0
        ensurePixels(n)
        for (i in 0 until n) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            if (v == mn) minI = i
            if (v == mx) maxI = i
            pixels[i] = palette[((v - mn) * 255 / span).coerceIn(0, 255)]
        }
        val center = frameU16[offset + (rows / 2) * w + w / 2].toInt() and 0xFFFF
        drawMarker(minI % w, minI / w, w, rows, 0xFF40A0FF.toInt())
        drawMarker(maxI % w, maxI / w, w, rows, 0xFFFF4040.toInt())
        drawMarker(w / 2, rows / 2, w, rows, 0xFFFFFFFF.toInt())
        fun k64(v: Int) = (v / 64.0 - 273.15).toFloat()
        show(
            "radiometric (K/64) · frame $framesReceived",
            "▼%s  ⌖%s  ▲%s".format(fmt(k64(mn)), fmt(k64(center)), fmt(k64(mx))),
            w, rows
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

    private fun show(statusMsg: String, temps: String, w: Int, h: Int) {
        runOnUiThread {
            if (bitmap.width != w || bitmap.height != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            thermalView.setImageBitmap(bitmap)
            thermalView.invalidate()
            tempText.text = temps
            statusText.text = statusMsg
        }
    }

    private fun fmt(celsius: Float): String =
        if (fahrenheit) "%.1f°F".format(celsius * 9f / 5f + 32f)
        else "%.1f°C".format(celsius)

    private fun drawMarker(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (d in -3..3) {
            val px = (x + d).coerceIn(0, w - 1)
            val py = (y + d).coerceIn(0, h - 1)
            pixels[y.coerceIn(0, h - 1) * w + px] = color
            pixels[py * w + x.coerceIn(0, w - 1)] = color
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
