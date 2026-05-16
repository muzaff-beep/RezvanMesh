// android/app/src/main/java/com/rezvani/mesh/ui/navigation/NavGraph.kt

package com.rezvani.mesh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.ui.screens.*

@Composable
fun NavGraph(
    navController: NavHostController,
    meshConnection: MeshServiceConnection,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "status",
        modifier = modifier
    ) {
        composable("status") {
            StatusScreen(
                onNavigateToMessages = { navController.navigate("messages") },
                onNavigateToContacts = { navController.navigate("contacts") },
                onNavigateToDiagnostics = { navController.navigate("diagnostics") }
            )
        }
        composable("messages") {
            MessagesScreen(meshConnection)
        }
        composable("contacts") {
            ContactsScreen(meshConnection)
        }
        composable("emergency") {
            EmergencyScreen()
        }
        composable("voice") {
            VoiceScreen()
        }
        composable("settings") {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("diagnostics") {
            DiagnosticsScreen()
        }
    }
}