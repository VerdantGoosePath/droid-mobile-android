package com.droidmobile.client.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
    var confirmForget by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("") }
    var autonomy by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
        as android.app.NotificationManager
    var notificationsAllowed by remember {
        mutableStateOf(notificationManager.areNotificationsEnabled())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
    }
    val title = settingsPages.find { it.first == name }?.second ?: "Settings"
    LaunchedEffect(name) {
        if (name == "defaults") vm.fetch("settings.defaults", "settings.defaults")
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (name) {
                "connection" -> {
                    SectionHeading("Connected computer",
                        "Manage the Factory relay session for this device.")
                    StatusPill(state.status)
                    OutlinedTextField(value = url, onValueChange = { url = it },
                        label = { Text("Relay endpoint") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = key, onValueChange = { key = it },
                        label = { Text("Factory API key") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Button(onClick = { vm.connect(url, key) }) { Text("Reconnect") }
                    OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                }
                "security" -> {
                    SectionHeading("Device protection",
                        "Your Factory credential is encrypted with an Android Keystore key.")
                    GroupedItems {
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            leadingContent = { Icon(Icons.Filled.VerifiedUser, null) },
                            headlineContent = { Text("Encrypted credential storage") },
                            supportingContent = {
                                Text("AES-GCM encrypted and unavailable to other apps.")
                            },
                            trailingContent = { StatusPill("active") }
                        )
                    }
                    SectionHeading("Remove credentials",
                        "Disconnect this device and remove the stored Factory API key.")
                    OutlinedButton(onClick = { confirmForget = true }) {
                        Text("Forget credentials…")
                    }
                    Text("Biometric locking of the whole app is not yet available in the native migration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val defaults = state.sections["settings.defaults"] as? JSONObject
                    if (defaults == null) {
                        Text("Loading default settings…",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        GroupedItems {
                            listOf("modelId" to "Model",
                                "autonomyLevel" to "Autonomy",
                                "reasoningEffort" to "Reasoning").forEachIndexed { index, pair ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    headlineContent = { Text(pair.second) },
                                    supportingContent = {
                                        Text(defaults.optString(pair.first, "Not set"))
                                    }
                                )
                                if (index != 2) HorizontalDivider(
                                    Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
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
                    SectionHeading("Alerts and permissions",
                        "Control how Android notifies you about Droid.")
                    GroupedItems {
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            leadingContent = { Icon(Icons.Filled.Notifications, null) },
                            headlineContent = { Text("Notifications") },
                            supportingContent = {
                                Text(if (notificationsAllowed)
                                    "Allowed by Android" else "Disabled or not permitted")
                            },
                            trailingContent = {
                                StatusPill(if (notificationsAllowed) "Allowed" else "Blocked")
                            }
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow notifications")
                        }
                    }
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).putExtra(
                                android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName
                            )
                            context.startActivity(intent)
                        }
                    ) { Text("Manage Android notification settings") }
                    Text("Background connection service and remote push alerts are not yet available in this experimental native build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "battery" -> {
                    SectionHeading("Battery optimisation",
                        "Android may limit network sessions while the app is in the background.")
                    val power = context.getSystemService(android.content.Context.POWER_SERVICE)
                        as android.os.PowerManager
                    val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                    StatusPill(if (unrestricted) "Unrestricted" else "Optimized")
                    Text(if (unrestricted)
                        "This app is excluded from Android battery optimisations."
                    else "Battery optimisation may interrupt a connected session.")
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
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget Factory credentials?") },
            text = {
                Text("This will disconnect the app and permanently erase the Factory API key from this device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.repository.secrets.clear()
                    key = ""
                    confirmForget = false
                    vm.disconnect()
                }) { Text("Forget key") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            }
        )
    }
}
