package app.varco.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.Server
import app.varco.data.SshKey
import app.varco.ui.components.Bar
import app.varco.ui.components.SwitchRow
import app.varco.ui.nav.Navigator
import java.util.UUID

/** Add or change a server. [id] null = new. */
@Composable
fun EditServerScreen(id: String?, nav: Navigator) {
    val app = LocalContext.current.app
    val state by app.store.state.collectAsStateWithLifecycle()
    // The saved server when the screen opened; the fields below start from it.
    val old = remember(id) { state.servers.firstOrNull { it.id == id } }
    var name by rememberSaveable { mutableStateOf(old?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(old?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((old?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(old?.user.orEmpty()) }
    var keyId by rememberSaveable { mutableStateOf(old?.keyId) }
    var savePassword by rememberSaveable { mutableStateOf(old?.password != null) }
    // remember, not rememberSaveable: the saved instance state is kept outside the app.
    var password by remember { mutableStateOf(old?.password.orEmpty()) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && user.isNotBlank() && portNumber != null && host.none { it.isWhitespace() }

    fun save() {
        val server = Server(
            id = old?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { host.trim() },
            host = host.trim(),
            port = portNumber ?: 22,
            user = user.trim(),
            keyId = keyId,
            password = if (savePassword && password.isNotEmpty()) password else null,
            lastUsed = old?.lastUsed ?: 0,
        )
        app.store.update { st ->
            st.copy(servers = if (old == null) st.servers + server else st.servers.map { if (it.id == server.id) server else it })
        }
        nav.back()
    }

    Scaffold(
        topBar = {
            Bar(if (old == null) "New server" else "Edit server", onBack = nav::back) {
                TextButton(onClick = ::save, enabled = valid) { Text("Save") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field("Host", host, { host = it }, KeyboardType.Uri, Modifier.fillMaxWidth(), placeholder = "example.com or 192.168.1.10")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("User", user, { user = it }, KeyboardType.Ascii, Modifier.weight(2f), placeholder = "root")
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, KeyboardType.Number, Modifier.weight(1f))
            }
            Field("Name", name, { name = it }, KeyboardType.Text, Modifier.fillMaxWidth(), placeholder = host.ifBlank { "Shown in the list" }, capitalize = true)
            KeyPicker(state.keys, keyId) { keyId = it }
            Text(
                if (keyId == null) "Without a key, Varco signs in with a password or a code (keyboard-interactive)."
                else "Varco tries the key first, then a password if the server asks for one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                title = "Save password",
                subtitle = "Stored encrypted on this phone. Off: Varco asks on each connection.",
                checked = savePassword,
                onChange = { savePassword = it },
                horizontalPadding = 0.dp,
            )
            if (savePassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    capitalize: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = type,
            autoCorrectEnabled = false,
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        modifier = modifier,
    )
}

@Composable
private fun KeyPicker(keys: List<SshKey>, keyId: String?, onPick: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = keys.firstOrNull { it.id == keyId }?.name ?: "No key (password)"
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Key") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.exposedDropdownSize()) {
            DropdownMenuItem(text = { Text("No key (password)") }, onClick = { onPick(null); open = false })
            keys.forEach { key ->
                DropdownMenuItem(text = { Text(key.name) }, onClick = { onPick(key.id); open = false })
            }
        }
    }
}
