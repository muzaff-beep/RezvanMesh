// android/app/src/main/java/com/rezvani/mesh/ui/NavGraph.kt

package com.rezvani.mesh.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rezvani.mesh.MeshServiceConnection

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