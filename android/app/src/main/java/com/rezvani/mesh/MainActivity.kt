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

    private val isServiceBound = mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRadioService()
        } else {
            Log.w(TAG, "Precise location permission denied – Wi‑Fi Direct disabled")
            startRadioService()  // BLE may still work on Android 12+
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RezvanRadioService.LocalBinder
            val meshService = binder?.getService()
            if (meshService != null) {
                MeshServiceConnection.onServiceConnected(meshService)
                isServiceBound.value = true
                Log.i(TAG, "RezvanRadioService connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            MeshServiceConnection.onServiceDisconnected()
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
            startRadioService()
        } else {
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
