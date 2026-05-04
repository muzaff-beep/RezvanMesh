package com.rezvani.mesh.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.rezvani.mesh.PowerState  // unified import

object PowerProfileManager {

    fun applyPowerState(activity: Activity?, state: PowerState) {
        applyBrightness(activity, state)
        cancelVibrations(activity)
    }

    fun applyBrightness(activity: Activity?, state: PowerState) {
        val brightness = when (state) {
            PowerState.EMERGENCY,
            PowerState.ACTIVE -> -1f
            PowerState.BALANCED -> 0.8f
            PowerState.POWER_SAVER -> 0.5f
            PowerState.MINIMAL,
            PowerState.HIBERNATION,
            PowerState.DEAD -> 0.25f
        }
        activity?.window?.attributes = activity?.window?.attributes?.apply {
            screenBrightness = brightness
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = android.net.Uri.parse("package:${activity.packageName}")
            activity.startActivityForResult(intent, requestCode)
        }
    }
}
