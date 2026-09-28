package com.alliot.osmo.demo.app.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alliot.osmo.demo.app.ui.home.HomeFilledButton
import com.alliot.osmo.demo.app.ui.home.HomeHapticKind
import com.alliot.osmo.demo.app.ui.home.HomeOutlinedButton
import com.alliot.osmo.demo.app.ui.home.HomeSectionCard

/**
 * Media offload as an album/gallery: a thumbnail grid grouped by store, with a per-item detail
 * dialog carrying the download / frame-capture / trim / delete actions. Connection is self-contained
 * (scan + pure-DUML), so the top card only exposes connect / reload / disconnect.
 */
@Composable
fun MediaScreen(
    viewModel: MediaViewModel,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detailPath by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "connect") {
            HomeSectionCard(title = "相机连接") {
                Text(
                    text = "点“连接并加载”会自动扫描并连接相机、读取相机 Wi-Fi 并入网、载入相册，" +
                        "全程独立于工作台，无需先连蓝牙、也无需填写任何信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            item(key = "loading") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (state.connection == MediaConnectionState.CONNECTED) {
            if (state.sdItems.isEmpty() && state.internalItems.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "没有读到媒体条目。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            mediaSection("SD 卡", state.sdItems, state, viewModel) { detailPath = it }
            mediaSection("机身内存", state.internalItems, state, viewModel) { detailPath = it }
        }
    }

    // ---- per-item detail (album entry actions) ----
    val detailRow = detailPath?.let { p ->
        (state.sdItems + state.internalItems).firstOrNull { it.item.path == p }
    }
    if (detailRow != null) {
        MediaDetailDialog(
            row = detailRow,
            thumbnail = state.thumbnails[detailRow.item.path],
            progress = state.downloadProgress[detailRow.item.path],
            busy = state.processing.contains(detailRow.item.path),
            onDownload = { viewModel.download(detailRow) },
            onCapture = { viewModel.requestFrameCapture(detailRow); detailPath = null },
            onTrim = { viewModel.requestTrim(detailRow); detailPath = null },
            onDelete = { viewModel.requestDelete(detailRow); detailPath = null },
            onDismiss = { detailPath = null },
        )
    } else if (detailPath != null) {
        // The item vanished (e.g. deleted) — close the stale sheet.
        detailPath = null
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
                TextButton(onClick = viewModel::cancelDelete) { Text("取消") }
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

private fun LazyListScope.mediaSection(
    title: String,
    rows: List<MediaRow>,
    state: MediaUiState,
    viewModel: MediaViewModel,
    onOpen: (String) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "hdr_$title") { MediaStoreHeader(title = title, count = rows.size) }
    val gridRows = rows.chunked(GRID_COLUMNS)
    items(gridRows, key = { "row_${title}_${it.first().item.path}" }) { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowItems.forEach { row ->
                MediaTile(
                    row = row,
                    thumbnail = state.thumbnails[row.item.path],
                    progress = state.downloadProgress[row.item.path],
                    onRequestThumbnail = { viewModel.loadThumbnail(row) },
                    onClick = { onOpen(row.item.path) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(GRID_COLUMNS - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
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
private fun MediaTile(
    row: MediaRow,
    thumbnail: ByteArray?,
    progress: Float?,
    onRequestThumbnail: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(row.item.path) { onRequestThumbnail() }
    val bmp = remember(thumbnail) {
        thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = row.item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // type / duration badge
        Text(
            text = tileBadge(row),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        if (row.downloaded) {
            Text(
                text = "✓ 已下载",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (progress != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(progress = { progress }, color = Color.White)
            }
        }
    }
}

@Composable
private fun MediaDetailDialog(
    row: MediaRow,
    thumbnail: ByteArray?,
    progress: Float?,
    busy: Boolean,
    onDownload: () -> Unit,
    onCapture: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bmp = remember(thumbnail) {
        thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = row.item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = if (row.item.isVideo) "视频" else "照片",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
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
                )
                if (progress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeFilledButton(
                        onClick = onDownload,
                        enabled = progress == null && !busy,
                        kind = HomeHapticKind.PRIMARY,
                    ) {
                        Text(if (row.downloaded) "重新下载" else "下载")
                    }
                    if (row.item.isVideo) {
                        HomeOutlinedButton(onClick = onCapture, enabled = !busy, kind = HomeHapticKind.SECONDARY) {
                            Text("抽帧")
                        }
                        HomeOutlinedButton(onClick = onTrim, enabled = !busy, kind = HomeHapticKind.SECONDARY) {
                            Text("裁剪")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (row.canDelete) {
                        TextButton(onClick = onDelete) {
                            Text("从相机删除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (!row.canDelete) {
                    Text(
                        text = if (row.downloaded) "该文件无可删除句柄，或已删除。" else "先下载并校验后才能从相机删除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Short badge shown on a tile: `▶ mm:ss` for a video, `照片` otherwise. */
private fun tileBadge(row: MediaRow): String {
    if (!row.item.isVideo) return "照片"
    val s = row.item.durationSec
    if (s <= 0) return "▶"
    return "▶ %d:%02d".format(s / 60, s % 60)
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

private const val GRID_COLUMNS = 3
