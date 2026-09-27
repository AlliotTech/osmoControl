package com.alliot.osmo.demo.app.ui.media

import com.alliot.osmo.demo.media.model.MediaItem

/** "1.2 GB" style, one decimal, binary units. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "${bytes} B"
    } else {
        "%.1f %s".format(value, units[unit])
    }
}

/** 83 -> "01:23". */
fun formatDurationSec(totalSeconds: Int): String {
    if (totalSeconds <= 0) return ""
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/** Second line under a media row: size, kind, badges. */
fun mediaMetaLine(row: MediaRow): String {
    val item: MediaItem = row.item
    val kind = when {
        item.isVideo -> "视频" + (formatDurationSec(item.durationSec).takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        item.extension.isNotEmpty() -> item.extension.uppercase()
        else -> "文件"
    }
    val badges = buildList {
        if (row.downloaded) add("已下载")
        if (row.canDelete) add("可删除")
    }
    return buildString {
        append(formatBytes(item.sizeBytes))
        append(" · ")
        append(kind)
        if (badges.isNotEmpty()) {
            append(" · ")
            append(badges.joinToString(" · "))
        }
    }
}
