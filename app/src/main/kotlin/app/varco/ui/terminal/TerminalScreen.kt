package app.varco.ui.terminal

import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.Server
import app.varco.data.Settings.Companion.MAX_FONT
import app.varco.data.Settings.Companion.MIN_FONT
import app.varco.ssh.Shell
import app.varco.term.Scheme
import app.varco.term.Schemes
import app.varco.term.TermLook
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.components.SectionHeader
import app.varco.ui.nav.Navigator
import app.varco.ui.nav.Route
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The full-screen terminal of the current tab, with the extra-keys bar above the keyboard. */
@Composable
fun TerminalScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val state by app.store.state.collectAsStateWithLifecycle()
    val shells by app.sessions.shells.collectAsStateWithLifecycle()
    val currentId by app.sessions.currentId.collectAsStateWithLifecycle()
    val shell = shells.firstOrNull { it.id == currentId } ?: shells.lastOrNull()
    if (shell == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val settings = state.settings
    val scheme = Schemes.byId(settings.schemeId, state.schemes)
    val modifiers = remember { Modifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var sessionSheet by remember { mutableStateOf(false) }
    var snippetSheet by remember { mutableStateOf(false) }
    val shellState by shell.state.collectAsStateWithLifecycle()

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
    }

    val sink = remember {
        object : KeySink {
            override fun key(keyCode: Int) {
                view?.handleKeyCode(keyCode, modifiers.keyMod())
            }

            override fun char(codePoint: Int) {
                view?.inputCodePoint(codePoint, false, false)
            }
        }
    }

    LaunchedEffect(scheme) {
        TermLook.apply(scheme, shells.map { it.term })
        view?.invalidate()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(scheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(
                            TermClient(
                                modifiers,
                                onTap = ::showKeyboard,
                                onFontStep = { step ->
                                    app.store.update { st ->
                                        st.copy(settings = st.settings.copy(fontSize = (st.settings.fontSize + step).coerceIn(MIN_FONT, MAX_FONT)))
                                    }
                                },
                            ),
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                        // The renderer comes from setTextSize; setTypeface needs it.
                        val px = fontPx(settings.fontSize, resources.displayMetrics)
                        setTextSize(px)
                        tag = px
                        view = this
                        app.sessions.view = this
                    }
                },
                update = { v ->
                    val px = fontPx(settings.fontSize, v.resources.displayMetrics)
                    // setTextSize makes a new renderer and lays out again: only on a real change.
                    if (v.tag != px) {
                        v.setTextSize(px)
                        v.tag = px
                    }
                    v.keepScreenOn = settings.keepScreenOn
                    if (v.currentSession !== shell.term) {
                        v.attachSession(shell.term)
                        v.onScreenUpdated()
                    }
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            )
            (shellState as? Shell.State.Closed)?.let { closed ->
                ClosedBar(
                    message = closed.message ?: "Session ended",
                    onReconnect = shell::reconnect,
                    onClose = { app.sessions.close(shell) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        ExtraKeys(
            scheme = scheme,
            modifiers = modifiers,
            sink = sink,
            chipLabel = shell.server.name,
            chipDot = dotColor(shellState, scheme),
            onChip = { sessionSheet = true },
            onSnippets = { snippetSheet = true },
        )
    }

    // The font is read off the main thread, for a new view and after a font change in Settings. Until then,
    // the view has the built-in monospace font.
    LaunchedEffect(view, settings.font) {
        val v = view ?: return@LaunchedEffect
        v.setTypeface(withContext(Dispatchers.IO) { TermLook.typeface(context, settings) })
    }
    LaunchedEffect(shell.id) { showKeyboard() }
    DisposableEffect(Unit) {
        onDispose {
            if (app.sessions.view === view) app.sessions.view = null
        }
    }

    if (sessionSheet) {
        SessionSheet(
            shells = shells,
            current = shell,
            servers = state.servers,
            scheme = scheme,
            onDismiss = { sessionSheet = false },
            onSwitch = { app.sessions.currentId.value = it.id; sessionSheet = false },
            onCloseTab = { app.sessions.close(it) },
            onOpen = { app.sessions.open(it); sessionSheet = false },
            onFiles = { sessionSheet = false; nav.go(Route.Files(shell.server.id)) },
            onHome = { sessionSheet = false; nav.back() },
        )
    }
    if (snippetSheet) {
        SnippetSheet(
            onDismiss = { snippetSheet = false },
            onSend = { text ->
                snippetSheet = false
                shell.term.write(text.replace("\r\n", "\r").replace('\n', '\r'))
            },
            onManage = { snippetSheet = false; nav.go(Route.Snippets) },
        )
    }
}

private fun fontPx(sp: Int, metrics: android.util.DisplayMetrics) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), metrics).toInt()

private fun dotColor(state: Shell.State, scheme: Scheme) = when (state) {
    Shell.State.Connecting -> Color(scheme.ansi[3])
    Shell.State.Connected -> Color(scheme.ansi[2])
    is Shell.State.Closed -> Color(scheme.ansi[8])
}

@Composable
private fun ClosedBar(message: String, onReconnect: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClose) { Text("Close") }
            FilledTonalButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun SessionSheet(
    shells: List<Shell>,
    current: Shell,
    servers: List<Server>,
    scheme: Scheme,
    onDismiss: () -> Unit,
    onSwitch: (Shell) -> Unit,
    onCloseTab: (Shell) -> Unit,
    onOpen: (Server) -> Unit,
    onFiles: () -> Unit,
    onHome: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onHome) { Icon(Icons.Back, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Home") }
            FilledTonalButton(onClick = onFiles) { Icon(Icons.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Files") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            item { SectionHeader("Sessions") }
            items(shells, key = { it.id }) { shell ->
                val st by shell.state.collectAsStateWithLifecycle()
                val title by shell.title.collectAsStateWithLifecycle()
                // The state as text too, not only as the dot colour (as in the home screen).
                val label = when (val s = st) {
                    Shell.State.Connecting -> "Connecting…"
                    Shell.State.Connected -> title?.takeUnless { it.isBlank() } ?: "Connected"
                    is Shell.State.Closed -> s.message ?: "Session ended"
                }
                ListRow(
                    title = shell.server.name + if (shell === current) "  ·  shown" else "",
                    // The server sets the title: it comes after the real user@host and never replaces it.
                    subtitle = "${shell.server.user}@${shell.server.host}  ·  $label",
                    onClick = { onSwitch(shell) },
                ) {
                    Box(Modifier.size(10.dp).background(dotColor(st, scheme), CircleShape))
                    IconButton(onClick = { onCloseTab(shell) }) { Icon(Icons.Close, contentDescription = "Close") }
                }
            }
            item { SectionHeader("New session") }
            items(servers.sortedByDescending { it.lastUsed }, key = { it.id }) { server ->
                ListRow(
                    title = server.name,
                    subtitle = "${server.user}@${server.host}",
                    icon = Icons.Add,
                    onClick = { onOpen(server) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SnippetSheet(onDismiss: () -> Unit, onSend: (String) -> Unit, onManage: () -> Unit) {
    val snippets = LocalContext.current.app.store.state.collectAsStateWithLifecycle().value.snippets
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item { SectionHeader("Snippets") }
            if (snippets.isEmpty()) {
                item {
                    Text(
                        "No snippets yet. A snippet is a saved command that you send with one tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(snippets, key = { it.id }) { snippet ->
                ListRow(
                    title = snippet.name,
                    subtitle = snippet.text,
                    monoSubtitle = true,
                    icon = Icons.Bolt,
                    onClick = { onSend(if (snippet.run) snippet.text + "\r" else snippet.text) },
                )
            }
            item {
                TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Manage snippets") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
