package com.mobileharness.code.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Vt100TerminalTest {
    private fun Vt100Screen.textLines(): List<String> =
        screenLines().map { row -> row.map { it.char }.joinToString("").trimEnd() }

    private fun Vt100Screen.visibleText(): List<String> =
        visibleLines().map { row -> row.map { it.char }.joinToString("").trimEnd() }

    @Test
    fun plainTextWritesLeftToRight() {
        val s = Vt100Screen(10, 3)
        s.write("abc")
        assertEquals(listOf("abc", "", ""), s.textLines())
        assertEquals(0 to 3, s.cursorPosition())
    }

    @Test
    fun carriageReturnMovesToColumnZero() {
        val s = Vt100Screen(10, 3)
        s.write("abc\rX")
        assertEquals("Xbc", s.textLines()[0])
    }

    @Test
    fun lineFeedMovesToNextRow() {
        val s = Vt100Screen(10, 3)
        s.write("a\r\nb")
        assertEquals(listOf("a", "b", ""), s.textLines())
    }

    @Test
    fun backspaceMovesCursorBack() {
        val s = Vt100Screen(10, 3)
        s.write("ab\bc")
        assertEquals("ac", s.textLines()[0])
    }

    @Test
    fun tabAdvancesToNextTabStop() {
        val s = Vt100Screen(20, 3)
        s.write("a\tb")
        // 'a' at col 0, tab to col 8, 'b' at col 8.
        assertEquals('a', s.screenLines()[0][0].char)
        assertEquals('b', s.screenLines()[0][8].char)
    }

    @Test
    fun wrapAtRightMarginThenScrolls() {
        val s = Vt100Screen(2, 2)
        s.write("abc")
        assertEquals(listOf("ab", "c"), s.textLines())
    }

    @Test
    fun scrollbackRetainsLinesScrolledOffTop() {
        val s = Vt100Screen(4, 2, maxScrollbackLines = 100)
        s.write("a\r\nb\r\nc")
        // Screen shows b, c; scrollback holds "a".
        assertEquals(listOf("b", "c"), s.textLines())
        assertEquals(1, s.scrollbackSize())
        assertEquals(listOf("a", "b", "c"), s.visibleText())
    }

    @Test
    fun ansiColorSgrApplied() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[31mX")
        assertEquals('X', s.screenLines()[0][0].char)
        assertEquals(TerminalColor.Palette(1), s.screenLines()[0][0].style.fg)
    }

    @Test
    fun sgrResetRestoresDefault() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[31mX\u001b[0mY")
        assertEquals(TerminalColor.Palette(1), s.screenLines()[0][0].style.fg)
        assertEquals(TerminalColor.Default, s.screenLines()[0][1].style.fg)
    }

    @Test
    fun sgr256Color() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[38;5;196mX")
        assertEquals(TerminalColor.Palette256(196), s.screenLines()[0][0].style.fg)
    }

    @Test
    fun sgrTrueColor() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[38;2;1;2;3mX")
        assertEquals(TerminalColor.Rgb(1, 2, 3), s.screenLines()[0][0].style.fg)
    }

    @Test
    fun boldAndUnderlineFlags() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[1;4mX")
        val style = s.screenLines()[0][0].style
        assertTrue(style.bold)
        assertTrue(style.underline)
    }

    @Test
    fun cursorPositionCup() {
        val s = Vt100Screen(10, 5)
        s.write("\u001b[3;4H")
        assertEquals(2 to 3, s.cursorPosition())
    }

    @Test
    fun cursorMovementUpDownForwardBack() {
        val s = Vt100Screen(10, 5)
        s.write("\u001b[3;3H") // row 2, col 2
        s.write("\u001b[1A") // row 1
        assertEquals(1 to 2, s.cursorPosition())
        s.write("\u001b[1B") // row 2
        assertEquals(2 to 2, s.cursorPosition())
        s.write("\u001b[2C") // col 4
        assertEquals(2 to 4, s.cursorPosition())
        s.write("\u001b[1D") // col 3
        assertEquals(2 to 3, s.cursorPosition())
    }

    @Test
    fun eraseLineWholeClearsRow() {
        val s = Vt100Screen(10, 3)
        s.write("hello\u001b[2K")
        assertEquals("", s.textLines()[0])
    }

    @Test
    fun eraseDisplayWholeClearsScreen() {
        val s = Vt100Screen(10, 3)
        s.write("a\nb\nc")
        s.write("\u001b[2J")
        assertEquals(listOf("", "", ""), s.textLines())
    }

    @Test
    fun unknownEscapeSequenceIgnored() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[999zhello")
        assertEquals("hello", s.textLines()[0])
    }

    @Test
    fun oscHyperlinkIgnored() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b]0;fake title\u0007hello")
        assertEquals("hello", s.textLines()[0])
    }

    @Test
    fun privateModeIgnored() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[?25lX")
        assertEquals("X", s.textLines()[0])
    }

    @Test
    fun splitSequenceAcrossWritesIsReassembled() {
        val s = Vt100Screen(10, 3)
        s.write("\u001b[3")
        s.write("1mX")
        assertEquals('X', s.screenLines()[0][0].char)
        assertEquals(TerminalColor.Palette(1), s.screenLines()[0][0].style.fg)
    }

    @Test
    fun resizeClampsCursorAndPreservesContent() {
        val s = Vt100Screen(10, 5)
        s.write("abcdef")
        s.resize(4, 3)
        assertEquals(4, s.cols)
        assertEquals(3, s.rows)
        assertEquals("abcd", s.textLines()[0])
        assertEquals(0 to 3, s.cursorPosition()) // col clamped to cols-1
    }
}
