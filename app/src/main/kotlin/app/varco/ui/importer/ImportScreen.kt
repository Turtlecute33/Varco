package app.varco.ui.importer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.varco.app
import app.varco.importer.Importer
import app.varco.ui.components.Bar
import app.varco.ui.nav.Navigator
import kotlinx.coroutines.launch

class ImportModel : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<Importer.Result?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun import(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { context.app.importer.import(uris) }
                .onSuccess { result = it }
                .onFailure { error = it.message ?: "Import failed" }
            busy = false
        }
    }
}

/**
 * Import from Termux through the system file picker: Termux shares its home folder with the picker, so no
 * file goes through shared storage, and Termux needs no change.
 */
@Composable
fun ImportScreen(nav: Navigator) {
    val context = LocalContext.current
    val model: ImportModel = viewModel()
    val pick = rememberLauncherForActivityResult(PickFiles()) { uris -> model.import(context.applicationContext, uris) }

    Scaffold(topBar = { Bar("Import from Termux", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Varco reads the files directly from Termux. Keys move into the phone's secure chip when it can hold them.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Step(1, "Tap \"Choose files\".")
            Step(2, "In the picker, open the menu (⋮) and turn on \"Show hidden files\". Android hides ~/.ssh without it.")
            Step(3, "Open \"Termux\" in the side panel, then the folder .ssh.")
            Step(4, "Long-press to select more files: your private keys (for example id_ed25519), config and known_hosts.")
            Text(
                "Also possible: ~/.termux/colors.properties (colour scheme) and ~/.termux/font.ttf (font).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Choose files") }
            if (model.busy) LoadingIndicator()
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            model.result?.let { Summary(it) }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Text("$n.  $text", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Summary(r: Importer.Result) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Imported", style = MaterialTheme.typography.titleMedium)
            val lines = buildList {
                if (r.keys > 0) add("${r.keys} key(s), ${r.keysInChip} in the secure chip")
                if (r.servers > 0) add("${r.servers} server(s)")
                if (r.knownHosts > 0) add("${r.knownHosts} known host key(s)")
                if (r.scheme) add("Colour scheme (now in use)")
                if (r.font) add("Font (now in use)")
                if (isEmpty()) add("Nothing new")
            }
            lines.forEach { Text("• $it") }
            if (r.skipped.isNotEmpty()) {
                Text("Not imported", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                r.skipped.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** ACTION_OPEN_DOCUMENT for many files, opened at Termux's ~/.ssh when Termux is there. */
private class PickFiles : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(TERMUX_DOCUMENTS, TERMUX_SSH))

    private companion object {
        const val TERMUX_DOCUMENTS = "com.termux.documents"
        // A document ID of Termux's provider (its absolute path), not a file that Varco opens.
        @SuppressLint("SdCardPath")
        const val TERMUX_SSH = "/data/data/com.termux/files/home/.ssh"
    }
}
