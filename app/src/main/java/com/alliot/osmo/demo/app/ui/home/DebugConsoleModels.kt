package com.alliot.osmo.demo.app.ui.home

import com.alliot.osmo.demo.session.log.SessionLogEntry
import com.alliot.osmo.demo.session.model.DeviceCapabilities
import com.alliot.osmo.demo.session.model.LogCategory
import com.alliot.osmo.demo.session.model.ProtocolFamily
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.SessionTransportMode
import java.util.Locale

data class ManualHexValidation(
    val normalizedHex: String?,
    val errorMessage: String?,
) {
    val isValid: Boolean
        get() = normalizedHex != null && errorMessage == null
}

data class DebugLogGroup(
    val category: LogCategory,
    val entries: List<SessionLogEntry>,
    val expandedByDefault: Boolean,
)

enum class DebugConsoleActionType {
    VERSION_QUERY,
    REBOOT,
    TOGGLE_RECORD,
    SUBSCRIBE_STATUS,
    SLEEP,
    WAKE,
    SWITCH_MODE,
    WAKE_AND_SNAPSHOT,
    GATT_WAKE,
    RECORD_KEY,
    QS_KEY,
    SNAPSHOT_KEY,
}

data class DebugConsoleActionUiModel(
    val actionType: DebugConsoleActionType,
    val label: String,
    val enabled: Boolean,
    val mode: Int? = null,
)

data class DebugConsoleUiModel(
    val capabilityNotice: String?,
    val coreActions: List<DebugConsoleActionUiModel>,
    val modeActions: List<DebugConsoleActionUiModel>,
    val auxiliaryActions: List<DebugConsoleActionUiModel>,
    val keyActions: List<DebugConsoleActionUiModel>,
    val showGpsSection: Boolean,
    val gpsControlsEnabled: Boolean,
)

data class DebugConsoleDeviceRowUiModel(
    val name: String,
    val macAddress: String,
    val capabilityLabel: String,
    val isConnected: Boolean,
)

fun validateManualHexInput(input: String): ManualHexValidation {
    val compact = input.trim().replace("\\s+".toRegex(), "")
    if (compact.isEmpty()) {
        return ManualHexValidation(
            normalizedHex = null,
            errorMessage = "请输入 HEX 指令",
        )
    }
    if (!compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return ManualHexValidation(
            normalizedHex = null,
            errorMessage = "仅支持十六进制字符和空格",
        )
    }
    if (compact.length % 2 != 0) {
        return ManualHexValidation(
            normalizedHex = null,
            errorMessage = "HEX 必须按完整字节成对输入",
        )
    }
    return ManualHexValidation(
        normalizedHex = compact.uppercase(Locale.US),
        errorMessage = null,
    )
}

fun groupDebugLogs(
    logs: List<SessionLogEntry>,
    clearedAtMillis: Long = Long.MIN_VALUE,
): List<DebugLogGroup> {
    val visibleLogs = logs.filter { it.timestampMillis > clearedAtMillis }
    val orderedCategories = listOf(LogCategory.ERROR) + LogCategory.entries.filterNot { it == LogCategory.ERROR }
    return orderedCategories.mapNotNull { category ->
        val entries = visibleLogs
            .filter { it.category == category }
            .sortedByDescending { it.timestampMillis }
        if (entries.isEmpty()) {
            null
        } else {
            DebugLogGroup(
                category = category,
                entries = entries,
                expandedByDefault = category == LogCategory.ERROR,
            )
        }
    }
}

fun mapDebugConsoleUiModel(state: DebugHomeState): DebugConsoleUiModel {
    val session = state.sessionStatus
    val capabilities = session.connectedProfile?.capabilities
    val busy = state.busyAction != null
    val canSendProtocol = state.selectedMode == SessionTransportMode.FAKE || session.protocolReady
    val baseControlEnabled = !busy && canSendProtocol
    val wakeEnabled = !busy && canWakeFromDebugConsole(state)
    val gattWakeEnabled = !busy && (state.selectedMode == SessionTransportMode.FAKE ||
        (session.connectedDevice != null &&
            state.prerequisites.bluetoothPermissionsGranted &&
            session.bluetoothEnabled))
    val presetCommandsAvailable = capabilities?.supportsDebugConsole ?: true

    if (!presetCommandsAvailable) {
        return DebugConsoleUiModel(
            capabilityNotice = "当前设备未提供预置调试命令，请改用日志和手动报文工具。",
            coreActions = emptyList(),
            modeActions = emptyList(),
            auxiliaryActions = emptyList(),
            keyActions = emptyList(),
            showGpsSection = false,
            gpsControlsEnabled = false,
        )
    }

    val coreActions = buildList {
        addIfSupported(DebugConsoleActionType.VERSION_QUERY, "版本查询", capabilities, DeviceCapabilities::supportsVersionQuery, baseControlEnabled)
        addIfSupported(DebugConsoleActionType.REBOOT, "重启相机", capabilities, DeviceCapabilities::supportsReboot, baseControlEnabled)
        addIfSupported(DebugConsoleActionType.TOGGLE_RECORD, "拍录键", capabilities, DeviceCapabilities::supportsDirectRecord, baseControlEnabled)
        addIfSupported(DebugConsoleActionType.SUBSCRIBE_STATUS, "订阅状态", capabilities, DeviceCapabilities::supportsStateSubscribe, baseControlEnabled)
        addIfSupported(DebugConsoleActionType.SLEEP, "休眠", capabilities, DeviceCapabilities::supportsSleep, baseControlEnabled)
        addIfSupported(DebugConsoleActionType.WAKE, "唤醒", capabilities, DeviceCapabilities::supportsWake, wakeEnabled)
        addIfSupported(DebugConsoleActionType.GATT_WAKE, "GATT 唤醒", capabilities, DeviceCapabilities::supportsWake, gattWakeEnabled)
    }
    val modeActions = buildList {
        if (capabilities == null || capabilities.supportsModeSwitch) {
            val supportedModes = capabilities?.supportedModes?.takeIf { it.isNotEmpty() } ?: linkedSetOf(0x01, 0x05)
            supportedModes.forEach { mode ->
                add(
                    DebugConsoleActionUiModel(
                        actionType = DebugConsoleActionType.SWITCH_MODE,
                        label = workbenchModeLabel(mode),
                        enabled = baseControlEnabled,
                        mode = mode,
                    ),
                )
            }
        }
    }
    val auxiliaryActions = buildList {
        if (session.sleeping && (capabilities == null || capabilities.supportsWakeAndSnapshot)) {
            add(
                DebugConsoleActionUiModel(
                    actionType = DebugConsoleActionType.WAKE_AND_SNAPSHOT,
                    label = "唤醒并快拍",
                    enabled = wakeEnabled,
                ),
            )
        }
    }
    val keyActions = buildList {
        addIfSupported(DebugConsoleActionType.RECORD_KEY, "按键录制", capabilities, DeviceCapabilities::supportsRecordKey, baseControlEnabled)
        addIfSupported(DebugConsoleActionType.QS_KEY, "按键QS", capabilities, DeviceCapabilities::supportsQsKey, baseControlEnabled)
    }
    val showGpsSection = capabilities?.supportsGpsPush ?: true
    val capabilityNotice = when {
        capabilities == null -> null
        !capabilities.supportsWorkbench -> "当前设备未进入 Workbench 兼容列表，仅保留已验证的调试命令。"
        else -> null
    }

    return DebugConsoleUiModel(
        capabilityNotice = capabilityNotice,
        coreActions = coreActions,
        modeActions = modeActions,
        auxiliaryActions = auxiliaryActions,
        keyActions = keyActions,
        showGpsSection = showGpsSection,
        gpsControlsEnabled = baseControlEnabled,
    )
}

fun mapDebugConsoleDeviceRows(state: DebugHomeState): List<DebugConsoleDeviceRowUiModel> {
    val connectedMac = state.sessionStatus.connectedDevice?.macAddress
    val connectedProfile = state.sessionStatus.connectedProfile
    return state.discoveredDevices.map { device ->
        val isConnected = device.macAddress == connectedMac
        val capabilityLabel = if (isConnected && connectedProfile != null) {
            capabilityLabelFor(connectedProfile.capabilities)
        } else {
            capabilityLabelFor(device)
        }
        DebugConsoleDeviceRowUiModel(
            name = device.name,
            macAddress = device.macAddress,
            capabilityLabel = capabilityLabel,
            isConnected = isConnected,
        )
    }
}

fun debugConsoleConnectedDeviceName(state: DebugHomeState): String {
    return state.sessionStatus.connectedProfile?.displayName
        ?: state.sessionStatus.connectedDevice?.name
        ?: "无"
}

fun debugConsoleConnectedCapabilityLabel(state: DebugHomeState): String {
    val capabilities = state.sessionStatus.connectedProfile?.capabilities ?: return "未识别"
    return capabilityLabelFor(capabilities)
}

private fun canWakeFromDebugConsole(state: DebugHomeState): Boolean {
    val session = state.sessionStatus
    return state.selectedMode == SessionTransportMode.FAKE ||
        (session.connectedDevice != null &&
            state.prerequisites.bluetoothPermissionsGranted &&
            state.sessionStatus.bluetoothEnabled &&
            state.sessionStatus.wakeAdvertisingSupported)
}

private fun capabilityLabelFor(capabilities: DeviceCapabilities): String {
    return when {
        capabilities.supportsWorkbench -> "Workbench 兼容"
        capabilities.supportsDebugConsole -> "受限调试"
        else -> "仅日志 / 手动"
    }
}

private fun capabilityLabelFor(device: SessionDevice): String {
    return when {
        device.workbenchSupported -> "Workbench 兼容"
        device.inferredProtocolFamily != ProtocolFamily.UNKNOWN -> "受限调试"
        else -> "仅日志 / 手动"
    }
}

private fun MutableList<DebugConsoleActionUiModel>.addIfSupported(
    actionType: DebugConsoleActionType,
    label: String,
    capabilities: DeviceCapabilities?,
    predicate: (DeviceCapabilities) -> Boolean,
    enabled: Boolean,
) {
    if (capabilities == null || predicate(capabilities)) {
        add(
            DebugConsoleActionUiModel(
                actionType = actionType,
                label = label,
                enabled = enabled,
            ),
        )
    }
}
