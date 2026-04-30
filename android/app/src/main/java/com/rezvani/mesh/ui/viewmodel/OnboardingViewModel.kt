import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

class OnboardingViewModel : ViewModel() {
    private val _uiState = mutableStateOf(OnboardingUiState())
    val uiState: State<OnboardingUiState> = _uiState

    fun nextStep() {
        _uiState.value = _uiState.value.copy(step = OnboardingStep.GENERATE)
    }

    fun previousStep() {
        _uiState.value = _uiState.value.copy(step = OnboardingStep.WELCOME)
    }

    fun generateIdentity(context: Context) {
        _uiState.value = _uiState.value.copy(isGenerating = true, errorMessage = null)
        try {
            val seed = IdentityBackupHelper.generateSeed()
            IdentityBackupHelper.saveSeed(context, seed)
            val mnemonic = IdentityBackupHelper.seedToMnemonic(seed)
            _uiState.value = _uiState.value.copy(
                isGenerating = false,
                mnemonicWords = mnemonic,
                step = OnboardingStep.BACKUP
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isGenerating = false,
                errorMessage = e.message ?: "Failed to generate identity"
            )
        }
    }

    fun showRestoreDialog() {
        _uiState.value = _uiState.value.copy(showRestoreDialog = true)
    }

    fun dismissRestoreDialog() {
        _uiState.value = _uiState.value.copy(showRestoreDialog = false)
    }

    fun goToRestoreStep() {
        _uiState.value = _uiState.value.copy(step = OnboardingStep.RESTORE)
    }

    fun updateMnemonicInput(input: String) {
        _uiState.value = _uiState.value.copy(mnemonicInput = input)
    }

    fun restoreIdentity(context: Context, mnemonic: String) {
        _uiState.value = _uiState.value.copy(isRestoring = true, errorMessage = null)
        val words = mnemonic.trim().split("\\s+".toRegex())
        val seed = IdentityBackupHelper.mnemonicToSeed(words)
        if (seed != null) {
            IdentityBackupHelper.saveSeed(context, seed)
            _uiState.value = _uiState.value.copy(isRestoring = false, step = OnboardingStep.BACKUP)
        } else {
            _uiState.value = _uiState.value.copy(
                isRestoring = false,
                errorMessage = "Invalid mnemonic. Check the 12 words and try again."
            )
        }
    }

    fun toggleConfirmBackup() {
        _uiState.value = _uiState.value.copy(hasConfirmedBackup = !_uiState.value.hasConfirmedBackup)
    }

    fun confirmBackup() {
        // Seed is already saved – mark onboarding as complete
    }
}
