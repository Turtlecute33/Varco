package app.varco.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.SshKey
import app.varco.keys.Piv
import app.varco.keys.SshWire
import app.varco.ui.components.Bar
import app.varco.ui.components.EmptyState
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.components.SwitchRow
import app.varco.ui.nav.Navigator
import app.varco.ui.nav.Route
import app.varco.ui.prompts.YubiKeyDiscovery
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

/** All keys: in the secure chip, on a YubiKey, or imported files that the chip cannot hold. */
@Composable
fun KeysScreen(nav: Navigator) {
    val app = LocalContext.current.app
    val keys = app.store.state.collectAsStateWithLifecycle().value.keys
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var yubikey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { Bar("Keys", onBack = nav::back) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menu = true },
                    icon = { Icon(Icons.Add, null) },
                    text = { Text("Key") },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("New key in secure chip") },
                        leadingIcon = { Icon(Icons.Chip, null) },
                        onClick = { menu = false; create = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Add YubiKey") },
                        leadingIcon = { Icon(Icons.Usb, null) },
                        onClick = { menu = false; yubikey = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Import key files") },
                        leadingIcon = { Icon(Icons.Import, null) },
                        onClick = { menu = false; nav.go(Route.Import) },
                    )
                }
            }
        },
    ) { padding ->
        if (keys.isEmpty()) {
            EmptyState(
                icon = Icons.Key,
                title = "No keys yet",
                message = "Make a key in the phone's secure chip (it can never be copied out), add a YubiKey, " +
                    "or import your Termux keys.",
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Button(onClick = { create = true }) { Text("New key in secure chip") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { yubikey = true }) { Text("Add YubiKey") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(keys, key = { it.id }) { key ->
                    ListRow(
                        title = key.name,
                        subtitle = "${where(key)} · ${SshWire.fingerprint(key.publicBlob).take(20)}…",
                        icon = icon(key),
                        onClick = { nav.go(Route.Key(key.id)) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (create) CreateKeyDialog(onDone = { id -> create = false; id?.let { nav.go(Route.Key(it)) } })
    if (yubikey) AddYubiKeyDialog(onDone = { id -> yubikey = false; id?.let { nav.go(Route.Key(it)) } })
}

fun icon(key: SshKey) = when (key.kind) {
    SshKey.Kind.KEYSTORE -> Icons.Chip
    SshKey.Kind.YUBIKEY_PIV -> Icons.Usb
    SshKey.Kind.FILE -> Icons.File
}

/** Where the private key is, in words. */
fun where(key: SshKey) = when (key.kind) {
    SshKey.Kind.KEYSTORE -> (if (key.strongBox) "StrongBox" else "Secure chip (TEE)") + if (key.authPerUse) " · fingerprint" else ""
    SshKey.Kind.YUBIKEY_PIV -> "YubiKey PIV slot %02x".format(key.pivSlot ?: 0)
    SshKey.Kind.FILE -> "Encrypted on phone"
} + " · " + typeName(key.type)

fun typeName(type: String) = when (type) {
    SshWire.ED25519 -> "Ed25519"
    SshWire.ECDSA_P256 -> "ECDSA P-256"
    SshWire.RSA -> "RSA"
    else -> type
}

@Composable
private fun CreateKeyDialog(onDone: (String?) -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Phone") }
    var ed25519 by remember { mutableStateOf(app.chip.supportsEd25519) }
    var authPerUse by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text("New key in secure chip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (app.chip.supportsEd25519) {
                    Choice("Ed25519 (secure chip, TEE)", ed25519) { ed25519 = true }
                }
                Choice(if (app.chip.hasStrongBox) "ECDSA P-256 (StrongBox)" else "ECDSA P-256 (secure chip, TEE)", !ed25519) { ed25519 = false }
                SwitchRow("Fingerprint for each use", authPerUse, { authPerUse = it }, "Ask on each sign-in", horizontalPadding = 0.dp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { app.chip.generate(name.trim(), ed25519, authPerUse) } }
                            .onSuccess { key -> app.store.update { it.copy(keys = it.keys + key) }; onDone(key.id) }
                            .onFailure { error = it.message ?: "The key could not be made"; busy = false }
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    ListRow(title = label, onClick = onClick, horizontalPadding = 0.dp) {
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** Reads the PIV slots of a YubiKey (no PIN needed) and adds the key that the user picks. */
@Composable
private fun AddYubiKeyDialog(onDone: (String?) -> Unit) {
    val app = LocalContext.current.app
    var slots by remember { mutableStateOf<List<Piv.SlotKey>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (slots == null) {
        YubiKeyDiscovery(onDevice = { device ->
            runCatching { device.openConnection(SmartCardConnection::class.java).use(Piv::readKeys) }
                .onSuccess { slots = it }
                .onFailure { error = it.message ?: "The YubiKey could not be read" }
        }, onNfcOff = { error = "NFC is off: turn it on, or plug the YubiKey in." })
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        icon = { Icon(Icons.Nfc, null) },
        title = { Text(if (slots == null) "Hold your YubiKey" else "Pick the key") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val found = slots
                when {
                    found == null -> {
                        Text("Hold the YubiKey to the back of the phone, or plug it in. Varco reads the public keys of the PIV slots; no PIN is needed.")
                        Spacer(Modifier.height(16.dp))
                        LoadingIndicator()
                    }
                    found.isEmpty() -> Text("This YubiKey has no PIV key that SSH can use (RSA, ECDSA P-256 or Ed25519).")
                    else -> found.forEach { slot ->
                        val blob = slot.blob
                        ListRow(
                            title = "Slot %02x · %s".format(slot.slot.value, typeName(SshWire.typeOf(slot.publicKey))),
                            subtitle = SshWire.fingerprint(blob),
                            monoSubtitle = true,
                            horizontalPadding = 0.dp,
                            onClick = {
                                val key = SshKey(
                                    id = UUID.randomUUID().toString(),
                                    name = "YubiKey %02x".format(slot.slot.value),
                                    kind = SshKey.Kind.YUBIKEY_PIV,
                                    type = SshWire.typeOf(slot.publicKey),
                                    publicKey = Base64.getEncoder().encodeToString(blob),
                                    pivSlot = slot.slot.value,
                                )
                                val existing = app.store.state.value.keys.firstOrNull { it.publicKey == key.publicKey }
                                if (existing == null) app.store.update { it.copy(keys = it.keys + key) }
                                onDone(existing?.id ?: key.id)
                            },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Default) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
