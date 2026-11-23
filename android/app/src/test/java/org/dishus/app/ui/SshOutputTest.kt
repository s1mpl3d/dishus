package org.dishus.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshOutputTest {
    @Test fun backspaceErasesPreviousChar() = assertEquals("ab", applyOutput("", "abc\b"))

    // bash with TERM=dumb echoes the line, backspaces over it, then redraws it.
    @Test fun shellRedrawCollapses() =
        assertEquals("test$ echo hi\nhi\n", applyOutput("test$ ", "echo hi" + "\b".repeat(7) + "echo hi\nhi\n"))

    @Test fun backspaceStopsAtLineStart() = assertEquals("x\n", applyOutput("x\n", "\b\b"))

    @Test fun keepsOnlyTail() = assertEquals(50_000, applyOutput("a".repeat(49_999), "bb").length)

    @Test fun friendlyErrors() {
        assertEquals("Wrong username or password.", friendlyError(com.jcraft.jsch.JSchException("Auth fail"), "h", "22"))
        assertEquals("No answer from h. Is it on, on the same network, with SSH turned on there?",
            friendlyError(com.jcraft.jsch.JSchException("timeout: socket is not established"), "h", "22"))
        assertTrue(friendlyError(com.jcraft.jsch.JSchException("x", java.net.ConnectException()), "h", "2222").contains("port 2222"))
        assertTrue(friendlyError(com.jcraft.jsch.JSchException("x", java.net.UnknownHostException()), "pc", "22").startsWith("Can't find"))
    }

    @Test fun friendlyErrorsViaDishus() {
        val off = friendlyError(com.jcraft.jsch.JSchException("Session.connect: java.io.IOException: End of IO Stream Read"), "LS", "22", viaDishus = true)
        assertTrue(off, off.contains("SSH server is off"))
        assertTrue(friendlyError(RuntimeException("Lockdown"), "LS", "22", viaDishus = true).contains("dishus unlock"))
        assertTrue(friendlyError(RuntimeException("timed out reaching the PC"), "LS", "22", viaDishus = true).startsWith("Can't reach LS"))
        assertEquals("Wrong username or password.", friendlyError(com.jcraft.jsch.JSchException("Auth fail"), "LS", "22", viaDishus = true))
    }
}
