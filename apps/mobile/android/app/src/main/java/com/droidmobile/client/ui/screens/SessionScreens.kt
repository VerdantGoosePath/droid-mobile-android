package com.droidmobile.client.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidmobile.client.backend.DroidState
import com.droidmobile.client.backend.DroidViewModel
import com.droidmobile.client.ui.theme.DroidTokens
import com.droidmobile.client.ui.components.DroidEmptyState
import com.droidmobile.client.ui.components.DroidLoadingState
import com.droidmobile.client.ui.components.StatusPill
import com.droidmobile.client.ui.components.InlineError
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SessionListScreen(vm: DroidViewModel, state: DroidState, open: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var archiveTarget by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { vm.refreshSessions() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(DroidTokens.contentPadding),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${state.sessions.size} conversation${if (state.sessions.size == 1) "" else "s"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                IconButton(onClick = { vm.refreshSessions() }) {
                    Icon(Icons.Filled.Refresh, "Refresh sessions")
                }
                FilledIconButton(onClick = { showNew = true }) {
                    Icon(Icons.Filled.Add, "New session")
                }
            }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it }, modifier = Modifier
                .fillMaxWidth().padding(horizontal = DroidTokens.contentPadding),
            label = { Text("Search sessions") }, singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (state.sessions.isEmpty()) item {
                DroidEmptyState(
                    title = "No sessions yet",
                    supportingText = "Create your first session to start working with Droid.",
                    modifier = Modifier.height(350.dp),
                    actionLabel = "New session",
                    onAction = { showNew = true }
                )
            }
            items(
                state.sessions.filter {
                    search.isBlank() ||
                        it.optString("title").contains(search, true) ||
                        it.optString("cwd").contains(search, true)
                }, key = { it.optString("id") }
            ) { session ->
                Card(onClick = { open(session.optString("id")) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(session.optString("title").ifBlank { "Untitled session" },
                            style = MaterialTheme.typography.titleMedium)
                        Text(session.optString("cwd"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(session.optString("status").ifBlank { "Open" })
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selected = session }) { Text("Manage") }
                        }
                    }
                }
            }
        }
    }
    if (selected != null) {
        val session = selected!!
        var title by remember(session) { mutableStateOf(session.optString("title")) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Manage session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it },
                        label = { Text("Title") })
                    Text("Archive removes the session from your active list.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.action("sessions.rename", JSONObject().put("id", session.optString("id")).put("title", title))
                    vm.refreshSessions(); selected = null
                }) { Text("Save name") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        archiveTarget = session
                        selected = null
                    }) { Text("Archive") }
                    TextButton(onClick = { selected = null }) { Text("Cancel") }
                }
            }
        )
    }
    if (archiveTarget != null) {
        val target = archiveTarget!!
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("Archive session?") },
            text = { Text("The session will be removed from your active list. You can restore it later.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.action("sessions.archive", JSONObject().put("id", target.optString("id")))
                    vm.refreshSessions()
                    archiveTarget = null
                }) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("Keep session") } }
        )
    }
    if (showNew) NewSessionSheet(vm, onDismiss = { showNew = false }, onCreate = { id ->
        showNew = false; open(id)
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionSheet(vm: DroidViewModel, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var cwd by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var autonomy by remember { mutableStateOf("default") }
    var worktree by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var trustWarning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("New session", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(value = cwd, onValueChange = { cwd = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Working directory on PC") },
                singleLine = true)
            Text("Interaction mode", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("auto", "spec", "mission").forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = mode == choice,
                        onClick = { mode = choice },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                        label = { Text(choice.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Text("Autonomy", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("default", "low", "medium", "high").forEach { choice ->
                    FilterChip(
                        selected = autonomy == choice,
                        onClick = { autonomy = choice },
                        label = { Text(choice.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use Git worktree", modifier = Modifier.weight(1f))
                Switch(checked = worktree, onCheckedChange = { worktree = it })
            }
            if (trustWarning) Text("The daemon may require folder trust confirmation.",
                color = MaterialTheme.colorScheme.error)
            Button(
                modifier = Modifier.fillMaxWidth(), enabled = cwd.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val check = vm.repository.request("workspace.validate", JSONObject().put("path", cwd))
                            val trust = vm.repository.request("workspace.trustCheck", JSONObject().put("path", cwd))
                            if (trust is JSONObject && trust.optBoolean("trustRequired")) {
                                trustWarning = true
                                vm.repository.request("workspace.trust", JSONObject().put("path", cwd))
                            }
                            val params = JSONObject().put("cwd", cwd)
                                .put("interactionMode", mode)
                                .put("worktree", worktree)
                            if (autonomy != "default") params.put("autonomyLevel", autonomy)
                            val session = vm.repository.request("sessions.create", JSONObject().put("params", params)) as JSONObject
                            vm.refreshSessions()
                            onCreate(session.getString("id"))
                        } catch (e: Exception) {
                            vm.repository.updateError(e.message)
                        } finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Creating…" else "Create session") }
        }
    }
}

@Composable
fun SessionDetailScreen(vm: DroidViewModel, state: DroidState, id: String, onBack: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("chat") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(id) { vm.openSession(id) }
    LaunchedEffect(state.messages.size, state.streamText) {
        val count = state.messages.size + if (state.streaming) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Session ${id.take(8)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showSettings = true }) { Text("Options") }
        }
        PrimaryTabRow(selectedTabIndex = listOf("chat", "mission", "context").indexOf(tab)) {
            listOf("chat", "mission", "context").forEach { choice ->
                Tab(
                    selected = tab == choice,
                    onClick = { tab = choice },
                    text = { Text(choice.replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        when (tab) {
            "mission" -> {
                LaunchedEffect(id) { vm.fetch("mission", "missions.snapshot", JSONObject().put("id", id)) }
                if (state.sections["mission"] == null)
                    DroidLoadingState("Loading mission…")
                else StructuredDataPanel(state.sections["mission"])
            }
            "context" -> {
                LaunchedEffect(id) { vm.fetch("context", "sessions.context", JSONObject().put("id", id)) }
                if (state.sections["context"] == null)
                    DroidLoadingState("Calculating context usage…")
                else StructuredDataPanel(state.sections["context"])
            }
            else -> {
                LazyColumn(
                    state = listState, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages) { message -> TranscriptCard(message) }
                    if (state.streaming) item {
                        ElevatedCard(colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Droid is working", style = MaterialTheme.typography.labelLarge)
                                if (state.streamText.isNotEmpty())
                                    Text(state.streamText, style = MaterialTheme.typography.bodyMedium)
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().imePadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f), maxLines = 5,
                        placeholder = { Text("Message Droid…") })
                    FilledIconButton(
                        enabled = draft.isNotBlank() && !state.streaming,
                        onClick = { vm.send(draft); draft = "" }
                    ) { Icon(Icons.Filled.Send, "Send") }
                    if (state.streaming) {
                        TextButton(onClick = {
                            vm.action("sessions.interrupt", JSONObject().put("id", id))
                        }) { Text("Stop") }
                    }
                }
            }
        }
    }
    if (showSettings) {
        val current = state.sections["session.settings"] as? JSONObject
        var model by remember { mutableStateOf(current?.optString("modelId") ?: "") }
        var autonomy by remember { mutableStateOf(current?.optString("autonomyLevel") ?: "") }
        LaunchedEffect(id) { vm.fetch("session.settings", "sessions.settings", JSONObject().put("id", id)) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Session options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = model, onValueChange = { model = it },
                        label = { Text("Model ID") })
                    OutlinedTextField(value = autonomy, onValueChange = { autonomy = it },
                        label = { Text("Autonomy") })
                    TextButton(onClick = {
                        vm.action("sessions.compact", JSONObject().put("id", id))
                    }) { Text("Compact context") }
                    TextButton(onClick = {
                        vm.action("sessions.fork", JSONObject().put("id", id))
                    }) { Text("Fork session") }
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.action("sessions.updateSettings", JSONObject().put("id", id)
                    .put("patch", JSONObject().put("modelId", model).put("autonomyLevel", autonomy)))
                showSettings = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
    PermissionDialogs(vm, state)
}

private fun textFromPayload(value: Any?, depth: Int = 0): String {
    if (depth > 5 || value == null || value == JSONObject.NULL) return ""
    return when (value) {
        is String -> value
        is JSONArray -> (0 until value.length())
            .map { textFromPayload(value.opt(it), depth + 1) }
            .filter { it.isNotBlank() }.joinToString("\n")
        is JSONObject -> {
            val keys = listOf("text", "message", "content", "output", "result", "body",
                "markdown", "summary", "description", "error")
            val parts = keys.mapNotNull { key ->
                if (value.has(key)) textFromPayload(value.opt(key), depth + 1)
                    .takeIf { it.isNotBlank() } else null
            }
            if (parts.isEmpty()) "" else parts.distinct().joinToString("\n")
        }
        else -> value.toString()
    }
}

@Composable
private fun TranscriptCard(message: JSONObject) {
    val role = message.optString("role", message.optString("type", "message"))
    val content = message.opt("content")
    val body = textFromPayload(content).ifBlank {
        textFromPayload(message.opt("text")).ifBlank {
            textFromPayload(message.opt("message"))
        }
    }
    val tool = role.contains("tool", ignoreCase = true) ||
        message.has("toolName") || message.has("toolUse")
    val label = when {
        role == "user" -> "You"
        tool -> message.optString("toolName").ifBlank { "Tool activity" }
        role == "assistant" -> "Droid"
        else -> role.replaceFirstChar { it.uppercase() }
    }
    var expanded by remember(message) { mutableStateOf(false) }
    val user = role == "user"

    Surface(
        shape = MaterialTheme.shapes.large,
        color = when {
            user -> MaterialTheme.colorScheme.primaryContainer
            tool -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = if (tool) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (user) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                if (tool || body.isBlank()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide details" else "Details")
                    }
                }
            }
            if (body.isNotBlank()) {
                SelectionContainer {
                    Text(
                        body,
                        style = if (tool) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodyLarge,
                        maxLines = if (tool && !expanded) 4 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!tool) {
                Text("Non-text message", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                SelectionContainer {
                    Text(message.toString(2), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun humanLabel(key: String): String =
    key.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
fun StructuredDataPanel(value: Any?) {
    if (value == null || value == JSONObject.NULL) {
        DroidLoadingState("Loading details…")
        return
    }
    if (value is String) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
            }
        }
        return
    }
    if (value is JSONObject) {
        val failure = value.optBoolean("success", true).not()
        if (failure) {
            val reason = value.optString("unavailableMessage")
                .ifBlank { value.optString("message", "This information is unavailable.") }
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                InlineError(reason)
                Text("No changes can be displayed for this workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp))
            }
            return
        }
    }
    var raw by remember(value) { mutableStateOf(false) }
    val fields: List<Pair<String, Any?>> = when (value) {
        is JSONObject -> value.keys().asSequence().map { it to value.opt(it) }.toList()
        is JSONArray -> (0 until value.length()).map { (it + 1).toString() to value.opt(it) }
        else -> listOf("Value" to value)
    }
    val budget = (value as? JSONObject)?.optDouble("contextBudget", 0.0) ?: 0.0
    val used = (value as? JSONObject)?.optDouble("usedTokens", 0.0) ?: 0.0
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(DroidTokens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (budget > 0) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Context usage", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${used.toLong().toString()} / ${budget.toLong()} tokens",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    LinearProgressIndicator(
                        progress = { (used / budget).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                fields.forEachIndexed { index, (key, item) ->
                    val display = when (item) {
                        is JSONObject -> textFromPayload(item)
                            .ifBlank { "${item.length()} properties" }
                        is JSONArray -> "${item.length()} items"
                        JSONObject.NULL, null -> "—"
                        else -> item.toString()
                    }
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        headlineContent = {
                            Text(humanLabel(key), style = MaterialTheme.typography.titleSmall)
                        },
                        supportingContent = {
                            Text(display, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    )
                    if (index != fields.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
        TextButton(onClick = { raw = !raw }) {
            Text(if (raw) "Hide technical details" else "View technical details")
        }
        if (raw) {
            SelectionContainer {
                Text(
                    when (value) {
                        is JSONObject -> value.toString(2)
                        is JSONArray -> value.toString(2)
                        else -> value.toString()
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PermissionDialogs(vm: DroidViewModel, state: DroidState) {
    val permission = state.permissionRequest
    if (permission != null) {
        val req = permission.optJSONObject("request")
        val options = req?.optJSONArray("options") ?: JSONArray()
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Droid needs permission") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(req?.optJSONArray("toolUses")?.toString(2) ?: req?.toString(2).orEmpty())
                }
            },
            confirmButton = { TextButton(onClick = { vm.permission(JSONObject().put("decision", "proceed_once")) }) {
                Text("Allow once")
            } },
            dismissButton = { TextButton(onClick = { vm.permission(JSONObject().put("decision", "cancel")) }) {
                Text("Deny")
            } }
        )
    }
    val question = state.questionRequest
    if (question != null) {
        var answer by remember(question) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Droid has a question") },
            text = {
                Column {
                    Text(question.optJSONObject("request")?.toString(2).orEmpty())
                    OutlinedTextField(value = answer, onValueChange = { answer = it },
                        label = { Text("Answer") })
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.question(JSONObject().put("cancelled", false)
                    .put("answers", JSONArray().put(JSONObject().put("answer", answer))))
            }) { Text("Submit") } },
            dismissButton = { TextButton(onClick = {
                vm.question(JSONObject().put("cancelled", true).put("answers", JSONArray()))
            }) { Text("Cancel") } }
        )
    }
}
