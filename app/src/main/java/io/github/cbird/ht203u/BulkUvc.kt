package io.github.cbird.ht203u

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer

/**
 * Minimal UVC-over-bulk client using only the standard Android USB host API.
 * Works for cameras whose VideoStreaming interface uses a bulk IN endpoint
 * (alt setting 0), like the HIKMICRO-based HT-203U.
 */
class BulkUvc(
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val onFrame: (ByteBuffer) -> Unit,
    private val log: (String) -> Unit,
) {
    data class FrameDesc(
        val formatIndex: Int, val frameIndex: Int,
        val width: Int, val height: Int, val defaultInterval: Int,
    )

    private var vsIfNum = -1
    private var vsInterface: UsbInterface? = null
    private var bulkEp: UsbEndpoint? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var claimed = false

    var frames: List<FrameDesc> = emptyList()
        private set

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun putU32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v and 0xFF).toByte(); b[i + 1] = ((v shr 8) and 0xFF).toByte()
        b[i + 2] = ((v shr 16) and 0xFF).toByte(); b[i + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /** Parse uncompressed frame descriptors from the raw config descriptor. */
    fun parseDescriptors(): List<FrameDesc> {
        val raw = conn.rawDescriptors ?: return emptyList()
        val out = ArrayList<FrameDesc>()
        var i = 0
        var inVS = false
        var curFormat = 0
        var curIsUncompressed = false
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            when (raw[i + 1].toInt() and 0xFF) {
                0x04 -> {
                    val cls = raw[i + 5].toInt() and 0xFF
                    val sub = raw[i + 6].toInt() and 0xFF
                    inVS = cls == 0x0E && sub == 0x02
                    if (inVS && vsIfNum < 0) vsIfNum = raw[i + 2].toInt() and 0xFF
                }
                0x24 -> if (inVS) {
                    when (raw[i + 2].toInt() and 0xFF) {
                        0x04 -> { curFormat = raw[i + 3].toInt() and 0xFF; curIsUncompressed = true }
                        0x06, 0x10 -> curIsUncompressed = false
                        0x05 -> if (curIsUncompressed && len >= 26) {
                            out.add(FrameDesc(
                                curFormat, raw[i + 3].toInt() and 0xFF,
                                u16(raw, i + 5), u16(raw, i + 7), u32(raw, i + 21)))
                        }
                    }
                }
            }
            i += len
        }
        frames = out
        log("bulkuvc: VS interface #$vsIfNum, uncompressed frames: " +
                out.joinToString { "[${it.frameIndex}] ${it.width}x${it.height}" })
        return out
    }

    /** Stop any running stream, negotiate [fd] and start the reader thread. */
    fun start(fd: FrameDesc): Boolean {
        stopReader()
        if (vsInterface == null) {
            for (k in 0 until device.interfaceCount) {
                val itf = device.getInterface(k)
                if (itf.id == vsIfNum && itf.interfaceClass == 14 && itf.interfaceSubclass == 2) {
                    vsInterface = itf
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_IN) bulkEp = ep
                    }
                }
            }
        }
        val itf = vsInterface ?: run { log("bulkuvc: no VS interface found"); return false }
        val ep = bulkEp ?: run { log("bulkuvc: no bulk IN endpoint"); return false }
        if (!claimed) {
            claimed = conn.claimInterface(itf, true)
            log("bulkuvc: claimInterface -> $claimed")
            if (!claimed) return false
        }

        // UVC PROBE/COMMIT negotiation
        val ctrl = ByteArray(34)
        ctrl[0] = 0x01 // bmHint: keep dwFrameInterval
        ctrl[2] = fd.formatIndex.toByte()
        ctrl[3] = fd.frameIndex.toByte()
        putU32(ctrl, 4, fd.defaultInterval)
        var r = conn.controlTransfer(0x21, 0x01, 0x0100, vsIfNum, ctrl, 26, 1000)
        if (r < 0) r = conn.controlTransfer(0x21, 0x01, 0x0100, vsIfNum, ctrl, 34, 1000)
        log("bulkuvc: PROBE SET_CUR fmt=${fd.formatIndex} frm=${fd.frameIndex} " +
                "(${fd.width}x${fd.height}) -> $r")
        val resp = ByteArray(34)
        val g = conn.controlTransfer(0xA1, 0x81, 0x0100, vsIfNum, resp, 34, 1000)
        log("bulkuvc: PROBE GET_CUR -> $g " +
                (if (g > 0) resp.take(g).joinToString("") { "%02x".format(it) } else ""))
        val commitData: ByteArray
        val commitLen: Int
        if (g >= 26) { commitData = resp; commitLen = g } else { commitData = ctrl; commitLen = 26 }
        val c = conn.controlTransfer(0x21, 0x01, 0x0200, vsIfNum, commitData, commitLen, 1000)
        log("bulkuvc: COMMIT SET_CUR -> $c")
        if (c < 0) return false

        val maxFrame = if (g >= 22) u32(resp, 18) else 0
        val maxPayload = if (g >= 26) u32(resp, 22) else 0
        log("bulkuvc: negotiated maxVideoFrameSize=$maxFrame maxPayloadTransferSize=$maxPayload")

        val expected = fd.width * fd.height * 2
        running = true
        thread = Thread { readerLoop(ep, maxOf(expected, maxFrame)) }.apply {
            name = "bulkuvc-reader"; isDaemon = true; start()
        }
        return true
    }

    private fun readerLoop(ep: UsbEndpoint, maxFrame: Int) {
        val frameBuf = ByteArray(maxFrame + 8192)
        var framePos = 0
        var lastFid = -1
        var expectHeader = true
        val chunk = ByteArray(65536)
        var delivered = 0L
        var errStreak = 0
        log("bulkuvc: reader started (frame buffer ${frameBuf.size} B)")
        while (running) {
            val n = conn.bulkTransfer(ep, chunk, chunk.size, 1000)
            if (n < 0) {
                errStreak++
                if (errStreak == 1 || errStreak % 50 == 0) log("bulkuvc: bulkTransfer -> $n (streak $errStreak)")
                if (errStreak > 300) { log("bulkuvc: giving up (persistent errors)"); break }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                continue
            }
            errStreak = 0
            if (n == 0) { expectHeader = true; continue } // ZLP = payload boundary
            if (expectHeader) {
                val hlen = chunk[0].toInt() and 0xFF
                if (hlen < 2 || hlen > 12 || hlen > n) {
                    // lost sync; drop and wait for next payload boundary
                    framePos = 0
                    expectHeader = n < chunk.size
                    continue
                }
                val flags = chunk[1].toInt() and 0xFF
                val fid = flags and 0x01
                val eof = flags and 0x02
                val err = flags and 0x40
                if (err != 0) { framePos = 0 }
                if (lastFid != -1 && fid != lastFid && framePos > 0) {
                    delivered++; onFrame(ByteBuffer.wrap(frameBuf, 0, framePos))
                    framePos = 0
                }
                lastFid = fid
                val dataLen = n - hlen
                if (dataLen > 0) {
                    if (framePos + dataLen <= frameBuf.size) {
                        System.arraycopy(chunk, hlen, frameBuf, framePos, dataLen)
                        framePos += dataLen
                    } else framePos = 0
                }
                if (eof != 0 && framePos > 0) {
                    delivered++; onFrame(ByteBuffer.wrap(frameBuf, 0, framePos))
                    framePos = 0
                }
                // full read => payload continues headerless in the next transfer
                expectHeader = n < chunk.size
            } else {
                if (framePos + n <= frameBuf.size) {
                    System.arraycopy(chunk, 0, frameBuf, framePos, n)
                    framePos += n
                } else framePos = 0
                expectHeader = n < chunk.size
            }
        }
        log("bulkuvc: reader exited (delivered $delivered frames)")
    }

    private fun stopReader() {
        running = false
        thread?.let { t -> try { t.join(700) } catch (_: InterruptedException) {} }
        thread = null
    }

    fun close() {
        stopReader()
        vsInterface?.let { if (claimed) conn.releaseInterface(it) }
        claimed = false
    }
}
