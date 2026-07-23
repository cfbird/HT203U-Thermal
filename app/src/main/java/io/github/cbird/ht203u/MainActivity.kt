package io.github.cbird.ht203u

import android.app.Activity
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
    private val frameU16 = ShortArray(Xtherm.FRAME_U16)

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
            if (helper != null && rawModeRequested && framesReceived == 0L) {
                if (previewRestarts < 2 && helper.isCameraOpened) {
                    // one gentle kick: renegotiate the stream
                    previewRestarts++
                    statusText.text = "No frames — restarting stream (attempt $previewRestarts)…"
                    try {
                        helper.stopPreview()
                        helper.startPreview()
                    } catch (t: Throwable) {
                        statusText.text = "Stream restart failed: ${t.message}"
                    }
                } else {
                    statusText.text =
                        "Stream started but no frames arriving (last buffer: $lastFrameBytes B) — try replugging"
                }
            }
            mainHandler.postDelayed(this, 3000)
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

    override fun onStart() {
        super.onStart()
        initCameraHelper()
        mainHandler.postDelayed(watchdog, 3000)
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
            val size = pickThermalSize(helper.supportedSizeList)
            if (size == null) {
                status("No ${Xtherm.WIDTH}x${Xtherm.FRAME_HEIGHT} YUYV mode found — is this an HT-203U?")
                return
            }
            helper.previewSize = size
            helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RAW)
            attachSinkIfReady()
            helper.startPreview()
            status("Streaming ${size.width}x${size.height} — switching to raw mode…")
            rawModeRequested = false
            // Give the stream a moment to start, then switch to 16-bit radiometric mode
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
        control.setZoomAbsolute(Xtherm.CMD_RAW_MODE)
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
        if (frame.remaining() < Xtherm.FRAME_U16 * 2) return
        framesReceived++
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, Xtherm.FRAME_U16)

        val meta = Xtherm.parseMeta(frameU16)
        if (meta == null) {
            // Still in visible-video mode: render the YUYV luma so the user sees
            // *something*, and keep retrying the raw-mode switch (~1x per second)
            if (rawModeRequested && frameCount % 25 == 0L) {
                cameraHelper?.getUVCControl()?.setZoomAbsolute(Xtherm.CMD_RAW_MODE)
            }
            frameCount++
            for (i in 0 until Xtherm.PIXELS) {
                val y = frameU16[i].toInt() and 0xFF   // low byte = luma
                pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
            runOnUiThread {
                bitmap.setPixels(pixels, 0, Xtherm.WIDTH, 0, 0, Xtherm.WIDTH, Xtherm.HEIGHT)
                thermalView.setImageBitmap(bitmap)
                thermalView.invalidate()
                tempText.text = ""
                statusText.text =
                    "video mode · frame $framesReceived · waiting for raw switch (0x8004)"
            }
            return
        }

        // Rebuild the temperature table every 8 frames (calibration drifts slowly)
        if (tempTable == null || frameCount % 8 == 0L) {
            tempTable = Xtherm.tempTable(meta, highRange)
        }
        val table = tempTable ?: return
        frameCount++

        // Auto-exposure straight from the camera-computed min/max
        val minRaw = meta.minRaw
        val span = (meta.maxRaw - minRaw).coerceAtLeast(1)
        for (i in 0 until Xtherm.PIXELS) {
            val v = frameU16[i].toInt() and 0xFFFF
            val idx = ((v - minRaw) * 255 / span).coerceIn(0, 255)
            pixels[i] = palette[idx]
        }
        drawMarker(meta.minX, meta.minY, 0xFF40A0FF.toInt())
        drawMarker(meta.maxX, meta.maxY, 0xFFFF4040.toInt())
        drawMarker(Xtherm.WIDTH / 2, Xtherm.HEIGHT / 2, 0xFFFFFFFF.toInt())

        val tMin = table[meta.minRaw]
        val tMax = table[meta.maxRaw]
        val tCenter = table[meta.centerRaw]

        runOnUiThread {
            bitmap.setPixels(pixels, 0, Xtherm.WIDTH, 0, 0, Xtherm.WIDTH, Xtherm.HEIGHT)
            thermalView.setImageBitmap(bitmap)
            thermalView.invalidate()
            tempText.text = "▼%s  ⌖%s  ▲%s".format(fmt(tMin), fmt(tCenter), fmt(tMax))
            statusText.text = "radiometric · frame %d · shutter %.1f°C · fpa %.1f°C".format(
                framesReceived, meta.tempShutter, meta.tempFpa
            )
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
}
