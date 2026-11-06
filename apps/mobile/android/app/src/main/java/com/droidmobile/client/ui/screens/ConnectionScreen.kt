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
            Spacer(Modifier.height(20.dp))
            Text("Droid Mobile", style = MaterialTheme.typography.displaySmall)
            Text("Remote Control", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Connect through Factory's relay. No Tailscale, proxy, or inbound firewall rule.",
                style = MaterialTheme.typography.bodyLarge)
        }
        item {
            ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Your Factory connection", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("Factory API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(selected = region == "global", onClick = { region = "global" },
                            label = { Text("Global") })
                        FilterChip(selected = region == "eu", onClick = { region = "eu" },
                            label = { Text("EU") })
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
                    if (!state.bridgeReady) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Status: ${state.status}", style = MaterialTheme.typography.labelLarge)
                    val err = state.error ?: issue
                    if (err != null) Text(err, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(computers, key = { it.optString("id") }) { computer ->
            ElevatedCard(onClick = {
                url = computer.optString("relayClientUrl")
                vm.connect(url, key)
            }) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(computer.optString("name", "Computer"),
                        style = MaterialTheme.typography.titleMedium)
                    Text(computer.optString("status", "unknown"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Security: this experimental bridge uses relay TLS, but does not yet implement Factory's additional end-to-end X25519 handshake. Avoid sensitive sessions until parity is verified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
