package org.dishus.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SshOutputTest {
    @Test fun backspaceErasesPreviousChar() = assertEquals("ab", applyOutput("", "abc\b"))

    // bash with TERM=dumb echoes the line, backspaces over it, then redraws it.
    @Test fun shellRedrawCollapses() =
        assertEquals("test$ echo hi\nhi\n", applyOutput("test$ ", "echo hi" + "\b".repeat(7) + "echo hi\nhi\n"))

    @Test fun backspaceStopsAtLineStart() = assertEquals("x\n", applyOutput("x\n", "\b\b"))

    @Test fun keepsOnlyTail() = assertEquals(50_000, applyOutput("a".repeat(49_999), "bb").length)
}
