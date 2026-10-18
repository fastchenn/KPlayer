package dev.davplayer.app.ui

import androidx.compose.foundation.background
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

@Composable
fun RecentScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("继续上次的故事", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("观看记录会留在这里。", fontSize = 13.sp, color = MutedColor)
        Spacer(Modifier.height(36.dp))
        EmptyState(Icons.Outlined.History, "还没有观看记录", "观看记录功能尚未开放，可以从媒体库打开视频。")
    }
}

@Composable
fun SettingsScreen(state: AppState, onHardware: (Boolean) -> Unit, onRemember: (Boolean) -> Unit) {
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    if (showLicenses) {
        val notice = remember { context.assets.open("mpv/NOTICE.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showLicenses = false }, title = { Text("开源组件") },
            text = { SelectionContainer { Text(notice, fontSize = 12.sp,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { uriHandler.openUri("https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17") }) { Text("组件来源") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("让播放更合心意", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("简单一点，专注影片。", color = MutedColor, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
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
            PreferenceRow("记住播放位置", "断点续播将在后续版本提供", state.rememberPosition, onRemember, enabled = false)
        }
        Text("硬件解码偏好将在下次打开视频时生效。", color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        Spacer(Modifier.height(12.dp)); SectionLabel("关于")
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
            Row(Modifier.fillMaxWidth().padding(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp)); Text("自己的收藏，自己的播放器。", color = MutedColor, fontSize = 12.sp)
                }
                Text("0.2.3\nmpv 测试版", fontSize = 11.sp, color = MutedColor)
            }
            TextButton(onClick = { showLicenses = true }, modifier = Modifier.padding(horizontal = 10.dp)) { Text("开源组件与许可") }
        }
        Spacer(Modifier.height(24.dp))
    }
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
