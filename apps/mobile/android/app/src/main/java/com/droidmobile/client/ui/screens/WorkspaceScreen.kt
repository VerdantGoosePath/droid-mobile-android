package com.droidmobile.client.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidmobile.client.backend.DroidState
import com.droidmobile.client.backend.DroidViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Files, Git and terminal panels share one session, just as the React workspace did. */
@Composable
fun WorkspaceScreen(vm: DroidViewModel, state: DroidState) {
    var tab by remember { mutableStateOf("Files") }
    var chosen by remember { mutableStateOf(state.activeSession ?: "") }
    var showHidden by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var diffBase by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("") }
    var terminalId by remember { mutableStateOf("") }
    var terminalInput by remember { mutableStateOf("") }
    val files = state.sections["workspace.files"] as? JSONArray
    val terminalLines = state.sections["event.terminal.event"]
    LaunchedEffect(chosen, tab, showHidden) {
        if (chosen.isBlank()) return@LaunchedEffect
        when (tab) {
            "Files" -> vm.fetch("workspace.files", "workspace.files", JSONObject()
                .put("id", chosen).put("showHidden", showHidden))
            "Changes" -> vm.fetch("workspace.diff", "workspace.diff", JSONObject().put("id", chosen))
            "Terminal" -> vm.action("terminal.connect")
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Workspace", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(vertical = 16.dp))
        if (state.sessions.isEmpty()) {
            Text("Create or open a Droid session to browse its workspace.")
            return@Column
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.sessions.find { it.optString("id") == chosen }?.optString("title") ?: "Choose session")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                state.sessions.forEach { session ->
                    DropdownMenuItem(
                        text = { Text(session.optString("title", "Untitled")) },
                        onClick = { chosen = session.optString("id"); menu = false }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Files", "Changes", "Terminal").forEach {
                FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(it) })
            }
        }
        when (tab) {
            "Files" -> {
                OutlinedTextField(value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Find file") },
                    trailingIcon = { IconButton(onClick = {
                        vm.fetch("workspace.search", "workspace.search", JSONObject()
                            .put("id", chosen).put("query", search))
                    }) { Icon(Icons.Filled.Refresh, "Search") } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show hidden", modifier = Modifier.weight(1f))
                    Switch(checked = showHidden, onCheckedChange = { showHidden = it })
                }
                if (file.isNotBlank()) {
                    TextButton(onClick = { file = "" }) { Text("Back to files") }
                    Text(file, style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.weight(1f)) { StructuredDataPanel(state.sections["workspace.content"]) }
                } else {
                    val paths = files ?: (state.sections["workspace.search"] as? JSONArray) ?: JSONArray()
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items((0 until paths.length()).map { paths.optString(it) }
                            .filter { search.isBlank() || it.contains(search, true) }) { path ->
                            ElevatedCard(onClick = {
                                file = path
                                vm.fetch("workspace.content", "workspace.content", JSONObject()
                                    .put("id", chosen).put("path", path))
                            }) {
                                Text(path, modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                OutlinedTextField(value = cwd, onValueChange = { cwd = it },
                    label = { Text("Change working directory") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = cwd.isNotBlank(), onClick = {
                    vm.action("workspace.changeDirectory", JSONObject().put("id", chosen).put("path", cwd))
                }) { Text("Change directory") }
            }
            "Changes" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = diffBase, onValueChange = { diffBase = it },
                        label = { Text("Diff base branch") }, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.fetch("workspace.diff", "workspace.diff", JSONObject()
                        .put("id", chosen).put("baseBranch", diffBase)) }) { Text("Diff") }
                }
                Box(Modifier.weight(1f)) { StructuredDataPanel(state.sections["workspace.diff"]) }
                OutlinedTextField(value = commitMessage, onValueChange = { commitMessage = it },
                    label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = commitMessage.isNotBlank(), onClick = {
                        vm.action("git.commit", JSONObject().put("id", chosen).put("message", commitMessage))
                    }) { Text("Commit") }
                    FilledTonalButton(onClick = {
                        vm.action("git.push", JSONObject().put("id", chosen))
                    }) { Text("Push") }
                    TextButton(onClick = {
                        vm.action("git.pr", JSONObject().put("params", JSONObject().put("sessionId", chosen)))
                    }) { Text("Create PR") }
                }
            }
            else -> {
                LaunchedEffect(chosen) { vm.fetch("terminal.list", "terminal.list", JSONObject().put("id", chosen)) }
                Text("Terminal", style = MaterialTheme.typography.titleLarge)
                Button(onClick = {
                    vm.action("terminal.create", JSONObject().put("id", chosen).put("params",
                        JSONObject().put("cols", 80).put("rows", 24)))
                }) { Text("New shell") }
                OutlinedTextField(value = terminalId, onValueChange = { terminalId = it },
                    label = { Text("Terminal ID") }, modifier = Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {
                    SelectionContainer {
                        Text(terminalLines?.toString() ?: "Shell output will appear here.",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                }
                Row {
                    OutlinedTextField(value = terminalInput, onValueChange = { terminalInput = it },
                        label = { Text("Input") }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        vm.action("terminal.write", JSONObject().put("id", chosen)
                            .put("terminalId", terminalId).put("data", terminalInput + "\n"))
                        terminalInput = ""
                    }) { Text("Send") }
                }
            }
        }
    }
}
