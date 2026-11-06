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
import com.droidmobile.client.ui.components.*
import androidx.compose.material.icons.filled.*
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
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            SectionHeading("Preferences", "Everything in one place, arranged by purpose.")
        }
        item {
            SectionHeading("Your connection")
            Spacer(Modifier.height(10.dp))
            GroupedItems {
                GroupedActionRow("Connection", "Registered computer and relay endpoint",
                    onClick = { open("connection") }, leadingIcon = Icons.Filled.Link)
                HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp))
                GroupedActionRow("Security", "Credentials and device protection",
                    onClick = { open("security") }, leadingIcon = Icons.Filled.Lock)
            }
        }
        item {
            SectionHeading("App preferences")
            Spacer(Modifier.height(10.dp))
            GroupedItems {
                listOf(
                    Triple("appearance", "Appearance", Icons.Filled.Palette),
                    Triple("language", "Language", Icons.Filled.Language),
                    Triple("notifications", "Notifications", Icons.Filled.Notifications),
                    Triple("battery", "Battery settings", Icons.Filled.BatterySaver)
                ).forEachIndexed { index, setting ->
                    GroupedActionRow(setting.second, null,
                        onClick = { open(setting.first) }, leadingIcon = setting.third)
                    if (index < 3) HorizontalDivider(
                        Modifier.padding(start = 64.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
        item {
            SectionHeading("Droid")
            Spacer(Modifier.height(10.dp))
            GroupedItems {
                GroupedActionRow("Session defaults", "Default model and behaviour",
                    onClick = { open("defaults") }, leadingIcon = Icons.Filled.Tune)
                HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp))
                GroupedActionRow("About", "Version and project information",
                    onClick = { open("about") }, leadingIcon = Icons.Filled.Info)
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
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("system", "light", "dark").forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = darkMode == option,
                                onClick = {
                                    darkMode = option
                                    prefs.edit().putString("theme", option).apply()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                                label = { Text(option.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
                "language" -> {
                    Text("App language", style = MaterialTheme.typography.titleMedium)
                    GroupedItems {
                        listOf("system", "en", "ro").forEachIndexed { index, tag ->
                            val label = when(tag) {
                                "en" -> "English"
                                "ro" -> "Română"
                                else -> "Use device language"
                            }
                            ListItem(
                                modifier = Modifier.clickable {
                                    language = tag
                                    prefs.edit().putString("language", tag).apply()
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                headlineContent = { Text(label) },
                                trailingContent = {
                                    RadioButton(selected = language == tag, onClick = {
                                        language = tag
                                        prefs.edit().putString("language", tag).apply()
                                    })
                                }
                            )
                            if (index < 2) HorizontalDivider(
                                Modifier.padding(start = 16.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Text("Translation of the new native interface is still being completed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
