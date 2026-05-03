package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.viewmodel.SettingsViewModel
import com.rezvani.mesh.utils.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showPowerDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCrashInfoDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // Identity section
            SettingsSection(title = stringResource(R.string.identity)) {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.node_id),
                    subtitle = uiState.nodeId
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Appearance section (Theme + Language)
            SettingsSection(title = stringResource(R.string.appearance)) {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = stringResource(R.string.theme),
                    subtitle = if (uiState.darkMode) stringResource(R.string.dark) else stringResource(R.string.light),
                    onClick = { viewModel.toggleDarkMode() }
                )
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.language),
                    subtitle = when (uiState.currentLanguage) {
                        "fa" -> stringResource(R.string.language_farsi)
                        else -> stringResource(R.string.language_english)
                    },
                    onClick = { showLanguageDialog = true }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Power & Battery section
            SettingsSection(title = stringResource(R.string.power_battery)) {
                SettingsItem(
                    icon = Icons.Default.BatteryStd,
                    title = stringResource(R.string.power_profile),
                    subtitle = when (uiState.powerOverride ?: uiState.autoPowerState) {
                        PowerState.EMERGENCY -> stringResource(R.string.power_emergency)
                        PowerState.ACTIVE -> stringResource(R.string.power_active)
                        PowerState.BALANCED -> stringResource(R.string.power_balanced)
                        PowerState.POWER_SAVER -> stringResource(R.string.power_saver)
                        PowerState.MINIMAL -> stringResource(R.string.power_minimal)
                        PowerState.HIBERNATION -> stringResource(R.string.power_hibernation)
                        PowerState.DEAD -> stringResource(R.string.power_dead)
                    },
                    onClick = { showPowerDialog = true }
                )
                if (uiState.powerOverride != null) {
                    TextButton(
                        onClick = { viewModel.clearPowerOverride() },
                        modifier = Modifier.padding(start = 72.dp)
                    ) {
                        Text(stringResource(R.string.reset_to_auto))
                    }
                }
                SettingsItem(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.system_power_settings),
                    subtitle = stringResource(R.string.system_power_settings_description),
                    onClick = { viewModel.openSystemPowerSettings() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Storage section
            SettingsSection(title = stringResource(R.string.storage)) {
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.storage_used),
                    subtitle = uiState.storageUsed
                )
                SettingsItem(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.clear_old_messages),
                    subtitle = stringResource(R.string.clear_messages_description),
                    onClick = { showClearDataDialog = true },
                    tint = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About section
            SettingsSection(title = stringResource(R.string.about)) {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.app_version),
                    subtitle = uiState.versionName,
                    onClick = { showAboutDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.build_info),
                    subtitle = "${uiState.versionCode} (${uiState.buildVariant})"
                )
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.crash_logs),
                    subtitle = stringResource(R.string.crash_logs_description),
                    onClick = { showCrashInfoDialog = true }
                )
            }
        }
    }

    // Language dialog (unchanged)
    if (showLanguageDialog) {
        AlertDialog(...)  // same as before
    }

    // Power profile dialog (unchanged)
    if (showPowerDialog) { ... }

    // Clear data dialog
    if (showClearDataDialog) { ... }

    // About dialog
    if (showAboutDialog) { ... }

    // Crash info dialog
    if (showCrashInfoDialog) { ... }
}

// Helper composables (SettingsSection, SettingsItem, getPowerStateDescription) remain unchanged.
