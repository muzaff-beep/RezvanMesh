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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.backup.MacIdentityProvider
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.ui.navigation.NavGraph
import com.rezvani.mesh.ui.theme.RezvanMeshTheme
import com.rezvani.mesh.utils.LocaleHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var boundService: RezvanRadioService? = null
    private val isServiceBound: MutableState<Boolean> = mutableStateOf(false)
    private val PERMISSION_LOCAL_MAC_ADDRESS = "android.permission.LOCAL_MAC_ADDRESS"

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
                Log.i(TAG, "RezvanRadioService connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            MeshServiceConnection.onServiceDisconnected()
            boundService = null
            isServiceBound.value = false
            Log.i(TAG, "RezvanRadioService disconnected")
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

        setContent {
            RezvanMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindRadioService()
    }

    private fun requestAllPermissions() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMacPermission()) {
            macPermissionLauncher.launch(PERMISSION_LOCAL_MAC_ADDRESS)
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
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isServiceBound.value = false
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMacPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, PERMISSION_LOCAL_MAC_ADDRESS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureIdentityExists() {
        val existingSeed = IdentityBackupHelper.loadSeed(this)
        if (existingSeed == null) {
            Log.i(TAG, "No identity found — deriving from MAC")
            val seed = if (hasMacPermission()) {
                MacIdentityProvider.deriveSeed(this)
            } else null
            if (seed != null) {
                MacIdentityProvider.saveSeed(this, seed)
            } else {
                val randomSeed = ByteArray(32)
                java.security.SecureRandom().nextBytes(randomSeed)
                MacIdentityProvider.saveSeed(this, randomSeed)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
