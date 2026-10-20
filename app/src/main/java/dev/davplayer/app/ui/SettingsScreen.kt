package dev.davplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davplayer.app.AppState
import dev.davplayer.app.R
import dev.davplayer.app.PlaybackCacheSizesGb
import dev.davplayer.app.data.LibraryText
import java.util.Locale

@Composable
fun SettingsScreen(
    state: AppState, onHardware: (Boolean) -> Unit, onRemember: (Boolean) -> Unit,
    onCacheEnabled: (Boolean) -> Unit, onCacheSizeGb: (Int) -> Unit, onClearCache: () -> Unit,
    onLibraryTextSave: suspend (LibraryText) -> Unit,
) {
    var showLicenses by remember { mutableStateOf(false) }
    var cacheSizeMenu by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var editLibraryText by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repositoryUrl = stringResource(R.string.repository_url)
    if (editLibraryText) LibraryTextEditor(state.libraryText, onDismiss = { editLibraryText = false }, onSave = onLibraryTextSave)
    if (confirmClearCache) AlertDialog(
        onDismissRequest = { confirmClearCache = false },
        title = { Text("清除播放缓存？") },
        text = { Text("将清除 ${cacheSizeLabel(state.playbackCacheUsedBytes)} 已缓存的视频数据，再次播放时会重新下载。") },
        confirmButton = {
            TextButton(onClick = { confirmClearCache = false; onClearCache() }, enabled = !state.playbackCacheBusy) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("取消") } },
    )
    if (showLicenses) {
        val notice = remember { context.assets.open("mpv/NOTICE.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("开源组件") },
            text = { SelectionContainer { Text(notice, fontSize = 12.sp,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { uriHandler.openUri("https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17") }) { Text("组件来源") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        SectionLabel("播放内核")
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(MintColor.copy(alpha = .1f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Memory, null, tint = MintColor) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("mpv", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp)); Text("默认内核 · libmpv", color = MutedColor, fontSize = 12.sp)
                }
                Tag("首版")
            }
            HorizontalDivider(color = LineColor, modifier = Modifier.padding(horizontal = 20.dp))
            Text("后续支持 ExoPlayer，届时可选择默认内核。", fontSize = 12.sp, color = MutedColor, modifier = Modifier.padding(20.dp))
        }
        Spacer(Modifier.height(16.dp)); SectionLabel("播放偏好")
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            PreferenceRow("优先硬件解码", "支持时优先使用设备解码器", state.preferHardware, onHardware)
            HorizontalDivider(color = LineColor, modifier = Modifier.padding(horizontal = 20.dp))
            PreferenceRow("记住播放位置", "从媒体库打开视频时继续上次进度；观看记录始终保存", state.rememberPosition, onRemember)
        }
        Text("硬件解码偏好将在下次打开视频时生效。", color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        SectionLabel("播放缓存")
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            PreferenceRow("启用播放缓存", "从播放位置持续缓存到片尾，退出后保留", state.playbackCacheEnabled, onCacheEnabled,
                enabled = state.playbackCacheReady && !state.playbackCacheBusy)
            HorizontalDivider(color = LineColor, modifier = Modifier.padding(horizontal = 20.dp))
            Box {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = state.playbackCacheReady && !state.playbackCacheBusy) { cacheSizeMenu = true }
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("缓存总大小", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${state.playbackCacheSizeGb}.00 GB", fontSize = 14.sp, color = MutedColor)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.UnfoldMore, "选择缓存容量", tint = MutedColor, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = cacheSizeMenu, onDismissRequest = { cacheSizeMenu = false }) {
                    PlaybackCacheSizesGb.forEach { sizeGb ->
                        DropdownMenuItem(
                            text = { Text("$sizeGb GB${if (sizeGb == state.playbackCacheSizeGb) " ✓" else ""}") },
                            onClick = { cacheSizeMenu = false; onCacheSizeGb(sizeGb) },
                        )
                    }
                }
            }
            HorizontalDivider(color = LineColor, modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                Modifier.fillMaxWidth().clickable(enabled = state.playbackCacheReady && !state.playbackCacheBusy) { confirmClearCache = true }
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("清除缓存", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (state.playbackCacheBusy) "处理中…" else if (!state.playbackCacheReady) "正在统计…" else cacheSizeLabel(state.playbackCacheUsedBytes), fontSize = 14.sp, color = MutedColor)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.ChevronRight, null, tint = MutedColor, modifier = Modifier.size(20.dp))
            }
        }
        Text("达到容量上限会自动淘汰最久未使用的缓存；关闭后保留已有缓存，可手动清除。", color = MutedColor, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        state.playbackCacheError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        SectionLabel("首页文案")
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            Row(Modifier.fillMaxWidth().clickable { editLibraryText = true }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("修改首页文字", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("${state.libraryText.firstLine} / ${state.libraryText.secondLine}", color = MutedColor, fontSize = 12.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("仅修改内容，保留原有两行标题与文字样式", color = MutedColor, fontSize = 11.sp)
                }
                Icon(Icons.Outlined.ChevronRight, "修改首页文字", tint = MutedColor, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp)); SectionLabel("关于")
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            Row(Modifier.fillMaxWidth().padding(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp)); Text("自己的收藏，自己的播放器。", color = MutedColor, fontSize = 12.sp)
                }
                Text("${dev.davplayer.app.BuildConfig.VERSION_NAME}\nmpv ${if (dev.davplayer.app.BuildConfig.DEBUG) "调试版" else "正式版"}", fontSize = 11.sp, color = MutedColor)
            }
            HorizontalDivider(color = LineColor, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().clickable { uriHandler.openUri(repositoryUrl) }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GitHub 仓库", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(repositoryUrl, color = MutedColor, fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.OpenInNew, null, tint = MutedColor, modifier = Modifier.size(20.dp))
            }
            TextButton(onClick = { showLicenses = true }, modifier = Modifier.padding(horizontal = 10.dp)) { Text("开源组件与许可") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun cacheSizeLabel(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.2f MB", bytes / 1_048_576.0)
    else -> formatSize(bytes)
}

@Composable
private fun PreferenceRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp)); Text(description, fontSize = 11.sp, color = MutedColor)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
