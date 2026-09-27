package com.alliot.osmo.demo.session.model

enum class ProtocolFamily {
    DJI_RSDK_ACTION,
    POCKET3_DUML,
    UNKNOWN,
}

enum class CameraFamily {
    ACTION_4,
    ACTION_5_PRO,
    ACTION_6,
    OSMO_360,
    POCKET_3,
    UNKNOWN,
}

enum class StatusPresentationStyle {
    ACTION_STANDARD,
    OSMO_360,
    DEBUG_ONLY,
}

data class DeviceCapabilities(
    val supportsWorkbench: Boolean,
    val supportsRecordKey: Boolean,
    val supportsDirectRecord: Boolean,
    val supportsModeSwitch: Boolean,
    val supportedModes: Set<Int>,
    val supportsGpsPush: Boolean,
    val supportsSleep: Boolean,
    val supportsWake: Boolean,
    val supportsWakeAndSnapshot: Boolean,
    val supportsVersionQuery: Boolean,
    val supportsStateSubscribe: Boolean = false,
    val supportsReboot: Boolean = false,
    val supportsQsKey: Boolean = false,
    val supportsSnapshotKey: Boolean = false,
    val supportsDebugConsole: Boolean,
    val statusPresentationStyle: StatusPresentationStyle,
)

data class DeviceProfile(
    val protocolFamily: ProtocolFamily,
    val cameraFamily: CameraFamily,
    val deviceId: Long?,
    val productId: String?,
    val displayName: String,
    val capabilities: DeviceCapabilities,
)

enum class SessionTransportMode {
    FAKE,
    REAL,
}

enum class HandshakeStage {
    IDLE,
    REQUEST_SENT,
    CAMERA_CONFIRMATION_RECEIVED,
    COMPLETED,
    REJECTED,
}

enum class LogCategory {
    BLE,
    TX,
    RX,
    STATE,
    ERROR,
}

data class SessionDevice(
    val name: String,
    val macAddress: String,
    val deviceId: Long,
    val inferredProtocolFamily: ProtocolFamily = ProtocolFamily.UNKNOWN,
    val inferredCameraFamily: CameraFamily = CameraFamily.UNKNOWN,
    val workbenchSupported: Boolean = true,
)

/** The camera's own AP credentials, read over BLE (0x07/0x07 SSID, 0x07/0x0e password). */
data class WifiCredentials(
    val ssid: String,
    val password: String,
    /** The Osmo 360 AP is WPA3-SAE; every other body is WPA2-PSK. */
    val wpa3: Boolean = false,
)

data class CameraStatusSnapshot(
    val mode: Int = 0x01,
    val state: Int = 0x01,
    val modeLabel: String = "Video",
    val stateLabel: String = "Preview",
    val recording: Boolean = false,
    val videoResolution: Int = 0,
    val fpsIndex: Int = 0,
    val eisMode: Int = 0,
    val recordTimeSeconds: Int = 0,
    val photoRatio: Int = 0,
    val realTimeCountdownSeconds: Int = 0,
    val timelapseIntervalDeciSeconds: Int = 0,
    val timelapseDurationSeconds: Int = 0,
    val powerMode: Int = 0,
    val powerModeLabel: String = "Awake",
    val batteryPercent: Int = 100,
    val remainCapacityMb: Long = 0,
    val remainPhotoCount: Long = 0,
    val remainTimeSeconds: Long = 0,
    val userMode: Int = 0,
    val cameraModeNextFlag: Int = 0,
    val temperatureState: Int = 0,
    val photoCountdownMilliseconds: Long = 0,
    val loopRecordSeconds: Int = 0,
    val detail: String = "Idle",
    val modeName: String = "",
    val modeParameters: String = "",
    val lastPushCommandId: String = "-",
    val lastPushSummary: String = "No push yet",
)

data class SessionStatus(
    val mode: SessionTransportMode,
    val scanning: Boolean = false,
    val connectedDevice: SessionDevice? = null,
    val connectedProfile: DeviceProfile? = null,
    val handshakeStage: HandshakeStage = HandshakeStage.IDLE,
    val handshakeVerifyMode: Int = 1,
    val handshakeVerifyCode: Int? = null,
    val protocolReady: Boolean = false,
    val sleeping: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val wakeAdvertisingSupported: Boolean = false,
    val controllerDeviceId: Long = 0x12345678L,
    val controllerMacAddress: String? = null,
    val latestError: String? = null,
    val latestVersion: String? = null,
    val gpsPushActive: Boolean = false,
    val gpsAutoPushEnabled: Boolean = true,
    val gpsAutoPushHz: Int = 10,
    val gpsLocationRequestHz: Int = 1,
    val lastVersionResult: String? = null,
    val lastSubscribeResult: String? = null,
    val lastModeSwitchResult: String? = null,
    val lastRecordResult: String? = null,
    val lastSleepResult: String? = null,
    val lastWakeResult: String? = null,
    val lastGpsResult: String? = null,
    val lastKeyReportResult: String? = null,
    val lastRebootResult: String? = null,
    val gpsSignalLocked: Boolean = false,
    val lastGpsCoordinate: String? = null,
    val lastGpsAltitudeMeters: Double? = null,
    val lastGpsSpeedMps: Float? = null,
    val lastGpsBearingDegrees: Float? = null,
    val lastGpsAccuracyMeters: Float? = null,
    val lastGpsProvider: String? = null,
    val lastGpsSource: String? = null,
    val lastGpsSentAtMs: Long? = null,
)
