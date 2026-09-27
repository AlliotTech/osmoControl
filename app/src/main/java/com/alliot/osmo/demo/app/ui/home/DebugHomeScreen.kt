package com.alliot.osmo.demo.app.ui.home

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alliot.osmo.demo.app.ui.media.MediaScreen
import com.alliot.osmo.demo.app.ui.media.MediaViewModel

@Composable
fun DebugHomeScreen(
    viewModel: DebugHomeViewModel,
    mediaViewModel: MediaViewModel,
    darkThemeEnabled: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value

    HomeShell(
        state = state,
        darkThemeEnabled = darkThemeEnabled,
        onDestinationSelected = viewModel::selectDestination,
        onDismissSheet = viewModel::dismissSheet,
        onToggleDarkTheme = onToggleDarkTheme,
        onResetAppState = viewModel::resetAppState,
        onOpenConnectionSheet = viewModel::openConnectionSheet,
        onPermissionAction = viewModel::performPermissionAction,
        onSwitchMode = viewModel::switchMode,
        onSetGpsAutoPushEnabled = viewModel::setGpsAutoPushEnabled,
        onSetGpsAutoPushFrequencyHz = viewModel::setGpsAutoPushFrequencyHz,
        onSetGpsLocationRequestFrequencyHz = viewModel::setGpsLocationRequestFrequencyHz,
        onPushSampleGps = viewModel::pushSampleGps,
        onSleep = viewModel::sleep,
        onWake = viewModel::wakeViaGatt,
        onWakeAndSnapshot = viewModel::wakeAndSnapshot,
        onRequestVersion = viewModel::requestVersion,
        onSendQsKeyClick = viewModel::sendQsKeyClick,
        onSendSnapshotKeyClick = viewModel::sendSnapshotKeyClick,
        onStartScan = viewModel::startScan,
        onStopScan = viewModel::stopScan,
        onUpdateDeviceFilterQuery = viewModel::updateDeviceFilterQuery,
        onSelectConnectionDevice = viewModel::selectConnectionDevice,
        onConfirmConnection = viewModel::confirmConnection,
        onDisconnect = viewModel::disconnect,
        onRetryConnectionScan = viewModel::retryConnectionScan,
        onNavigateToDebugConsole = viewModel::openRecentEvents,
        workbenchContent = { listState, isLandscape ->
            WorkbenchScreen(
                state = state,
                listState = listState,
                isLandscape = isLandscape,
                onToggleRecord = viewModel::performWorkbenchPrimaryAction,
                onOpenModeSheet = { viewModel.openSheet(HomeSheet.MODE) },
                onOpenGpsSheet = { viewModel.openSheet(HomeSheet.GPS) },
                onOpenDeviceActionsSheet = { viewModel.openSheet(HomeSheet.DEVICE_ACTIONS) },
                onPermissionAction = viewModel::performPermissionAction,
                onOpenRecentEvents = viewModel::openRecentEvents,
            )
        },
        mediaContent = { listState, _ ->
            MediaScreen(
                viewModel = mediaViewModel,
                listState = listState,
            )
        },
        debugConsoleContent = { listState, _ ->
            DebugConsoleScreen(
                state = state,
                listState = listState,
                onModeSelected = viewModel::selectMode,
                onVerifyModeSelected = viewModel::updateHandshakeVerifyMode,
                onStartScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onUpdateDeviceFilterQuery = viewModel::updateDeviceFilterQuery,
                onDisconnect = viewModel::disconnect,
                onConnect = viewModel::connect,
                onVersion = viewModel::requestVersion,
                onReboot = viewModel::rebootCamera,
                onToggleRecord = viewModel::toggleRecording,
                onSwitchMode = viewModel::switchMode,
                onSubscribe = viewModel::subscribeStatus,
                onSleep = viewModel::sleep,
                onWake = viewModel::wake,
                onWakeViaGatt = viewModel::wakeViaGatt,
                onWakeAndSnapshot = viewModel::wakeAndSnapshot,
                onRecordKeyClick = viewModel::sendRecordKeyClick,
                onQsKeyClick = viewModel::sendQsKeyClick,
                onSnapshotKeyClick = viewModel::sendSnapshotKeyClick,
                onPushGps = viewModel::pushSampleGps,
                onSetGpsFrequency = viewModel::setGpsAutoPushFrequencyHz,
                onSetLocationRequestFrequency = viewModel::setGpsLocationRequestFrequencyHz,
                onUpdateManualHex = viewModel::updateManualHex,
                onSendManual = viewModel::sendManual,
                onSetGpsSyncEnabled = viewModel::setGpsAutoPushEnabled,
                onPermissionAction = viewModel::performPermissionAction,
            )
        },
    )
}
