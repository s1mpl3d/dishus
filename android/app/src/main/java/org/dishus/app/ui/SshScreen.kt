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
import java.io.OutputStream

// shortcut: output is ANSI-stripped plain text, not a terminal emulator, so vim/top/htop won't
// render; upgrade to a real emulator (e.g. termux terminal-view) if full-screen apps matter.
// shortcut: host keys are accepted without checking (StrictHostKeyChecking=no); pin them before
// using over untrusted networks.
private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\r")

@Composable
fun SshScreen(onExit: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<OutputStream?>(null) }
    var session by remember { mutableStateOf<Session?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val haptic = LocalView.current

    fun close() { runCatching { session?.disconnect() }; session = null; stream = null }
    DisposableEffect(Unit) { onDispose { close() } }
    BackHandler { close(); onExit() }
    LaunchedEffect(out) { scroll.scrollTo(scroll.maxValue) }

    fun send(s: String) {
        val o = stream ?: return
        scope.launch(Dispatchers.IO) { runCatching { o.write(s.toByteArray()); o.flush() } }
    }

    fun connect() {
        status = "connecting…"
        scope.launch(Dispatchers.IO) {
            runCatching {
                val s = JSch().getSession(user, host.trim(), port.toIntOrNull() ?: 22)
                s.setPassword(pass)
                s.setConfig("StrictHostKeyChecking", "no")
                s.connect(15_000)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("dumb")
                val input = ch.inputStream
                stream = ch.outputStream
                ch.connect(15_000)
                session = s
                withContext(Dispatchers.Main) { status = "" }
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val text = ANSI.replace(String(buf, 0, n), "")
                    withContext(Dispatchers.Main) { out = (out + text).takeLast(50_000) }
                }
                withContext(Dispatchers.Main) { status = "disconnected"; close() }
            }.onFailure { e -> withContext(Dispatchers.Main) { status = "error: ${e.message}"; close() } }
        }
    }

    val connecting = status == "connecting…"
    val fieldShape = RoundedCornerShape(12.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent, unfocusedBorderColor = KeyBgPressed,
        focusedLabelColor = Accent, cursorColor = Accent,
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
                val ready = host.isNotBlank() && user.isNotBlank()
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Accent)) { append("~ $ ") }
                        append("ssh ")
                        withStyle(SpanStyle(color = if (ready) Color(0xFFE6EBEF) else Color(0xFF5C6670))) {
                            append("${user.ifBlank { "user" }}@${host.trim().ifBlank { "host" }}")
                            if (port.isNotBlank() && port != "22") append(" -p $port")
                        }
                        withStyle(SpanStyle(color = Accent)) { append(" ▌") }
                    },
                    color = Color(0xFFE6EBEF), fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                )
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        host, { host = it }, label = { Text("Host") }, placeholder = { Text("192.168.1.20") },
                        singleLine = true, shape = fieldShape, colors = fieldColors, textStyle = mono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            user, { user = it }, label = { Text("User") }, singleLine = true,
                            shape = fieldShape, colors = fieldColors, textStyle = mono,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            port, { v -> port = v.filter { it.isDigit() }.take(5) }, label = { Text("Port") }, singleLine = true,
                            shape = fieldShape, colors = fieldColors, textStyle = mono,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                        shape = fieldShape, colors = fieldColors,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (!connecting && host.isNotBlank() && user.isNotBlank()) { out = ""; connect() } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !connecting && host.isNotBlank() && user.isNotBlank(),
                        onClick = { out = ""; connect() }, shape = RoundedCornerShape(12.dp),
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
                "$user@${host.trim()}" + if (port != "22") ":$port" else "", color = Color(0xFFC9D1D9),
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
