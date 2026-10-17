package app.varco.ui.keys

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.SshKey
import app.varco.keys.SshWire
import app.varco.ui.components.Bar
import app.varco.ui.components.ConfirmDialog
import app.varco.ui.components.Icons
import app.varco.ui.components.copy
import app.varco.ui.nav.Navigator

/** One key: public key to copy or share (for authorized_keys), name, delete. */
@Composable
fun KeyScreen(id: String, nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val state by app.store.state.collectAsStateWithLifecycle()
    val key = state.keys.firstOrNull { it.id == id }
    if (key == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    var name by remember(key.id) { mutableStateOf(key.name) }
    var deleting by remember { mutableStateOf(false) }
    val line = SshWire.authorizedKey(key.publicBlob, key.name.replace(' ', '-'))
    val users = state.servers.filter { it.keyId == key.id }

    Scaffold(
        topBar = {
            Bar(key.name, onBack = nav::back) {
                IconButton(onClick = { deleting = true }) { Icon(Icons.Delete, contentDescription = "Delete") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(name) {
                if (name.isNotBlank() && name != key.name) {
                    app.store.update { st -> st.copy(keys = st.keys.map { if (it.id == key.id) it.copy(name = name.trim()) else it }) }
                }
            }
            Info("Where", where(key))
            Info("Fingerprint", SshWire.fingerprint(key.publicBlob), mono = true)
            Text(explain(key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Public key", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                SelectionContainer {
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                }
            }
            Text(
                "Add this line to ~/.ssh/authorized_keys on each server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { copy(context, line, sensitive = false) }) { Text("Copy") }
                FilledTonalButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, line)
                    context.startActivity(Intent.createChooser(send, "Public key"))
                }) { Text("Share") }
            }
            if (users.isNotEmpty()) Info("Used by", users.joinToString { it.name })
        }
    }

    if (deleting) {
        ConfirmDialog(
            title = "Delete ${key.name}?",
            text = when (key.kind) {
                SshKey.Kind.YUBIKEY_PIV -> "Varco forgets this YubiKey key. The key on the YubiKey stays."
                else -> "The private key is deleted from this phone for good. Servers that accept only this key lock you out."
            } + if (users.isNotEmpty()) "\n\nUsed by: ${users.joinToString { it.name }}." else "",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                key.alias?.let { alias -> runCatching { app.chip.delete(alias) } }
                app.store.update { st ->
                    st.copy(
                        keys = st.keys.filterNot { it.id == key.id },
                        servers = st.servers.map { if (it.keyId == key.id) it.copy(keyId = null) else it },
                    )
                }
                deleting = false
                nav.back()
            },
            onDismiss = { deleting = false },
        )
    }
}

private fun explain(key: SshKey) = when (key.kind) {
    SshKey.Kind.KEYSTORE -> "The private key is inside the phone's secure hardware. No app, also not Varco, can read or copy it."
    SshKey.Kind.YUBIKEY_PIV -> "The private key is on the YubiKey. Each sign-in needs the YubiKey and its PIN."
    SshKey.Kind.FILE -> "This phone's secure chip cannot hold this key type, so Varco keeps it encrypted with a key from the chip."
}

@Composable
private fun Info(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
