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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "Location permission granted")
        else Log.w(TAG, "Location permission denied – Wi‑Fi Direct disabled")
        startRadioServiceIfReady()
    }

    private val bluetoothScanPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "Bluetooth scan permission granted")
        else Log.w(TAG, "Bluetooth scan permission denied – BLE scanning disabled")
        startRadioServiceIfReady()
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
            // Small delay to let EncryptedSharedPreferences finish writing
            delay(100)
            val seed = IdentityBackupHelper.loadSeed(this@MainActivity)
            if (seed != null) {
                DiagLogger.log(this@MainActivity, "Seed verified before starting service")
            } else {
                DiagLogger.log(this@MainActivity, "ERROR: Seed missing after save!")
            }
            startRadioServiceIfReady()
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
            if (!hasBluetoothScanPermission()) {
                bluetoothScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMacPermission()) {
            macPermissionLauncher.launch(PERMISSION_LOCAL_MAC_ADDRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PowerProfileManager.hasWriteSettingsPermission(this)) {
            PowerProfileManager.requestWriteSettingsPermission(this, REQUEST_WRITE_SETTINGS)
        }
    }

    private fun startRadioServiceIfReady() {
        val seed = IdentityBackupHelper.loadSeed(this)
        if (seed != null && hasLocationPermission()) {
            startRadioService()
        }
    }

    private fun startRadioService() {
        try {
            val intent = Intent(this, RezvanRadioService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start radio service", e)
        }
    }

    private fun unbindRadioService() {
        if (isServiceBound.value) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isServiceBound.value = false
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
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
