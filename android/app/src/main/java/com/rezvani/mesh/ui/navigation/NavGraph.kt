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
            // OnboardingScreen expects: onEnterMesh: () -> Unit
            OnboardingScreen(
                onEnterMesh = {
                    navController.navigate("chats") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        composable("chats") {
            // ChatsScreen expects:
            // onConversationClick: (String, String) -> Unit
            // onNewMessageClick: () -> Unit
            // onNewChannelClick: () -> Unit
            // onEmergencyClick: () -> Unit
            ChatsScreen(
                onConversationClick = { conversationId, _ ->
                    navController.navigate("chat/$conversationId")
                },
                onNewMessageClick = {
                    // TODO: navigate to contact picker or new message screen
                },
                onNewChannelClick = {
                    navController.navigate("channels")
                },
                onEmergencyClick = {
                    navController.navigate("emergency")
                }
            )
        }

        composable("chat/{conversationId}") { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            // ChatDetailScreen expects:
            // conversationId: String, contactName: String, onNavigateBack: () -> Unit
            ChatDetailScreen(
                conversationId = conversationId,
                contactName = "", // placeholder, real name from ViewModel
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("channels") {
            // ChannelsScreen expects:
            // onChannelClick: (Int) -> Unit, onCreateChannel: () -> Unit
            ChannelsScreen(
                onChannelClick = { channelId ->
                    // TODO: navigate to channel detail or join channel
                },
                onCreateChannel = {
                    // TODO: show create channel dialog
                }
            )
        }

        composable("contacts") {
            // ContactsScreen expects:
            // onContactClick: (String) -> Unit, onAddContact: () -> Unit, onScanQrCode: () -> Unit
            ContactsScreen(
                onContactClick = { contactId ->
                    // TODO: open chat with this contact
                },
                onAddContact = {
                    // TODO: show add contact dialog
                },
                onScanQrCode = {
                    // TODO: launch QR scanner
                }
            )
        }

        composable("emergency") {
            // EmergencyScreen expects: onNavigateBack: () -> Unit
            EmergencyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            // SettingsScreen expects: onNavigateBack: () -> Unit
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
