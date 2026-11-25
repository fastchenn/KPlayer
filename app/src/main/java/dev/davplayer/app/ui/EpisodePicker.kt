package dev.davplayer.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.davplayer.app.VideoTarget
import dev.davplayer.app.data.MediaEntry
import kotlinx.coroutines.CancellationException

@Composable
internal fun EpisodePicker(
    target: VideoTarget,
    fullscreen: Boolean,
    onLoadDirectory: suspend (VideoTarget) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (MediaEntry) -> Unit,
) {
    var attempt by remember(target.entry.url) { mutableIntStateOf(0) }
    var loadFailed by remember(target.entry.url) { mutableStateOf(false) }
    LaunchedEffect(target, attempt) {
        if (target.directory != null) return@LaunchedEffect
        loadFailed = false
        try {
            onLoadDirectory(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val episodes = remember(target.directory) { target.directory?.filter(MediaEntry::isVideo) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !fullscreen),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        if (fullscreen && window != null) {
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        val safeInsets = if (fullscreen) WindowInsets.displayCutout else WindowInsets.safeDrawing
        BoxWithConstraints(
            Modifier.padding(horizontal = 16.dp).windowInsetsPadding(safeInsets),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp)),
                shape = RoundedCornerShape(24.dp),
                color = PanelColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("选集", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭选集") }
                    }
                    when {
                        loadFailed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text("无法读取选集，请检查服务器连接。")
                            TextButton(onClick = { attempt++ }) { Text("重试") }
                        }
                        episodes == null -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在读取选集…")
                        }
                        episodes.isEmpty() -> Text("此文件夹没有可选视频。", Modifier.padding(20.dp))
                        else -> {
                            val initialIndex = episodes.indexOfFirst { it.url == target.entry.url }.coerceAtLeast(0)
                            LazyColumn(
                                modifier = Modifier.selectableGroup(),
                                state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex),
                                contentPadding = PaddingValues(bottom = 8.dp),
                            ) {
                                items(episodes, key = { it.url }) { entry ->
                                    val selected = entry.url == target.entry.url
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(entry) })
                                            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(entry.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            color = if (selected) MintColor else MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.width(12.dp))
                                        Box(Modifier.size(22.dp)) {
                                            if (selected) Icon(Icons.Outlined.Check, null, tint = MintColor)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
