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
import androidx.compose.ui.Modifier
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

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(8.dp)) {
        if (session == null) {
            Text("SSH", color = Color.White, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = host.isNotBlank() && user.isNotBlank(), onClick = { out = ""; connect() }) { Text("Connect") }
            if (status.isNotEmpty()) Text(status, color = Color(0xFFFFB74D))
            if (out.isNotEmpty()) Text(out.takeLast(2000), color = Color.LightGray, fontFamily = FontFamily.Monospace)
        } else {
            Text(out, color = Color(0xFFE6EBEF), fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((label, seq) in listOf("Tab" to "\t", "Esc" to "\u001B", "^C" to "\u0003", "^D" to "\u0004",
                    "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C"))
                    OutlinedButton(onClick = { send(seq) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text(label) }
            }
            OutlinedTextField(line, { line = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(line + "\n"); line = "" }))
        }
    }
}
