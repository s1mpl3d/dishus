package org.dishus.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dishus.app.DishusApp
import org.dishus.app.R
import uniffi.dishus_core.SshTunnel

// shortcut: host keys are accepted without checking (StrictHostKeyChecking=no); through DiShus the
// tunnel already proves the PC's identity, but a typed address does not: pin keys before using
// that over untrusted networks.

private const val TAG = "DishusSsh"

/** The shell of an already logged-in SSH session, fed to Termux's emulator (xterm-256color). */
private class ChannelTransport(val session: Session, val onClose: () -> Unit) : TerminalSession.Transport {
    @Volatile private var channel: ChannelShell? = null
    private val io = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var pending: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var sentSize = 0 to 0

    override fun connect(columns: Int, rows: Int) {
        val ch = session.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        ch.setPtySize(columns, rows, 0, 0)
        channel = ch
        sentSize = columns to rows
        Log.d(TAG, "shell open ${columns}x$rows")
        ch.connect(15_000)
    }

    override fun getInputStream(): InputStream = channel!!.inputStream
    override fun getOutputStream(): OutputStream = channel!!.outputStream
    // Termux calls this on the main thread, many times while the keyboard animates. Writing to the
    // socket from there broke a packet and Windows' sshd dropped us ("incomplete message"), so
    // send only the final size, from a background thread.
    override fun resize(columns: Int, rows: Int) {
        pending?.cancel(false)
        pending = io.schedule({
            if (sentSize != columns to rows) {
                sentSize = columns to rows
                Log.d(TAG, "window-change ${columns}x$rows")
                runCatching { channel?.setPtySize(columns, rows, 0, 0) }.onFailure { Log.w(TAG, "window-change failed", it) }
            }
        }, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    override fun close() {
        io.shutdownNow()
        runCatching { channel?.disconnect() }; runCatching { session.disconnect() }; onClose()
    }
}

@Composable
fun SshScreen(app: DishusApp, onExit: () -> Unit) {
    val prefs = app.prefs
    val pcs = remember { runCatching { app.core.pairedPcs() }.getOrDefault(emptyList()) }
    // A paired PC (through DiShus, no address needed) or "" for a typed address.
    var pcId by rememberSaveable {
        mutableStateOf(prefs.sshPc.takeIf { id -> pcs.any { it.id == id } } ?: if (prefs.sshTarget.first.isBlank()) pcs.firstOrNull()?.id ?: "" else "")
    }
    val pc = pcs.firstOrNull { it.id == pcId }
    var keepLogin by rememberSaveable { mutableStateOf(true) }
    val saved = remember { prefs.sshTarget }
    var host by rememberSaveable { mutableStateOf(saved.first) }
    var user by rememberSaveable { mutableStateOf(saved.second) }
    var port by rememberSaveable { mutableStateOf(saved.third) }
    var pass by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var term by remember { mutableStateOf<TerminalSession?>(null) }
    var tunnel by remember { mutableStateOf<SshTunnel?>(null) }
    // A PC's saved login fills the form when it is picked.
    LaunchedEffect(pcId) {
        if (pcId.isEmpty()) { user = saved.second; pass = "" }
        else app.vault.sshLogin(pcId)?.let { (u, p) -> user = u; pass = p } ?: run { user = ""; pass = "" }
    }
    val scope = rememberCoroutineScope()

    fun close() {
        term?.finishIfRunning(); term = null
        runCatching { tunnel?.stop(); tunnel?.close() }; tunnel = null
    }
    DisposableEffect(Unit) { onDispose { close() } }
    // A stray Back must not drop a live session: ask first. Without one, Back leaves.
    BackHandler { if (term != null) confirmExit = true else onExit() }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Disconnect?") },
            text = { Text("This closes the SSH session.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; close() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancel") } },
        )
    }
    fun connect() {
        status = "connecting…"
        val via = pc
        prefs.sshPc = via?.id ?: ""
        if (via == null) prefs.sshTarget = Triple(host.trim(), user, port)
        scope.launch(Dispatchers.IO) {
            runCatching {
                // Through DiShus: the core gives a local port tunnelled to the PC's SSH server.
                val (h, p) = if (via != null) {
                    val t = app.core.sshTunnel(via.id)
                    withContext(Dispatchers.Main) { tunnel = t }
                    "127.0.0.1" to t.port().toInt()
                } else host.trim() to (port.toIntOrNull() ?: 22)
                val s = JSch().getSession(user, h, p)
                s.setPassword(pass)
                s.setConfig("StrictHostKeyChecking", "no")
                s.connect(15_000)
                if (via != null) {
                    if (keepLogin) app.vault.saveSshLogin(via.id, user, pass) else app.vault.forgetSshLogin(via.id)
                }
                withContext(Dispatchers.Main) {
                    status = ""
                    // Logged in: the shell runs in Termux's emulator (colours, cursor, full-screen apps).
                    term = TerminalSession(ChannelTransport(s) { }, 2000, NoopSessionClient)
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    status = friendlyError(e, via?.name ?: host.trim(), port, viaDishus = via != null)
                    runCatching { tunnel?.stop(); tunnel?.close() }; tunnel = null
                }
            }
        }
    }

    val connecting = status == "connecting…"
    val canConnect = !connecting && (pc != null || host.isNotBlank()) && user.isNotBlank()
    val target = pc?.name ?: host.trim()
    val fieldShape = RoundedCornerShape(12.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent, unfocusedBorderColor = KeyBgPressed,
        focusedLabelColor = Accent, cursorColor = Accent,
        unfocusedLabelColor = Color(0xFFC9D1D9), unfocusedPlaceholderColor = Color(0xFF6B7682),
        focusedSupportingTextColor = Color(0xFF9AA4AE), unfocusedSupportingTextColor = Color(0xFF9AA4AE),
    )
    val mono = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)

    val t = term
    if (t != null) {
        val label = "$user@$target" + if (pc == null && port != "22") ":$port" else ""
        TermuxTerminal(t, label, onClose = { close() })
        return
    }
    run {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { close(); onExit() }) { Text("←", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground) }
                Text("SSH", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            // Live preview of the command being built.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black)
                    .border(1.dp, KeyBg, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                val ready = canConnect || connecting
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Accent)) { append("~ $ ") }
                        append("ssh ")
                        withStyle(SpanStyle(color = if (ready) Color(0xFFE6EBEF) else Color(0xFF5C6670))) {
                            append("${user.ifBlank { "user" }}@${target.ifBlank { "host" }}")
                            if (pc == null && port.isNotBlank() && port != "22") append(" -p $port")
                        }
                        withStyle(SpanStyle(color = Accent)) { append(" ▌") }
                        if (pc != null) withStyle(SpanStyle(color = Color(0xFF5C6670))) { append("\n# via DiShus, no IP needed") }
                    },
                    color = Color(0xFFE6EBEF), fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                )
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pcs.isNotEmpty()) {
                        Text("Computer", color = Color(0xFFC9D1D9), style = MaterialTheme.typography.labelLarge)
                        for (p in pcs) PcChoice(p.name, "Paired · works anywhere", p.id == pcId) { pcId = p.id }
                        PcChoice("Other computer", "Type its address", pcId.isEmpty()) { pcId = "" }
                    }
                    if (pc == null) OutlinedTextField(
                        host, { host = it }, label = { Text("Computer address (IP or name)") }, placeholder = { Text("192.168.1.20") },
                        supportingText = { Text("Linux: hostname -I · Windows: ipconfig") },
                        singleLine = true, shape = fieldShape, colors = fieldColors, textStyle = mono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            user, { user = it }, label = { Text("Username") }, placeholder = { Text("login name on that PC") }, singleLine = true,
                            shape = fieldShape, colors = fieldColors, textStyle = mono,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        if (pc == null) OutlinedTextField(
                            port, { v -> port = v.filter { it.isDigit() }.take(5) }, label = { Text("Port") }, placeholder = { Text("22") }, singleLine = true,
                            shape = fieldShape, colors = fieldColors, textStyle = mono,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("Password") },
                        supportingText = { Text("The password you use to log in to that computer") }, singleLine = true,
                        shape = fieldShape, colors = fieldColors,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (canConnect) connect() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pc != null) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { keepLogin = !keepLogin },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(keepLogin, { keepLogin = it }, colors = CheckboxDefaults.colors(checkedColor = Accent))
                        Text("Remember on this phone", color = Color(0xFFC9D1D9))
                    }
                    Button(
                        enabled = canConnect,
                        onClick = { connect() }, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
            }
            if (status.isNotEmpty() && !connecting) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0x33FF8A80)) {
                    Text(status, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
        }
    }
}

private val KeyboardBg = Color(0xFF0B0E11)

@Composable
private fun PcChoice(name: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) KeySticky else KeyBg)
            .border(1.dp, if (selected) Accent else KeyBg, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color(0xFFE6EBEF), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(hint, color = Color(0xFF9AA4AE), fontSize = 12.sp)
        }
        if (selected) Text("✓", color = Accent, fontWeight = FontWeight.Bold)
    }
}

/** JSch's messages ("Auth fail", "timeout: socket is not established") in plain words. */
internal fun friendlyError(e: Throwable, host: String, port: String, viaDishus: Boolean = false): String {
    val m = e.message.orEmpty()
    val chain = generateSequence(e) { it.cause }.toList()
    return when {
        m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> "Wrong username or password."
        viaDishus && m == "Lockdown" -> "$host is locked (dishus kill). Run `dishus unlock` on it."
        viaDishus && m == "Expired" -> "The pairing with $host expired. Run `dishus renew` on it or pair again."
        viaDishus && (m.contains("reaching the PC") || m.contains("could not reach")) ->
            "Can't reach $host through DiShus. Is it on and is DiShus running there (and up to date)?"
        // The tunnel works but the PC closed the stream: nothing listens on its SSH port.
        viaDishus && listOf("End of IO Stream", "foreign host", "reset", "Broken pipe", "Connection closed")
            .any { m.contains(it, true) } -> "$host answered, but its SSH server is off. Windows: run the DiShus installer again and " +
            "answer y to SSH. Linux: enable sshd (install.sh shows how)."
        chain.any { it is java.net.UnknownHostException } -> "Can't find \"$host\". Check the address."
        chain.any { it is java.net.ConnectException } ->
            "$host is reachable but nothing answers on port ${port.ifBlank { "22" }}. Is the SSH server on?"
        m.contains("timeout", true) || chain.any { it is java.net.SocketTimeoutException } ->
            "No answer from $host. Is it on, on the same network, with SSH turned on there?"
        else -> "Could not connect: ${m.ifBlank { e.javaClass.simpleName }}"
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
                    setTextSize(textPx.toInt()) // first: it creates the renderer setTypeface needs
                    // Nerd Font: oh-my-posh / starship prompt icons render instead of boxes.
                    setTypeface(ResourcesCompat.getFont(ctx, R.font.nerd_mono) ?: Typeface.MONOSPACE)
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
