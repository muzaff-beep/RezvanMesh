package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.rezvani.mesh.ui.components.ConfirmationDialog
import com.rezvani.mesh.ui.components.PowerState
import com.rezvani.mesh.ui.viewmodel.SettingsViewModel
import com.rezvani.mesh.utils.CrashLogger
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
    var showCrashLogDialog by remember { mutableStateOf(false) }

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
            SettingsSection(title = stringResource(R.string.identity)) {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.node_id),
                    subtitle = uiState.nodeId
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSection(title = stringResource(R.string.language_region)) {
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
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

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
                    title = "Crash Log",
                    subtitle = "View crash history",
                    onClick = { showCrashLogDialog = true }
                )
            }
        }
    }

    // Language dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.select_language)) },
            text = {
                Column {
                    listOf(
                        "fa" to stringResource(R.string.language_farsi),
                        "en" to stringResource(R.string.language_english)
                    ).forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setLanguage(code)
                                    LocaleHelper.saveLanguage(context, code)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.currentLanguage == code,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Power profile dialog
    if (showPowerDialog) {
        AlertDialog(
            onDismissRequest = { showPowerDialog = false },
            title = { Text(stringResource(R.string.select_power_profile)) },
            text = {
                Column {
                    PowerState.values().forEach { state ->
                        if (state != PowerState.DEAD) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setPowerOverride(state)
                                        showPowerDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.powerOverride == state,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(text = when (state) {
                                        PowerState.EMERGENCY -> stringResource(R.string.power_emergency)
                                        PowerState.ACTIVE -> stringResource(R.string.power_active)
                                        PowerState.BALANCED -> stringResource(R.string.power_balanced)
                                        PowerState.POWER_SAVER -> stringResource(R.string.power_saver)
                                        PowerState.MINIMAL -> stringResource(R.string.power_minimal)
                                        PowerState.HIBERNATION -> stringResource(R.string.power_hibernation)
                                        PowerState.DEAD -> stringResource(R.string.power_dead)
                                    })
                                    Text(
                                        text = getPowerStateDescription(state),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.powerOverride != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.clearPowerOverride()
                                    showPowerDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.auto_adaptive),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPowerDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Clear data confirmation
    if (showClearDataDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.clear_old_messages),
            message = stringResource(R.string.clear_messages_confirmation),
            confirmText = stringResource(R.string.clear),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.clearOldMessages()
                showClearDataDialog = false
            },
            onDismiss = { showClearDataDialog = false },
            isDestructive = true
        )
    }

    // About dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.about_rezvan_mesh)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.about_description))
                    Text(
                        text = stringResource(R.string.version_format, uiState.versionName),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.open_source_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Crash log dialog
    if (showCrashLogDialog) {
        val crashText = remember { CrashLogger.getCrashLog() }
        AlertDialog(
            onDismissRequest = { showCrashLogDialog = false },
            title = { Text("Crash Log") },
            text = {
                Column {
                    if (crashText == "No crashes recorded") {
                        Text(
                            text = crashText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = crashText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = {
                        CrashLogger.clearCrashLog()
                        showCrashLogDialog = false
                    }) {
                        Text("Clear")
                    }
                    TextButton(onClick = { showCrashLogDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun getPowerStateDescription(state: PowerState): String {
    return when (state) {
        PowerState.EMERGENCY -> "2-4 hours battery, maximum range"
        PowerState.ACTIVE -> "4-6 hours, high performance"
        PowerState.BALANCED -> "8-12 hours, default"
        PowerState.POWER_SAVER -> "24-36 hours, reduced range"
        PowerState.MINIMAL -> "48+ hours, listen only"
        PowerState.HIBERNATION -> "7+ days, beacon only"
        PowerState.DEAD -> "Critical reserve"
    }
}
