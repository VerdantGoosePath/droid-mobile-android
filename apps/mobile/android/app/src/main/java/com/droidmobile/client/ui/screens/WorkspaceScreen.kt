package com.droidmobile.client.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.droidmobile.client.ui.components.*
import kotlinx.coroutines.launch
import com.droidmobile.client.backend.DroidState
import com.droidmobile.client.backend.DroidViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Files, Git and terminal panels share one session, just as the React workspace did. */
@Composable
fun WorkspaceScreen(vm: DroidViewModel, state: DroidState) {
    var tab by remember { mutableStateOf("Files") }
    var chosen by remember { mutableStateOf(state.activeSession ?: state.sessions.firstOrNull()?.optString("id").orEmpty()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.sessions.size) {
        if (chosen.isBlank() && state.sessions.isNotEmpty()) chosen = state.sessions.first().optString("id")
    }
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
        if (state.sessions.isEmpty()) {
            DroidEmptyState("Choose a workspace",
                "Start a Droid session to explore its files, changes and terminal.")
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
        PrimaryTabRow(selectedTabIndex = listOf("Files", "Changes", "Terminal").indexOf(tab)) {
            listOf("Files", "Changes", "Terminal").forEach { choice ->
                Tab(
                    selected = tab == choice,
                    onClick = { tab = choice },
                    text = { Text(choice) }
                )
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
                    val matching = (0 until paths.length())
                        .map { paths.optString(it) }
                        .filter { search.isBlank() || it.contains(search, true) }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (matching.isEmpty()) item {
                            DroidEmptyState("No matching files",
                                "Try another filename or refresh the workspace.",
                                modifier = Modifier.height(250.dp))
                        }
                        items(matching) { path ->
                            GroupedItems {
                                GroupedActionRow(
                                    title = path.substringAfterLast('/').substringAfterLast('\\'),
                                    supportingText = path,
                                    onClick = {
                                        file = path
                                        vm.fetch("workspace.content", "workspace.content", JSONObject()
                                            .put("id", chosen).put("path", path))
                                    }
                                )
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
                val gitAvailable = (state.sections["workspace.diff"] as? JSONObject)
                    ?.optBoolean("success", true) != false
                OutlinedTextField(value = commitMessage, onValueChange = { commitMessage = it },
                    label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = gitAvailable && commitMessage.isNotBlank(), onClick = {
                        vm.action("git.commit", JSONObject().put("id", chosen).put("message", commitMessage))
                    }) { Text("Commit") }
                    FilledTonalButton(enabled = gitAvailable, onClick = {
                        vm.action("git.push", JSONObject().put("id", chosen))
                    }) { Text("Push") }
                    TextButton(enabled = gitAvailable, onClick = {
                        vm.action("git.pr", JSONObject().put("params", JSONObject().put("sessionId", chosen)))
                    }) { Text("Create PR") }
                }
            }
            else -> {
                LaunchedEffect(chosen) {
                    try {
                        vm.repository.request("terminal.connect")
                        vm.fetch("terminal.list", "terminal.list", JSONObject().put("id", chosen))
                    } catch (e: Exception) { vm.repository.updateError(e.message) }
                }
                val terminals = state.sections["terminal.list"] as? JSONArray ?: JSONArray()
                var terminalMenu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        FilledTonalButton(onClick = { terminalMenu = true },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (terminalId.isBlank()) "Choose shell"
                                else "Shell ${terminalId.take(8)}"
                            )
                        }
                        DropdownMenu(expanded = terminalMenu, onDismissRequest = { terminalMenu = false }) {
                            (0 until terminals.length()).mapNotNull { terminals.optJSONObject(it) }
                                .forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text("Shell ${item.optString("id").take(8)}") },
                                        onClick = {
                                            terminalId = item.optString("id")
                                            terminalMenu = false
                                        }
                                    )
                                }
                        }
                    }
                    Button(onClick = {
                        val id = java.util.UUID.randomUUID().toString()
                        scope.launch {
                            try {
                                vm.repository.request("terminal.create", JSONObject()
                                    .put("id", chosen).put("params", JSONObject()
                                        .put("terminalId", id).put("cols", 80).put("rows", 24)))
                                terminalId = id
                                vm.fetch("terminal.list", "terminal.list", JSONObject().put("id", chosen))
                            } catch (e: Exception) { vm.repository.updateError(e.message) }
                        }
                    }) { Text("New shell") }
                }
                Text("Connection: ${state.terminalStatus}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large
                ) {
                    SelectionContainer {
                        Text(
                            state.terminalOutput[terminalId]
                                ?: (0 until terminals.length())
                                    .mapNotNull { terminals.optJSONObject(it) }
                                    .find { it.optString("id") == terminalId }
                                    ?.optJSONObject("state")?.optString("plainText")
                                ?: "Select or create a shell to see its output.",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().imePadding().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = terminalInput,
                        onValueChange = { terminalInput = it },
                        label = { Text("Send to shell") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    Button(
                        enabled = terminalId.isNotBlank() && terminalInput.isNotBlank(),
                        onClick = {
                            vm.action("terminal.write", JSONObject()
                                .put("id", chosen)
                                .put("terminalId", terminalId)
                                .put("data", terminalInput + "\n"))
                            terminalInput = ""
                        }
                    ) { Text("Send") }
                }

            }
        }
    }
}
