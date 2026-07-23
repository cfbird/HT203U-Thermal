package io.github.cbird.ht203u

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Radiometry for XTherm/InfiRay-family 256x192 cores (Hti HT-203U, T2S+, T2L…).
 *
 * The camera streams UVC "YUYV" frames of 256 x 196. Interpreted as little-endian
 * uint16, the first 192 rows are the image (raw 14-bit values after the 0x8004
 * command) and the last 4 rows are metadata: calibration coefficients, shutter/FPA
 * temperatures, user parameters (emissivity etc.) and precomputed min/max/center.
 *
 * Math ported from stawel/ht301_hacklib (GPL-3.0).
 */
object Xtherm {
    const val WIDTH = 256
    const val HEIGHT = 192
    const val META_ROWS = 4
    const val FRAME_HEIGHT = HEIGHT + META_ROWS
    const val PIXELS = WIDTH * HEIGHT              // u16 offset of meta block
    const val FRAME_U16 = WIDTH * FRAME_HEIGHT
    const val TABLE_SIZE = 16384                   // 14-bit raw values

    const val ZEROC = 273.15

    // 256-wide core parameters (init_parameters in ht301_hacklib)
    private const val FPA_OFF = 8617.0
    private const val FPA_DIV = 37.682
    private const val AMOUNT_PIXELS = WIDTH        // meta stride for 256-wide cores
    private const val CAL_00_OFFSET = 170.0
    private const val CAL_00_FPAMUL = 0.0

    // UVC zoom-absolute command channel
    const val CMD_CALIBRATE = 0x8000               // trigger shutter calibration
    const val CMD_RAW_MODE = 0x8004                // switch stream to raw 16-bit
    const val CMD_SAVE = 0x80FF
    const val CMD_RANGE_NORMAL = 0x8020            // -20..120 C
    const val CMD_RANGE_HIGH = 0x8021              // -20..450 C

    data class Meta(
        val fpaAverage: Int,
        val tempFpa: Double,
        val tempShutter: Double,
        val tempCore: Double,
        val maxX: Int, val maxY: Int, val maxRaw: Int,
        val minX: Int, val minY: Int, val minRaw: Int,
        val avgRaw: Int, val centerRaw: Int,
        val correction: Double,
        val tempReflected: Double,
        val tempAir: Double,
        val humidity: Double,
        val emissivity: Double,
        val distance: Int,
        val cal00: Double,
        val cal01: Double, val cal02: Double,
        val cal03: Double, val cal04: Double, val cal05: Double,
    )

    private fun u16(f: ShortArray, off: Int): Int = f[off].toInt() and 0xFFFF

    private fun f32(f: ShortArray, off: Int): Double {
        val bits = (u16(f, off)) or (u16(f, off + 1) shl 16)
        return Float.fromBits(bits).toDouble()
    }

    private fun Double.orDefault(default: Double, min: Double, max: Double): Double =
        if (this.isFinite() && this in min..max) this else default

    /**
     * Parse 4 metadata rows located at u16 offset [metaOffset]. Returns null if
     * the values are implausible — e.g. plain video data.
     */
    fun parseMeta(frame: ShortArray, metaOffset: Int = PIXELS): Meta? {
        if (frame.size < metaOffset + META_ROWS * WIDTH) return null
        val m = metaOffset

        val maxX = u16(frame, m + 2)
        val maxY = u16(frame, m + 3)
        val maxRaw = u16(frame, m + 4)
        val minX = u16(frame, m + 5)
        val minY = u16(frame, m + 6)
        val minRaw = u16(frame, m + 7)
        val centerRaw = u16(frame, m + 12)

        // sanity: only valid in raw mode
        if (maxX >= WIDTH || maxY >= HEIGHT || minX >= WIDTH || minY >= HEIGHT) return null
        if (minRaw > maxRaw || maxRaw >= TABLE_SIZE || maxRaw == 0) return null
        if (centerRaw !in minRaw..maxRaw) return null

        val fpaTmpRaw = u16(frame, m + 1)
        val tempFpa = 20.0 - (fpaTmpRaw - FPA_OFF) / FPA_DIV
        val tempShutter = u16(frame, m + AMOUNT_PIXELS + 1) / 10.0 - ZEROC
        val tempCore = u16(frame, m + AMOUNT_PIXELS + 2) / 10.0 - ZEROC

        val userArea = m + AMOUNT_PIXELS + 127
        return Meta(
            fpaAverage = u16(frame, m),
            tempFpa = tempFpa,
            tempShutter = tempShutter,
            tempCore = tempCore,
            maxX = maxX, maxY = maxY, maxRaw = maxRaw,
            minX = minX, minY = minY, minRaw = minRaw,
            avgRaw = u16(frame, m + 8),
            centerRaw = centerRaw,
            correction = f32(frame, userArea).orDefault(0.0, -50.0, 50.0),
            tempReflected = f32(frame, userArea + 2).orDefault(25.0, -50.0, 300.0),
            tempAir = f32(frame, userArea + 4).orDefault(25.0, -50.0, 100.0),
            humidity = f32(frame, userArea + 6).orDefault(0.45, 0.0, 1.0),
            emissivity = f32(frame, userArea + 8).orDefault(0.95, 0.01, 1.0),
            distance = u16(frame, userArea + 10),
            cal00 = u16(frame, m + AMOUNT_PIXELS).toDouble(),
            cal01 = f32(frame, m + AMOUNT_PIXELS + 3),
            cal02 = f32(frame, m + AMOUNT_PIXELS + 5),
            cal03 = f32(frame, m + AMOUNT_PIXELS + 7),
            cal04 = f32(frame, m + AMOUNT_PIXELS + 9),
            cal05 = f32(frame, m + AMOUNT_PIXELS + 11),
        )
    }

    // Water vapor coefficient from humidity and ambient temperature
    private fun wvc(h: Double, tAtm: Double): Double {
        val h1 = 1.5587; val h2 = 0.06939; val h3 = -2.7816e-4; val h4 = 6.8455e-7
        return h * exp(h1 + h2 * tAtm + h3 * tAtm.pow(2) + h4 * tAtm.pow(3))
    }

    // Transmittance of the atmosphere
    private fun atmt(h: Double, tAtm: Double, d: Double): Double {
        val kAtm = 1.9
        val nsqd = -sqrt(d)
        val sqw = sqrt(wvc(h, tAtm))
        val a1 = 0.006569; val a2 = 0.01262
        val b1 = -0.002276; val b2 = -0.00667
        return kAtm * exp(nsqd * (a1 + b1 * sqw)) + (1.0 - kAtm) * exp(nsqd * (a2 + b2 * sqw))
    }

    /**
     * Build the raw-value -> temperature (°C) lookup table for a frame's metadata.
     * highRange: apply the empirical correction for the -20..450°C range.
     */
    fun tempTable(meta: Meta, highRange: Boolean = false): FloatArray {
        val distAdj = (if (meta.distance >= 20) 20.0 else meta.distance.toDouble()) * 1.0
        val atm = atmt(meta.humidity, meta.tempAir, distAdj)
        val numeratorSub = (1.0 - meta.emissivity) * atm * (meta.tempReflected + ZEROC).pow(4) +
                (1.0 - atm) * (meta.tempAir + ZEROC).pow(4)
        val denominator = meta.emissivity * atm

        val ts = meta.tempShutter
        val tfpa = meta.tempFpa
        val calA = meta.cal02 / (meta.cal01 + meta.cal01)
        val calB = meta.cal02 * meta.cal02 / (meta.cal01 * meta.cal01 * 4.0)
        val calC = meta.cal01 * ts.pow(2) + ts * meta.cal02
        val calD = meta.cal03 * tfpa.pow(2) + meta.cal04 * tfpa + meta.cal05

        val cal00Corr = (CAL_00_OFFSET - tfpa * CAL_00_FPAMUL).toInt()
        val tableOffset = meta.cal00 - (if (cal00Corr > 0) cal00Corr else 0)

        val corrM = if (highRange) 1.17 else 1.0
        val corrB = if (highRange) -40.9 else 0.0

        val table = FloatArray(TABLE_SIZE)
        for (i in 0 until TABLE_SIZE) {
            var n = sqrt(abs(((i - tableOffset) * calD + calC) / meta.cal01 + calB))
            if (n.isNaN()) n = 0.0
            val wtot = (n - calA + ZEROC).pow(4)
            val ttot = ((wtot - numeratorSub) / denominator).pow(0.25) - ZEROC
            val t = ttot + (distAdj * 0.85 - 1.125) * (ttot - meta.tempAir) / 100.0 + meta.correction
            table[i] = (corrM * t + corrB).toFloat()
        }
        return table
    }

    /** Ironbow-style palette, 256 ARGB entries. */
    fun ironPalette(): IntArray {
        val stops = intArrayOf(
            0x000000, 0x1E0A3C, 0x6A0A69, 0xA01E70,
            0xC83C50, 0xE86828, 0xF0A830, 0xF8DC50, 0xFFFFC8, 0xFFFFFF
        )
        val palette = IntArray(256)
        val seg = 255.0 / (stops.size - 1)
        for (i in 0 until 256) {
            val f = i / seg
            val idx = f.toInt().coerceAtMost(stops.size - 2)
            val t = f - idx
            val c0 = stops[idx]; val c1 = stops[idx + 1]
            val r = ((c0 shr 16 and 0xFF) * (1 - t) + (c1 shr 16 and 0xFF) * t).toInt()
            val g = ((c0 shr 8 and 0xFF) * (1 - t) + (c1 shr 8 and 0xFF) * t).toInt()
            val b = ((c0 and 0xFF) * (1 - t) + (c1 and 0xFF) * t).toInt()
            palette[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return palette
    }
}
