package dev.davplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.davplayer.app.VideoTarget
import dev.davplayer.app.data.MediaEntry
import dev.davplayer.app.data.sortedByMediaName
import kotlinx.coroutines.CancellationException

@Composable
internal fun EpisodePicker(
    target: VideoTarget,
    onLoadDirectory: suspend (VideoTarget) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val active = target.entry
    var retry by remember(active.url) { mutableIntStateOf(0) }
    var loadFailed by remember(active.url, retry) { mutableStateOf(false) }
    LaunchedEffect(active.url, target.directoryEntries, retry) {
        if (target.directoryEntries == null) {
            try {
                onLoadDirectory(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    }

    val episodes = remember(active, target.directoryEntries) {
        (target.directoryEntries.orEmpty().filter { it.isVideo && it.url != active.url } + active)
            .distinctBy(MediaEntry::url)
            .sortedByMediaName()
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(episodes) {
        scroll.scrollToItem((episodes.indexOfFirst { it.url == active.url } - 2).coerceAtLeast(0))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭选集") }
                }
                LazyColumn(state = scroll, modifier = Modifier.weight(1f, fill = false)) {
                    items(episodes, key = MediaEntry::url) { entry ->
                        val playing = entry.url == active.url
                        val colors = MaterialTheme.colorScheme
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (playing) colors.primaryContainer else Color.Transparent)
                                .selectable(selected = playing, enabled = !playing, role = Role.RadioButton) {
                                    onSelect(entry.url)
                                }
                                .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.name, modifier = Modifier.weight(1f),
                                fontSize = 14.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (playing) colors.onPrimaryContainer else colors.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (playing) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Outlined.PlayArrow, "当前播放", Modifier.size(18.dp), tint = colors.onPrimaryContainer)
                            }
                        }
                    }
                }
                if (target.directoryEntries == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loadFailed) {
                            Text("无法读取同目录视频", modifier = Modifier.weight(1f), color = MutedColor, fontSize = 13.sp)
                            TextButton(onClick = { retry++ }) { Text("重试") }
                        } else {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在读取同目录视频…", color = MutedColor, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
