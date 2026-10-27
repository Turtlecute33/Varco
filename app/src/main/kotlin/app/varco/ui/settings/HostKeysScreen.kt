package app.varco.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.KnownHost
import app.varco.keys.SshWire
import app.varco.ui.components.Bar
import app.varco.ui.components.ConfirmDialog
import app.varco.ui.components.EmptyState
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.components.SectionHeader
import app.varco.ui.nav.Navigator
import java.util.Base64

/**
 * The host keys that Varco trusts, each with its SHA-256 fingerprint and a delete, and a clear for the
 * imported known_hosts lines. After a delete, the next connection asks again (trust on first use).
 */
@Composable
fun HostKeysScreen(nav: Navigator) {
    val app = LocalContext.current.app
    val state by app.store.state.collectAsStateWithLifecycle()
    val imported = remember(state.importedKnownHosts) { state.importedKnownHosts.lines().count { it.isNotBlank() } }
    var deleting by remember { mutableStateOf<KnownHost?>(null) }
    var clearing by remember { mutableStateOf(false) }

    Scaffold(topBar = { Bar("Trusted host keys", onBack = nav::back) }) { padding ->
        if (state.knownHosts.isEmpty() && imported == 0) {
            EmptyState(
                icon = Icons.Key,
                title = "No trusted host keys",
                message = "When you trust the host key of a server at the first connection, Varco keeps it here.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (state.knownHosts.isNotEmpty()) item { SectionHeader("Trusted in Varco") }
            items(state.knownHosts, key = { "${it.host} ${it.port} ${it.type} ${it.key}" }) { k ->
                val fingerprint = remember(k.key) {
                    runCatching { SshWire.fingerprint(Base64.getDecoder().decode(k.key)) }.getOrDefault("?")
                }
                ListRow(
                    title = (if (k.port == 22) k.host else "${k.host}:${k.port}") + "  ·  " + k.type,
                    subtitle = fingerprint,
                    monoSubtitle = true,
                ) {
                    IconButton(onClick = { deleting = k }) { Icon(Icons.Delete, contentDescription = "Delete") }
                }
            }
            if (imported > 0) {
                item { SectionHeader("Imported from known_hosts") }
                item {
                    ListRow(
                        title = "Clear imported known_hosts",
                        subtitle = "$imported line(s)",
                        icon = Icons.Delete,
                        onClick = { clearing = true },
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    deleting?.let { k ->
        ConfirmDialog(
            title = "Delete the host key of ${k.host}?",
            text = "The next connection to this server asks you again to trust its host key. " +
                "Compare the fingerprint with the server before you trust it.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                app.store.update { st -> st.copy(knownHosts = st.knownHosts - k) }
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    if (clearing) {
        ConfirmDialog(
            title = "Clear imported known_hosts?",
            text = "Varco forgets the $imported imported line(s), also the @revoked lines. " +
                "Servers that are not trusted in Varco ask again on the next connection.",
            confirm = "Clear",
            destructive = true,
            onConfirm = {
                app.store.update { st -> st.copy(importedKnownHosts = "") }
                clearing = false
            },
            onDismiss = { clearing = false },
        )
    }
}
