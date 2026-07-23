package io.github.cbird.ht203u

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 1
        private const val TAG = "ht203u"
        private const val ACTION_USB_PERMISSION = "io.github.cbird.ht203u.USB_PERMISSION"
    }

    private val traceBuf = ArrayDeque<String>()

    private fun dlog(m: String) {
        android.util.Log.i(TAG, m)
        synchronized(traceBuf) {
            traceBuf.addLast(m)
            while (traceBuf.size > 250) traceBuf.removeFirst()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private lateinit var thermalView: ImageView
    private lateinit var statusText: TextView
    private lateinit var tempText: TextView
    private lateinit var btnUnit: Button

    private var bitmap: Bitmap = Bitmap.createBitmap(Xtherm.WIDTH, Xtherm.HEIGHT, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(Xtherm.PIXELS)
    private val palette = Xtherm.ironPalette()
    private var frameU16 = ShortArray(256 * 520)

    private var usbConn: UsbDeviceConnection? = null
    private var bulk: BulkUvc? = null
    private var usbDevice: UsbDevice? = null
    private var modes: List<BulkUvc.FrameDesc> = emptyList()
    private var modeIdx = 0
    @Volatile private var curW = Xtherm.WIDTH
    @Volatile private var curH = Xtherm.FRAME_HEIGHT
    @Volatile private var radiometricSeen = false

    @Volatile private var fahrenheit = false
    @Volatile private var streaming = false
    @Volatile private var dumpRequested = false
    @Volatile private var framesReceived = 0L
    @Volatile private var lastFrameBytes = 0
    private var receiversRegistered = false

    private var tempTable: FloatArray? = null
    private var frameCount = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (streaming && framesReceived == 0L) {
                if (modeIdx + 1 < modes.size) {
                    modeIdx++
                    startMode("no frames in previous mode")
                } else {
                    statusText.text =
                        "No mode delivered frames (last buffer: $lastFrameBytes B) — tap Log and share it"
                }
            }
            mainHandler.postDelayed(this, 3500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        thermalView = findViewById(R.id.thermalView)
        statusText = findViewById(R.id.statusText)
        tempText = findViewById(R.id.tempText)
        btnUnit = findViewById(R.id.btnUnit)

        findViewById<Button>(R.id.btnCalibrate).setOnClickListener { xuProbe() }
        findViewById<Button>(R.id.btnRange).setOnClickListener {
            dumpRequested = true
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        btnUnit.setOnClickListener {
            fahrenheit = !fahrenheit
            btnUnit.setText(if (fahrenheit) R.string.unit_f else R.string.unit_c)
        }
        statusText.setOnClickListener { scanForDevice() }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        registerReceivers()
        if (!hasCameraPermission()) {
            status("Camera permission needed (Android requires it for USB cameras)…")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            scanForDevice()
        }
        mainHandler.postDelayed(watchdog, 3500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                scanForDevice()
            } else {
                status("Camera permission denied — USB cameras can't work without it. Grant it in App info → Permissions.")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(watchdog)
        stopAll()
        if (receiversRegistered) {
            unregisterReceiver(usbReceiver)
            receiversRegistered = false
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        receiversRegistered = true
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    dlog("usb permission result: granted=$granted dev=${device?.productName}")
                    if (granted && device != null) {
                        openAndStart(device)
                    } else {
                        status("USB permission denied — tap this text to retry, or replug the camera")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    dlog("device attached")
                    if (hasCameraPermission()) scanForDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    dlog("device detached")
                    stopAll()
                    status(getString(R.string.waiting))
                    runOnUiThread { tempText.text = "" }
                }
            }
        }
    }

    private fun hasVideoInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    private fun scanForDevice() {
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val device = usbManager.deviceList.values.firstOrNull { hasVideoInterface(it) }
        if (device == null) {
            status("No USB camera found — check GrapheneOS USB peripheral settings, then replug")
            return
        }
        dlog("found device vid=%04x pid=%04x name=%s".format(
            device.vendorId, device.productId, device.productName))
        if (usbManager.hasPermission(device)) {
            openAndStart(device)
        } else {
            status("Requesting USB permission…")
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun openAndStart(device: UsbDevice) {
        stopAll()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            status("openDevice failed — replug and retry")
            dlog("openDevice returned null")
            return
        }
        usbDevice = device
        usbConn = conn
        conn.rawDescriptors?.let { dlog(UsbDesc.summarize(it)) }

        val uvc = BulkUvc(device, conn, ::onBulkFrame, ::dlog)
        bulk = uvc
        val all = uvc.parseDescriptors()
        if (all.isEmpty()) {
            status("No uncompressed frame descriptors found — tap Log")
            return
        }
        // Preference: stacked 256x392 first (image+thermal), then the rest
        val prio = mapOf(392 to 0, 196 to 1, 400 to 2, 200 to 3, 192 to 4)
        modes = all.sortedBy { (if (it.width == 256) 0 else 10) + (prio[it.height] ?: 9) }
        dlog("mode order: " + modes.joinToString { "${it.width}x${it.height}" })
        modeIdx = 0
        radiometricSeen = false
        streaming = true
        startMode("initial")
    }

    private fun startMode(reason: String) {
        val uvc = bulk ?: return
        val fd = modes.getOrNull(modeIdx) ?: return
        dlog("startMode ${fd.width}x${fd.height} ($reason)")
        status("Trying mode ${fd.width}x${fd.height}…")
        curW = fd.width
        curH = fd.height
        framesReceived = 0
        frameCount = 0
        tempTable = null
        if (!uvc.start(fd)) {
            status("Mode ${fd.width}x${fd.height} failed to start — tap Log")
        }
    }

    /** Read-only walk of the HIKMICRO vendor extension unit (id 10, VC interface 0). */
    private fun xuProbe() {
        val conn = usbConn ?: run { status("No device connected"); return }
        status("Probing XU controls…")
        Thread {
            dlog("XU probe: unit=10 vcIf=0")
            for (sel in 1..15) {
                val info = ByteArray(1)
                val ri = conn.controlTransfer(0xA1, 0x86, sel shl 8, (10 shl 8), info, 1, 300)
                val lenBuf = ByteArray(2)
                val rl = conn.controlTransfer(0xA1, 0x85, sel shl 8, (10 shl 8), lenBuf, 2, 300)
                val len = if (rl >= 2)
                    (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8) else 0
                var curHex = ""
                if (len in 1..64) {
                    val cur = ByteArray(len)
                    val rc = conn.controlTransfer(0xA1, 0x81, sel shl 8, (10 shl 8), cur, len, 300)
                    curHex = if (rc > 0) cur.take(rc).joinToString("") { "%02x".format(it) } else "err$rc"
                }
                dlog("XU sel=%d info=%s len=%d cur=%s".format(
                    sel, if (ri > 0) "%02x".format(info[0]) else "err$ri", len, curHex))
            }
            status("XU probe done — tap Log and share")
        }.start()
    }

    private fun stopAll() {
        streaming = false
        bulk?.close()
        bulk = null
        usbConn?.close()
        usbConn = null
        usbDevice = null
    }

    private fun onBulkFrame(frame: ByteBuffer) {
        try {
            processFrame(frame)
        } catch (t: Throwable) {
            dlog("frame error: $t")
        }
    }

    private fun ensurePixels(n: Int) {
        if (pixels.size < n) pixels = IntArray(n)
    }

    /** Fraction of sampled pixels at [off] that fall inside [lo..hi] (±256). */
    private fun inRange(off: Int, lo: Int, hi: Int): Double {
        var hit = 0
        var total = 0
        var i = off
        val end = off + Xtherm.PIXELS
        while (i < end) {
            val v = frameU16[i].toInt() and 0xFFFF
            if (v >= lo - 256 && v <= hi + 256) hit++
            total++
            i += 97
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun processFrame(frame: ByteBuffer) {
        lastFrameBytes = frame.remaining()
        if (framesReceived == 0L) {
            dlog("first frame: ${frame.remaining()} B in mode ${curW}x${curH}")
        }
        framesReceived++
        frameCount++

        val nU16 = frame.remaining() / 2
        if (nU16 < curW * 100) return
        if (frameU16.size < nU16) frameU16 = ShortArray(nU16)
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, nU16)

        val w = curW
        val h = if (nU16 % w == 0) nU16 / w else curH

        // 1) Xtherm-style meta blocks (256-wide layouts only).
        // The meta's raw min/max tells us which block holds the matching raw
        // thermal data — HIKMICRO puts a YUY2 display image in one block and
        // the radiometric array in another.
        if (w == 256) {
            var k = 1
            while (k * 196 <= h) {
                val base = (k - 1) * 196
                val meta = Xtherm.parseMeta(frameU16, w * (base + Xtherm.HEIGHT))
                if (meta != null) {
                    val cands = ArrayList<Int>(3)
                    cands.add(w * base)                                            // same block
                    if (w * (base + 196) + Xtherm.PIXELS <= nU16) cands.add(w * (base + 196)) // next block
                    if (base >= 196) cands.add(w * (base - 196))                   // previous block
                    val best = cands.maxByOrNull { inRange(it, meta.minRaw, meta.maxRaw) } ?: (w * base)
                    if (!radiometricSeen) {
                        radiometricSeen = true
                        dlog("xtherm meta in block $k: minRaw=${meta.minRaw} maxRaw=${meta.maxRaw} " +
                                "center=${meta.centerRaw} shutter=%.1f fpa=%.1f emiss=%.2f".format(
                                    meta.tempShutter, meta.tempFpa, meta.emissivity))
                        dlog("image block scores: " + cands.joinToString {
                            "row ${it / w}: %.2f".format(inRange(it, meta.minRaw, meta.maxRaw))
                        } + " -> using row ${best / w}")
                    }
                    if (frameCount % 300L == 2L) logFrameStats(w, h)
                    renderXtherm(meta, best)
                    return
                }
                k++
            }
        }

        // 2) HIKMICRO stacked layout: one block is the rendered YUY2 image
        // (chroma byte pinned to 0x80), the other is raw thermal counts.
        if (w == 256 && h >= 384) {
            fun chromaFrac(rowStart: Int): Double {
                var hit = 0; var total = 0
                var i = w * rowStart
                val end = i + Xtherm.PIXELS
                while (i < end) {
                    if (((frameU16[i].toInt() shr 8) and 0xFF) == 0x80) hit++
                    total++; i += 101
                }
                return hit.toDouble() / total
            }
            val f0 = chromaFrac(0)
            val f1 = chromaFrac(196)
            val rawRow = when {
                f0 < 0.2 && f1 > 0.8 -> 0
                f1 < 0.2 && f0 > 0.8 -> 196
                else -> -1
            }
            if (rawRow >= 0) {
                if (!radiometricSeen) {
                    radiometricSeen = true
                    dlog("HIK layout: raw block at row $rawRow (chroma frac row0=%.2f row196=%.2f)".format(f0, f1))
                    dumpMetaRows(w, h)
                }
                if (dumpRequested) {
                    dumpRequested = false
                    logFrameStats(w, h)
                    dumpMetaRows(w, h)
                    status("meta rows dumped — tap Log")
                }
                renderHikRaw(w * rawRow)
                return
            }
        }

        // 3) Stacked frame: bottom half raw Kelvin*64 (TC001 style)
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
        show("video ${w}x$h · frame $framesReceived · no thermal data recognized yet", "", w, rows)
    }

    private fun renderXtherm(meta: Xtherm.Meta, imageOff: Int) {
        if (tempTable == null || frameCount % 8 == 0L) {
            tempTable = Xtherm.tempTable(meta, false)
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

    private fun renderHikRaw(offset: Int) {
        val w = Xtherm.WIDTH
        val rows = Xtherm.HEIGHT
        var mn = 65535; var mx = 0; var minI = 0; var maxI = 0
        for (i in 0 until w * rows) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            if (v < mn) { mn = v; minI = i }
            if (v > mx) { mx = v; maxI = i }
        }
        val span = (mx - mn).coerceAtLeast(1)
        ensurePixels(w * rows)
        for (i in 0 until w * rows) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            pixels[i] = palette[((v - mn) * 255 / span).coerceIn(0, 255)]
        }
        val center = frameU16[offset + (rows / 2) * w + w / 2].toInt() and 0xFFFF
        drawMarker(minI % w, minI / w, w, rows, 0xFF40A0FF.toInt())
        drawMarker(maxI % w, maxI / w, w, rows, 0xFFFF4040.toInt())
        drawMarker(w / 2, rows / 2, w, rows, 0xFFFFFFFF.toInt())
        show(
            "raw thermal (HIK) · frame $framesReceived · scale not yet calibrated",
            "▼$mn  ⌖$center  ▲$mx (raw counts)",
            w, rows
        )
    }

    private fun dumpMetaRows(w: Int, h: Int) {
        for (row in intArrayOf(192, 193, 194, 195, 388, 389, 390, 391)) {
            if (row >= h) continue
            val off = row * w
            if (off + 32 > frameU16.size) continue
            val hex = (0 until 32).joinToString(" ") {
                "%04x".format(frameU16[off + it].toInt() and 0xFFFF)
            }
            dlog("row $row: $hex")
        }
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
        sb.append("\n=== logcat ===\n")
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400"))
            val keys = listOf("usb", "Usb", "USB", TAG, "AndroidRuntime", "bulkuvc")
            proc.inputStream.bufferedReader().readLines()
                .filter { line -> keys.any { line.contains(it) } }
                .takeLast(120)
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
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(i, "Share log"))
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
