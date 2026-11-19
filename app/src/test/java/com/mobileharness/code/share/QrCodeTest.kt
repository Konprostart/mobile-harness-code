package com.mobileharness.code.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    @Test
    fun encodesValidTextIntoSquarereMatrix() {
        val m = QrCode.encode("https://example.com")
        assertNotNull(m)
        m!!
        assertTrue(m.isNotEmpty())
        assertEquals(m.size, m[0].size)
        val hasDark = m.any { row -> row.any { it } }
        val hasLight = m.any { row -> row.any { !it } }
        assertTrue("must contain dark modules", hasDark)
        assertTrue("must contain light modules", hasLight)
    }

    @Test
    fun blankInputFailsClosed() {
        assertNull(QrCode.encode(""))
        assertNull(QrCode.encode("   "))
    }

    @Test
    fun oversizedInputFailsClosed() {
        val huge = "x".repeat(1500)
        assertNull(QrCode.encode(huge))
    }

    @Test
    fun encodingIsDeterministic() {
        val a = QrCode.encode("https://example.com/app")!!
        val b = QrCode.encode("https://example.com/app")!!
        assertTrue(matricesEqual(a, b))
    }

    @Test
    fun quietZoneCornerIsWhite() {
        val m = QrCode.encode("https://example.com")!!
        assertFalse("top-left quiet-zone cell must be light", m[0][0])
        assertFalse("bottom-right quiet-zone cell must be light", m[m.size - 1][m.size - 1])
    }

    @Test
    fun containsThreeFinderPatterns() {
        val m = QrCode.encode("https://github.com/Konprostart/mobile-harness-code")!!
        val finders = (0..m.size - 7).sumOf { r ->
            (0..m.size - 7).count { c -> hasFinderAt(m, r, c) }
        }
        assertTrue("expected >= 3 finder patterns, got $finders", finders >= 3)
    }

    private fun matricesEqual(a: Array<BooleanArray>, b: Array<BooleanArray>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (!a[i].contentEquals(b[i])) return false
        return true
    }

    private fun hasFinderAt(m: Array<BooleanArray>, r0: Int, c0: Int): Boolean {
        for (r in r0 until r0 + 7) {
            for (c in c0 until c0 + 7) {
                val onBorder = r == r0 || r == r0 + 6 || c == c0 || c == c0 + 6
                val inCenter = r in (r0 + 2)..(r0 + 4) && c in (c0 + 2)..(c0 + 4)
                val expected = onBorder || inCenter
                if (m[r][c] != expected) return false
            }
        }
        return true
    }
}
