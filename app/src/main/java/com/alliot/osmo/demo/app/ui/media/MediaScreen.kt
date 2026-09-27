package com.alliot.osmo.demo.app.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alliot.osmo.demo.app.ui.home.HomeFilledButton
import com.alliot.osmo.demo.app.ui.home.HomeHapticKind
import com.alliot.osmo.demo.app.ui.home.HomeOutlinedButton
import com.alliot.osmo.demo.app.ui.home.HomeSectionCard

@Composable
fun MediaScreen(
    viewModel: MediaViewModel,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HomeSectionCard(title = "相机连接") {
                Text(
                    text = "先让手机连上相机的 Wi-Fi 热点，再填写相机 IP（Action 5 Pro 默认 192.168.2.1）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.cameraIp,
                    onValueChange = viewModel::updateCameraIp,
                    label = { Text("相机 IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.connection != MediaConnectionState.CONNECTING && !state.isLoading,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeFilledButton(
                        onClick = viewModel::connectAndLoad,
                        enabled = state.connection != MediaConnectionState.CONNECTING && !state.isLoading,
                        kind = HomeHapticKind.PRIMARY,
                    ) {
                        Text(if (state.connection == MediaConnectionState.CONNECTED) "重新加载" else "连接并加载")
                    }
                    HomeOutlinedButton(
                        onClick = viewModel::disconnect,
                        enabled = state.connection == MediaConnectionState.CONNECTED ||
                            state.connection == MediaConnectionState.FAILED,
                        kind = HomeHapticKind.SECONDARY,
                    ) {
                        Text("断开")
                    }
                }
                val status = state.connectionError ?: state.status
                if (status != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.connection == MediaConnectionState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        if (state.isLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (state.connection == MediaConnectionState.CONNECTED) {
            item {
                MediaStoreHeader(title = "SD 卡", count = state.sdItems.size)
            }
            items(state.sdItems, key = { it.item.path }) { row ->
                MediaRowCard(
                    row = row,
                    progress = state.downloadProgress[row.item.path],
                    onDownload = { viewModel.download(row) },
                    onDelete = { viewModel.requestDelete(row) },
                )
            }
            item {
                MediaStoreHeader(title = "机身内存", count = state.internalItems.size)
            }
            items(state.internalItems, key = { it.item.path }) { row ->
                MediaRowCard(
                    row = row,
                    progress = state.downloadProgress[row.item.path],
                    onDownload = { viewModel.download(row) },
                    onDelete = { viewModel.requestDelete(row) },
                )
            }
        }
    }

    val candidate = state.deleteCandidate
    if (candidate != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("从相机删除？") },
            text = {
                Text("将从相机永久删除「${candidate.item.name}」。该文件已下载到本地并通过 SHA-256 校验，操作不可撤销。")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun MediaStoreHeader(title: String, count: Int) {
    Text(
        text = "$title（$count）",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun MediaRowCard(
    row: MediaRow,
    progress: Float?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetaLine(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (progress == null) {
                TextButton(onClick = onDownload) {
                    Text(if (row.downloaded) "重下" else "下载")
                }
            }
            if (row.canDelete) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
