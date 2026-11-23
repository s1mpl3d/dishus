package org.dishus.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.InputStream
import java.io.OutputStream

// shortcut: host keys are accepted without checking (StrictHostKeyChecking=no); pin them before
// using over untrusted networks.
private class SshTransport(val user: String, val host: String, val port: Int, val password: String) : TerminalSession.Transport {
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null

    override fun connect(columns: Int, rows: Int) {
        val s = JSch().getSession(user, host, port)
        session = s
        s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(15_000)
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        ch.setPtySize(columns, rows, 0, 0)
        channel = ch
        ch.connect(15_000)
    }

    override fun getInputStream(): InputStream = channel!!.inputStream
    override fun getOutputStream(): OutputStream = channel!!.outputStream
    override fun resize(columns: Int, rows: Int) { runCatching { channel?.setPtySize(columns, rows, 0, 0) } }
    override fun close() { runCatching { channel?.disconnect() }; runCatching { session?.disconnect() } }
}

/** "user@host[:port]" -> (user, host, port), or null if malformed. */
internal fun parseTarget(input: String): Triple<String, String, Int>? {
    val s = input.trim()
    val at = s.lastIndexOf('@')
    if (at <= 0) return null
    val rest = s.substring(at + 1)
    val colon = rest.lastIndexOf(':')
    val host = if (colon > 0) rest.substring(0, colon) else rest
    val port = if (colon > 0) rest.substring(colon + 1).toIntOrNull() ?: return null else 22
    return if (host.isEmpty()) null else Triple(s.substring(0, at), host, port)
}

@Composable
fun SshScreen(onExit: () -> Unit) {
    var target by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var label by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { session?.finishIfRunning() } }
    BackHandler { if (session != null) { session?.finishIfRunning(); session = null } else onExit() }

    val s = session
    if (s != null) {
        TermuxTerminal(s, label, onClose = { s.finishIfRunning(); session = null })
        return
    }
    val parsed = parseTarget(target)
    fun connect() {
        val (u, h, p) = parsed ?: return
        label = if (p == 22) "$u@$h" else "$u@$h:$p"
        session = TerminalSession(SshTransport(u, h, p, pass), 2000, NoopSessionClient)
    }
    val fieldShape = RoundedCornerShape(12.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent, unfocusedBorderColor = KeyBgPressed,
        focusedLabelColor = Accent, cursorColor = Accent,
    )
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("←", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground) }
            Text("SSH", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        // Live preview of the command, so it reads like typing it in Termux.
        val (pu, ph, pp) = parsed ?: Triple("user", "host", 22)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .border(1.dp, KeyBg, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Accent)) { append("~ $ ") }
                    append("ssh ")
                    withStyle(SpanStyle(color = if (parsed != null) Color(0xFFE6EBEF) else Color(0xFF5C6670))) {
                        append("$pu@$ph"); if (pp != 22) append(" -p $pp")
                    }
                    withStyle(SpanStyle(color = Accent)) { append(" ▌") }
                },
                color = Color(0xFFE6EBEF), fontFamily = FontFamily.Monospace, fontSize = 15.sp,
            )
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    target, { target = it.trim() }, label = { Text("user@host[:port]") }, singleLine = true,
                    shape = fieldShape, colors = fieldColors, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    isError = target.isNotEmpty() && parsed == null,
                    supportingText = if (target.isNotEmpty() && parsed == null) {{ Text("e.g. me@192.168.1.20 or me@box:2222") }} else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    shape = fieldShape, colors = fieldColors,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = parsed != null, onClick = { connect() }, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            }
        }
        Text(
            "Tap the terminal for the keyboard · pinch to zoom · long-press to select",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A949E),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

/** Termux's default extra keys. CTRL/ALT are one-shot toggles, as in Termux. */
private val EXTRA_KEYS = listOf(
    listOf("ESC", "/", "-", "HOME", "↑", "END", "PGUP"),
    listOf("TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN"),
)

private val KEYCODES = mapOf(
    "ESC" to KeyEvent.KEYCODE_ESCAPE, "TAB" to KeyEvent.KEYCODE_TAB,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END,
    "PGUP" to KeyEvent.KEYCODE_PAGE_UP, "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
    "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
    "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
)

@Composable
private fun TermuxTerminal(session: TerminalSession, label: String, onClose: () -> Unit) {
    var running by remember { mutableStateOf(true) }
    val haptic = LocalView.current
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<TerminalView?>(null) }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(KeyboardBg).padding(start = 14.dp, end = 4.dp).height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) Accent else Color(0xFFFFB74D)))
            Text(
                if (running) label else "$label · disconnected", color = Color(0xFFC9D1D9),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            TextButton(onClick = onClose) { Text("✕", color = Color(0xFF8A949E)) }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    view = this
                    val imm = ctx.getSystemService(InputMethodManager::class.java)
                    val density = ctx.resources.displayMetrics.scaledDensity
                    var textPx = 12 * density
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setTypeface(Typeface.MONOSPACE)
                    setTextSize(textPx.toInt())
                    isFocusable = true
                    isFocusableInTouchMode = true
                    session.updateTerminalSessionClient(UiSessionClient(ctx, this) { running = false })
                    setTerminalViewClient(object : TerminalViewClient by NoopViewClient {
                        // Pinch to zoom, like Termux.
                        override fun onScale(scale: Float): Float {
                            if (scale in 0.9f..1.1f) return scale
                            textPx = (textPx * scale).coerceIn(6 * density, 36 * density)
                            setTextSize(textPx.toInt())
                            return 1f
                        }
                        override fun onSingleTapUp(e: MotionEvent) { requestFocus(); imm.showSoftInput(this@apply, 0) }
                        override fun readControlKey(): Boolean = ctrl.also { ctrl = false }
                        override fun readAltKey(): Boolean = alt.also { alt = false }
                    })
                    attachSession(session)
                    post { requestFocus(); imm.showSoftInput(this, 0) }
                }
            },
        )
        // Extra keys styled like the app's PC keyboard.
        Column(Modifier.fillMaxWidth().background(KeyboardBg).padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (row in EXTRA_KEYS) {
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (k in row) {
                        val on = (k == "CTRL" && ctrl) || (k == "ALT" && alt)
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                                .background(if (on) KeySticky else KeyBg)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    val v = view ?: return@clickable
                                    when (k) {
                                        "CTRL" -> ctrl = !ctrl
                                        "ALT" -> alt = !alt
                                        "/", "-" -> v.inputCodePoint(k[0].code, false, false)
                                        else -> {
                                            var mod = 0
                                            if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
                                            if (alt) mod = mod or KeyHandler.KEYMOD_ALT
                                            ctrl = false; alt = false
                                            v.handleKeyCode(KEYCODES.getValue(k), mod)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                k, color = if (on) Accent else Color(0xFFE6EBEF),
                                fontSize = if (k.length > 2) 12.sp else 16.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val KeyboardBg = Color(0xFF0B0E11)

private class UiSessionClient(val ctx: Context, val view: TerminalView, val onFinished: () -> Unit) : TerminalSessionClient by NoopSessionClient {
    override fun onSessionFinished(finishedSession: TerminalSession) = onFinished()
    override fun onTextChanged(changedSession: TerminalSession) = view.onScreenUpdated()
    override fun onColorsChanged(session: TerminalSession) = view.invalidate()
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return
        session.emulator?.paste(text)
    }
}

private object NoopSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

private object NoopViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}
