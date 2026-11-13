package io.github.ynkcc.moredisplay.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.ui.navigation.Screen
import io.github.ynkcc.moredisplay.ui.screens.DisplaysScreen
import io.github.ynkcc.moredisplay.ui.screens.HomeScreen
import io.github.ynkcc.moredisplay.ui.screens.PoliciesScreen
import io.github.ynkcc.moredisplay.ui.screens.SettingsScreen
import io.github.ynkcc.moredisplay.ui.theme.MoreDisplayTheme
import io.github.ynkcc.moredisplay.ui.viewmodel.DisplaysViewModel
import io.github.ynkcc.moredisplay.ui.viewmodel.MainViewModel
import io.github.ynkcc.moredisplay.ui.viewmodel.PoliciesViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MoreDisplayTheme {
                MainRoot()
            }
        }
    }
}

@Composable
private fun MainRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = Screen.fromRoute(backStackEntry?.destination?.route)
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = current == screen,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.HOME.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.HOME.route) {
                val vm: MainViewModel = viewModel { MainViewModel(App.displays()) }
                HomeScreen(vm, snackbarHostState)
            }
            composable(Screen.DISPLAYS.route) {
                val vm: DisplaysViewModel = viewModel { DisplaysViewModel(App.displays()) }
                DisplaysScreen(vm, snackbarHostState)
            }
            composable(Screen.POLICIES.route) {
                val vm: PoliciesViewModel = viewModel {
                    PoliciesViewModel(App.displays(), App.context as android.app.Application)
                }
                PoliciesScreen(vm, snackbarHostState)
            }
            composable(Screen.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}
