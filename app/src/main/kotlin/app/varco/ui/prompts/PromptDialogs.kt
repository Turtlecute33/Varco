package app.varco.ui.prompts

import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.ssh.Ask
import app.varco.ui.components.Icons
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import java.io.IOException

/** Shows the oldest open question of any connection (or of an import) as a dialog. */
@Composable
fun PromptDialogs() {
    val open by LocalContext.current.app.prompts.open.collectAsStateWithLifecycle()
    when (val ask = open.firstOrNull()) {
        null -> Unit
        is Ask.HostKey -> HostKeyDialog(ask)
        is Ask.Password -> PasswordDialog(ask)
        is Ask.Interactive -> InteractiveDialog(ask)
        is Ask.Pin -> SecretDialog(
            ask = ask,
            title = "YubiKey PIN",
            text = "${ask.keyName} for ${ask.server}" +
                (ask.triesLeft?.let { "\nWrong PIN. $it ${if (it == 1) "try" else "tries"} left before the PIN locks." } ?: ""),
            label = "PIN",
            confirm = "Next",
            onAnswer = { ask.answer(it) },
        )
        is Ask.Passphrase -> SecretDialog(
            ask = ask,
            title = "Key passphrase",
            text = ask.keyName + if (ask.retry) "\nWrong passphrase. Try again." else "",
            label = "Passphrase",
            confirm = "Import",
            dismiss = "Skip",
            onAnswer = { ask.answer(it) },
        )
        is Ask.Touch -> TouchDialog(ask)
        is Ask.Unlock -> UnlockPrompt(ask)
    }
}

@Composable
private fun HostKeyDialog(ask: Ask.HostKey) {
    val where = if (ask.port == 22) ask.host else "${ask.host}:${ask.port}"
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(if (ask.changed) Icons.Warning else Icons.Server, null) },
        title = { Text(if (ask.changed) "Host key changed" else "New server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (ask.changed) {
                        "The key of $where is not the key that you trusted before. Someone may be between you and the " +
                            "server. Replace the key only if you know that the server got a new key."
                    } else {
                        "First connection to $where. Compare the fingerprint with the server's key " +
                            "(ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub)."
                    },
                    color = if (ask.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(ask.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ask.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (ask.changed) {
                Button(
                    onClick = { ask.answer(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Replace key") }
            } else {
                TextButton(onClick = { ask.answer(true) }) { Text("Trust") }
            }
        },
        dismissButton = { TextButton(onClick = { ask.answer(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(ask: Ask.Password) {
    var password by remember(ask) { mutableStateOf("") }
    var save by remember(ask) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Password") },
        text = {
            Column {
                Text(
                    "${ask.user}@${ask.server}" + if (ask.retry) "\nWrong password. Try again." else "",
                    color = if (ask.retry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SecretField(password, { password = it }, "Password", focus) { if (password.isNotEmpty()) ask.answer(Ask.Password.Reply(password, save)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = save, onCheckedChange = { save = it })
                    Text("Save password")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { ask.answer(Ask.Password.Reply(password, save)) }, enabled = password.isNotEmpty()) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = { ask.answer(null) }) { Text("Cancel") } },
    )
    LaunchedEffect(ask) { focus.requestFocus() }
}

@Composable
private fun InteractiveDialog(ask: Ask.Interactive) {
    val answers = remember(ask) { mutableStateListOf(*Array(ask.prompts.size) { "" }) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(ask.name.ifBlank { ask.server }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ask.instruction.isNotBlank()) Text(ask.instruction)
                ask.prompts.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = answers[i],
                        onValueChange = { answers[i] = it },
                        label = { Text(field.text.trim().removeSuffix(":")) },
                        singleLine = true,
                        visualTransformation = if (field.echo) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.echo) KeyboardType.Ascii else KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(focus) else Modifier),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { ask.answer(answers.toList()) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { ask.answer(null) }) { Text("Cancel") } },
    )
    LaunchedEffect(ask) { if (ask.prompts.isNotEmpty()) focus.requestFocus() }
}

/** A dialog for one secret (PIN or passphrase). The answer is a CharArray that the caller wipes. */
@Composable
private fun SecretDialog(
    ask: Ask<*>,
    title: String,
    text: String,
    label: String,
    confirm: String,
    onAnswer: (CharArray?) -> Unit,
    dismiss: String = "Cancel",
) {
    var value by remember(ask) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val send = { if (value.isNotEmpty()) onAnswer(value.toCharArray()) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SecretField(value, { value = it }, label, focus, send)
            }
        },
        confirmButton = { TextButton(onClick = send, enabled = value.isNotEmpty()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(dismiss) } },
    )
    LaunchedEffect(ask) { focus.requestFocus() }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, focus: FocusRequester, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

@Composable
private fun TouchDialog(ask: Ask.Touch) {
    var nfcOff by remember(ask) { mutableStateOf(false) }
    var busy by remember(ask) { mutableStateOf(false) }
    var again by remember(ask) { mutableStateOf(false) }
    YubiKeyDiscovery(onDevice = { device ->
        busy = true
        try {
            val result = device.openConnection(SmartCardConnection::class.java).use { connection ->
                runCatching { ask.use(connection) }
            }
            val error = result.exceptionOrNull()
            // A link that ends during the work (key moved away): wait for the next tap.
            if (error is IOException) again = true else ask.answer(result)
        } catch (_: IOException) {
            again = true
        } finally {
            busy = false
        }
    }, onNfcOff = { nfcOff = true })
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Nfc, null) },
        title = { Text(if (busy) "Keep holding" else "Hold your YubiKey") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        busy -> "Signing with ${ask.keyName}…"
                        again -> "The YubiKey moved away too early. Hold it to the phone again and keep it still."
                        else -> "Hold the YubiKey to the back of the phone until this closes, or plug it in."
                    } + "\n${ask.keyName} · ${ask.server}" + if (nfcOff) "\nNFC is off: turn it on, or use USB." else "",
                )
                Spacer(Modifier.height(16.dp))
                LoadingIndicator(Modifier.size(48.dp).padding(4.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { ask.answer(null) }, enabled = !busy) { Text("Cancel") } },
    )
}

/** The system prompt for a key that asks for fingerprint or screen lock on each use. */
@Composable
private fun UnlockPrompt(ask: Ask.Unlock) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(ask) {
        val signal = CancellationSignal()
        BiometricPrompt.Builder(activity)
            .setTitle("Use ${ask.keyName}")
            .setSubtitle(ask.server)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(ask.signature),
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = ask.answer(true)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = ask.answer(false)
                },
            )
        onDispose { signal.cancel() }
    }
}
