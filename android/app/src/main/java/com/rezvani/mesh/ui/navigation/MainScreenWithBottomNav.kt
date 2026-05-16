// android/app/src/main/java/com/rezvani/mesh/ui/navigation/MainScreenWithBottomNav.kt

package com.rezvani.mesh.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rezvani.mesh.MeshServiceConnection

@Composable
fun MainScreenWithBottomNav() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val meshConnection = remember { MeshServiceConnection(context) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Chat, contentDescription = "Status") },
                    label = { Text("Status") },
                    selected = currentRoute == "status",
                    onClick = { navController.navigate("status") { popUpTo("status") { inclusive = true } } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Warning, contentDescription = "SOS") },
                    label = { Text("SOS") },
                    selected = currentRoute == "emergency",
                    onClick = { navController.navigate("emergency") { popUpTo("status") { inclusive = false } } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Mic, contentDescription = "PTT") },
                    label = { Text("PTT") },
                    selected = currentRoute == "voice",
                    onClick = { navController.navigate("voice") { popUpTo("status") { inclusive = false } } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentRoute == "settings",
                    onClick = { navController.navigate("settings") { popUpTo("status") { inclusive = false } } }
                )
            }
        }
    ) { innerPadding ->
        NavGraph(
            navController = navController,
            meshConnection = meshConnection,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
