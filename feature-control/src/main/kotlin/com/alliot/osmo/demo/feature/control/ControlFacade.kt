package com.alliot.osmo.demo.feature.control

import com.alliot.osmo.demo.session.SessionController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ControlFacade(
    private val sessionController: SessionController,
) {
    val state: Flow<ControlState> = sessionController.status.map { status ->
        val snapshot = sessionController.cameraStatus.value
        ControlState(
            canSendCommands = status.protocolReady,
            recording = snapshot.recording,
            currentMode = snapshot.mode,
            lastResult = status.latestError ?: snapshot.detail,
        )
    }

    suspend fun toggleRecording() = sessionController.toggleRecording()
    suspend fun switchMode(mode: Int) = sessionController.switchMode(mode)
    suspend fun requestVersion() = sessionController.requestVersion()
    suspend fun subscribeStatus() = sessionController.subscribeStatus()
    suspend fun sleep() = sessionController.sleep()
    suspend fun wake() = sessionController.wake()
    suspend fun wakeViaGatt() = sessionController.wakeViaGatt()
}
