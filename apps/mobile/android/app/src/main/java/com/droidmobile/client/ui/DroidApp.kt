package com.droidmobile.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.droidmobile.client.backend.DroidViewModel
import com.droidmobile.client.ui.screens.ConnectionScreen
import com.droidmobile.client.ui.screens.SessionListScreen
import com.droidmobile.client.ui.screens.SessionDetailScreen
import com.droidmobile.client.ui.screens.WorkspaceScreen
import com.droidmobile.client.ui.screens.ExtensionsIndexScreen
import com.droidmobile.client.ui.screens.ExtensionsDetailScreen
import com.droidmobile.client.ui.screens.SettingsIndexScreen
import com.droidmobile.client.ui.screens.SettingsDetailScreen

private data class NavDestination(val route: String, val label: String)

@Composable
fun DroidApp(vm: DroidViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.status != "ready") {
        ConnectionScreen(vm, state)
        return
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val main = listOf(
        NavDestination("sessions", "Sessions"),
        NavDestination("workspace", "Workspace"),
        NavDestination("extensions", "Extensions"),
        NavDestination("settings", "Settings")
    )
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            main.forEachIndexed { index, item ->
                item(
                    selected = route == item.route || route.startsWith(item.route + "/"),
                    onClick = {
                        nav.navigate(item.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        when (index) {
                            0 -> Icon(Icons.Filled.List, contentDescription = null)
                            1 -> Icon(Icons.Filled.Folder, contentDescription = null)
                            2 -> Icon(Icons.Filled.Extension, contentDescription = null)
                            else -> Icon(Icons.Filled.Settings, contentDescription = null)
                        }
                    },
                    label = { Text(item.label) }
                )
            }
        }
    ) {
        NavHost(navController = nav, startDestination = "sessions", modifier = Modifier.fillMaxSize()) {
            composable("sessions") {
                SessionListScreen(vm, state, open = { id -> nav.navigate("sessions/$id") })
            }
            composable("sessions/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                SessionDetailScreen(vm, state, id, onBack = { nav.popBackStack() })
            }
            composable("workspace") { WorkspaceScreen(vm, state) }
            composable("extensions") {
                ExtensionsIndexScreen(open = { name -> nav.navigate("extensions/$name") })
            }
            composable("extensions/{name}") { entry ->
                ExtensionsDetailScreen(
                    vm, state, entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
            composable("settings") {
                SettingsIndexScreen(open = { name -> nav.navigate("settings/$name") })
            }
            composable("settings/{name}") { entry ->
                SettingsDetailScreen(
                    vm, state, entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
