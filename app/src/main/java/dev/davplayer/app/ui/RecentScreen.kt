package dev.davplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davplayer.app.AppState
import dev.davplayer.app.data.WatchRecord
import java.text.DateFormat
import java.util.Date

@Composable
fun RecentScreen(
    state: AppState, onOpen: (WatchRecord) -> Unit, onLocate: (WatchRecord) -> Unit, onRemove: (WatchRecord) -> Unit,
    onClear: () -> Unit, onReload: () -> Unit, onRetrySave: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val editable = !state.historyLoading && !state.historyLoadError && state.historyOpeningId == null
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("清空观看记录？") },
        text = { Text("将移除本机的观看记录和对应续播进度。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("继续上次的故事", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.watchHistory.isNotEmpty()) TextButton(onClick = { confirmClear = true }, enabled = editable) { Text("清空") }
            }
            Spacer(Modifier.height(8.dp))
            Text("记录每一次观看，随时继续播放。", fontSize = 13.sp, color = MutedColor)
        }
        if (state.historySaveError) item {
            Text("观看进度暂未保存到本机，请重试。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            TextButton(onClick = onRetrySave) { Text("重试保存") }
        }
        when {
            state.historyLoading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
            state.historyLoadError -> item {
                EmptyState(Icons.Outlined.History, "暂时无法读取观看记录", "原记录文件仍然保留，请重试读取。") {
                    OutlinedButton(onClick = onReload) { Text("重试读取") }
                }
            }
            state.watchHistory.isEmpty() -> item {
                EmptyState(Icons.Outlined.History, "还没有观看记录", "从媒体库播放视频后，会在这里保存观看进度。")
            }
        }
        items(state.watchHistory, key = { it.id }) { record ->
            val server = state.servers.firstOrNull { record.matches(it) }
            val opening = state.historyOpeningId == record.id
            val canOpen = editable && server != null && !state.initializing && !state.storageError
            Card(
                onClick = { onOpen(record) }, enabled = canOpen,
                shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(record.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(record) }, enabled = editable) { Icon(Icons.Outlined.Close, "删除观看记录", tint = MutedColor, modifier = Modifier.size(18.dp)) }
                    }
                    Text(server?.name ?: "原连接已移除或修改", fontSize = 12.sp, color = MutedColor)
                    Spacer(Modifier.height(10.dp))
                    val duration = record.durationMs
                    if (duration != null && duration > 0) {
                        LinearProgressIndicator(progress = { (record.positionMs.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(4.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (record.completed) "已看完 · ${formatTime(record.positionMs)}" else "${formatTime(record.positionMs)} / ${formatTime(duration)}", fontSize = 12.sp, color = MutedColor)
                            Spacer(Modifier.height(4.dp))
                            val watchedAt = remember(record.updatedAtMs) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.updatedAtMs)) }
                            Text(watchedAt, fontSize = 10.sp, color = MutedColor)
                        }
                        if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else {
                            IconButton(onClick = { onLocate(record) }, enabled = canOpen, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.FolderOpen, "定位文件夹", tint = if (canOpen) MintColor else MutedColor, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(if (record.resumePositionMs > 0) "继续播放" else if (record.completed) "重新播放" else "播放", fontSize = 12.sp, color = if (canOpen) MintColor else MutedColor)
                            Icon(Icons.Outlined.PlayArrow, null, tint = if (canOpen) MintColor else MutedColor, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
