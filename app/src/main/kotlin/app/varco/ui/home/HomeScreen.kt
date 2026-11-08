package app.varco.ui.home

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.Server
import app.varco.data.withoutServer
import app.varco.ssh.LocalNetwork
import app.varco.ssh.Shell
import app.varco.term.Schemes
import app.varco.ui.components.Bar
import app.varco.ui.components.ConfirmDialog
import app.varco.ui.components.EmptyState
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.components.SectionHeader
import app.varco.ui.nav.Navigator
import app.varco.ui.nav.Route

/** Open sessions and saved servers. A tap on a server opens a new terminal tab. */
@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val state by app.store.state.collectAsStateWithLifecycle()
    val shells by app.sessions.shells.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Server?>(null) }
    var closing by remember { mutableStateOf<Shell?>(null) }
    val activity = LocalActivity.current

    fun open(server: Server) {
        app.sessions.open(server)
        nav.go(Route.Terminal)
    }

    // The server that waits for the answer to the local network question; saved over a configuration change.
    var waiting by rememberSaveable { mutableStateOf<String?>(null) }
    // One launcher: Android cancels a second permission request while one is open.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Connect also after a denial: the time-out message then names the permission (LocalNetwork).
        state.servers.firstOrNull { it.id == waiting }?.let(::open)
        waiting = null
    }

    fun connect(server: Server) {
        if (server.user.isBlank()) return nav.go(Route.EditServer(server.id))
        // The notification shows that sessions stay open and offers "Disconnect all". Denied is fine. After one
        // denial the rationale flag is true, so the app does not ask again.
        val notification = Manifest.permission.POST_NOTIFICATIONS
        val ask = buildList {
            if (shells.isEmpty() && activity?.shouldShowRequestPermissionRationale(notification) == false) add(notification)
            if (!LocalNetwork.allowed(context) && LocalNetwork.looksLocal(server.host)) add(LocalNetwork.PERMISSION)
        }
        // A local server waits for the answer, because its connection fails while the permission is off.
        val wait = LocalNetwork.PERMISSION in ask
        if (wait) waiting = server.id
        if (ask.isNotEmpty()) permissions.launch(ask.toTypedArray())
        if (!wait) open(server)
    }

    Scaffold(
        topBar = {
            Bar("Varco") {
                IconButton(onClick = { nav.go(Route.Snippets) }) { Icon(Icons.Bolt, contentDescription = "Snippets") }
                IconButton(onClick = { nav.go(Route.Keys) }) { Icon(Icons.Key, contentDescription = "Keys") }
                IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Settings, contentDescription = "Settings") }
            }
        },
        floatingActionButton = {
            if (state.servers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nav.go(Route.EditServer(null)) },
                    icon = { Icon(Icons.Add, contentDescription = null) },
                    text = { Text("Server") },
                )
            }
        },
    ) { padding ->
        if (state.servers.isEmpty() && shells.isEmpty()) {
            EmptyState(
                icon = Icons.Terminal,
                title = "No servers yet",
                message = if (app.store.loadFailed) {
                    LOAD_FAILED
                } else {
                    "Add a server, or import your keys and ~/.ssh/config from Termux."
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Button(onClick = { nav.go(Route.EditServer(null)) }) { Text("Add server") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { nav.go(Route.Import) }) { Text("Import from Termux") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (app.store.loadFailed) {
                item {
                    Text(
                        LOAD_FAILED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (shells.isNotEmpty()) {
                item { SectionHeader("Open") }
                items(shells, key = { it.id }) { shell -> ShellRow(shell, onOpen = {
                    app.sessions.currentId.value = shell.id
                    nav.go(Route.Terminal)
                }, onClose = {
                    if (shell.state.value is Shell.State.Connected) closing = shell else app.sessions.close(shell)
                }) }
            }
            item { SectionHeader("Servers") }
            val sorted = state.servers.sortedWith(compareByDescending<Server> { it.lastUsed }.thenBy { it.name.lowercase() })
            items(sorted, key = { it.id }) { server ->
                var menu by remember { mutableStateOf(false) }
                val keyName = state.keys.firstOrNull { it.id == server.keyId }?.name
                ListRow(
                    title = server.name,
                    subtitle = buildString {
                        append(server.user.ifBlank { "?" }).append('@').append(server.host)
                        if (server.port != 22) append(':').append(server.port)
                        if (keyName != null) append("  ·  ").append(keyName)
                    },
                    icon = Icons.Server,
                    onClick = { connect(server) },
                ) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.More, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Files") },
                                leadingIcon = { Icon(Icons.Folder, null) },
                                onClick = { menu = false; nav.go(Route.Files(server.id)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Edit, null) },
                                onClick = { menu = false; nav.go(Route.EditServer(server.id)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Delete, null) },
                                onClick = { menu = false; deleting = server },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    // A live session ends only after a confirmation.
    closing?.let { s ->
        ConfirmDialog(
            title = "Close ${s.target.collectAsStateWithLifecycle().value.name}?",
            text = "The connection ends. Programs that run in this session stop.",
            confirm = "Close",
            destructive = true,
            onConfirm = { app.sessions.close(s); closing = null },
            onDismiss = { closing = null },
        )
    }

    deleting?.let { server ->
        ConfirmDialog(
            title = "Delete ${server.name}?",
            text = "The server, its saved password and its trusted host keys are deleted. " +
                "Host keys stay when another server has the same host and port. Your keys stay.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                app.store.update { it.withoutServer(server.id) }
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** The store file is kept aside, and the next start reads it again if nothing was saved. */
private const val LOAD_FAILED = "Saved data could not be opened. It is kept. Restart Varco before you make changes."

@Composable
private fun ShellRow(shell: Shell, onOpen: () -> Unit, onClose: () -> Unit) {
    val state by shell.state.collectAsStateWithLifecycle()
    val title by shell.title.collectAsStateWithLifecycle()
    val target by shell.target.collectAsStateWithLifecycle()
    val (dot, label) = when (val s = state) {
        Shell.State.Connecting -> Color(Schemes.default.ansi[3]) to "Connecting…"
        Shell.State.Connected -> Color(Schemes.default.ansi[2]) to (title ?: "Connected")
        is Shell.State.Closed -> MaterialTheme.colorScheme.outline to (s.message ?: "Session ended")
    }
    ListRow(
        title = target.name,
        subtitle = label,
        onClick = onOpen,
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        IconButton(onClick = onClose) { Icon(Icons.Close, contentDescription = "Close") }
    }
}
