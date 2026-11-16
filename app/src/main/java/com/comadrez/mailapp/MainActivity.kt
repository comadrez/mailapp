package com.comadrez.mailapp

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

class TempVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("temp_accounts", Context.MODE_PRIVATE)
    private val tokens = ConcurrentHashMap<String, String>()

    var accounts by mutableStateOf(loadAccounts())
    var currentId by mutableStateOf(prefs.getString("current", "") ?: "")
    var screen by mutableStateOf("home")
    var messages by mutableStateOf(listOf<Msg>())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var openMsg by mutableStateOf<Msg?>(null)
    var body by mutableStateOf("")

    val current: Acct? get() = accounts.firstOrNull { it.id == currentId } ?: accounts.firstOrNull()

    private fun loadAccounts(): List<Acct> = try {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Acct(o.getString("id"), o.getString("address"), o.getString("password"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach {
            arr.put(JSONObject().put("id", it.id).put("address", it.address).put("password", it.password))
        }
        prefs.edit().putString("list", arr.toString()).putString("current", currentId).apply()
    }

    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        loading = true
        error = null
        try {
            block()
        } catch (e: MailTm.ApiError) {
            error = if (e.code == 401) "This address expired or was deleted. Remove it in Addresses."
            else e.message
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }

    private suspend fun <T> authed(a: Acct, call: (String) -> T): T = withContext(Dispatchers.IO) {
        try {
            call(tokens.getOrPut(a.id) { MailTm.token(a.address, a.password) })
        } catch (e: MailTm.ApiError) {
            if (e.code == 401) {
                tokens.remove(a.id)
                call(MailTm.token(a.address, a.password).also { tokens[a.id] = it })
            } else throw e
        }
    }

    fun go(s: String) { error = null; screen = s }

    fun back() { error = null; screen = "home" }

    fun refresh(silent: Boolean = false) {
        val a = current ?: return
        if (silent) {
            viewModelScope.launch {
                try {
                    val m = authed(a) { MailTm.messages(it) }
                    if (current?.id == a.id) messages = m
                } catch (_: Exception) {
                }
            }
        } else {
            task {
                val m = authed(a) { MailTm.messages(it) }
                if (current?.id == a.id) messages = m
            }
        }
    }

    fun create(custom: String) = task {
        val name = custom.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "._-" }
        val user = name.ifBlank { MailTm.randomString(10) }
        val a = withContext(Dispatchers.IO) {
            val domain = MailTm.domains().firstOrNull() ?: throw IllegalStateException("No domains available")
            val address = "$user@$domain"
            val pw = MailTm.randomString(16)
            Acct(MailTm.createAccount(address, pw), address, pw)
        }
        accounts = accounts + a
        currentId = a.id
        messages = emptyList()
        screen = "home"
        save()
    }

    fun switchTo(a: Acct) {
        currentId = a.id
        messages = emptyList()
        error = null
        screen = "home"
        save()
    }

    fun deleteAddress(a: Acct) = task {
        try {
            authed(a) { MailTm.deleteAccount(it, a.id) }
        } catch (e: MailTm.ApiError) {
            if (e.code != 401 && e.code != 404) throw e
        }
        tokens.remove(a.id)
        accounts = accounts.filter { it.id != a.id }
        if (currentId == a.id) {
            currentId = accounts.firstOrNull()?.id ?: ""
            messages = emptyList()
        }
        save()
    }

    fun open(m: Msg) {
        val a = current ?: return
        openMsg = m
        body = ""
        error = null
        screen = "read"
        task {
            body = authed(a) { MailTm.body(it, m.id) }
            if (!m.seen) {
                runCatching { authed(a) { MailTm.markSeen(it, m.id) } }
                messages = messages.map { if (it.id == m.id) it.copy(seen = true) else it }
            }
        }
    }

    fun deleteMessage(m: Msg) {
        val a = current ?: return
        task {
            authed(a) { MailTm.deleteMessage(it, m.id) }
            messages = messages.filter { it.id != m.id }
            screen = "home"
        }
    }
}

@Composable
fun App(vm: TempVM = viewModel()) {
    when (vm.screen) {
        "read" -> { BackHandler { vm.back() }; ReadScreen(vm) }
        "accounts" -> { BackHandler { vm.back() }; AccountsScreen(vm) }
        else -> HomeScreen(vm)
    }
}

@Composable
fun Attribution() {
    val uri = LocalUriHandler.current
    Text(
        "Powered by mail.tm",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable { uri.openUri("https://mail.tm") }.padding(12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: TempVM) {
    val a = vm.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(a?.id) { mutableStateOf(false) }

    LaunchedEffect(a?.id) {
        if (a != null) {
            vm.refresh()
            while (true) {
                delay(10_000)
                vm.refresh(silent = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Temp Mail") },
                actions = {
                    if (a != null) {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (a == null) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    Arrangement.Center,
                    Alignment.CenterHorizontally
                ) {
                    Icon(
                        painterResource(R.drawable.ic_mail), null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Disposable email in one tap", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create a temporary address, use it to sign up for things, and read the emails that arrive here.",
                        textAlign = TextAlign.Center
                    )
                    vm.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.create("") }, enabled = !vm.loading) {
                        Text(if (vm.loading) "Creating..." else "Create temporary address")
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                        Text("Your temporary address", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.address, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(a.address))
                                copied = true
                            }) {
                                Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy address")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.go("accounts") }) {
                                Text("Addresses (${vm.accounts.size})")
                            }
                            if (copied) Text("Copied", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (vm.messages.isEmpty() && !vm.loading) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_inbox), null, Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Waiting for emails...")
                                Text("Checking every 10 seconds", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    items(vm.messages, key = { it.id }) { m ->
                        MsgRow(m) { vm.open(m) }
                        HorizontalDivider()
                    }
                }
            }
            Attribution()
        }
    }
}

@Composable
fun MsgRow(m: Msg, onClick: () -> Unit) {
    val w = if (m.seen) FontWeight.Normal else FontWeight.Bold
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (m.seen) Color.Transparent else MaterialTheme.colorScheme.primary, CircleShape
            )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.from, fontWeight = w, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.subject, fontWeight = w, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.intro.isNotBlank()) {
                Text(
                    m.intro, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(fmt(m.date), style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmt(ms: Long): String =
    if (ms == 0L) "" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(vm: TempVM) {
    val m = vm.openMsg
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = "Back")
                    }
                },
                actions = {
                    if (m != null) {
                        IconButton(onClick = { vm.deleteMessage(m) }) {
                            Icon(painterResource(R.drawable.ic_trash), contentDescription = "Delete message")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (m != null) {
                Text(m.subject, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("From: ${m.from}", style = MaterialTheme.typography.bodySmall)
                Text(fmt(m.date), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
            }
            if (vm.loading) CircularProgressIndicator()
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SelectionContainer { Text(vm.body) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: TempVM) {
    var name by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Acct?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Addresses") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                name, { name = it },
                label = { Text("Custom name (optional)") },
                supportingText = { Text("Leave empty for a random address") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.create(name) }, enabled = !vm.loading) {
                Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (vm.loading) "Creating..." else "Create new address")
            }
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(vm.accounts, key = { it.id }) { a ->
                    val active = a.id == vm.current?.id
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.switchTo(a) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.address, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                            if (active) Text("Active", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { confirm = a }) {
                            Icon(painterResource(R.drawable.ic_trash), contentDescription = "Delete address")
                        }
                    }
                    HorizontalDivider()
                }
            }
            Text(
                "Temporary inboxes can be removed at any time. Don't use them for accounts you can't afford to lose.",
                style = MaterialTheme.typography.bodySmall
            )
            Attribution()
        }
    }
    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete address?") },
            text = { Text("${a.address} and all of its emails will be deleted permanently.") },
            confirmButton = { TextButton(onClick = { vm.deleteAddress(a); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}
