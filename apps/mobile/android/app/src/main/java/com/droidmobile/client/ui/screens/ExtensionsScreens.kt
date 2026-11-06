package com.droidmobile.client.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidmobile.client.backend.DroidState
import com.droidmobile.client.backend.DroidViewModel
import org.json.JSONArray
import org.json.JSONObject

private data class Extension(val id: String, val label: String, val summary: String, val read: String)
private val sections = listOf(
    Extension("mcp", "MCP servers", "Servers, tools, authentication and permissions", "mcp.list"),
    Extension("skills", "Skills", "Discover, enable and disable skills", "skills.list"),
    Extension("commands", "Commands", "Custom slash commands", "commands.list"),
    Extension("plugins", "Plugins", "Available and installed plugins", "plugins.installed"),
    Extension("marketplaces", "Marketplaces", "Manage plugin catalogues", "plugins.marketplaces"),
    Extension("custom-models", "Custom models", "Bring your own model provider", "models.custom.list"),
    Extension("automations", "Automations", "Scheduled runs, history and controls", "automations.list")
)

@Composable
fun ExtensionsIndexScreen(open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Extensions", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Manage skills, tools, models and scheduled work.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(sections) { section ->
            ElevatedCard(onClick = { open(section.id) }) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(section.label, style = MaterialTheme.typography.titleMedium)
                        Text(section.summary, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ArrowForward, null)
                }
            }
        }
    }
}

@Composable
fun ExtensionsDetailScreen(vm: DroidViewModel, state: DroidState, name: String, onBack: () -> Unit) {
    val section = sections.find { it.id == name } ?: return
    var query by remember(name) { mutableStateOf("") }
    var input by remember(name) { mutableStateOf("") }
    var selected by remember(name) { mutableStateOf<JSONObject?>(null) }
    val value = state.sections[name]
    val items = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> {
            val array = value.optJSONArray("items") ?: value.optJSONArray("skills")
                ?: value.optJSONArray("servers") ?: value.optJSONArray("plugins")
            if (array != null) (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            else listOf(value)
        }
        else -> emptyList()
    }
    LaunchedEffect(name) { vm.fetch(name, section.read) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(section.label, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.fetch(name, section.read) }) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        LazyColumn(Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items.filter { it.toString().contains(query, ignoreCase = true) }) { item ->
                ElevatedCard(onClick = { selected = item }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.optString("displayName",
                            item.optString("name", item.optString("id", "Item"))),
                            style = MaterialTheme.typography.titleMedium)
                        Text(item.optString("description", item.optString("status", "")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val enabled = item.optBoolean("enabled", true)
                            if (name in listOf("skills", "mcp", "plugins", "automations")) {
                                TextButton(onClick = {
                                    val id = item.optString("name", item.optString("id"))
                                    when (name) {
                                        "skills" -> vm.action("skills.toggle", JSONObject()
                                            .put("input", JSONObject().put("name", id)
                                                .put("disabled", enabled).put("level", "user")))
                                        "mcp" -> vm.action("mcp.toggle", JSONObject()
                                            .put("name", id).put("enabled", !enabled))
                                        "plugins" -> vm.action("plugins.toggle", JSONObject()
                                            .put("id", id).put("scope", "user").put("enabled", !enabled))
                                        "automations" -> vm.action(
                                            if (enabled) "automations.pause" else "automations.resume",
                                            JSONObject().put("id", item.optString("id")))
                                    }
                                    vm.fetch(name, section.read)
                                }) { Text(if (enabled) "Disable / Pause" else "Enable / Resume") }
                            }
                            if (name == "automations") {
                                TextButton(onClick = {
                                    vm.action("automations.run", JSONObject().put("id", item.optString("id")))
                                }) { Text("Run now") }
                                TextButton(onClick = {
                                    vm.fetch("automation.history", "automations.history",
                                        JSONObject().put("id", item.optString("id")))
                                    selected = item
                                }) { Text("History") }
                            }
                        }
                    }
                }
            }
        }
        if (name in listOf("mcp", "marketplaces", "plugins", "custom-models")) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = {
                    Text(when(name) {
                        "marketplaces" -> "GitHub marketplace repository"
                        "plugins" -> "Plugin name"
                        "custom-models" -> "Model ID"
                        else -> "MCP server name"
                    })
                }, singleLine = true)
            Button(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = input.isNotBlank(),
                onClick = {
                    val args = when(name) {
                        "marketplaces" -> JSONObject().put("repo", input)
                        "plugins" -> JSONObject().put("input", JSONObject().put("name", input)
                            .put("marketplace", "default").put("scope", "user"))
                        "custom-models" -> JSONObject().put("input", JSONObject().put("id", input))
                        else -> JSONObject().put("input", JSONObject().put("name", input))
                    }
                    vm.action(when(name) {
                        "marketplaces" -> "plugins.marketplaceAdd"
                        "plugins" -> "plugins.install"
                        "custom-models" -> "models.custom.save"
                        else -> "mcp.add"
                    }, args)
                    vm.fetch(name, section.read)
                }
            ) { Text("Add") }
        }
    }
    if (selected != null) {
        val item = selected!!
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.optString("name", item.optString("id", "Details"))) },
            text = {
                Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(if(name == "automations") state.sections["automation.history"]?.toString()
                        ?: item.toString(2) else item.toString(2),
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = item.optString("id", item.optString("name"))
                    val operation = when(name) {
                        "mcp" -> "mcp.remove"
                        "plugins" -> "plugins.uninstall"
                        "marketplaces" -> "plugins.marketplaceRemove"
                        "custom-models" -> "models.custom.remove"
                        else -> ""
                    }
                    if (operation.isNotBlank()) vm.action(operation, JSONObject()
                        .put("id", id).put("name", id).put("scope", "user")
                        .put("target", JSONObject().put("id", id)))
                    selected = null
                    vm.fetch(name, section.read)
                }) { Text("Remove") }
            }
        )
    }
}
