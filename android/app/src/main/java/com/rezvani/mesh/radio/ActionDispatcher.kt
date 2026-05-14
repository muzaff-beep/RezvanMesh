// android/app/src/main/java/com/rezvani/mesh/ActionDispatcher.kt

package com.rezvani.mesh

import com.rezvani.mesh.rust.Action
import com.rezvani.mesh.utils.DiagLogger

object ActionDispatcher {
    fun dispatch(action: Action, meshConnection: MeshServiceConnection? = null) {
        when (action) {
            is Action.SendBleAdvertisement -> {
                // handled by RezvanRadioService tick
            }
            is Action.SendBlePacket -> {
                // handled by RezvanRadioService tick
            }
            is Action.DiagLog -> {
                DiagLogger.custom(action.tag, action.level, action.message)
            }
            else -> {
                // future actions
            }
        }
    }
}