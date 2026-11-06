package com.droidmobile.client.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.droidmobile.client.backend.DroidState
import com.droidmobile.client.backend.DroidViewModel
import com.droidmobile.client.ui.theme.DroidTokens
import com.droidmobile.client.ui.components.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ConnectionScreen(vm: DroidViewModel, state: DroidState) {
    var url by remember { mutableStateOf(state.connectedUrl) }
    var key by remember { mutableStateOf(vm.repository.secrets.read().orEmpty()) }
    var region by remember { mutableStateOf("global") }
    var computers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(DroidTokens.contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Remote Control", style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text("Your computer, wherever you are.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Factory handles the connection. No VPN, reverse proxy or open ports.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Your Factory connection", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("Factory API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Account region", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("global", "eu").forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = region == choice,
                                onClick = { region = choice },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                label = { Text(if (choice == "global") "Global" else "Europe") }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            searching = true; issue = null
                            scope.launch {
                                try { computers = vm.repository.discoverComputers(region, key) }
                                catch (e: Exception) { issue = e.message }
                                finally { searching = false }
                            }
                        },
                        enabled = !searching && key.isNotBlank() && state.bridgeReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Computer, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (searching) "Finding computers…" else "Find registered computers")
                    }
                    HorizontalDivider()
                    Text("Or paste the /agent URL shown by droid daemon --remote-control",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text("Factory relay URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilledTonalButton(
                        enabled = state.bridgeReady && key.isNotBlank() && url.startsWith("wss://"),
                        onClick = { vm.connect(url, key) }, modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Link, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Connect")
                    }
                    if (!state.bridgeReady) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Starting secure connection engine…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(state.status)
                    val err = state.error ?: issue
                    if (err != null) InlineError(err)
                }
            }
        }
        items(computers, key = { it.optString("id") }) { computer ->
            GroupedItems {
                GroupedActionRow(
                    title = computer.optString("name", "Computer"),
                    supportingText = "Registered computer · ${computer.optString("status", "unknown")}",
                    onClick = {
                        url = computer.optString("relayClientUrl")
                        vm.connect(url, key)
                    },
                    leadingIcon = Icons.Filled.Computer
                )
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Experimental connection", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        "TLS protects the connection to Factory's relay. Additional end-to-end " +
                            "X25519 protection is not implemented yet. Avoid sensitive sessions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}
