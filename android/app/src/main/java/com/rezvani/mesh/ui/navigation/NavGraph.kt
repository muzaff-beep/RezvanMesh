package com.rezvani.mesh.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rezvani.mesh.ui.screens.*

@Composable
fun MainScreenWithBottomNav() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { BottomNavigationBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "onboarding",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("onboarding") {
                OnboardingScreen(onEnterMesh = {
                    navController.navigate("status") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                })
            }
            composable("status") {
                StatusScreen()
            }
            composable("chats") {
                ChatsScreen(
                    onConversationClick = { _, _ -> },
                    onNewMessageClick = {},
                    onNewChannelClick = { navController.navigate("channels") },
                    onEmergencyClick = { navController.navigate("emergency") }
                )
            }
            composable("channels") {
                ChannelsScreen(
                    onChannelClick = {},
                    onCreateChannel = {}
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
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val items = listOf(
        BottomNavItem("status", "Status"),
        BottomNavItem("chats", "Chats"),
        BottomNavItem("channels", "Channels"),
        BottomNavItem("emergency", "Emergency")
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        items.forEach { item ->
            NavigationBarItem(
                icon = {
                    when (item.route) {
                        "status" -> Icon(Icons.Default.Home, contentDescription = null)
                        "chats" -> Icon(Icons.Default.Chat, contentDescription = null)
                        "channels" -> Icon(Icons.Default.Groups, contentDescription = null)
                        "emergency" -> Icon(Icons.Default.Warning, contentDescription = null)
                    }
                },
                label = { Text(item.label) },
                selected = currentRoute == item.route,
                onClick = {
                    if (currentRoute != item.route) {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}

data class BottomNavItem(val route: String, val label: String)
