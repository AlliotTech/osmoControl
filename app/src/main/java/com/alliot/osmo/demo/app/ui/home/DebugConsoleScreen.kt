package com.alliot.osmo.demo.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alliot.osmo.demo.session.log.SessionLogEntry
import com.alliot.osmo.demo.session.model.HandshakeStage
import com.alliot.osmo.demo.session.model.LogCategory
import com.alliot.osmo.demo.session.model.SessionDevice
import com.alliot.osmo.demo.session.model.SessionTransportMode

@Composable
fun DebugConsoleScreen(
    state: DebugHomeState,
    listState: LazyListState,
    onModeSelected: (SessionTransportMode) -> Unit,
    onVerifyModeSelected: (Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onUpdateDeviceFilterQuery: (String) -> Unit,
    onDisconnect: () -> Unit,
    onConnect: (SessionDevice) -> Unit,
    onVersion: () -> Unit,
    onReboot: () -> Unit,
    onToggleRecord: () -> Unit,
    onSwitchMode: (Int) -> Unit,
    onSubscribe: () -> Unit,
    onSleep: () -> Unit,
    onWake: () -> Unit,
    onWakeViaGatt: () -> Unit,
    onWakeAndSnapshot: () -> Unit,
    onRecordKeyClick: () -> Unit,
    onQsKeyClick: () -> Unit,
    onSnapshotKeyClick: () -> Unit,
    onPushGps: () -> Unit,
    onSetGpsFrequency: (Int) -> Unit,
    onSetLocationRequestFrequency: (Int) -> Unit,
    onUpdateManualHex: (String) -> Unit,
    onSendManual: () -> Unit,
    onSetGpsSyncEnabled: (Boolean) -> Unit,
    onPermissionAction: () -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    var logsClearedAtMillis by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    val logGroups = remember(state.logs, logsClearedAtMillis) {
        groupDebugLogs(state.logs, logsClearedAtMillis)
    }
    val debugConsoleUiModel = state.debugConsoleUiModel

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DebugSessionSection(
                state = state,
                onModeSelected = onModeSelected,
                onVerifyModeSelected = onVerifyModeSelected,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onDisconnect = onDisconnect,
                onPermissionAction = onPermissionAction,
            )
        }
        item {
            DebugDeviceListSection(
                state = state,
                onFilterChange = onUpdateDeviceFilterQuery,
                onConnect = onConnect,
            )
        }
        item {
            DebugCommandsSection(
                state = state,
                uiModel = debugConsoleUiModel,
                onVersion = onVersion,
                onReboot = onReboot,
                onToggleRecord = onToggleRecord,
                onSwitchMode = onSwitchMode,
                onSubscribe = onSubscribe,
                onSleep = onSleep,
                onWake = onWake,
                onWakeViaGatt = onWakeViaGatt,
                onWakeAndSnapshot = onWakeAndSnapshot,
                onRecordKeyClick = onRecordKeyClick,
                onQsKeyClick = onQsKeyClick,
                onSnapshotKeyClick = onSnapshotKeyClick,
                onPushGps = onPushGps,
                onSetGpsFrequency = onSetGpsFrequency,
                onSetLocationRequestFrequency = onSetLocationRequestFrequency,
                onSetGpsSyncEnabled = onSetGpsSyncEnabled,
            )
        }
        item {
            DebugStatusSection(state = state)
        }
        item {
            DebugLogsSection(
                groups = logGroups,
                onClearLogs = {
                    logsClearedAtMillis = state.logs.maxOfOrNull(SessionLogEntry::timestampMillis) ?: Long.MIN_VALUE
                },
                onCopyRecent = {
                    val recent = logGroups.flatMap { group ->
                        group.entries.take(10).map { entry ->
                            "[${entry.category.name}] ${entry.message}${entry.hex?.let { " | $it" } ?: ""}"
                        }
                    }
                    clipboardManager.setText(AnnotatedString(recent.joinToString("\n")))
                },
            )
        }
        item {
            DebugManualCommandSection(
                value = state.manualHex,
                onValueChange = onUpdateManualHex,
                onSend = onSendManual,
            )
        }
    }
}

@Composable
private fun DebugSessionSection(
    state: DebugHomeState,
    onModeSelected: (SessionTransportMode) -> Unit,
    onVerifyModeSelected: (Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onPermissionAction: () -> Unit,
) {
    val isBusy = state.busyAction != null
    val permissionsReady = state.prerequisites.bluetoothPermissionsGranted && state.prerequisites.locationPermissionGranted
    val canScan = state.selectedMode == SessionTransportMode.FAKE || (permissionsReady && state.sessionStatus.bluetoothEnabled)
    val permissionCta = state.permissionCta

    HomeSectionCard(title = "传输与会话") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SessionTransportMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.selectedMode == mode,
                    onClick = rememberHapticClick(
                        kind = HomeHapticKind.NAVIGATION,
                        onClick = { onModeSelected(mode) },
                    ),
                    enabled = !isBusy,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SessionTransportMode.entries.size),
                ) {
                    Text(if (mode == SessionTransportMode.FAKE) "模拟" else "真机")
                }
            }
        }
        if (state.selectedMode == SessionTransportMode.REAL && permissionCta != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "真机模式前置条件未满足，需要先处理权限或系统设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                HomeFilledButton(onClick = onPermissionAction) {
                    Text(permissionCta.label)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryPill(modifier = Modifier.weight(1f), title = "连接", value = debugConnectionSummary(state))
            SummaryPill(modifier = Modifier.weight(1f), title = "握手", value = debugHandshakeLabel(state.sessionStatus.handshakeStage))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryPill(modifier = Modifier.weight(1f), title = "设备", value = debugConsoleConnectedDeviceName(state))
            SummaryPill(
                modifier = Modifier.weight(1f),
                title = "能力",
                value = debugConsoleConnectedCapabilityLabel(state),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryPill(
                modifier = Modifier.weight(1f),
                title = "广播唤醒",
                value = if (state.sessionStatus.wakeAdvertisingSupported) "支持" else "不支持",
            )
            SummaryPill(
                modifier = Modifier.weight(1f),
                title = "协议族",
                value = state.sessionStatus.connectedProfile?.protocolFamily?.name ?: "-",
            )
        }
        if (state.selectedMode == SessionTransportMode.REAL) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeOutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onVerifyModeSelected(0) },
                    enabled = !isBusy && state.sessionStatus.handshakeVerifyMode != 0,
                ) {
                    Text("已配对(0)")
                }
                HomeOutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onVerifyModeSelected(1) },
                    enabled = !isBusy && state.sessionStatus.handshakeVerifyMode != 1,
                ) {
                    Text("配对中(1)")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeFilledButton(modifier = Modifier.weight(1f), onClick = onStartScan, enabled = !isBusy && !state.sessionStatus.scanning && canScan) {
                Text("扫描")
            }
            HomeOutlinedButton(modifier = Modifier.weight(1f), onClick = onStopScan, enabled = !isBusy && state.sessionStatus.scanning) {
                Text("停止")
            }
            HomeOutlinedButton(modifier = Modifier.weight(1f), onClick = onDisconnect, enabled = !isBusy && state.sessionStatus.connectedDevice != null) {
                Text("断开")
            }
        }
    }
}

@Composable
private fun DebugDeviceListSection(
    state: DebugHomeState,
    onFilterChange: (String) -> Unit,
    onConnect: (SessionDevice) -> Unit,
) {
    val isBusy = state.busyAction != null
    val deviceRows = remember(state.discoveredDevices, state.sessionStatus.connectedDevice, state.sessionStatus.connectedProfile) {
        mapDebugConsoleDeviceRows(state)
    }
    val filteredDevices = remember(deviceRows, state.deviceFilterQuery, state.discoveredDevices) {
        val filteredMacs = filterDiscoveredDevices(state.discoveredDevices, state.deviceFilterQuery)
            .map(SessionDevice::macAddress)
            .toSet()
        deviceRows.filter { it.macAddress in filteredMacs }
    }
    HomeSectionCard(title = "设备列表") {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.deviceFilterQuery,
            onValueChange = onFilterChange,
            label = { Text("筛选设备") },
            placeholder = { Text("按名称或 MAC 搜索") },
            singleLine = true,
            supportingText = {
                val summary = if (state.deviceFilterQuery.isBlank()) {
                    "已发现 ${state.discoveredDevices.size} 台设备"
                } else {
                    "匹配 ${filteredDevices.size} / ${state.discoveredDevices.size}"
                }
                Text(summary)
            },
        )
        if (state.discoveredDevices.isEmpty()) {
            Text(
                text = when {
                    state.sessionStatus.scanning -> "扫描中..."
                    state.selectedMode == SessionTransportMode.REAL && state.permissionCta != null -> "先完成权限和系统前置条件"
                    else -> "暂无设备"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (filteredDevices.isEmpty()) {
            Text(
                text = "没有匹配的设备",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            filteredDevices.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.Medium)
                        Text(
                            text = "${device.macAddress} · ${device.capabilityLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HomeFilledButton(
                        onClick = {
                            state.discoveredDevices.firstOrNull { it.macAddress == device.macAddress }?.let(onConnect)
                        },
                        enabled = !isBusy && !device.isConnected,
                    ) {
                        Text(if (device.isConnected) "已连接" else "连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugCommandsSection(
    state: DebugHomeState,
    uiModel: DebugConsoleUiModel,
    onVersion: () -> Unit,
    onReboot: () -> Unit,
    onToggleRecord: () -> Unit,
    onSwitchMode: (Int) -> Unit,
    onSubscribe: () -> Unit,
    onSleep: () -> Unit,
    onWake: () -> Unit,
    onWakeViaGatt: () -> Unit,
    onWakeAndSnapshot: () -> Unit,
    onRecordKeyClick: () -> Unit,
    onQsKeyClick: () -> Unit,
    onSnapshotKeyClick: () -> Unit,
    onPushGps: () -> Unit,
    onSetGpsFrequency: (Int) -> Unit,
    onSetLocationRequestFrequency: (Int) -> Unit,
    onSetGpsSyncEnabled: (Boolean) -> Unit,
) {
    val onDebugAction = { action: DebugConsoleActionUiModel ->
        handleDebugAction(
            action = action,
            onVersion = onVersion,
            onReboot = onReboot,
            onToggleRecord = onToggleRecord,
            onSubscribe = onSubscribe,
            onSleep = onSleep,
            onWake = onWake,
            onWakeViaGatt = onWakeViaGatt,
            onWakeAndSnapshot = onWakeAndSnapshot,
            onRecordKeyClick = onRecordKeyClick,
            onQsKeyClick = onQsKeyClick,
            onSnapshotKeyClick = onSnapshotKeyClick,
            onSwitchMode = onSwitchMode,
        )
    }

    HomeSectionCard(title = "命令与 GPS") {
        uiModel.capabilityNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiModel.coreActions.isNotEmpty()) {
            DebugButtonGrid(
                actions = uiModel.coreActions,
                onAction = onDebugAction,
            )
        }

        if (uiModel.modeActions.isNotEmpty()) {
            DebugButtonGrid(
                actions = uiModel.modeActions,
                onAction = onDebugAction,
            )
        }

        if (uiModel.auxiliaryActions.isNotEmpty()) {
            DebugButtonGrid(
                actions = uiModel.auxiliaryActions,
                onAction = onDebugAction,
            )
        }

        if (uiModel.keyActions.isNotEmpty()) {
            DebugButtonGrid(
                actions = uiModel.keyActions,
                onAction = onDebugAction,
            )
        }

        if (uiModel.showGpsSection) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SummaryPill(modifier = Modifier.weight(1f), title = "GPS 自动同步", value = if (state.sessionStatus.gpsAutoPushEnabled) "开启" else "关闭")
                SummaryPill(modifier = Modifier.weight(1f), title = "定位请求", value = "${state.sessionStatus.gpsLocationRequestHz}Hz")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("持续同步")
                Switch(
                    checked = state.sessionStatus.gpsAutoPushEnabled,
                    onCheckedChange = onSetGpsSyncEnabled,
                    enabled = uiModel.gpsControlsEnabled,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(1, 2, 5, 10).forEach { hz ->
                    HomeOutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onSetGpsFrequency(hz) },
                        enabled = uiModel.gpsControlsEnabled,
                    ) {
                        Text("${hz}Hz")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(1, 2, 5).forEach { hz ->
                    HomeOutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onSetLocationRequestFrequency(hz) },
                        enabled = uiModel.gpsControlsEnabled,
                    ) {
                        Text("定位${hz}Hz")
                    }
                }
            }
            HomeFilledButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPushGps,
                enabled = uiModel.gpsControlsEnabled,
            ) {
                Text("推送示例 GPS")
            }
        }

        if (
            uiModel.coreActions.isEmpty() &&
            uiModel.modeActions.isEmpty() &&
            uiModel.auxiliaryActions.isEmpty() &&
            uiModel.keyActions.isEmpty() &&
            !uiModel.showGpsSection
        ) {
            Text(
                text = "暂无已验证的预置调试命令，可继续使用日志与手动报文工具。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DebugStatusSection(state: DebugHomeState) {
    HomeSectionCard(title = "解析状态") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryPill(modifier = Modifier.weight(1f), title = "最近推送", value = state.cameraStatus.lastPushCommandId)
            SummaryPill(modifier = Modifier.weight(1f), title = "录制中", value = if (state.cameraStatus.recording) "是" else "否")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryPill(modifier = Modifier.weight(1f), title = "模式", value = state.cameraStatus.modeName.ifBlank { state.cameraStatus.modeLabel })
            SummaryPill(modifier = Modifier.weight(1f), title = "状态", value = state.cameraStatus.stateLabel)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryPill(modifier = Modifier.weight(1f), title = "录制结果", value = state.sessionStatus.lastRecordResult ?: "-")
            SummaryPill(modifier = Modifier.weight(1f), title = "唤醒结果", value = state.sessionStatus.lastWakeResult ?: "-")
        }
        Text(
            text = state.cameraStatus.lastPushSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DebugLogsSection(
    groups: List<DebugLogGroup>,
    onClearLogs: () -> Unit,
    onCopyRecent: () -> Unit,
) {
    HomeSectionCard(title = "日志") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeOutlinedButton(modifier = Modifier.weight(1f), onClick = onClearLogs) {
                Text("清空显示")
            }
            HomeOutlinedButton(modifier = Modifier.weight(1f), onClick = onCopyRecent, enabled = groups.isNotEmpty()) {
                Text("复制最近日志")
            }
        }
        if (groups.isEmpty()) {
            Text("暂无日志", style = MaterialTheme.typography.bodySmall)
        } else {
            groups.forEach { group ->
                var expanded by rememberSaveable(group.category.name) { mutableStateOf(group.expandedByDefault) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${group.category.name} (${group.entries.size}) ${if (expanded) "收起" else "展开"}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (expanded) {
                        group.entries.take(20).forEach { entry ->
                            Text(
                                text = "[${entry.category.name}] ${entry.message}${entry.hex?.let { " | $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugManualCommandSection(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val validation = remember(value) { validateManualHexInput(value) }
    val showError = value.isNotBlank() && !validation.isValid

    HomeSectionCard(title = "手动命令") {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            label = { Text("HEX") },
            isError = showError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            supportingText = {
                if (showError) {
                    Text(validation.errorMessage.orEmpty())
                } else {
                    Text("支持空格分隔，发送前会自动归一化。")
                }
            },
        )
        HomeFilledButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val normalized = validation.normalizedHex ?: return@HomeFilledButton
                onValueChange(normalized)
                onSend()
            },
            enabled = validation.isValid,
        ) {
            Text("发送")
        }
    }
}

@Composable
private fun DebugButtonGrid(
    actions: List<DebugConsoleActionUiModel>,
    onAction: (DebugConsoleActionUiModel) -> Unit,
) {
    actions.chunked(3).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { action ->
                HomeOutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(action) },
                    enabled = action.enabled,
                ) {
                    Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun handleDebugAction(
    action: DebugConsoleActionUiModel,
    onVersion: () -> Unit,
    onReboot: () -> Unit,
    onToggleRecord: () -> Unit,
    onSubscribe: () -> Unit,
    onSleep: () -> Unit,
    onWake: () -> Unit,
    onWakeViaGatt: () -> Unit,
    onWakeAndSnapshot: () -> Unit,
    onRecordKeyClick: () -> Unit,
    onQsKeyClick: () -> Unit,
    onSnapshotKeyClick: () -> Unit,
    onSwitchMode: (Int) -> Unit,
) {
    when (action.actionType) {
        DebugConsoleActionType.VERSION_QUERY -> onVersion()
        DebugConsoleActionType.REBOOT -> onReboot()
        DebugConsoleActionType.TOGGLE_RECORD -> onToggleRecord()
        DebugConsoleActionType.SUBSCRIBE_STATUS -> onSubscribe()
        DebugConsoleActionType.SLEEP -> onSleep()
        DebugConsoleActionType.WAKE -> onWake()
        DebugConsoleActionType.GATT_WAKE -> onWakeViaGatt()
        DebugConsoleActionType.SWITCH_MODE -> onSwitchMode(action.mode ?: return)
        DebugConsoleActionType.WAKE_AND_SNAPSHOT -> onWakeAndSnapshot()
        DebugConsoleActionType.RECORD_KEY -> onRecordKeyClick()
        DebugConsoleActionType.QS_KEY -> onQsKeyClick()
        DebugConsoleActionType.SNAPSHOT_KEY -> onSnapshotKeyClick()
    }
}

private fun debugConnectionSummary(state: DebugHomeState): String = when {
    state.sessionStatus.protocolReady -> "协议已连接"
    state.sessionStatus.connectedDevice != null -> "蓝牙已连接"
    state.sessionStatus.scanning -> "扫描中"
    else -> "未连接"
}

private fun debugHandshakeLabel(stage: HandshakeStage): String = when (stage) {
    HandshakeStage.IDLE -> "空闲"
    HandshakeStage.REQUEST_SENT -> "请求已发"
    HandshakeStage.CAMERA_CONFIRMATION_RECEIVED -> "相机确认"
    HandshakeStage.COMPLETED -> "完成"
    HandshakeStage.REJECTED -> "拒绝"
}
