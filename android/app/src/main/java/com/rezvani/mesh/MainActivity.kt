package com.rezvani.mesh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.ui.navigation.NavGraph
import com.rezvani.mesh.ui.theme.RezvanMeshTheme
import com.rezvani.mesh.utils.CrashLogger
import com.rezvani.mesh.utils.LocaleHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var boundService: RezvanRadioService? = null
    private val isServiceBound = mutableStateOf(false)

    // Only request ACCESS_FINE_LOCATION
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Location permission granted – starting mesh service")
        } else {
            Log.w(TAG, "Location permission denied – Wi‑Fi Direct disabled")
        }
        startRadioService()
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
        CrashLogger.init(this)

        if (hasLocationPermission()) {
            // Already granted, start immediately
            startRadioService()
        } else {
            // Request – launcher will start service after result
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        lifecycleScope.launch {
            ensureIdentityExists()
        }

        setContent {
            RezvanMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
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

    /**
     * Called when the onboarding flow successfully creates an identity.
     * The service is already running; this hands the seed to initialise the mesh engine.
     */
    fun onOnboardingComplete() {
        val seed = IdentityBackupHelper.loadSeed(this)
        if (seed != null) {
            boundService?.initializeMeshEngine(seed)
            Log.i(TAG, "Mesh engine initialised with identity seed")
        } else {
            Log.w(TAG, "onOnboardingComplete called but no seed found")
        }
    }

    private fun startRadioService() {
        val intent = Intent(this, RezvanRadioService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindRadioService() {
        if (isServiceBound.value) {
            unbindService(serviceConnection)
            isServiceBound.value = false
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureIdentityExists() {
        val seed = IdentityBackupHelper.loadSeed(this)
        if (seed == null) {
            Log.i(TAG, "No identity found - onboarding required")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
