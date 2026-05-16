package com.rezvani.mesh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import com.rezvani.mesh.utils.DiagLogger
import com.rezvani.mesh.utils.LocaleHelper
import com.rezvani.mesh.utils.PowerProfileManager
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private var boundService: RezvanRadioService? = null
    private val isServiceBound: MutableState<Boolean> = mutableStateOf(false)
    private val PERMISSION_LOCAL_MAC_ADDRESS = "android.permission.LOCAL_MAC_ADDRESS"
    private val REQUEST_WRITE_SETTINGS = 1001

    // Track whether the service has been started so we don't start it twice
    private var serviceStarted = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "Location permission granted")
        else Log.w(TAG, "Location permission denied – Wi‑Fi Direct disabled")
        tryStartRadioService()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "Bluetooth permission granted")
        else Log.w(TAG, "Bluetooth permission denied – BLE features disabled")
        tryStartRadioService()
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

        // Log device info (MIUI detection)
        DiagLogger.log(this, "Manufacturer: ${Build.MANUFACTURER}, Model: ${Build.MODEL}")
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            DiagLogger.log(this, "MIUI detected — verify Autostart + Battery unrestricted")
        }

        lifecycleScope.launch {
            ensureIdentityExists()
            delay(100)
            val seed = IdentityBackupHelper.loadSeed(this@MainActivity)
            if (seed != null) {
                DiagLogger.log(this@MainActivity, "Seed verified before starting service")
            } else {
                DiagLogger.log(this@MainActivity, "ERROR: Seed missing after save!")
            }
            tryStartRadioService()
        }

        val prefs = getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE)
        val powerOverride = prefs.getString("power_override", null)?.let { PowerState.valueOf(it) }
        if (powerOverride != null) {
            PowerProfileManager.applyPowerState(this, powerOverride)
        }

        setContent {
            val darkMode = getDarkModeState()
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
    fun getDarkModeState(): Boolean {
        val prefs = remember { getSharedPreferences("rezvan_settings", Context.MODE_PRIVATE) }
        var dark by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
        return dark
    }

    private fun requestAllPermissions() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBluetoothPermission()) {
                // Request BLUETOOTH_SCAN – on API 31+ this also grants
                // BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (same permission group).
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMacPermission()) {
            macPermissionLauncher.launch(PERMISSION_LOCAL_MAC_ADDRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PowerProfileManager.hasWriteSettingsPermission(this)) {
            PowerProfileManager.requestWriteSettingsPermission(this, REQUEST_WRITE_SETTINGS)
        }
    }

    /**
     * Starts the radio service only when all required permissions are granted and a seed exists.
     * Safe to call multiple times – it will only start the service once.
     */
    private fun tryStartRadioService() {
        if (serviceStarted) return
        val seed = IdentityBackupHelper.loadSeed(this)
        if (seed == null) {
            DiagLogger.log(this, "Service start deferred: no identity seed yet")
            return
        }
        if (!hasAllRadioPermissions()) {
            DiagLogger.log(this, "Service start deferred: waiting for permissions")
            return
        }
        serviceStarted = true
        startRadioService()
    }

    private fun startRadioService() {
        try {
            val intent = Intent(this, RezvanRadioService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start radio service", e)
            serviceStarted = false
        }
    }

    private fun hasAllRadioPermissions(): Boolean {
        if (!hasLocationPermission()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBluetoothPermission()) return false
        }
        return true
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMacPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, PERMISSION_LOCAL_MAC_ADDRESS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureIdentityExists() {
        val existingSeed = withContext(Dispatchers.IO) {
            IdentityBackupHelper.loadSeed(this@MainActivity)
        }
        if (existingSeed == null) {
            Log.i(TAG, "No identity found — deriving from MAC")
            val seed = if (hasMacPermission()) {
                MacIdentityProvider.deriveSeed(this)
            } else null
            val finalSeed = seed ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            MacIdentityProvider.saveSeed(this, finalSeed)
            DiagLogger.log(this, "Identity seed saved")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
