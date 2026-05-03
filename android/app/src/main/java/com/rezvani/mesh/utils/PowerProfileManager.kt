package com.rezvani.mesh.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.rezvani.mesh.ui.components.PowerState

object PowerProfileManager {

    fun applyPowerState(activity: Activity?, state: PowerState) {
        applyBrightness(activity, state)
        cancelVibrations(activity)
        // In the future we could also disable BLE/WiFi scanning from here,
        // but currently that's managed by the native engine.
    }

    fun applyBrightness(activity: Activity?, state: PowerState) {
        val brightness = when (state) {
            PowerState.EMERGENCY,
            PowerState.ACTIVE -> -1f   // system default
            PowerState.BALANCED -> 0.8f
            PowerState.POWER_SAVER -> 0.5f
            PowerState.MINIMAL,
            PowerState.HIBERNATION,
            PowerState.DEAD -> 0.25f
        }

        val layoutParams = activity?.window?.attributes
        layoutParams?.screenBrightness = brightness
        activity?.window?.attributes = layoutParams
    }

    fun cancelVibrations(context: Context?) {
        val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        vibrator?.cancel()
    }

    fun openBatterySaverSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    fun hasWriteSettingsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    fun requestWriteSettingsPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(activity)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = android.net.Uri.parse("package:${activity.packageName}")
                activity.startActivityForResult(intent, requestCode)
            }
        }
    }
}
