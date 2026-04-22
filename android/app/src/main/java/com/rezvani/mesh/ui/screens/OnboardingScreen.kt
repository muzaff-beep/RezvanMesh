package com.rezvani.mesh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rezvani.mesh.R
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.ui.components.LoadingOverlay
import com.rezvani.mesh.ui.viewmodel.OnboardingViewModel

/**
 * Three-step onboarding screen:
 * 1. Welcome message explaining mesh offline capability.
 * 2. Generate new identity (or restore from mnemonic).
 * 3. Display 12-word mnemonic for backup.
 */
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        bottomBar = {
            OnboardingBottomBar(
                uiState = uiState,
                onNext = {
                    viewModel.nextStep()
                },
                onBack = {
                    viewModel.previousStep()
                },
                onGenerateIdentity = {
                    viewModel.generateIdentity(context)
                },
                onRestoreIdentity = {
                    viewModel.showRestoreDialog()
                },
                onConfirmBackup = {
                    viewModel.confirmBackup()
                    onOnboardingComplete()
                }
            )
        }
    ) {
        paddingValues ->
        Box(
            modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Logo / Icon placeholder
                Surface(
                    modifier = Modifier.size(100.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "RV",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (uiState.step) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.GENERATE -> GenerateStep(
                        isGenerating = uiState.isGenerating,
                        errorMessage = uiState.errorMessage
                    )
                    OnboardingStep.RESTORE -> RestoreStep(
                        mnemonicInput = uiState.mnemonicInput,
                        onMnemonicChange = {
                            viewModel.updateMnemonicInput(it)
                        },
                        isRestoring = uiState.isRestoring,
                        errorMessage = uiState.errorMessage,
                        onRestore = {
                            viewModel.restoreIdentity(context, uiState.mnemonicInput)
                        }
                    )
                    OnboardingStep.BACKUP -> BackupStep(
                        mnemonicWords = uiState.mnemonicWords,
                        hasConfirmed = uiState.hasConfirmedBackup,
                        onToggleConfirm = {
                            viewModel.toggleConfirmBackup()
                        }
                    )
                }
            }

            if (uiState.isLoading) {
                LoadingOverlay(message = stringResource(R.string.please_wait))
            }
        }
    }

    // Restore dialog
    if (uiState.showRestoreDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissRestoreDialog()
            },
            title = {
                Text(stringResource(R.string.restore_identity))
            },
            text = {
                Column {
                    Text(stringResource(R.string.restore_identity_message))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.restore_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissRestoreDialog()
                        viewModel.goToRestoreStep()
                    }
                ) {
                    Text(stringResource(R.string.continue_text))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissRestoreDialog()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun WelcomeStep() {
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Text(
        text = stringResource(R.string.onboarding_welcome_message),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "📡 " + stringResource(R.string.offline_mesh_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.offline_mesh_description),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun GenerateStep(
    isGenerating: Boolean,
    errorMessage: String?
) {
    Text(
        text = stringResource(R.string.generate_identity_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Text(
        text = stringResource(R.string.generate_identity_message),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (isGenerating) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
    }

    if (errorMessage != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun RestoreStep(
    mnemonicInput: String,
    onMnemonicChange: (String) -> Unit,
    isRestoring: Boolean,
    errorMessage: String?,
    onRestore: () -> Unit
) {
    Text(
        text = stringResource(R.string.restore_identity_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Text(
        text = stringResource(R.string.restore_identity_instructions),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedTextField(
        value = mnemonicInput,
        onValueChange = onMnemonicChange,
        modifier = Modifier
        .fillMaxWidth()
        .height(120.dp),
        placeholder = {
            Text(stringResource(R.string.enter_12_words))
        },
        minLines = 3,
        maxLines = 5,
        enabled = !isRestoring
    )

    if (errorMessage != null) {
        Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    Button(
        onClick = onRestore,
        enabled = mnemonicInput.isNotBlank() && !isRestoring,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isRestoring) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(stringResource(R.string.restore))
        }
    }
}

@Composable
fun BackupStep(
    mnemonicWords: List<String>,
    hasConfirmed: Boolean,
    onToggleConfirm: () -> Unit
) {
    Text(
        text = stringResource(R.string.backup_identity_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Text(
        text = stringResource(R.string.backup_identity_warning),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error
    )

    // Mnemonic display grid (3 columns x 4 rows)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            mnemonicWords.chunked(3).forEach {
                rowWords ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowWords.forEachIndexed {
                        index, word ->
                        val wordNumber = mnemonicWords.indexOf(word) + 1
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$wordNumber.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = word,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (index < rowWords.size - 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    // Fill empty slots if row has less than 3 words
                    repeat(3 - rowWords.size) {
                        if (it > 0) Spacer(modifier = Modifier.width(8.dp))
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    Text(
        text = stringResource(R.string.backup_instructions),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Confirmation checkbox
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = hasConfirmed,
            onCheckedChange = {
                onToggleConfirm()
            }
        )
        Text(
            text = stringResource(R.string.i_have_saved_my_recovery_phrase),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun OnboardingBottomBar(
    uiState: OnboardingUiState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onGenerateIdentity: () -> Unit,
    onRestoreIdentity: () -> Unit,
    onConfirmBackup: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            when (uiState.step) {
                OnboardingStep.WELCOME -> {
                    Spacer(modifier = Modifier.weight(1f))
                    Row {
                        TextButton(onClick = onRestoreIdentity) {
                            Text(stringResource(R.string.restore))
                        }
                        Button(onClick = onNext) {
                            Text(stringResource(R.string.get_started))
                        }
                    }
                }
                OnboardingStep.GENERATE -> {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                    Button(
                        onClick = onGenerateIdentity,
                        enabled = !uiState.isGenerating
                    ) {
                        Text(stringResource(R.string.generate))
                    }
                }
                OnboardingStep.RESTORE -> {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back))
                    }
                    TextButton(onClick = onGenerateIdentity) {
                        Text(stringResource(R.string.create_new))
                    }
                }
                OnboardingStep.BACKUP -> {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = onConfirmBackup,
                        enabled = uiState.hasConfirmedBackup
                    ) {
                        Text(stringResource(R.string.finish))
                    }
                }
            }
        }
    }
}

enum class OnboardingStep {
    WELCOME, GENERATE, RESTORE, BACKUP
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val isRestoring: Boolean = false,
    val mnemonicWords: List<String> = emptyList(),
    val mnemonicInput: String = "",
    val hasConfirmedBackup: Boolean = false,
    val errorMessage: String? = null,
    val showRestoreDialog: Boolean = false
)
