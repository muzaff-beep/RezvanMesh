// android/app/src/main/java/com/rezvani/mesh/ui/navigation/NavGraph.kt

package com.rezvani.mesh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rezvani.mesh.MeshServiceConnection
import com.rezvani.mesh.ui.screens.ContactsScreen
import com.rezvani.mesh.ui.screens.DiagnosticsScreen
import com.rezvani.mesh.ui.screens.MessagesScreen
import com.rezvani.mesh.ui.screens.SettingsScreen
import com.rezvani.mesh.ui.screens.StatusScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    meshConnection: MeshServiceConnection
) {
    NavHost(navController = navController, startDestination = "status") {
        composable("status") {
            StatusScreen(meshConnection)
        }
        composable("messages") {
            MessagesScreen(meshConnection)
        }
        composable("contacts") {
            ContactsScreen(meshConnection)
        }
        composable("settings") {
            SettingsScreen()
        }
        composable("diagnostics") {
            DiagnosticsScreen()
        }
    }
}