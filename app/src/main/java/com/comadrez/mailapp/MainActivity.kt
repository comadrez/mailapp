package com.comadrez.mailapp

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

class MailVM(app: Application) : AndroidViewModel(app) {
    private val prefs = EncryptedSharedPreferences.create(
        app, "creds",
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private var user = prefs.getString("u", "") ?: ""
    private var pass = prefs.getString("p", "") ?: ""

    var screen by mutableStateOf(if (user.isNotEmpty()) "inbox" else "login")
    var headers by mutableStateOf(listOf<MailHeader>())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var onlyUnread by mutableStateOf(false)
    var current by mutableStateOf<MailHeader?>(null)
    var body by mutableStateOf("")

    init { if (user.isNotEmpty()) refresh() }

    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        loading = true
        error = null
        try { block() } catch (e: Exception) { error = e.message ?: e.toString() }
        loading = false
    }

    fun login(u: String, p: String) = task {
        val email = u.trim()
        val h = withContext(Dispatchers.IO) { MailClient.fetchInbox(email, p) }
        user = email
        pass = p
        prefs.edit().putString("u", user).putString("p", pass).apply()
        headers = h
        screen = "inbox"
    }

    fun refresh() = task {
        headers = withContext(Dispatchers.IO) { MailClient.fetchInbox(user, pass) }
    }

    fun open(h: MailHeader) {
        current = h
        body = ""
        screen = "read"
        task {
            body = withContext(Dispatchers.IO) { MailClient.readMessage(user, pass, h.uid) }
            headers = headers.map { if (it.uid == h.uid) it.copy(seen = true) else it }
        }
    }

    fun send(to: String, subject: String, text: String) = task {
        withContext(Dispatchers.IO) { MailClient.send(user, pass, to, subject, text) }
        screen = "inbox"
    }

    fun back() { screen = "inbox"; error = null }

    fun logout() {
        prefs.edit().clear().apply()
        user = ""; pass = ""
        headers = emptyList()
        error = null
        screen = "login"
    }
}

@Composable
fun App(vm: MailVM = viewModel()) {
    when (vm.screen) {
        "login" -> LoginScreen(vm)
        "inbox" -> InboxScreen(vm)
        "read" -> { BackHandler { vm.back() }; ReadScreen(vm) }
        "compose" -> { BackHandler { vm.back() }; ComposeScreen(vm) }
    }
}

@Composable
fun LoginScreen(vm: MailVM) {
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("mail.com", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            u, { u = it }, label = { Text("Email address") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            p, { p = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        vm.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.login(u, p) },
            enabled = !vm.loading && u.isNotBlank() && p.isNotBlank()
        ) { Text(if (vm.loading) "Signing in..." else "Sign in") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: MailVM) {
    val list = if (vm.onlyUnread) vm.headers.filter { !it.seen } else vm.headers
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inbox") },
                actions = {
                    TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    TextButton(onClick = { vm.logout() }) { Text("Sign out") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.screen = "compose" }) { Text("Compose") }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!vm.onlyUnread, { vm.onlyUnread = false }, { Text("All") })
                FilterChip(
                    vm.onlyUnread, { vm.onlyUnread = true },
                    { Text("Unread (${vm.headers.count { !it.seen }})") }
                )
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            LazyColumn {
                items(list, key = { it.uid }) { h ->
                    HeaderRow(h) { vm.open(h) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun HeaderRow(h: MailHeader, onClick: () -> Unit) {
    val w = if (h.seen) FontWeight.Normal else FontWeight.Bold
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (h.seen) Color.Transparent else MaterialTheme.colorScheme.primary, CircleShape
            )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.from, fontWeight = w, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.subject, fontWeight = w, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(fmt(h.date), style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmt(ms: Long): String =
    if (ms == 0L) "" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(vm: MailVM) {
    val h = vm.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message") },
                navigationIcon = { TextButton(onClick = { vm.back() }) { Text("Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (h != null) {
                Text(h.subject, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("From: ${h.from}", style = MaterialTheme.typography.bodySmall)
                Text(fmt(h.date), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
            }
            if (vm.loading) CircularProgressIndicator()
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(vm.body)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(vm: MailVM) {
    var to by remember { mutableStateOf("") }
    var subj by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = { TextButton(onClick = { vm.back() }) { Text("Cancel") } },
                actions = {
                    TextButton(
                        onClick = { vm.send(to, subj, text) },
                        enabled = !vm.loading && to.isNotBlank()
                    ) { Text(if (vm.loading) "Sending..." else "Send") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(subj, { subj = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(text, { text = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
