package com.rezvani.mesh

import android.app.Application
import com.rezvani.mesh.utils.CrashLogger

class RezvanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
