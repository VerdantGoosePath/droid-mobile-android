package com.droidmobile.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
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
import com.droidmobile.client.ui.theme.DroidTokens

private data class NavDestination(val route: String, val label: String)

private val primaryDestinations = listOf(
    NavDestination("sessions", "Sessions"),
    NavDestination("workspace", "Workspace"),
    NavDestination("extensions", "Extensions"),
    NavDestination("settings", "Settings")
)
private val extensionTitles = mapOf(
    "mcp" to "MCP servers",
    "skills" to "Skills",
    "commands" to "Commands",
    "plugins" to "Plugins",
    "marketplaces" to "Marketplaces",
    "custom-models" to "Custom models",
    "automations" to "Automations"
)
private val settingsTitles = mapOf(
    "connection" to "Connection",
    "security" to "Security",
    "notifications" to "Notifications",
    "battery" to "Battery settings",
    "appearance" to "Appearance",
    "language" to "Language",
    "defaults" to "Session defaults",
    "about" to "About"
)

/**
 * Root design system: a single inset-aware Material scaffold owns the screen
 * app bar. Individual pages never draw their own titles into the status bar.
 * Navigation adapts from a compact navigation bar to a rail on large screens.
 */
@Composable
fun DroidApp(vm: DroidViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (state.status != "ready") {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Droid Mobile") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            ) { inner ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentAlignment = Alignment.TopCenter
                ) {
                    ConnectionScreen(vm, state)
                }
            }
            return@Surface
        }

        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route.orEmpty()
        val detail = route.contains("/{")
        val section = route.substringBefore("/")
        val detailName = when (section) {
            "extensions", "settings" -> entry?.arguments?.getString("name")
            else -> entry?.arguments?.getString("id")
        }.orEmpty()
        val selectedTitle = state.sessions.find { it.optString("id") == detailName }
            ?.optString("title").orEmpty()
        val pageTitle = when (section) {
            "sessions" -> if (detail) selectedTitle.ifBlank { "Session" } else "Sessions"
            "workspace" -> "Workspace"
            "extensions" -> extensionTitles[detailName] ?: "Extensions"
            "settings" -> settingsTitles[detailName] ?: "Settings"
            else -> "Droid Mobile"
        }
        val pageSubtitle = when (section) {
            "sessions" -> "Conversations and activity"
            "workspace" -> "Files, changes and terminal"
            "extensions" -> "Tools and integrations"
            "settings" -> "Personalize your experience"
            else -> null
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        NavigationSuiteScaffold(
            navigationSuiteItems = {
                primaryDestinations.forEachIndexed { index, destination ->
                    item(
                        selected = section == destination.route,
                        onClick = {
                            nav.navigate(destination.route) {
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
                        label = { Text(destination.label) }
                    )
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    if (detail) {
                        TopAppBar(
                            title = { Text(pageTitle, maxLines = 1) },
                            navigationIcon = {
                                IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    } else {
                        MediumFlexibleTopAppBar(
                            title = { Text(pageTitle, maxLines = 1) },
                            subtitle = { Text(pageSubtitle.orEmpty(), maxLines = 1) },
                            scrollBehavior = scrollBehavior,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                },
                // NavigationSuite already owns the bottom-system-bar boundary.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = MaterialTheme.colorScheme.surface
            ) { contentPadding ->
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val contentWidth = maxWidth.coerceAtMost(DroidTokens.pageMaxWidth)
                    NavHost(
                        navController = nav,
                        startDestination = "sessions",
                        modifier = Modifier.width(contentWidth).fillMaxHeight()
                    ) {
                        composable("sessions") {
                            SessionListScreen(vm, state, open = { id -> nav.navigate("sessions/$id") })
                        }
                        composable("sessions/{id}") { destination ->
                            val id = destination.arguments?.getString("id").orEmpty()
                            SessionDetailScreen(vm, state, id, onBack = { nav.popBackStack() })
                        }
                        composable("workspace") { WorkspaceScreen(vm, state) }
                        composable("extensions") {
                            ExtensionsIndexScreen(open = { name -> nav.navigate("extensions/$name") })
                        }
                        composable("extensions/{name}") { destination ->
                            ExtensionsDetailScreen(
                                vm, state, destination.arguments?.getString("name").orEmpty(),
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsIndexScreen(open = { name -> nav.navigate("settings/$name") })
                        }
                        composable("settings/{name}") { destination ->
                            SettingsDetailScreen(
                                vm, state, destination.arguments?.getString("name").orEmpty(),
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
