package app.varco.ui.files

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.varco.app
import app.varco.ssh.Sftp
import app.varco.ssh.SshConnection
import app.varco.ssh.SshEnv
import app.varco.ui.components.Bar
import app.varco.ui.components.ConfirmDialog
import app.varco.ui.components.EmptyState
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.nav.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SFTP state of one screen. It uses the login of an open terminal tab of the server when there is one,
 * else its own connection (closed when the screen closes). One SFTP call at a time. When the channel or
 * the connection drops, the next call opens them again.
 */
class FilesModel(private val context: Application, private val serverId: String) : ViewModel() {
    private val sftpThread = Dispatchers.IO.limitedParallelism(1)
    private var own: SshConnection? = null
    private var sftp: Sftp? = null
    private var start: String? = null

    var path by mutableStateOf<String?>(null)
        private set
    var entries by mutableStateOf<List<Sftp.Entry>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Bytes done and total of the running transfer, or null. */
    var transfer by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    /** An upload and its file name, held back because the name is already in the folder; the screen asks. */
    var conflict by mutableStateOf<Pair<Uri, String>?>(null)
        private set

    val atStart: Boolean get() = path == start

    init {
        work { list(sftp().home) }
    }

    fun open(dir: String) = work { list(dir) }

    fun up() = path?.let { open(Sftp.parent(it)) }

    /** A tap on a link that is not shown as a folder ([Sftp.list] checks only some): open its folder, else [onFile]. */
    fun openLink(entry: Sftp.Entry, onFile: () -> Unit) = work {
        if (sftp().isDirLink(entry.path)) list(entry.path) else withContext(Dispatchers.Main) { onFile() }
    }

    /** Lists the open folder again; after a failed first listing, the home folder. */
    fun refresh() {
        val dir = path
        work { list(dir ?: sftp().home) }
    }

    fun mkdir(name: String) = work {
        val dir = requireNotNull(path)
        sftp().mkdir(Sftp.join(dir, name))
        list(dir)
    }

    fun rename(entry: Sftp.Entry, name: String) = work {
        val dir = requireNotNull(path)
        sftp().rename(entry.path, Sftp.join(dir, name))
        list(dir)
    }

    fun delete(entry: Sftp.Entry) = work {
        sftp().delete(entry)
        list(requireNotNull(path))
    }

    fun download(entry: Sftp.Entry, target: Uri) = work {
        transfer = 0L to entry.size
        try {
            val resolver = context.contentResolver
            // "w" may keep old bytes past the new end; some providers reject "wt".
            val truncating = runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            val out = truncating ?: resolver.openOutputStream(target) ?: return@work
            var wrote = 0L
            val done = runCatching {
                out.use { sftp().download(entry.path, it) { d -> wrote = d; transfer = d to entry.size } }
            }
            // A cut-off file must not look complete. A "w" file with no new bytes still has its old content: keep it.
            if (done.isFailure && (truncating != null || wrote > 0)) runCatching { DocumentsContract.deleteDocument(resolver, target) }
            done.getOrThrow()
        } finally {
            transfer = null
        }
    }

    /** Uploads [source] into the open folder. A file with the same name is replaced only with [replace]. */
    fun upload(source: Uri, replace: Boolean = false) = work {
        val dir = requireNotNull(path)
        val resolver = context.contentResolver
        val (shown, size) = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        } ?: ("upload" to 0L)
        // The provider supplies the name: keep only its last part, and never "." or "..".
        val name = shown.orEmpty().substringAfterLast('/').takeUnless { it.isBlank() || it == "." || it == ".." } ?: "upload"
        val target = Sftp.join(dir, name)
        if (!replace && sftp().exists(target)) {
            conflict = source to name
            return@work
        }
        transfer = 0L to size
        try {
            resolver.openInputStream(source)?.use { input ->
                sftp().upload(input, target) { done -> transfer = done to size }
            }
        } finally {
            transfer = null
        }
        list(dir)
    }

    /** The answer to [conflict]: replace the file on the server, or keep it. */
    fun answerConflict(replace: Boolean) {
        val (source, _) = conflict ?: return
        conflict = null
        if (replace) upload(source, replace = true)
    }

    /** The open SFTP channel; opens it (and a connection) when there is none or it dropped. Runs on [sftpThread]. */
    private fun sftp(): Sftp {
        sftp?.takeIf { it.isOpen }?.let { return it }
        runCatching { sftp?.close() }
        sftp = null
        own?.takeUnless { it.isConnected }?.let { it.close(); own = null }
        val app = context.app
        val shared = app.sessions.shells.value.firstNotNullOfOrNull { s -> s.openConnection.takeIf { s.server.id == serverId } }
        val connection = shared ?: own ?: run {
            val server = app.store.state.value.servers.first { it.id == serverId }
            SshConnection(SshEnv(app.store, app.chip, app.prompts), server).also {
                own = it
                it.connect(onBanner = {})
            }
        }
        return Sftp(connection).also {
            sftp = it
            if (start == null) start = it.home
        }
    }

    private fun list(dir: String) {
        val list = sftp().list(dir)
        entries = list
        path = dir
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching { withContext(sftpThread) { block() } }.onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    override fun onCleared() {
        val s = sftp
        val c = own
        // Closing talks to the network; not on the main thread.
        Thread { s?.close(); c?.close() }.start()
    }
}

@Composable
fun FilesScreen(serverId: String, nav: Navigator) {
    val context = LocalContext.current.applicationContext as Application
    val servers by context.app.store.state.collectAsStateWithLifecycle()
    val server = servers.servers.firstOrNull { it.id == serverId }
    val model: FilesModel = viewModel(key = serverId) { FilesModel(context, serverId) }
    var newFolder by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Sftp.Entry?>(null) }
    var deleting by remember { mutableStateOf<Sftp.Entry?>(null) }
    var downloading by remember { mutableStateOf<Sftp.Entry?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = downloading
        if (uri != null && entry != null) model.download(entry, uri)
        downloading = null
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::upload) }

    // Back goes up one folder; after an error it leaves the screen, so that it can never get stuck.
    BackHandler(enabled = model.path != null && !model.atStart && model.path != "/" && model.error == null) { model.up() }

    Scaffold(
        topBar = {
            // Cut a long path at the start, so that the name of the open folder stays visible.
            // A server name (no path yet) keeps the cut at the end, because its start is the useful part.
            val overflow = if (model.path != null) TextOverflow.StartEllipsis else TextOverflow.Ellipsis
            Bar(model.path ?: server?.name ?: "Files", onBack = nav::back, overflow = overflow) {
                IconButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = model.path != null) { Icon(Icons.Upload, contentDescription = "Upload") }
                IconButton(onClick = { newFolder = true }, enabled = model.path != null) { Icon(Icons.NewFolder, contentDescription = "New folder") }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.More, contentDescription = "More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("Go to folder") },
                            leadingIcon = { Icon(Icons.Folder, null) },
                            onClick = { more = false; goTo = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Refresh, null) },
                            onClick = { more = false; model.refresh() },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            model.transfer?.let { (done, total) ->
                if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            model.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            Box(Modifier.fillMaxSize()) {
                if (model.path != null && model.entries.isEmpty() && !model.loading) {
                    EmptyState(Icons.Folder, "Empty folder", Modifier.fillMaxSize())
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (model.path != null && !model.atStart && model.path != "/") {
                        item { ListRow(title = "..", icon = Icons.Folder, onClick = { model.up() }) }
                    }
                    items(model.entries, key = { it.path }) { entry ->
                        var menu by remember { mutableStateOf(false) }
                        ListRow(
                            title = entry.name + if (entry.isLink) " →" else "",
                            subtitle = buildString {
                                if (!entry.isDir) append(Formatter.formatShortFileSize(context, entry.size)).append(" · ")
                                append(DateUtils.getRelativeTimeSpanString(entry.modified))
                            },
                            icon = if (entry.isDir) Icons.Folder else Icons.File,
                            iconTint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                when {
                                    entry.isDir -> model.open(entry.path)
                                    entry.isLink -> model.openLink(entry) { menu = true }
                                    else -> menu = true
                                }
                            },
                        ) {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.More, contentDescription = "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (!entry.isDir) {
                                        DropdownMenuItem(
                                            text = { Text("Download") },
                                            leadingIcon = { Icon(Icons.Download, null) },
                                            onClick = { menu = false; downloading = entry; save.launch(entry.name) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Rename, null) },
                                        onClick = { menu = false; renaming = entry },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        leadingIcon = { Icon(Icons.Delete, null) },
                                        onClick = { menu = false; deleting = entry },
                                    )
                                }
                            }
                        }
                    }
                }
                if (model.loading && model.transfer == null) LoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    if (goTo) {
        NameDialog("Go to folder", model.path.orEmpty(), label = "Path", isPath = true, onDone = { path -> goTo = false; path?.let(model::open) })
    }
    if (newFolder) {
        NameDialog("New folder", "", onDone = { name -> newFolder = false; name?.let(model::mkdir) })
    }
    renaming?.let { entry ->
        NameDialog("Rename", entry.name, onDone = { name -> renaming = null; if (name != null && name != entry.name) model.rename(entry, name) })
    }
    model.conflict?.let { (_, name) ->
        ConfirmDialog(
            title = "Replace $name?",
            text = "A file with this name is in this folder. The upload replaces it on the server.",
            confirm = "Replace",
            destructive = true,
            onConfirm = { model.answerConflict(replace = true) },
            onDismiss = { model.answerConflict(replace = false) },
        )
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = "Delete ${entry.name}?",
            text = if (entry.isDir) "Only an empty folder can be deleted." else "The file is deleted on the server.",
            confirm = "Delete",
            destructive = true,
            onConfirm = { deleting = null; model.delete(entry) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDone: (String?) -> Unit,
    label: String = "Name",
    isPath: Boolean = false,
) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && (isPath || ('/' !in name && name != "." && name != ".."))
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
