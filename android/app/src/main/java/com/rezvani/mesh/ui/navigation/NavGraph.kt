package com.rezvani.mesh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.ui.screens.*
import com.rezvani.mesh.ui.viewmodel.OnboardingViewModel

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = "onboarding"
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("onboarding") {
            OnboardingScreen(
                onEnterMesh = {
                    navController.navigate("chats") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("chats") {
            ChatsScreen(
                onChatClick = { conversationId ->
                    navController.navigate("chat/$conversationId")
                },
                onChannelsClick = {
                    navController.navigate("channels")
                },
                onContactsClick = {
                    navController.navigate("contacts")
                },
                onEmergencyClick = {
                    navController.navigate("emergency")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }

        composable("chat/{conversationId}") { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            ChatDetailScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() }
            )
        }

        composable("channels") {
            ChannelsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("contacts") {
            ContactsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("emergency") {
            EmergencyScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
