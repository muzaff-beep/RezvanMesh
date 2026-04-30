package com.rezvani.mesh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rezvani.mesh.ui.screens.*

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
                onConversationClick = { conversationId ->
                    navController.navigate("chat/$conversationId")
                },
                onNewMessageClick = {
                    // navigate to new message/contact picker
                },
                onNewChannelClick = {
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
                contactName = "", // placeholder, real name from ViewModel
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("channels") {
            ChannelsScreen(
                onChannelClick = { channelId -> /* handle channel click */ },
                onCreateChannel = { /* show create channel dialog */ },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("contacts") {
            ContactsScreen(
                onContactClick = { contactId -> /* open chat with contact */ },
                onAddContact = { /* show add contact dialog */ },
                onScanQrCode = { /* launch QR scanner */ },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("emergency") {
            EmergencyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
