package com.rezvani.mesh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.backup.MacIdentityProvider
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.ui.navigation.MainScreenWithBottomNav
import com.rezvani.mesh.ui.theme.RezvanMeshTheme
import com.rezvani.mesh.utils.LocaleHelper
import com.rezvani.mesh.utils.PowerProfileManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var boundService: RezvanRadioService? = null
    private val isServiceBound: MutableState<Boolean> = mutableStateOf(false)
    private val PERMISSION_LOCAL_MAC_ADDRESS = "android.permission.LOCAL_MAC_ADDRESS"
    private val REQUEST_WRITE_SETTINGS = 1001

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "Location permission granted")
        else Log.w(TAG, "Location permission denied – Wi‑Fi Direct disabled")
        startRadioService()
    }

    private val macPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "MAC permission granted")
        else Log.w(TAG, "MAC permission denied – using random seed fallback")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RezvanRadioService.LocalBinder
            boundService = binder?.getService()
            if (boundService != null) {
                MeshServiceConnection.onServiceConnected(boundService!!)
                isServiceBound.value = true
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            MeshServiceConnection.onServiceDisconnected()
            boundService = null
            isServiceBound.value = false
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = LocaleHelper.getSavedLanguage(newBase)
        val context = LocaleHelper.setLocale(newBase, savedLanguage)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAllPermissions()

        lifecycleScope.launch {
            ensureIdentityExists()
        }

        lifecycleScope.launch {
            val seed = IdentityBackupHelper.loadSeed(this@MainActivity)
            if (seed != null && hasLocationPermission()) {
                startRadioService()
            }
        }

        // Apply initial power state
        applyPowerStateFromSettings()

        setContent {
            val darkMode by prefsFlow()   // observe dark mode preference
            RezvanMeshTheme(darkTheme = darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreenWithBottomNav()
                }
            }
        }
    }

    @Composable
    fun prefsFlow(): State<Boolean> {
        // This could be replaced with a proper StateFlow from ViewModel
        val prefs = remember { getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE) }
        var dark by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
        // Listen for changes (simplified – real app would use OnPreferenceChangeListener)
        return mutableStateOf(dark)
    }

    private fun applyPowerStateFromSettings() {
        val prefs = getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
        val powerOverride = prefs.getString("power_override", null)?.let { PowerState.valueOf(it) }
        if (powerOverride != null) {
            PowerProfileManager.applyPowerState(this, powerOverride)
        }
    }

    private fun requestAllPermissions() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMacPermission()) {
            macPermissionLauncher.launch(PERMISSION_LOCAL_MAC_ADDRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PowerProfileManager.hasWriteSettingsPermission(this)) {
            PowerProfileManager.requestWriteSettingsPermission(this, REQUEST_WRITE_SETTINGS)
        }
    }

    private fun startRadioService() { ... } // unchanged

    private fun unbindRadioService() { ... } // unchanged

    private fun hasLocationPermission(): Boolean { ... } // unchanged

    private fun hasMacPermission(): Boolean { ... } // unchanged

    private suspend fun ensureIdentityExists() { ... } // unchanged

    companion object {
        private const val TAG = "MainActivity"
    }
}
