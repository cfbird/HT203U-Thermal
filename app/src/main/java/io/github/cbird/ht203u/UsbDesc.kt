package io.github.cbird.ht203u

/** Minimal USB descriptor walker: video formats (with GUID) and endpoints. */
object UsbDesc {
    fun summarize(raw: ByteArray): String {
        val sb = StringBuilder("USB descriptors:\n")
        var i = 0
        var inVideoStreaming = false
        while (i + 1 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            when (raw[i + 1].toInt() and 0xFF) {
                0x04 -> { // interface
                    val num = raw[i + 2].toInt() and 0xFF
                    val alt = raw[i + 3].toInt() and 0xFF
                    val nEp = raw[i + 4].toInt() and 0xFF
                    val cls = raw[i + 5].toInt() and 0xFF
                    val sub = raw[i + 6].toInt() and 0xFF
                    inVideoStreaming = cls == 0x0E && sub == 0x02
                    sb.append("IF#%d alt=%d cls=%02x sub=%02x eps=%d\n".format(num, alt, cls, sub, nEp))
                }
                0x24 -> if (inVideoStreaming && len >= 5) {
                    when (raw[i + 2].toInt() and 0xFF) {
                        0x04 -> if (len >= 22) { // VS_FORMAT_UNCOMPRESSED
                            val guid = raw.copyOfRange(i + 5, i + 21)
                            val fourcc = guid.take(4).map {
                                val c = (it.toInt() and 0xFF).toChar()
                                if (c.code in 32..126) c else '?'
                            }.joinToString("")
                            sb.append(
                                "  FMT_UNCOMP idx=%d frames=%d fourcc='%s' bpp=%d guid=%s\n".format(
                                    raw[i + 3].toInt() and 0xFF, raw[i + 4].toInt() and 0xFF,
                                    fourcc, raw[i + 21].toInt() and 0xFF,
                                    guid.joinToString("") { "%02x".format(it) })
                            )
                        }
                        0x06 -> sb.append(
                            "  FMT_MJPEG idx=%d frames=%d\n".format(
                                raw[i + 3].toInt() and 0xFF, raw[i + 4].toInt() and 0xFF)
                        )
                        0x10 -> if (len >= 22) { // VS_FORMAT_FRAME_BASED (also carries a GUID)
                            val guid = raw.copyOfRange(i + 5, i + 21)
                            val fourcc = guid.take(4).map {
                                val c = (it.toInt() and 0xFF).toChar()
                                if (c.code in 32..126) c else '?'
                            }.joinToString("")
                            sb.append(
                                "  FMT_FRAME_BASED idx=%d frames=%d fourcc='%s' bpp=%d\n".format(
                                    raw[i + 3].toInt() and 0xFF, raw[i + 4].toInt() and 0xFF,
                                    fourcc, raw[i + 21].toInt() and 0xFF)
                            )
                        }
                    }
                }
                0x05 -> { // endpoint
                    val addr = raw[i + 2].toInt() and 0xFF
                    val attr = raw[i + 3].toInt() and 0xFF
                    val mp = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                    val kind = when (attr and 3) { 0 -> "ctrl"; 1 -> "iso"; 2 -> "bulk"; else -> "intr" }
                    sb.append("  EP 0x%02x %s maxPkt=%d\n".format(addr, kind, mp))
                }
            }
            i += len
        }
        return sb.toString()
    }
}
