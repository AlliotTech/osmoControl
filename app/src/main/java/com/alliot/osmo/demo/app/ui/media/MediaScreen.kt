package com.alliot.osmo.demo.app.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
                    text = "点“连接并加载”会自动扫描并连接相机、读取相机 Wi-Fi 并入网、载入媒体，" +
                        "全程独立于工作台，无需先连蓝牙、也无需填写任何信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    busy = state.processing.contains(row.item.path),
                    onDownload = { viewModel.download(row) },
                    onDelete = { viewModel.requestDelete(row) },
                    onCapture = { viewModel.requestFrameCapture(row) },
                    onTrim = { viewModel.requestTrim(row) },
                    thumbnail = state.thumbnails[row.item.path],
                    onRequestThumbnail = { viewModel.loadThumbnail(row) },
                )
            }
            item {
                MediaStoreHeader(title = "机身内存", count = state.internalItems.size)
            }
            items(state.internalItems, key = { it.item.path }) { row ->
                MediaRowCard(
                    row = row,
                    progress = state.downloadProgress[row.item.path],
                    busy = state.processing.contains(row.item.path),
                    onDownload = { viewModel.download(row) },
                    onDelete = { viewModel.requestDelete(row) },
                    onCapture = { viewModel.requestFrameCapture(row) },
                    onTrim = { viewModel.requestTrim(row) },
                    thumbnail = state.thumbnails[row.item.path],
                    onRequestThumbnail = { viewModel.loadThumbnail(row) },
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

    if (state.frameCaptureTarget != null) {
        OffsetInputDialog(
            title = "抽取一帧",
            label = "时间点（秒）",
            confirmLabel = "抽帧",
            onConfirm = { viewModel.confirmFrameCapture(it * 1000L) },
            onDismiss = viewModel::cancelFrameCapture,
        )
    }
    val trimTarget = state.trimTarget
    if (trimTarget != null) {
        TrimInputDialog(
            name = trimTarget.item.name,
            onConfirm = { s, e -> viewModel.confirmTrim(s * 1000L, e * 1000L) },
            onDismiss = viewModel::cancelTrim,
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
    busy: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCapture: () -> Unit,
    onTrim: () -> Unit,
    thumbnail: ByteArray?,
    onRequestThumbnail: () -> Unit,
) {
    LaunchedEffect(row.item.path) { onRequestThumbnail() }
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
            val bmp = remember(thumbnail) {
                thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
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
            if (busy) {
                Text(
                    text = "处理中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    if (progress == null) {
                        TextButton(onClick = onDownload) {
                            Text(if (row.downloaded) "重下" else "下载")
                        }
                        if (row.item.isVideo) {
                            TextButton(onClick = onCapture) { Text("抽帧") }
                            TextButton(onClick = onTrim) { Text("裁剪") }
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
    }
}

@Composable
private fun OffsetInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val value = text.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it.isDigit() } },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TrimInputDialog(
    name: String,
    onConfirm: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    val s = start.toLongOrNull()
    val e = end.toLongOrNull()
    val valid = s != null && e != null && e > s
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("裁剪下载") },
        text = {
            Column {
                Text(
                    text = "仅下载「$name」选定时间窗（秒），关键帧对齐流拷贝，只拉取窗口字节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = start,
                    onValueChange = { new -> start = new.filter { it.isDigit() } },
                    label = { Text("开始（秒）") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = end,
                    onValueChange = { new -> end = new.filter { it.isDigit() } },
                    label = { Text("结束（秒）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(s!!, e!!) }, enabled = valid) {
                Text("裁剪下载")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
