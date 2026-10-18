package dev.davplayer.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davplayer.app.AppState
import dev.davplayer.app.data.ServerConnection
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
fun LibraryScreen(
    state: AppState,
    onAdd: () -> Unit,
    onEdit: (ServerConnection) -> Unit,
    onDelete: (ServerConnection) -> Unit,
    onOpen: (ServerConnection) -> Unit,
    onDemo: () -> Unit,
    onRetry: () -> Unit,
) {
    var deleting by remember { mutableStateOf<ServerConnection?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF27483B), Color(0xFF1A3028))), RoundedCornerShape(28.dp),
            ).padding(24.dp)) {
                Column {
                    Text("YOUR PRIVATE CINEMA", color = MintColor, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(18.dp))
                    Text(state.libraryText.firstLine, fontSize = 29.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.libraryText.secondLine, fontSize = 29.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Text(state.libraryText.description, fontSize = 13.sp, color = Color(0xFFB5CBBF))
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = onAdd, enabled = !state.initializing && !state.storageError,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MintColor, contentColor = Color(0xFF153C2D))) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("添加连接", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item { SectionLabel("我的连接", if (state.servers.isEmpty()) null else "${state.servers.size} 个媒体源") }
        when {
            state.initializing -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp)) } }
            state.storageError -> item {
                EmptyState(Icons.Outlined.Lock, "暂时无法读取连接", "本机保存的配置无法解密，原配置仍保留。请重试读取。") {
                    OutlinedButton(onClick = onRetry) { Text("重试读取") }
                }
            }
            state.servers.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Storage, null, Modifier.size(32.dp), tint = MutedColor)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有连接媒体库", fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("添加 NAS 或其他 WebDAV 服务", color = MutedColor, fontSize = 12.sp)
                }
            }
            else -> items(state.servers, key = { it.id }) { server -> ServerCard(server, { onOpen(server) }, { onEdit(server) }, { deleting = server }) }
        }
        item {
            OutlinedCard(onClick = onDemo, shape = RoundedCornerShape(20.dp), colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Explore, null, Modifier.size(26.dp), tint = WarmColor)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("先逛逛示例媒体库", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("离线体验目录和播放页面", fontSize = 12.sp, color = MutedColor)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MutedColor)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = MutedColor)
                Spacer(Modifier.width(6.dp))
                Text("连接配置仅保存在本机", fontSize = 11.sp, color = MutedColor)
            }
        }
    }
    deleting?.let { server ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除连接？") },
            text = { Text("将从本机移除「${server.name}」的连接配置。服务器上的文件不会改变。") },
            confirmButton = { TextButton(onClick = { onDelete(server); deleting = null }) { Text("移除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun ServerCard(server: ServerConnection, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = PanelColor)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(MintColor.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Dns, null, tint = MintColor)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(server.rootUrl.toHttpUrl().host, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedColor, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp)); Tag("WebDAV")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "连接选项", tint = MutedColor) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑连接") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("移除连接") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 750)
@Composable
private fun LibraryPreview() = DavTheme {
    Surface(color = CanvasColor) { LibraryScreen(AppState(initializing = false), {}, {}, {}, {}, {}, {}) }
}
