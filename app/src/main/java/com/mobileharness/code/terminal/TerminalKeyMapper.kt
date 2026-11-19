package com.mobileharness.code.terminal

/**
 * Pure terminal key encoding and on-screen modifier state. Deliberately free of Android,
 * Compose, network and crypto dependencies so it is trivially JVM-testable.
 *
 * The byte sequences follow the VT100 / xterm conventions that real shells and TUIs
 * ([opencode], [claude], [antigravity], editors) rely on:
 *
 *  - Enter = CR, Tab = HT, Esc = ESC, Backspace = DEL (0x7f),
 *  - arrows = CSI A/B/C/D (modified arrows use CSI `1;{m}X` with the xterm modifier code),
 *  - Home = CSI H, End = CSI F, PageUp = CSI 5~, PageDown = CSI 6~,
 *    Insert = CSI 2~, Delete = CSI 3~,
 *  - F1..F4 = SS3 P/Q/R/S, F5..F12 = CSI 15/17/18/19/20/21/23/24~,
 *  - Ctrl+letter = the classic C0 control code (Ctrl+C = 0x03),
 *  - Alt+key = ESC-prefixed sequence.
 *
 * Modifier codes follow the xterm "CSI 1;{m}" convention: 5 = Ctrl, 3 = Alt, 7 = Ctrl+Alt.
 */

/** A semantic terminal key, independent of any particular hardware event source. */
enum class TerminalKey {
    ENTER, TAB, ESC, BACKSPACE, DELETE,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** One-shot/lock state for an on-screen modifier key (Ctrl / Alt). */
enum class TerminalModifier { OFF, ONE_SHOT, LOCKED }

object TerminalKeyMapper {

    /**
     * Advances an on-screen modifier through its states on tap:
     * OFF → ONE_SHOT (applies to the next key) → LOCKED (applies until toggled off) → OFF.
     */
    fun nextModifier(state: TerminalModifier): TerminalModifier = when (state) {
        TerminalModifier.OFF -> TerminalModifier.ONE_SHOT
        TerminalModifier.ONE_SHOT -> TerminalModifier.LOCKED
        TerminalModifier.LOCKED -> TerminalModifier.OFF
    }

    /** A modifier is "active" for the next key when it is one-shot or locked. */
    fun isModifierActive(state: TerminalModifier): Boolean = state != TerminalModifier.OFF

    /** After a key press, a one-shot modifier resets; a locked modifier stays locked. */
    fun consumeOneShot(state: TerminalModifier): TerminalModifier =
        if (state == TerminalModifier.ONE_SHOT) TerminalModifier.OFF else state

    /** Encodes a semantic key press to the byte sequence a PTY expects. */
    fun sequence(key: TerminalKey, ctrl: Boolean = false, alt: Boolean = false): String {
        val m = modParam(ctrl, alt)
        return when (key) {
            TerminalKey.ENTER -> "\r"
            TerminalKey.TAB -> "\t"
            TerminalKey.ESC -> "\u001b"
            TerminalKey.BACKSPACE -> "\u007f"
            TerminalKey.ARROW_UP -> csiNav("A", m)
            TerminalKey.ARROW_DOWN -> csiNav("B", m)
            TerminalKey.ARROW_RIGHT -> csiNav("C", m)
            TerminalKey.ARROW_LEFT -> csiNav("D", m)
            TerminalKey.HOME -> if (m == 0) "\u001b[H" else "\u001b[1;${m}H"
            TerminalKey.END -> if (m == 0) "\u001b[F" else "\u001b[1;${m}F"
            TerminalKey.PAGE_UP -> tilde(5, m)
            TerminalKey.PAGE_DOWN -> tilde(6, m)
            TerminalKey.INSERT -> tilde(2, m)
            TerminalKey.DELETE -> tilde(3, m)
            TerminalKey.F1 -> fKey(1, m)
            TerminalKey.F2 -> fKey(2, m)
            TerminalKey.F3 -> fKey(3, m)
            TerminalKey.F4 -> fKey(4, m)
            TerminalKey.F5 -> fKey(5, m)
            TerminalKey.F6 -> fKey(6, m)
            TerminalKey.F7 -> fKey(7, m)
            TerminalKey.F8 -> fKey(8, m)
            TerminalKey.F9 -> fKey(9, m)
            TerminalKey.F10 -> fKey(10, m)
            TerminalKey.F11 -> fKey(11, m)
            TerminalKey.F12 -> fKey(12, m)
        }
    }

    /**
     * Encodes a printable character press (e.g. from the input bar or a hardware keyboard).
     * Ctrl+letter folds to its C0 control code; Alt prefixes the result with ESC.
     */
    fun charSequence(ch: Char, ctrl: Boolean = false, alt: Boolean = false): String {
        val base = if (ctrl) ctrlCode(ch) ?: ch.toString() else ch.toString()
        return if (alt) "\u001b$base" else base
    }

    private fun modParam(ctrl: Boolean, alt: Boolean): Int = when {
        ctrl && alt -> 7
        ctrl -> 5
        alt -> 3
        else -> 0
    }

    private fun csiNav(letter: String, m: Int): String =
        if (m == 0) "\u001b[$letter" else "\u001b[1;${m}$letter"

    private fun tilde(n: Int, m: Int): String =
        if (m == 0) "\u001b[${n}~" else "\u001b[${n};${m}~"

    private fun fKey(n: Int, m: Int): String = when (n) {
        1 -> if (m == 0) "\u001bOP" else "\u001b[1;${m}P"
        2 -> if (m == 0) "\u001bOQ" else "\u001b[1;${m}Q"
        3 -> if (m == 0) "\u001bOR" else "\u001b[1;${m}R"
        4 -> if (m == 0) "\u001bOS" else "\u001b[1;${m}S"
        5 -> baseF(15, m)
        6 -> baseF(17, m)
        7 -> baseF(18, m)
        8 -> baseF(19, m)
        9 -> baseF(20, m)
        10 -> baseF(21, m)
        11 -> baseF(23, m)
        12 -> baseF(24, m)
        else -> ""
    }

    private fun baseF(code: Int, m: Int): String =
        if (m == 0) "\u001b[${code}~" else "\u001b[${code};${m}~"

    private fun ctrlCode(ch: Char): String? {
        val c = ch.lowercaseChar()
        if (c in 'a'..'z') return ((c.code - 'a'.code + 1).toChar()).toString()
        return null
    }
}
