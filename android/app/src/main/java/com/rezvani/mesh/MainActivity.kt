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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rezvani.mesh.backup.IdentityBackupHelper
import com.rezvani.mesh.backup.MacIdentityProvider
import com.rezvani.mesh.radio.RezvanRadioService
import com.rezvani.mesh.ui.navigation.NavGraph
import com.rezvani.mesh.ui.theme.RezvanMeshTheme
import com.rezvani.mesh.utils.CrashLogger
import com.rezvani.mesh.utils.LocaleHelper
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_LOCAL_MAC_ADDRESS = "android.permission.LOCAL_MAC_ADDRESS"
    }

    private var boundService: RezvanRadioService? = null
    private val isServiceBound = mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Location permission granted")
        }
        startRadioService()
    }

    private val macPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "MAC permission granted")
        }
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

        try {
            setContent {
                RezvanMeshTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = androidx.navigation.compose.rememberNavController()
                        NavGraph(navController = navController)
                    }
                }
            }
        } catch (e: Exception) {
            // Immediate error feedback
            val errorText = StringWriter().also {
                e.printStackTrace(PrintWriter(it))
            }.toString()
            CrashLogger.init(this)  // ensure logger
            Log.e(TAG, "Fatal error in setContent", e)
            val textView = TextView(this).apply {
                text = "FATAL ERROR\n\n$errorText"
                setTextColor(0xFFFF0000.toInt())
                textSize = 12f
                setPadding(32, 32, 32, 32)
            }
            setContentView(textView)
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
}
