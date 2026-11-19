package com.mobileharness.code.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeyMapperTest {

    // --- modifier state machine ----------------------------------------------------------------

    @Test
    fun modifierTapsCycleOffToOneShotToLockedToOff() {
        assertEquals(TerminalModifier.ONE_SHOT, TerminalKeyMapper.nextModifier(TerminalModifier.OFF))
        assertEquals(TerminalModifier.LOCKED, TerminalKeyMapper.nextModifier(TerminalModifier.ONE_SHOT))
        assertEquals(TerminalModifier.OFF, TerminalKeyMapper.nextModifier(TerminalModifier.LOCKED))
        assertEquals(TerminalModifier.ONE_SHOT, TerminalKeyMapper.nextModifier(TerminalModifier.OFF))
    }

    @Test
    fun modifierActiveOnlyWhenOneShotOrLocked() {
        assertFalse(TerminalKeyMapper.isModifierActive(TerminalModifier.OFF))
        assertTrue(TerminalKeyMapper.isModifierActive(TerminalModifier.ONE_SHOT))
        assertTrue(TerminalKeyMapper.isModifierActive(TerminalModifier.LOCKED))
    }

    @Test
    fun consumeOneShotResetsOneShotButKeepsLocked() {
        assertEquals(TerminalModifier.OFF, TerminalKeyMapper.consumeOneShot(TerminalModifier.OFF))
        assertEquals(TerminalModifier.OFF, TerminalKeyMapper.consumeOneShot(TerminalModifier.ONE_SHOT))
        assertEquals(TerminalModifier.LOCKED, TerminalKeyMapper.consumeOneShot(TerminalModifier.LOCKED))
    }

    // --- VT100 sequences for arrows -----------------------------------------------------------

    @Test
    fun arrowKeysEncodeClassicCsiSequences() {
        assertEquals("\u001b[A", TerminalKeyMapper.sequence(TerminalKey.ARROW_UP))
        assertEquals("\u001b[B", TerminalKeyMapper.sequence(TerminalKey.ARROW_DOWN))
        assertEquals("\u001b[C", TerminalKeyMapper.sequence(TerminalKey.ARROW_RIGHT))
        assertEquals("\u001b[D", TerminalKeyMapper.sequence(TerminalKey.ARROW_LEFT))
    }

    @Test
    fun modifiedArrowsUseXtermModifierParam() {
        assertEquals("\u001b[1;5A", TerminalKeyMapper.sequence(TerminalKey.ARROW_UP, ctrl = true))
        assertEquals("\u001b[1;3A", TerminalKeyMapper.sequence(TerminalKey.ARROW_UP, alt = true))
        assertEquals("\u001b[1;7B", TerminalKeyMapper.sequence(TerminalKey.ARROW_DOWN, ctrl = true, alt = true))
    }

    // --- Home / End / PgUp / PgDn / Insert / Delete -------------------------------------------

    @Test
    fun homeEndPageInsertDeleteEncodeExpectedSequences() {
        assertEquals("\u001b[H", TerminalKeyMapper.sequence(TerminalKey.HOME))
        assertEquals("\u001b[F", TerminalKeyMapper.sequence(TerminalKey.END))
        assertEquals("\u001b[5~", TerminalKeyMapper.sequence(TerminalKey.PAGE_UP))
        assertEquals("\u001b[6~", TerminalKeyMapper.sequence(TerminalKey.PAGE_DOWN))
        assertEquals("\u001b[2~", TerminalKeyMapper.sequence(TerminalKey.INSERT))
        assertEquals("\u001b[3~", TerminalKeyMapper.sequence(TerminalKey.DELETE))
    }

    @Test
    fun modifiedHomeEndUseCsiModifierParam() {
        assertEquals("\u001b[1;5H", TerminalKeyMapper.sequence(TerminalKey.HOME, ctrl = true))
        assertEquals("\u001b[1;3F", TerminalKeyMapper.sequence(TerminalKey.END, alt = true))
        assertEquals("\u001b[5;5~", TerminalKeyMapper.sequence(TerminalKey.PAGE_UP, ctrl = true))
        assertEquals("\u001b[3;3~", TerminalKeyMapper.sequence(TerminalKey.DELETE, alt = true))
    }

    // --- F1..F12 ------------------------------------------------------------------------------

    @Test
    fun functionKeysEncodeExpectedSequences() {
        assertEquals("\u001bOP", TerminalKeyMapper.sequence(TerminalKey.F1))
        assertEquals("\u001bOQ", TerminalKeyMapper.sequence(TerminalKey.F2))
        assertEquals("\u001bOR", TerminalKeyMapper.sequence(TerminalKey.F3))
        assertEquals("\u001bOS", TerminalKeyMapper.sequence(TerminalKey.F4))
        assertEquals("\u001b[15~", TerminalKeyMapper.sequence(TerminalKey.F5))
        assertEquals("\u001b[17~", TerminalKeyMapper.sequence(TerminalKey.F6))
        assertEquals("\u001b[18~", TerminalKeyMapper.sequence(TerminalKey.F7))
        assertEquals("\u001b[19~", TerminalKeyMapper.sequence(TerminalKey.F8))
        assertEquals("\u001b[20~", TerminalKeyMapper.sequence(TerminalKey.F9))
        assertEquals("\u001b[21~", TerminalKeyMapper.sequence(TerminalKey.F10))
        assertEquals("\u001b[23~", TerminalKeyMapper.sequence(TerminalKey.F11))
        assertEquals("\u001b[24~", TerminalKeyMapper.sequence(TerminalKey.F12))
    }

    @Test
    fun modifiedFunctionKeysUseCsiModifierParam() {
        assertEquals("\u001b[1;5P", TerminalKeyMapper.sequence(TerminalKey.F1, ctrl = true))
        assertEquals("\u001b[1;3S", TerminalKeyMapper.sequence(TerminalKey.F4, alt = true))
        assertEquals("\u001b[15;5~", TerminalKeyMapper.sequence(TerminalKey.F5, ctrl = true))
    }

    // --- hardware keyboard combinations (Ctrl / Alt over printable chars) ---------------------

    @Test
    fun ctrlLetterFoldsToControlCode() {
        assertEquals("\u0003", TerminalKeyMapper.charSequence('c', ctrl = true))
        assertEquals("\u0003", TerminalKeyMapper.charSequence('C', ctrl = true))
        assertEquals("\u001a", TerminalKeyMapper.charSequence('z', ctrl = true))
        assertEquals("\u0001", TerminalKeyMapper.charSequence('a', ctrl = true))
    }

    @Test
    fun altKeyPrefixesWithEsc() {
        assertEquals("\u001bx", TerminalKeyMapper.charSequence('x', alt = true))
        assertEquals("\u001bX", TerminalKeyMapper.charSequence('X', alt = true))
    }

    @Test
    fun plainCharIsUnchanged() {
        assertEquals("x", TerminalKeyMapper.charSequence('x'))
        assertEquals("-", TerminalKeyMapper.charSequence('-'))
        assertEquals("/", TerminalKeyMapper.charSequence('/'))
    }

    @Test
    fun altCtrlLetterPrefixesControlCodeWithEsc() {
        assertEquals("\u001b\u0003", TerminalKeyMapper.charSequence('c', ctrl = true, alt = true))
    }
}
