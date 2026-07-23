package io.github.cbird.ht203u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reference values computed with an independent Python implementation of the
 * ht301_hacklib math (see project README).
 */
class XthermTest {

    private fun putF32(f: ShortArray, off: Int, v: Float) {
        val bits = java.lang.Float.floatToIntBits(v)
        f[off] = (bits and 0xFFFF).toShort()
        f[off + 1] = (bits ushr 16).toShort()
    }

    private fun syntheticFrame(): ShortArray {
        val f = ShortArray(Xtherm.FRAME_U16)
        val m = Xtherm.PIXELS
        f[m + 1] = 8165.toShort()          // fpaTmp -> tempFpa ~= 31.995
        f[m + 2] = 100; f[m + 3] = 50      // max x,y
        f[m + 4] = 9000.toShort()          // maxRaw
        f[m + 5] = 10; f[m + 6] = 20       // min x,y
        f[m + 7] = 7000.toShort()          // minRaw
        f[m + 8] = 8000.toShort()          // avgRaw
        f[m + 12] = 8000.toShort()         // centerRaw
        f[m + 256] = 8000.toShort()        // cal00
        f[m + 257] = 3031.toShort()        // shutter -> 29.95 C
        f[m + 258] = 3100.toShort()        // core temp
        putF32(f, m + 259, 4.0f)           // cal01
        putF32(f, m + 261, 100.0f)         // cal02
        putF32(f, m + 263, 0.01f)          // cal03
        putF32(f, m + 265, 0.5f)           // cal04
        putF32(f, m + 267, 800.0f)         // cal05
        val ua = m + 256 + 127
        putF32(f, ua, 0.0f)                // correction
        putF32(f, ua + 2, 25.0f)           // reflected
        putF32(f, ua + 4, 25.0f)           // air
        putF32(f, ua + 6, 0.45f)           // humidity
        putF32(f, ua + 8, 0.95f)           // emissivity
        f[ua + 10] = 1                     // distance
        return f
    }

    @Test
    fun parseMeta_readsFields() {
        val meta = Xtherm.parseMeta(syntheticFrame())
        assertNotNull(meta)
        meta!!
        assertEquals(29.95, meta.tempShutter, 1e-9)
        assertEquals(31.9951170320, meta.tempFpa, 1e-6)
        assertEquals(9000, meta.maxRaw)
        assertEquals(7000, meta.minRaw)
        assertEquals(8000, meta.centerRaw)
        assertEquals(0.95, meta.emissivity, 1e-6)
        assertEquals(0.45, meta.humidity, 1e-6)
        assertEquals(25.0, meta.tempAir, 1e-6)
        assertEquals(1, meta.distance)
    }

    @Test
    fun parseMeta_rejectsVideoModeFrame() {
        // all zeros: maxRaw == 0 -> not a raw radiometric frame
        assertNull(Xtherm.parseMeta(ShortArray(Xtherm.FRAME_U16)))
    }

    @Test
    fun tempTable_matchesReferenceImplementation() {
        val meta = Xtherm.parseMeta(syntheticFrame())!!
        val table = Xtherm.tempTable(meta, highRange = false)
        assertEquals(890.217099, table[4000].toDouble(), 0.02)
        assertEquals(407.748100, table[7000].toDouble(), 0.02)
        assertEquals(184.576217, table[8000].toDouble(), 0.02)
        assertEquals(490.373019, table[9000].toDouble(), 0.02)
        assertEquals(931.326981, table[12000].toDouble(), 0.02)
    }

    @Test
    fun tempTable_highRangeAppliesLinearCorrection() {
        val meta = Xtherm.parseMeta(syntheticFrame())!!
        val normal = Xtherm.tempTable(meta, highRange = false)
        val high = Xtherm.tempTable(meta, highRange = true)
        assertEquals(normal[8000] * 1.17 - 40.9, high[8000].toDouble(), 0.01)
    }
}
