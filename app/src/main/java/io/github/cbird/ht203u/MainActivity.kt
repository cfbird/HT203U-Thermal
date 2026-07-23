package io.github.cbird.ht203u

import android.app.Activity
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val bitmap: Bitmap =
        Bitmap.createBitmap(Xtherm.WIDTH, Xtherm.HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(Xtherm.PIXELS)
    private val palette = Xtherm.ironPalette()
    private val frameU16 = ShortArray(Xtherm.FRAME_U16)

    @Volatile private var highRange = false
    @Volatile private var fahrenheit = false
    @Volatile private var rawModeRequested = false

    private var tempTable: FloatArray? = null
    private var frameCount = 0L

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
    }

    override fun onStop() {
        super.onStop()
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
            status("Camera attached — requesting permission…")
            // Triggers the system USB permission dialog (required on GrapheneOS)
            cameraHelper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            cameraHelper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            val size = pickThermalSize(helper.supportedSizeList)
            if (size == null) {
                status("No ${Xtherm.WIDTH}x${Xtherm.FRAME_HEIGHT} YUYV mode found — is this an HT-203U?")
                return
            }
            helper.previewSize = size
            helper.startPreview()
            helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RAW)
            status("Streaming ${size.width}x${size.height} — switching to raw mode…")
            rawModeRequested = false
            // Give the stream a moment to start, then switch to 16-bit radiometric mode
            mainHandler.postDelayed({ enterRawMode() }, 700)
        }

        override fun onCameraClose(device: UsbDevice) {
            status("Camera closed")
        }

        override fun onDeviceClose(device: UsbDevice) {}

        override fun onDetach(device: UsbDevice) {
            status(getString(R.string.waiting))
            runOnUiThread { tempText.text = "" }
        }

        override fun onCancel(device: UsbDevice) {
            status("USB permission denied")
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
        status("Raw radiometric mode")
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
        if (frame.remaining() < Xtherm.FRAME_U16 * 2) return
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, Xtherm.FRAME_U16)

        val meta = Xtherm.parseMeta(frameU16)
        if (meta == null) {
            // Still in visible-video mode; retry the mode switch occasionally
            if (rawModeRequested && frameCount % 50 == 0L) {
                mainHandler.post { enterRawMode() }
            }
            frameCount++
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
            statusText.text = "raw mode · shutter %.1f°C · fpa %.1f°C".format(
                meta.tempShutter, meta.tempFpa
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
