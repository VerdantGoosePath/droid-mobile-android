package com.droidmobile.client.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidmobile.client.backend.DroidState
import com.droidmobile.client.backend.DroidViewModel
import org.json.JSONObject

private val settingsPages = listOf(
    "connection" to "Connection",
    "security" to "Security",
    "notifications" to "Notifications",
    "battery" to "Battery settings",
    "appearance" to "Appearance",
    "language" to "Language",
    "defaults" to "Session defaults",
    "about" to "About"
)

@Composable
fun SettingsIndexScreen(open: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(vertical = 12.dp)) }
        items(settingsPages) { (route, title) ->
            ElevatedCard(onClick = { open(route) }) {
                Row(Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Filled.ArrowForward, null)
                }
            }
        }
    }
}

@Composable
fun SettingsDetailScreen(vm: DroidViewModel, state: DroidState, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("droid_preferences", 0) }
    var darkMode by remember(name) { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var dynamic by remember(name) { mutableStateOf(prefs.getBoolean("dynamic", true)) }
    var language by remember(name) { mutableStateOf(prefs.getString("language", "system") ?: "system") }
    var url by remember { mutableStateOf(state.connectedUrl) }
    var key by remember { mutableStateOf(vm.repository.secrets.read().orEmpty()) }
    var model by remember { mutableStateOf("") }
    var autonomy by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    val title = settingsPages.find { it.first == name }?.second ?: "Settings"
    LaunchedEffect(name) {
        if (name == "defaults") vm.fetch("settings.defaults", "settings.defaults")
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (name) {
                "connection" -> {
                    Text("Current connection: ${state.status}")
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("Relay endpoint") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = key, onValueChange = { key = it },
                        label = { Text("Factory API key") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Button(onClick = { vm.connect(url, key) }) { Text("Reconnect") }
                    OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                }
                "security" -> {
                    Text("Your Factory key is encrypted with Android Keystore (AES-GCM) and is never included in a URL or log.")
                    Button(onClick = {
                        vm.repository.secrets.clear()
                        key = ""
                        vm.disconnect()
                    }) { Text("Forget credentials") }
                    Text("Biometric app lock requires a separate native authentication implementation.",
                        style = MaterialTheme.typography.bodySmall)
                }
                "appearance" -> {
                    Text("Colour", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dynamic Material You colours", modifier = Modifier.weight(1f))
                        Switch(checked = dynamic, onCheckedChange = {
                            dynamic = it; prefs.edit().putBoolean("dynamic", it).apply()
                        })
                    }
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    listOf("system", "light", "dark").forEach { option ->
                        FilterChip(
                            selected = darkMode == option,
                            onClick = {
                                darkMode = option
                                prefs.edit().putString("theme", option).apply()
                            },
                            label = { Text(option.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                    Text("Typography uses the Material 3 Expressive type scale and adapts to system font scaling.")
                }
                "language" -> {
                    Text("App language", style = MaterialTheme.typography.titleMedium)
                    listOf("system", "en", "ro").forEach { tag ->
                        val label = when(tag) { "en" -> "English"; "ro" -> "Română"; else -> "System" }
                        FilterChip(selected = language == tag,
                            onClick = {
                                language = tag
                                prefs.edit().putString("language", tag).apply()
                            }, label = { Text(label) })
                    }
                }
                "defaults" -> {
                    Text("Daemon defaults", style = MaterialTheme.typography.titleMedium)
                    StructuredDataPanel(state.sections["settings.defaults"])
                    OutlinedTextField(value = model, onValueChange = { model = it },
                        label = { Text("Default model ID") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = effort, onValueChange = { effort = it },
                        label = { Text("Reasoning effort") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = autonomy, onValueChange = { autonomy = it },
                        label = { Text("Autonomy") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        val patch = JSONObject()
                        if (model.isNotBlank()) patch.put("modelId", model)
                        if (effort.isNotBlank()) patch.put("reasoningEffort", effort)
                        if (autonomy.isNotBlank()) patch.put("autonomyLevel", autonomy)
                        vm.action("settings.updateDefaults", JSONObject().put("patch", patch))
                        vm.fetch("settings.defaults", "settings.defaults")
                    }) { Text("Save defaults") }
                }
                "notifications" -> {
                    Text("Android notification preferences", style = MaterialTheme.typography.titleMedium)
                    val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    Text("Notifications ${if (manager.areNotificationsEnabled()) "enabled" else "disabled"} by the OS.")
                    FilledTonalButton(onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.startActivity(intent)
                    }) { Text("Open notification settings") }
                    Text("Foreground connection and push notifications need a native service implementation.",
                        style = MaterialTheme.typography.bodySmall)
                }
                "battery" -> {
                    Text("Android may stop background connections under battery restrictions.")
                    Button(onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    }) { Text("Battery optimisation settings") }
                }
                "about" -> {
                    Text("Droid Mobile Native", style = MaterialTheme.typography.headlineMedium)
                    Text("Version 2.0.0-native-alpha")
                    Text("Open-source community client for Factory Droid. Not affiliated with Factory.")
                    Text("Compose Material 3 Expressive + Android Keystore + isolated TypeScript SDK backend.")
                    Text("The native frontend is experimental; verify all sessions against a real CLI before relying on it.")
                }
                else -> Text("Unknown settings page")
            }
        }
    }
}
