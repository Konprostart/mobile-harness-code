package com.mobileharness.code.terminal

/**
 * Pure VT100 / ANSI terminal emulation for the interactive SSH shell.
 *
 * This is deliberately a self-contained, JVM-testable screen model with NO Android, Compose,
 * network or crypto dependencies. It understands the subset of the VT100/ANSI protocol that
 * real TUIs ([opencode], [claude], [antigravity], editors, shells) rely on:
 *
 *  - a column × row grid of styled cells,
 *  - cursor movement, save/restore cursor, and "pending wrap" at the right margin,
 *  - erase line/display/chars and insert/delete line/char,
 *  - scrollback (a bounded history of full lines scrolled off the top),
 *  - SGR colors/styles (8/16-color, 256-color and true-color, bold/italic/underline/reverse),
 *  - robust ignoring of unknown or unsafe sequences (OSC hyperlinks/titles, DEC private modes,
 *    character-set designators, keypad modes) so a hostile remote can never inject raw text
 *    into the grid outside the printable-character path.
 *
 * The parser buffers an in-progress escape sequence across [write] calls, because a PTY stream
 * can split a sequence at any byte boundary.
 */
class Vt100Screen(
    initialCols: Int = 80,
    initialRows: Int = 24,
    maxScrollbackLines: Int = 2000,
) {
    var cols: Int = initialCols.coerceAtLeast(1)
        private set
    var rows: Int = initialRows.coerceAtLeast(1)
        private set

    private val maxScrollback = maxScrollbackLines.coerceAtLeast(0)

    private val grid: MutableList<MutableList<TerminalCell>> = MutableList(rows) { blankRow() }
    private val scrollback = ArrayDeque<List<TerminalCell>>()

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var style = TerminalStyle()
    private var wrapPending = false

    private enum class ParserState { NORMAL, ESCAPE, CSI, OSC, OSC_ESC, CHARSET }
    private var state = ParserState.NORMAL
    private val pending = StringBuilder()

    init {
        cursorRow = 0
        cursorCol = 0
    }

    private fun blankRow(): MutableList<TerminalCell> = MutableList(cols) { TerminalCell() }

    /** Feeds a chunk of the PTY output stream into the emulator. */
    fun write(text: String) {
        for (ch in text) feed(ch)
    }

    /**
     * Returns the scrollback history plus the current screen as a list of rows (oldest first).
     * Each row is a snapshot of cells, so callers may hold it without it mutating underneath.
     */
    fun visibleLines(): List<List<TerminalCell>> {
        val result = ArrayList<List<TerminalCell>>(scrollback.size + rows)
        scrollback.forEach { result.add(it) }
        grid.forEach { result.add(it.toList()) }
        return result
    }

    /** Returns just the current on-screen grid (no scrollback), oldest row first. */
    fun screenLines(): List<List<TerminalCell>> = grid.map { it.toList() }

    /** Number of scrollback lines currently retained. */
    fun scrollbackSize(): Int = scrollback.size

    /** Current cursor position (0-based row/column) within the visible screen area. */
    fun cursorPosition(): Pair<Int, Int> = cursorRow to cursorCol

    /** Discards all scrollback history, keeping the current screen intact. */
    fun clearScrollback() {
        scrollback.clear()
    }

    /** Full reset (RIS): clears the grid, scrollback, cursor and SGR state. */
    fun reset() {
        grid.clear()
        repeat(rows) { grid.add(blankRow()) }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        style = TerminalStyle()
        wrapPending = false
        pending.clear()
        state = ParserState.NORMAL
    }

    /** Resizes the grid, preserving bottom-aligned content and clamping the cursor. */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c != cols) {
            for (row in grid) {
                while (row.size > c) row.removeAt(row.size - 1)
                while (row.size < c) row.add(TerminalCell())
            }
            cols = c
        }
        if (r != rows) {
            if (r < rows) {
                val toRemove = rows - r
                if (cursorRow < r) {
                    // Cursor already fits in the new height: drop the excess bottom lines so the
                    // cursor and everything above it stay visible.
                    repeat(toRemove) { grid.removeAt(grid.size - 1) }
                } else {
                    // Cursor sits below the new bottom: scroll the top lines into history and
                    // clamp the cursor to the last row.
                    repeat(toRemove) { pushScrollback(grid.removeAt(0)) }
                    cursorRow = r - 1
                }
            } else {
                repeat(r - rows) { grid.add(blankRow()) }
            }
            rows = r
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
        }
        wrapPending = false
    }

    // --- parser ----------------------------------------------------------------------------

    private fun feed(ch: Char) {
        when (state) {
            ParserState.NORMAL -> onNormal(ch)
            ParserState.ESCAPE -> onEscape(ch)
            ParserState.CSI -> onCsi(ch)
            ParserState.OSC -> onOsc(ch)
            ParserState.OSC_ESC -> onOscEsc(ch)
            ParserState.CHARSET -> {
                state = ParserState.NORMAL
            }
        }
    }

    private fun onNormal(ch: Char) {
        when (ch) {
            '\u001B' -> state = ParserState.ESCAPE
            '\r' -> {
                wrapPending = false
                cursorCol = 0
            }
            '\n' -> lineFeed()
            '\b' -> {
                wrapPending = false
                if (cursorCol > 0) cursorCol--
            }
            '\t' -> {
                wrapPending = false
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol >= cols) cursorCol = cols - 1
            }
            '\u0007', '\u000c' -> Unit // BEL / form feed: ignore
            else -> {
                if (ch.code < 32) return // other C0 controls ignored
                putChar(ch)
            }
        }
    }

    private fun onEscape(ch: Char) {
        when (ch) {
            '[' -> {
                pending.clear()
                state = ParserState.CSI
            }
            ']' -> {
                pending.clear()
                state = ParserState.OSC
            }
            '(', ')', '*', '+', '#', '%' -> state = ParserState.CHARSET
            '7' -> {
                savedRow = cursorRow
                savedCol = cursorCol
                state = ParserState.NORMAL
            }
            '8' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                wrapPending = false
                state = ParserState.NORMAL
            }
            'M' -> {
                reverseIndex()
                state = ParserState.NORMAL
            }
            'D' -> {
                lineFeed()
                state = ParserState.NORMAL
            }
            'E' -> {
                lineFeed()
                cursorCol = 0
                state = ParserState.NORMAL
            }
            'c' -> {
                reset()
                state = ParserState.NORMAL
            }
            else -> state = ParserState.NORMAL // unknown ESC: ignore safely
        }
    }

    private fun onCsi(ch: Char) {
        when {
            ch.code in 0x20..0x3F -> {
                if (pending.length < MAX_CSI) pending.append(ch)
            }
            ch.code in 0x40..0x7E -> {
                dispatchCsi(ch, pending.toString())
                pending.clear()
                state = ParserState.NORMAL
            }
            else -> {
                pending.clear()
                state = ParserState.NORMAL
            }
        }
    }

    private fun onOsc(ch: Char) {
        when (ch) {
            '\u0007' -> {
                pending.clear()
                state = ParserState.NORMAL
            }
            '\u001B' -> state = ParserState.OSC_ESC
            else -> if (pending.length < MAX_OSC) pending.append(ch)
        }
    }

    private fun onOscEsc(ch: Char) {
        if (ch == '\\') {
            pending.clear()
            state = ParserState.NORMAL
        } else {
            state = ParserState.OSC
        }
    }

    private fun dispatchCsi(final: Char, params: String) {
        wrapPending = false
        if (params.isNotEmpty() && params[0] in "?><=!") return // DEC private / extension: ignored
        val ps = parseParams(params)
        when (final) {
            'A' -> cursorRow = (cursorRow - coerce1(ps, 0)).coerceIn(0, rows - 1)
            'B' -> cursorRow = (cursorRow + coerce1(ps, 0)).coerceIn(0, rows - 1)
            'C' -> cursorCol = (cursorCol + coerce1(ps, 0)).coerceIn(0, cols - 1)
            'D' -> cursorCol = (cursorCol - coerce1(ps, 0)).coerceIn(0, cols - 1)
            'E' -> {
                cursorRow = (cursorRow + coerce1(ps, 0)).coerceIn(0, rows - 1)
                cursorCol = 0
            }
            'F' -> {
                cursorRow = (cursorRow - coerce1(ps, 0)).coerceIn(0, rows - 1)
                cursorCol = 0
            }
            'G' -> cursorCol = (coerce1(ps, 0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (coerce1(ps, 0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (coerce1(ps, 0) - 1).coerceIn(0, rows - 1)
                cursorCol = (coerce1(ps, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(ps.getOrElse(0) { 0 })
            'K' -> eraseLine(ps.getOrElse(0) { 0 })
            'm' -> applySgr(ps)
            's' -> {
                savedRow = cursorRow
                savedCol = cursorCol
            }
            'u' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
            }
            'X' -> eraseChars(coerce1(ps, 0))
            'P' -> deleteChars(coerce1(ps, 0))
            '@' -> insertChars(coerce1(ps, 0))
            'L' -> insertLines(coerce1(ps, 0))
            'M' -> deleteLines(coerce1(ps, 0))
            'S' -> repeat(coerce1(ps, 0)) { scrollUp() }
            'T' -> repeat(coerce1(ps, 0)) { reverseIndex() }
            else -> Unit // unsupported CSI: ignored safely
        }
    }

    private fun parseParams(params: String): List<Int> {
        val s = params.trimStart('?', '>', '<', '=', '!', ' ')
        if (s.isEmpty()) return listOf(0)
        return s.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun coerce1(ps: List<Int>, index: Int): Int = ps.getOrElse(index) { 1 }.coerceAtLeast(1)

    // --- screen primitives -------------------------------------------------------------------

    private fun putChar(ch: Char) {
        if (wrapPending) {
            cursorCol = 0
            lineFeed()
            wrapPending = false
        }
        if (cursorCol >= cols) cursorCol = cols - 1
        grid[cursorRow][cursorCol] = TerminalCell(ch, style)
        if (cursorCol == cols - 1) {
            wrapPending = true
        } else {
            cursorCol++
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == rows - 1) {
            scrollUp()
        } else {
            cursorRow++
        }
    }

    private fun scrollUp() {
        pushScrollback(grid.removeAt(0))
        grid.add(blankRow())
    }

    private fun reverseIndex() {
        if (cursorRow == 0) {
            grid.removeAt(rows - 1)
            grid.add(0, blankRow())
        } else {
            cursorRow--
        }
    }

    private fun pushScrollback(line: List<TerminalCell>) {
        if (maxScrollback == 0) return
        scrollback.addLast(line.toList())
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) eraseRow(r, 0, cols)
            }
            1 -> {
                for (r in 0 until cursorRow) eraseRow(r, 0, cols)
                eraseLine(1)
            }
            2 -> for (r in 0 until rows) eraseRow(r, 0, cols)
            3 -> {
                for (r in 0 until rows) eraseRow(r, 0, cols)
                scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseRow(cursorRow, cursorCol, cols)
            1 -> eraseRow(cursorRow, 0, cursorCol + 1)
            2 -> eraseRow(cursorRow, 0, cols)
        }
    }

    private fun eraseRow(row: Int, from: Int, to: Int) {
        if (row !in 0 until rows) return
        for (c in from until to) {
            if (c in 0 until cols) grid[row][c] = TerminalCell()
        }
    }

    private fun eraseChars(n: Int) {
        for (i in 0 until n) {
            val c = cursorCol + i
            if (c >= cols) break
            grid[cursorRow][c] = TerminalCell()
        }
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        val k = n.coerceAtLeast(1)
        for (c in cursorCol until cols) {
            row[c] = if (c + k < cols) row[c + k] else TerminalCell()
        }
    }

    private fun insertChars(n: Int) {
        val row = grid[cursorRow]
        val k = n.coerceAtLeast(1)
        for (c in cols - 1 downTo cursorCol) {
            row[c] = if (c - k >= cursorCol) row[c - k] else TerminalCell()
        }
    }

    private fun insertLines(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            grid.removeAt(rows - 1)
            grid.add(cursorRow, blankRow())
        }
    }

    private fun deleteLines(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            grid.removeAt(cursorRow)
            grid.add(blankRow())
        }
    }

    // --- SGR ---------------------------------------------------------------------------------

    private fun applySgr(ps: List<Int>) {
        if (ps.isEmpty()) {
            style = TerminalStyle()
            return
        }
        var i = 0
        while (i < ps.size) {
            val p = ps[i]
            when (p) {
                0 -> style = TerminalStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(bold = false) // faint → treated as normal weight
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(reverse = true)
                21, 22 -> style = style.copy(bold = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(reverse = false)
                in 30..37 -> style = style.copy(fg = TerminalColor.Palette(p - 30))
                39 -> style = style.copy(fg = TerminalColor.Default)
                in 40..47 -> style = style.copy(bg = TerminalColor.Palette(p - 40))
                49 -> style = style.copy(bg = TerminalColor.Default)
                in 90..97 -> style = style.copy(fg = TerminalColor.Palette(p - 90 + 8))
                in 100..107 -> style = style.copy(bg = TerminalColor.Palette(p - 100 + 8))
                38 -> {
                    val (color, next) = parseColor(ps, i + 1)
                    if (color != null) {
                        style = style.copy(fg = color)
                        i = next - 1
                    }
                }
                48 -> {
                    val (color, next) = parseColor(ps, i + 1)
                    if (color != null) {
                        style = style.copy(bg = color)
                        i = next - 1
                    }
                }
                else -> Unit // 5/6 blink, 8 conceal, 9 strikethrough, etc.: ignored
            }
            i++
        }
    }

    private fun parseColor(ps: List<Int>, start: Int): Pair<TerminalColor?, Int> {
        if (start >= ps.size) return null to start
        return when (ps[start]) {
            5 -> {
                if (start + 1 >= ps.size) return null to start
                TerminalColor.Palette256(ps[start + 1].coerceIn(0, 255)) to (start + 2)
            }
            2 -> {
                if (start + 3 >= ps.size) return null to start
                TerminalColor.Rgb(
                    ps[start + 1].coerceIn(0, 255),
                    ps[start + 2].coerceIn(0, 255),
                    ps[start + 3].coerceIn(0, 255),
                ) to (start + 4)
            }
            else -> null to start
        }
    }

    private companion object {
        const val MAX_CSI = 64
        const val MAX_OSC = 1024
    }
}
