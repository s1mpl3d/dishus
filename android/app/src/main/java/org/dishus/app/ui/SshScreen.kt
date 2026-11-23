package org.dishus.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStream
import org.dishus.app.DishusApp
import uniffi.dishus_core.SshTunnel

// shortcut: output is ANSI-stripped plain text, not a terminal emulator, so vim/top/htop won't
// render; upgrade to a real emulator (e.g. termux terminal-view) if full-screen apps matter.
// shortcut: host keys are accepted without checking (StrictHostKeyChecking=no); pin them before
// using over untrusted networks.
private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\r|\u0007")

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
    var out by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<OutputStream?>(null) }
    var session by remember { mutableStateOf<Session?>(null) }
    var tunnel by remember { mutableStateOf<SshTunnel?>(null) }
    // A PC's saved login fills the form when it is picked.
    LaunchedEffect(pcId) {
        if (pcId.isEmpty()) { user = saved.second; pass = "" }
        else app.vault.sshLogin(pcId)?.let { (u, p) -> user = u; pass = p } ?: run { pass = "" }
    }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val haptic = LocalView.current

    fun close() {
        runCatching { session?.disconnect() }; session = null; stream = null
        runCatching { tunnel?.stop(); tunnel?.close() }; tunnel = null
    }
    DisposableEffect(Unit) { onDispose { close() } }
    // A stray Back must not drop a live session: ask first. Without one, Back leaves.
    BackHandler { if (session != null) confirmExit = true else onExit() }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Disconnect?") },
            text = { Text("This closes the SSH session.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; close() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancel") } },
        )
    }
    LaunchedEffect(out) { scroll.scrollTo(scroll.maxValue) }

    fun send(s: String) {
        val o = stream ?: return
        scope.launch(Dispatchers.IO) { runCatching { o.write(s.toByteArray()); o.flush() } }
    }

    fun connect() {
        out = ""
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
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("dumb")
                val input = ch.inputStream
                stream = ch.outputStream
                ch.connect(15_000)
                session = s
                if (via != null) {
                    if (keepLogin) app.vault.saveSshLogin(via.id, user, pass) else app.vault.forgetSshLogin(via.id)
                }
                withContext(Dispatchers.Main) { status = "" }
                // A reader, not raw bytes: a UTF-8 character split across two reads stays whole.
                val reader = InputStreamReader(input, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val text = ANSI.replace(String(buf, 0, n), "")
                    withContext(Dispatchers.Main) { out = applyOutput(out, text) }
                }
                withContext(Dispatchers.Main) { status = "disconnected"; close() }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    // Closed by the user (✕ / Back): the read fails, but that is not an error.
                    if (session != null || status == "connecting…") status = friendlyError(e, via?.name ?: host.trim(), port, viaDishus = via != null)
                    close()
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

    if (session == null) {
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
                            user, { user = it }, label = { Text("Username") }, placeholder = { Text("your login") }, singleLine = true,
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
                val ended = status == "disconnected"
                Surface(shape = RoundedCornerShape(12.dp), color = if (ended) KeyBg else Color(0x33FF8A80)) {
                    Text(if (ended) "Session ended." else status,
                        color = if (ended) Color(0xFFC9D1D9) else MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            if (out.isNotEmpty()) {
                Text(out.takeLast(2000), color = Color(0xFF8A949E), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(KeyboardBg).padding(start = 14.dp, end = 4.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
            Text(
                "$user@$target" + if (pc == null && port != "22") ":$port" else "", color = Color(0xFFC9D1D9),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            TextButton(onClick = { close() }) { Text("✕", color = Color(0xFF8A949E)) }
        }
        Text(
            out, color = Color(0xFFE6EBEF), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Column(Modifier.fillMaxWidth().background(KeyboardBg).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((label, seq) in listOf("Tab" to "\t", "Esc" to "\u001B", "^C" to "\u0003", "^D" to "\u0004",
                    "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C")) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(KeyBg)
                            .clickable { haptic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); send(seq) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = Color(0xFFE6EBEF), fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
                }
            }
            OutlinedTextField(
                line, { line = it }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors, textStyle = mono,
                leadingIcon = { Text("$", color = Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                trailingIcon = { TextButton(onClick = { send(line + "\n"); line = "" }) { Text("⏎", color = Accent, fontSize = 18.sp) } },
                placeholder = { Text("command", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send(line + "\n"); line = "" }),
                modifier = Modifier.fillMaxWidth(),
            )
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

/** Appends shell output; a backspace erases the previous character on the line (shells redraw
 *  the input line with them). Keeps the last 50k chars. */
internal fun applyOutput(out: String, text: String): String {
    val sb = StringBuilder(out)
    for (ch in text) {
        if (ch == '\b') { if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1) } else sb.append(ch)
    }
    return if (sb.length > 50_000) sb.substring(sb.length - 50_000) else sb.toString()
}
