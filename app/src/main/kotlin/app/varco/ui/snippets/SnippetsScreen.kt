package app.varco.ui.snippets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.varco.app
import app.varco.data.Snippet
import app.varco.ui.components.Bar
import app.varco.ui.components.EmptyState
import app.varco.ui.components.Icons
import app.varco.ui.components.ListRow
import app.varco.ui.components.SwitchRow
import app.varco.ui.nav.Navigator
import java.util.UUID

/** Saved commands. The ⚡ key of the terminal sends them. */
@Composable
fun SnippetsScreen(nav: Navigator) {
    val app = LocalContext.current.app
    val snippets = app.store.state.collectAsStateWithLifecycle().value.snippets
    var editing by remember { mutableStateOf<Snippet?>(null) }
    val new = { editing = Snippet(UUID.randomUUID().toString(), "", "") }

    Scaffold(
        topBar = { Bar("Snippets", onBack = nav::back) },
        floatingActionButton = {
            if (snippets.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = new, icon = { Icon(Icons.Add, null) }, text = { Text("Snippet") })
            }
        },
    ) { padding ->
        if (snippets.isEmpty()) {
            EmptyState(
                icon = Icons.Bolt,
                title = "No snippets yet",
                message = "A snippet is a saved command, for example \"sudo apt update && sudo apt upgrade\". " +
                    "Send it with the ⚡ key in the terminal.",
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { Button(onClick = new) { Text("Add snippet") } }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(snippets, key = { it.id }) { snippet ->
                    ListRow(
                        title = snippet.name,
                        subtitle = snippet.text,
                        monoSubtitle = true,
                        icon = Icons.Bolt,
                        onClick = { editing = snippet },
                    ) {
                        IconButton(onClick = {
                            app.store.update { st -> st.copy(snippets = st.snippets.filterNot { it.id == snippet.id }) }
                        }) { Icon(Icons.Delete, contentDescription = "Delete") }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    editing?.let { snippet ->
        var name by remember(snippet.id) { mutableStateOf(snippet.name) }
        var text by remember(snippet.id) { mutableStateOf(snippet.text) }
        var run by remember(snippet.id) { mutableStateOf(snippet.run) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (snippet.text.isEmpty()) "New snippet" else "Edit snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Command") },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow("Run", run, { run = it }, "Send Enter after the command", horizontalPadding = 0.dp)
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val saved = snippet.copy(name = name.trim().ifEmpty { text.lineSequence().first().take(40) }, text = text, run = run)
                    app.store.update { st ->
                        st.copy(
                            snippets = if (st.snippets.any { it.id == saved.id }) {
                                st.snippets.map { if (it.id == saved.id) saved else it }
                            } else {
                                st.snippets + saved
                            },
                        )
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
