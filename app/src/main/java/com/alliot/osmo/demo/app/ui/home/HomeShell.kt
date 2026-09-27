package com.alliot.osmo.demo.app.ui.home

import android.content.Context
import android.os.Build
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alliot.osmo.demo.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeShell(
    state: DebugHomeState,
    darkThemeEnabled: Boolean,
    onDestinationSelected: (HomeDestination) -> Unit,
    onDismissSheet: () -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onResetAppState: () -> Unit,
    onOpenConnectionSheet: () -> Unit,
    onPermissionAction: () -> Unit,
    onSwitchMode: (Int) -> Unit,
    onSetGpsAutoPushEnabled: (Boolean) -> Unit,
    onSetGpsAutoPushFrequencyHz: (Int) -> Unit,
    onSetGpsLocationRequestFrequencyHz: (Int) -> Unit,
    onPushSampleGps: () -> Unit,
    onSleep: () -> Unit,
    onWake: () -> Unit,
    onWakeAndSnapshot: () -> Unit,
    onRequestVersion: () -> Unit,
    onSendQsKeyClick: () -> Unit,
    onSendSnapshotKeyClick: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onUpdateDeviceFilterQuery: (String) -> Unit,
    onSelectConnectionDevice: (Long) -> Unit,
    onConfirmConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryConnectionScan: () -> Unit,
    onNavigateToDebugConsole: () -> Unit,
    workbenchContent: @Composable (LazyListState, Boolean) -> Unit,
    mediaContent: @Composable (LazyListState, Boolean) -> Unit,
    debugConsoleContent: @Composable (LazyListState, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val workbenchListState = rememberLazyListState()
    val mediaListState = rememberLazyListState()
    val debugConsoleListState = rememberLazyListState()
    val versionLabel = remember(context) { appVersionLabel(context) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.debugConsoleLogsRequest) {
        if (state.debugConsoleLogsRequest > 0) {
            debugConsoleListState.animateScrollToItem(0)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val isLandscape = maxWidth >= 840.dp
        val showSidePanel = maxWidth >= 1180.dp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = state.sessionStatus.connectedDevice?.name ?: "Osmo Workbench",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!showSidePanel) {
                        IconButton(
                            onClick = rememberHapticClick(
                                kind = HomeHapticKind.NAVIGATION,
                                onClick = { showSettingsSheet = true },
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "打开设置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HomeDestinationToggle(
                    destination = state.destination,
                    onDestinationSelected = onDestinationSelected,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.destination == HomeDestination.WORKBENCH) {
                    ConnectionSummaryStrip(
                        state = state,
                        onOpenConnectionSheet = onOpenConnectionSheet,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (state.destination) {
                        HomeDestination.WORKBENCH -> workbenchContent(workbenchListState, isLandscape)
                        HomeDestination.MEDIA -> mediaContent(mediaListState, isLandscape)
                        HomeDestination.DEBUG_CONSOLE -> debugConsoleContent(debugConsoleListState, isLandscape)
                    }
                }
            }

            if (showSidePanel) {
                SettingsPanel(
                    modifier = Modifier.width(320.dp),
                    darkThemeEnabled = darkThemeEnabled,
                    versionLabel = versionLabel,
                    onToggleDarkTheme = onToggleDarkTheme,
                    onResetAppState = onResetAppState,
                    onOpenAbout = { showAboutDialog = true },
                )
            }
        }

        WorkbenchSheets(
            state = state,
            isLandscape = isLandscape,
            onDismiss = onDismissSheet,
            onSwitchMode = onSwitchMode,
            onSetGpsAutoPushEnabled = onSetGpsAutoPushEnabled,
            onSetGpsAutoPushFrequencyHz = onSetGpsAutoPushFrequencyHz,
            onSetGpsLocationRequestFrequencyHz = onSetGpsLocationRequestFrequencyHz,
            onPushSampleGps = onPushSampleGps,
            onSleep = onSleep,
            onWake = onWake,
            onWakeAndSnapshot = onWakeAndSnapshot,
            onRequestVersion = onRequestVersion,
            onSendQsKeyClick = onSendQsKeyClick,
            onSendSnapshotKeyClick = onSendSnapshotKeyClick,
            onPermissionAction = onPermissionAction,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onUpdateDeviceFilterQuery = onUpdateDeviceFilterQuery,
            onSelectConnectionDevice = onSelectConnectionDevice,
            onConfirmConnection = onConfirmConnection,
            onDisconnect = onDisconnect,
            onRetryConnectionScan = onRetryConnectionScan,
            onNavigateToDebugConsole = onNavigateToDebugConsole,
        )

        if (!showSidePanel && showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
            ) {
                SettingsPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    darkThemeEnabled = darkThemeEnabled,
                    versionLabel = versionLabel,
                    onToggleDarkTheme = onToggleDarkTheme,
                    onResetAppState = {
                        showSettingsSheet = false
                        onResetAppState()
                    },
                    onOpenAbout = {
                        showSettingsSheet = false
                        showAboutDialog = true
                    },
                )
            }
        }

        if (showAboutDialog) {
            AboutDialog(
                versionLabel = versionLabel,
                onDismiss = { showAboutDialog = false },
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    darkThemeEnabled: Boolean,
    versionLabel: String,
    onToggleDarkTheme: (Boolean) -> Unit,
    onResetAppState: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionCard(title = "设置") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "明暗模式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (darkThemeEnabled) "当前为深色界面。" else "当前为浅色界面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = darkThemeEnabled,
                    onCheckedChange = onToggleDarkTheme,
                )
            }
        }

        HomeSectionCard(title = "应用状态") {
            Text(
                text = "重置会清空主题偏好、控制器身份、已配对相机记录和权限请求记录，并重新创建应用会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HomeOutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onResetAppState,
                kind = HomeHapticKind.DANGER,
            ) {
                Text("重置 APP 状态")
            }
        }

        HomeSectionCard(title = "关于") {
            Text(
                text = "版本 $versionLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HomeFilledButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenAbout,
                kind = HomeHapticKind.SECONDARY,
            ) {
                Text("查看关于页面")
            }
        }
    }
}

@Composable
private fun AboutDialog(
    versionLabel: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("关于 Osmo Workbench")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryPill(
                    title = "作者",
                    value = "Alliot",
                )
                SummaryPill(
                    title = "博客",
                    value = "www.iots.vip",
                )
                SummaryPill(
                    title = "版本",
                    value = versionLabel,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { uriHandler.openUri("https://www.iots.vip") },
            ) {
                Text("访问博客")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private fun appVersionLabel(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionName = packageInfo.versionName ?: "unknown"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return "$versionName ($versionCode)"
}
