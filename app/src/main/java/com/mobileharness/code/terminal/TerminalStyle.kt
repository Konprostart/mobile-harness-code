package com.mobileharness.code.terminal

/**
 * Color model for the terminal grid. It is deliberately decoupled from Compose so the emulator
 * stays JVM-pure; the UI layer maps these values to [androidx.compose.ui.graphics.Color].
 */
sealed interface TerminalColor {
    /** Inherit the terminal's default foreground/background (no explicit SGR color). */
    data object Default : TerminalColor

    /** One of the 16 ANSI colors (0–15). */
    data class Palette(val index: Int) : TerminalColor

    /** A 256-color palette index (0–255, the standard xterm palette). */
    data class Palette256(val index: Int) : TerminalColor

    /** A 24-bit true-color value. */
    data class Rgb(val r: Int, val g: Int, val b: Int) : TerminalColor
}

/** Active SGR text attributes for a cell. */
data class TerminalStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
    val fg: TerminalColor = TerminalColor.Default,
    val bg: TerminalColor = TerminalColor.Default,
)

/** One character cell on the terminal grid. */
data class TerminalCell(
    val char: Char = ' ',
    val style: TerminalStyle = TerminalStyle(),
)
