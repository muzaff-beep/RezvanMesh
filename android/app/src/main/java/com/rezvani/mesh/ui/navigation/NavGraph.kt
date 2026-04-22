package com.rezvani.mesh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rezvani.mesh.ui.screens.*
import com.rezvani.mesh.ui.viewmodel.MainViewModel

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Onboarding.route,
    viewModel: MainViewModel = viewModel()
) {
    val isOnboardingComplete by viewModel.isOnboardingComplete.collectAsState()

    val actualStartDestination = if (isOnboardingComplete) {
        Screen.Chats.route
    } else {
        Screen.Onboarding.route
    }

    NavHost(
        navController = navController,
        startDestination = actualStartDestination
    ) {
        // Onboarding
        composable(route = Screen.Onboarding.route) {
            OnboardingScreen(
                onOnboardingComplete = {
                    viewModel.setOnboardingComplete(true)
                    navController.navigate(Screen.Chats.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // Chats (Main)
        composable(route = Screen.Chats.route) {
            ChatsScreen(
                onConversationClick = { conversationId, contactName ->
                    navController.navigate(
                        Screen.ChatDetail.createRoute(conversationId, contactName)
                    )
                },
                onNewMessageClick = {
                    navController.navigate(Screen.Contacts.route)
                },
                onNewChannelClick = {
                    navController.navigate(Screen.Channels.route)
                },
                onEmergencyClick = {
                    navController.navigate(Screen.Emergency.route)
                }
            )
        }

        // Chat Detail
        composable(
            route = Screen.ChatDetail.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("contactName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
            val contactName = backStackEntry.arguments?.getString("contactName") ?: ""

            ChatDetailScreen(
                conversationId = conversationId,
                contactName = contactName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Channels
        composable(route = Screen.Channels.route) {
            ChannelsScreen(
                onChannelClick = { channelId ->
                    navController.navigate(
                        Screen.ChatDetail.createRoute(
                            "channel_$channelId",
                            "Channel #$channelId"
                        )
                    )
                },
                onCreateChannel = {
                    // Navigate to create channel screen (future implementation)
                }
            )
        }

        // Contacts
        composable(route = Screen.Contacts.route) {
            ContactsScreen(
                onContactClick = { nodeId ->
                    navController.navigate(
                        Screen.ChatDetail.createRoute(nodeId, nodeId)
                    )
                },
                onAddContact = {
                    // Show add contact dialog (future implementation)
                },
                onScanQrCode = {
                    // Launch QR scanner (future implementation)
                }
            )
        }

        // Emergency
        composable(route = Screen.Emergency.route) {
            EmergencyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Settings
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Chats : Screen("chats")
    object ChatDetail : Screen("chat_detail/{conversationId}/{contactName}") {
        fun createRoute(conversationId: String, contactName: String): String {
            return "chat_detail/$conversationId/$contactName"
        }
    }
    object Channels : Screen("channels")
    object Contacts : Screen("contacts")
    object Emergency : Screen("emergency")
    object Settings : Screen("settings")
}
