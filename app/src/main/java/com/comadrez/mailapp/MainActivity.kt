package com.comadrez.mailapp

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

class DriftVM(app: Application) : AndroidViewModel(app) {
    private val prefs = EncryptedSharedPreferences.create(
        app, "driftbox_secure",
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val tokens = ConcurrentHashMap<String, String>()

    init { migrateOld(app) }

    var accounts by mutableStateOf(loadAccounts())
    var currentId by mutableStateOf(prefs.getString("current", "") ?: "")
    var screen by mutableStateOf(if (accounts.isEmpty()) "login" else "home")
    var messages by mutableStateOf(listOf<Msg>())
    var domains by mutableStateOf(listOf<String>())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var onlyUnread by mutableStateOf(false)
    var openMsg by mutableStateOf<Msg?>(null)
    var body by mutableStateOf("")
    var attachments by mutableStateOf(listOf<Att>())
    var refreshing by mutableStateOf(false)
    var savingAtt by mutableStateOf<String?>(null)
    private var loadingDomains = false

    val current: Acct? get() = accounts.firstOrNull { it.id == currentId } ?: accounts.firstOrNull()

    /** Moves addresses saved by the earlier unencrypted build into encrypted storage. */
    private fun migrateOld(app: Application) {
        if (prefs.contains("list")) return
        val old = app.getSharedPreferences("temp_accounts", Context.MODE_PRIVATE)
        val list = old.getString("list", null) ?: return
        prefs.edit().putString("list", list).putString("current", old.getString("current", "") ?: "").apply()
        old.edit().clear().apply()
    }

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
            error = if (e.code == 401) "This address expired or was deleted. Use Sign out in the menu to remove it."
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

    fun back() { error = null; screen = if (accounts.isEmpty()) "login" else "home" }

    fun loadDomains() {
        if (domains.isNotEmpty() || loadingDomains) return
        viewModelScope.launch {
            loadingDomains = true
            try {
                domains = withContext(Dispatchers.IO) { MailTm.domains() }
            } catch (e: Exception) {
                error = "Couldn't load domains: ${e.message}"
            }
            loadingDomains = false
        }
    }

    fun login(address: String, password: String) = task {
        val addr = address.trim().lowercase()
        if (addr.isBlank() || password.isBlank()) throw IllegalStateException("Enter your address and password")
        val (id, realAddr) = withContext(Dispatchers.IO) {
            val t = try {
                MailTm.token(addr, password)
            } catch (e: MailTm.ApiError) {
                if (e.code == 401) throw IllegalStateException("Wrong address or password") else throw e
            }
            val me = MailTm.me(t)
            tokens[me.first] = t
            me
        }
        accounts = accounts.filter { it.id != id } + Acct(id, realAddr, password)
        currentId = id
        messages = emptyList()
        screen = "home"
        save()
    }

    fun register(user: String, domain: String, password: String) = task {
        if (user.isBlank()) throw IllegalStateException("Choose a username")
        if (domain.isBlank()) throw IllegalStateException("Pick a domain")
        if (password.length < 6) throw IllegalStateException("Password must be at least 6 characters")
        val address = "$user@$domain"
        val id = withContext(Dispatchers.IO) { MailTm.createAccount(address, password) }
        accounts = accounts + Acct(id, address, password)
        currentId = id
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

    private fun removeLocal(a: Acct) {
        tokens.remove(a.id)
        accounts = accounts.filter { it.id != a.id }
        if (currentId == a.id) {
            currentId = accounts.firstOrNull()?.id ?: ""
            messages = emptyList()
        }
        screen = if (accounts.isEmpty()) "login" else "home"
        save()
    }

    fun signOut(a: Acct) = removeLocal(a)

    fun deleteAddress(a: Acct) = task {
        try {
            authed(a) { MailTm.deleteAccount(it, a.id) }
        } catch (e: MailTm.ApiError) {
            if (e.code != 401 && e.code != 404) throw e
        }
        removeLocal(a)
    }

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

    /** Pull-to-refresh: shows the pull indicator instead of the top progress bar. */
    fun pullRefresh() {
        val a = current ?: return
        viewModelScope.launch {
            refreshing = true
            error = null
            try {
                val m = authed(a) { MailTm.messages(it) }
                if (current?.id == a.id) messages = m
            } catch (e: Exception) {
                error = e.message ?: "Couldn't refresh"
            }
            refreshing = false
        }
    }

    /** Downloads one attachment into the file the user picked (Storage Access Framework). */
    fun saveAttachment(att: Att, uri: Uri) {
        val a = current ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            savingAtt = att.id
            error = null
            try {
                authed(a) { t ->
                    val out = app.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Can't write to that location")
                    out.use { MailTm.download(t, att.url, it) }
                }
                Toast.makeText(app, "Saved ${att.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't save the attachment"
            }
            savingAtt = null
        }
    }

    fun open(m: Msg) {
        val a = current ?: return
        openMsg = m
        body = ""
        attachments = emptyList()
        error = null
        screen = "read"
        task {
            val d = authed(a) { MailTm.detail(it, m.id) }
            body = d.text
            attachments = d.attachments
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

    /** Used by swipe-to-delete: removes from the list at once, then tells the server. */
    fun deleteInline(m: Msg) {
        val a = current ?: return
        messages = messages.filter { it.id != m.id }
        viewModelScope.launch {
            try {
                authed(a) { MailTm.deleteMessage(it, m.id) }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't delete the message"
                refresh(silent = true)
            }
        }
    }
}

@Composable
fun App(vm: DriftVM = viewModel()) {
    when (vm.screen) {
        "login" -> {
            if (vm.accounts.isNotEmpty()) BackHandler { vm.back() }
            LoginScreen(vm)
        }
        "register" -> { BackHandler { vm.go("login") }; RegisterScreen(vm) }
        "read" -> { BackHandler { vm.back() }; ReadScreen(vm) }
        "about" -> { BackHandler { vm.back() }; AboutScreen(vm) }
        else -> HomeScreen(vm)
    }
}

@Composable
fun Attribution() {
    val uri = LocalUriHandler.current
    Text(
        "Powered by mail.tm",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { uri.openUri("https://mail.tm") }.padding(vertical = 12.dp)
    )
}

@Composable
fun LoginScreen(vm: DriftVM) {
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_mail), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("Driftbox", style = MaterialTheme.typography.headlineMedium)
            Text("Disposable email, no strings attached", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                u, { u = it }, label = { Text("Email address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
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
                enabled = !vm.loading && u.isNotBlank() && p.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.loading) "Signing in..." else "Sign in") }
        }
        TextButton(
            onClick = { vm.go("register") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("No address yet? Register", style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(vm: DriftVM) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadDomains() }
    LaunchedEffect(vm.domains) {
        if (domain.isBlank() && vm.domains.isNotEmpty()) domain = vm.domains.first()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Register") },
                navigationIcon = {
                    IconButton(onClick = { vm.go("login") }) {
                        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Create a new Driftbox address.")
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    user,
                    { v -> user = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "._-" } },
                    label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = vm.domains.isNotEmpty()) {
                        Text("@" + domain.ifBlank { "..." })
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        vm.domains.forEach { d ->
                            DropdownMenuItem(text = { Text(d) }, onClick = { domain = d; menu = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password (6+ characters)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(
                "The password is shown so you can copy it. Write it down: there is no password recovery.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = {
                user = MailTm.randomString(10)
                pass = MailTm.randomString(14)
            }) { Text("Generate a random address and password") }
            vm.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.register(user, domain, pass) },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (vm.loading) "Creating..." else "Create address") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: DriftVM) {
    val a = vm.current ?: return
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val list = if (vm.onlyUnread) vm.messages.filter { !it.seen } else vm.messages

    LaunchedEffect(a.id) {
        vm.refresh()
        while (true) {
            delay(10_000)
            vm.refresh(silent = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Inbox", style = MaterialTheme.typography.titleMedium)
                        Text(
                            a.address, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (vm.accounts.size > 1) {
                        Box {
                            IconButton(onClick = { switcher = true }) {
                                Icon(painterResource(R.drawable.ic_users), contentDescription = "Switch address")
                            }
                            DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                                vm.accounts.forEach { acc ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                acc.address,
                                                fontWeight = if (acc.id == a.id) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = { switcher = false; vm.switchTo(acc) }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Copy address") }, onClick = {
                                menu = false
                                clipboard.setText(AnnotatedString(a.address))
                                Toast.makeText(ctx, "Address copied", Toast.LENGTH_SHORT).show()
                            })
                            DropdownMenuItem(text = { Text("Add address") }, onClick = {
                                menu = false
                                vm.go("login")
                            })
                            DropdownMenuItem(text = { Text("About and limits") }, onClick = {
                                menu = false
                                vm.go("about")
                            })
                            DropdownMenuItem(text = { Text("Delete this address") }, onClick = {
                                menu = false
                                dialog = "delete"
                            })
                            DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                                menu = false
                                dialog = "signout"
                            })
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(!vm.onlyUnread, { vm.onlyUnread = false }, { Text("All") })
                FilterChip(
                    vm.onlyUnread, { vm.onlyUnread = true },
                    { Text("Unread (${vm.messages.count { !it.seen }})") }
                )
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = { vm.pullRefresh() },
                modifier = Modifier.weight(1f)
            ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (list.isEmpty() && !vm.loading) {
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
                            Text(if (vm.onlyUnread) "No unread messages" else "Waiting for emails...")
                            Text("Checking every 10 seconds", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                items(list, key = { it.id }) { m ->
                    SwipeRow(m, onDelete = { vm.deleteInline(m) }, onClick = { vm.open(m) })
                    HorizontalDivider()
                }
            }
            }
        }
    }

    dialog?.let { d ->
        val deleting = d == "delete"
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (deleting) "Delete address?" else "Sign out?") },
            text = {
                Text(
                    if (deleting) "${a.address} and all of its emails will be deleted permanently."
                    else "This removes ${a.address} from this device. You can sign in again later with its password."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (deleting) vm.deleteAddress(a) else vm.signOut(a)
                    dialog = null
                }) { Text(if (deleting) "Delete" else "Sign out") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(m: Msg, onDelete: () -> Unit, onClick: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                onDelete()
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(painterResource(R.drawable.ic_trash), contentDescription = "Delete")
            }
        }
    ) {
        Surface { MsgRow(m, onClick) }
    }
}

@Composable
fun MsgRow(m: Msg, onClick: () -> Unit) {
    val w = if (m.seen) FontWeight.Normal else FontWeight.Bold
    val hue = (abs(m.from.lowercase().hashCode()) % 360).toFloat()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).background(Color.hsv(hue, 0.45f, 0.75f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                m.from.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White, fontWeight = FontWeight.Bold
            )
        }
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
        Text(fmtShort(m.date), style = MaterialTheme.typography.bodySmall, fontWeight = w)
    }
}

private fun fmtLong(ms: Long): String =
    if (ms == 0L) "" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

private fun fmtShort(ms: Long): String = when {
    ms == 0L -> ""
    DateUtils.isToday(ms) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(vm: DriftVM) {
    val m = vm.openMsg
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var pendingAtt by remember { mutableStateOf<Att?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest ->
        val att = pendingAtt
        if (dest != null && att != null) vm.saveAttachment(att, dest)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val linked = remember(vm.body, linkColor) {
        buildAnnotatedString {
            val text = vm.body
            var last = 0
            for (r in findLinkRanges(text)) {
                append(text.substring(last, r.first))
                val url = text.substring(r.first, r.last + 1)
                withLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = LinkInteractionListener { pendingLink = url }
                    )
                ) { append(url) }
                last = r.last + 1
            }
            append(text.substring(last))
        }
    }
    val codes = remember(vm.body, m?.subject) { findCodes((m?.subject ?: "") + "\n" + vm.body) }

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
                Text(fmtLong(m.date), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
            }
            if (vm.loading) CircularProgressIndicator()
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (codes.isNotEmpty()) {
                Text("Verification code", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                codes.forEach { c ->
                    FilledTonalButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(c))
                            Toast.makeText(ctx, "Code copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy  $c") }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (vm.attachments.isNotEmpty()) {
                Text("Attachments", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                vm.attachments.forEach { att ->
                    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(att.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Formatter.formatShortFileSize(ctx, att.size),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(
                                onClick = { pendingAtt = att; saver.launch(att.name) },
                                enabled = vm.savingAtt == null
                            ) { Text(if (vm.savingAtt == att.id) "Saving..." else "Save") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            SelectionContainer { Text(linked) }
        }
    }

    pendingLink?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("Open this link?") },
            text = {
                Column {
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Links in disposable email are often spam or phishing. Only open links you expect.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    try {
                        uri.openUri(url)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(vm: DriftVM) {
    val limits = listOf(
        "Receive only. Driftbox cannot send email: there is no Compose button and no Sent folder.",
        "No password recovery. If you forget the password, the address is lost.",
        "Addresses and emails can be removed by the provider at any time. Do not use Driftbox for accounts you cannot afford to lose.",
        "Not private. Mail is stored on Mail.tm's servers, so never use it for sensitive information.",
        "Anyone who knows the address and password can read that mailbox.",
        "Mail is checked every 10 seconds while the app is open. There are no notifications when the app is closed.",
        "Attachments can be saved to your phone but not previewed. Links in emails ask for confirmation before opening. Codes are detected automatically and may occasionally be missed.",
        "Some websites block disposable email domains, so a few sign-ups may be refused.",
        "The domain list comes from Mail.tm and can change. Too many requests in a short time can show a \"wait a few seconds\" error.",
        "Your addresses and passwords are saved encrypted on this phone only."
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Icon(
                painterResource(R.drawable.ic_mail), null, Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text("Driftbox", style = MaterialTheme.typography.titleLarge)
            Text("Version 0.6", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Text(
                "Driftbox gives you free disposable email addresses. The mailboxes are provided by the free Mail.tm API. " +
                    "Driftbox is an independent app and is not affiliated with Mail.tm."
            )
            Attribution()
            Spacer(Modifier.height(8.dp))
            Text("Limits", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            limits.forEach {
                Text("\u2022 $it", modifier = Modifier.padding(vertical = 3.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Credits", style = MaterialTheme.typography.titleMedium)
            Text("Icons: Lucide (ISC license).", style = MaterialTheme.typography.bodySmall)
        }
    }
}
