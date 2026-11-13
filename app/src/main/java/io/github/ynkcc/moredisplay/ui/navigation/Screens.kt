package io.github.ynkcc.moredisplay.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "Home", Icons.Filled.Home),
    DISPLAYS("displays", "Displays", Icons.AutoMirrored.Filled.List),
    POLICIES("policies", "Policies", Icons.Filled.Lock),
    SETTINGS("settings", "Settings", Icons.Filled.Settings);

    companion object {
        fun fromRoute(route: String?): Screen =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}
