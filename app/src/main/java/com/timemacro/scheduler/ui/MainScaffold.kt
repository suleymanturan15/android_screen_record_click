package com.timemacro.scheduler.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.timemacro.scheduler.AppContainer
import com.timemacro.scheduler.ui.screens.logs.LogsScreen
import com.timemacro.scheduler.ui.screens.macros.MacroDetailScreen
import com.timemacro.scheduler.ui.screens.macros.MacroRecordScreen
import com.timemacro.scheduler.ui.screens.macros.MacrosScreen
import com.timemacro.scheduler.ui.screens.settings.MiuiHelpScreen
import com.timemacro.scheduler.ui.screens.settings.DiagnosticsScreen
import com.timemacro.scheduler.ui.screens.settings.SettingsScreen
import com.timemacro.scheduler.ui.screens.tasks.HomeTasksScreen
import com.timemacro.scheduler.ui.screens.tasks.TaskEditorScreen
import com.timemacro.scheduler.ui.screens.tasks.TaskEditorMode

private sealed class BottomTab(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
) {
    data object Tasks : BottomTab("tasks", "Tasks", { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) })
    data object Macros : BottomTab("macros", "Macros", { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) })
    data object Logs : BottomTab("logs", "Logs", { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) })
    data object Settings : BottomTab("settings", "Settings", { Icon(Icons.Default.Checklist, contentDescription = null) })
}

object Routes {
    const val Tasks = "tasks"
    const val TaskCreate = "task/create"
    const val TaskEditPattern = "task/edit/{taskId}"
    fun taskEdit(taskId: String) = "task/edit/$taskId"
    const val Macros = "macros"
    const val MacroRecord = "macroRecord"
    const val MacroDetail = "macroDetail"
    const val Logs = "logs"
    const val Settings = "settings"
    const val MiuiHelp = "miuiHelp"
    const val Diagnostics = "diagnostics"
}

@Composable
fun MainScaffold(
    container: AppContainer,
    openRoute: String? = null,
    onOpenRouteConsumed: (() -> Unit)? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    LaunchedEffect(openRoute) {
        val route = openRoute ?: return@LaunchedEffect
        // If we deep-link into MacroDetail, ensure back goes to Macros list (and list is already Flow-backed).
        if (route.startsWith("${Routes.MacroDetail}/")) {
            navController.navigate(Routes.Macros) { launchSingleTop = true }
            navController.navigate(route) { launchSingleTop = true }
        } else {
            navController.navigate(route) { launchSingleTop = true }
        }
        onOpenRouteConsumed?.invoke()
    }

    val tabs = listOf(BottomTab.Tasks, BottomTab.Macros, BottomTab.Logs, BottomTab.Settings)
    val showBottomBar = currentDestination?.route in setOf(
        Routes.Tasks,
        Routes.Macros,
        Routes.Logs,
        Routes.Settings,
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = tab.icon,
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Tasks,
            modifier = Modifier,
        ) {
            composable(Routes.Tasks) {
                HomeTasksScreen(
                    container = container,
                    contentPadding = innerPadding,
                    onCreateTask = { navController.navigate(Routes.TaskCreate) },
                    onEditTask = { taskId -> navController.navigate(Routes.taskEdit(taskId)) },
                )
            }

            composable(Routes.TaskCreate) {
                TaskEditorScreen(
                    container = container,
                    contentPadding = innerPadding,
                    mode = TaskEditorMode.CREATE,
                    taskId = null,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }

            composable(Routes.TaskEditPattern) { entry ->
                val taskId = entry.arguments?.getString("taskId") ?: return@composable
                TaskEditorScreen(
                    container = container,
                    contentPadding = innerPadding,
                    mode = TaskEditorMode.EDIT,
                    taskId = taskId,
                    onDone = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }

            composable(Routes.Macros) {
                MacrosScreen(
                    container = container,
                    contentPadding = innerPadding,
                    onNewMacro = { navController.navigate(Routes.MacroRecord) },
                    onMacroSelected = { macroId ->
                        navController.navigate("${Routes.MacroDetail}/$macroId")
                    },
                )
            }

            composable(Routes.MacroRecord) {
                MacroRecordScreen(
                    container = container,
                    contentPadding = innerPadding,
                    onMacroSaved = { _ ->
                        // After auto-save, return to Macros list (Flow-backed list will update immediately).
                        navController.popBackStack(Routes.Macros, inclusive = false)
                    },
                    onCancel = { navController.popBackStack() },
                    onOpenMiuiHelp = { navController.navigate(Routes.MiuiHelp) },
                )
            }

            composable("${Routes.MacroDetail}/{macroId}") { entry ->
                val macroId = entry.arguments?.getString("macroId") ?: return@composable
                MacroDetailScreen(
                    container = container,
                    contentPadding = innerPadding,
                    macroId = macroId,
                    onDeleted = { navController.popBackStack(Routes.Macros, inclusive = false) },
                )
            }

            composable(Routes.Logs) {
                LogsScreen(
                    container = container,
                    contentPadding = innerPadding,
                )
            }

            composable(Routes.Settings) {
                SettingsScreen(
                    container = container,
                    contentPadding = innerPadding,
                    onOpenMiuiHelp = { navController.navigate(Routes.MiuiHelp) },
                    onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
                )
            }

            composable(Routes.MiuiHelp) {
                MiuiHelpScreen(
                    contentPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.Diagnostics) {
                DiagnosticsScreen(
                    container = container,
                    contentPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

