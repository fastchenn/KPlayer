package dev.davplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.davplayer.app.BrowserState
import dev.davplayer.app.data.DemoLibrary
import dev.davplayer.app.data.MediaEntry
import okhttp3.HttpUrl.Companion.toHttpUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(browser: BrowserState, onBack: () -> Unit, onRefresh: () -> Unit, onOpen: (MediaEntry) -> Unit, onHome: () -> Unit) {
    var filter by remember(browser.url) { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember(browser.url) { mutableStateOf("") }
    var descending by remember { mutableStateOf(false) }
    val folder = browser.url.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
    val isRoot = browser.url.trimEnd('/') == browser.server.rootUrl.trimEnd('/')
    val filtered = remember(browser.entries, filter, query, descending) {
        browser.entries.filter { (filter == 0 || (filter == 1 && it.isVideo) || (filter == 2 && it.isDirectory)) && it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<MediaEntry> { !it.isDirectory }.thenComparator { a, b ->
                val result = a.name.compareTo(b.name, ignoreCase = true)
                if (descending) -result else result
            })
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(if (isRoot) browser.server.name else folder, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (isRoot) "WebDAV 媒体库" else browser.server.name, color = MutedColor, fontSize = 11.sp)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") } },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "搜索当前目录", tint = MutedColor) }
                IconButton(onClick = onRefresh, enabled = !browser.loading) { Icon(Icons.Outlined.Refresh, "刷新目录", tint = MutedColor) }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onHome, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Home, "返回媒体库", Modifier.size(18.dp), tint = MintColor)
            }
            Text("  /  ", color = MutedColor, fontSize = 12.sp)
            Text(if (isRoot) "根目录" else folder, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedColor, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Tag(if (browser.server.isDemo) "示例" else "WebDAV", if (browser.server.isDemo) WarmColor else MintColor)
        }
        if (browser.server.isDemo) {
            Text("离线示例 · 文件仅用于界面预览", color = WarmColor, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(WarmColor.copy(alpha = .07f), RoundedCornerShape(12.dp)).padding(12.dp))
        }
        if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索当前目录") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "视频", "文件夹").forEachIndexed { index, text -> FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(text, fontSize = 12.sp) }) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { descending = !descending }) { Icon(Icons.Outlined.SortByAlpha, if (descending) "改为名称升序" else "改为名称降序", tint = MutedColor, modifier = Modifier.size(19.dp)) }
        }
        Text(if (browser.loading) "正在读取目录…" else "${filtered.size} 个项目", color = MutedColor, fontSize = 11.sp, modifier = Modifier.padding(start = 22.dp, bottom = 10.dp))
        HorizontalDivider(color = LineColor, modifier = Modifier.padding(horizontal = 20.dp))
        when {
            browser.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            browser.error != null -> EmptyState(Icons.Outlined.CloudOff, "暂时无法打开目录", browser.error) {
                Button(onClick = onRefresh) { Text("重试") }
            }
            filtered.isEmpty() -> EmptyState(Icons.Outlined.FolderOpen, if (query.isEmpty()) "这里还没有文件" else "没有找到匹配的文件", if (filter == 0) "可以返回上一级，或刷新目录。" else "试试切换到「全部」。")
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(filtered, key = { it.url }) { entry ->
                    MediaRow(entry) { onOpen(entry) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(entry: MediaEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = entry.isDirectory || entry.isVideo, onClick = onClick).padding(horizontal = 10.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = if (entry.isDirectory) MintColor else if (entry.isVideo) Color(0xFFBFC4F5) else MutedColor
        Box(Modifier.size(48.dp).background(color.copy(alpha = .09f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(if (entry.isDirectory) Icons.Outlined.Folder else if (entry.isVideo) Icons.Outlined.PlayCircleOutline else Icons.Outlined.Description, null, tint = color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Text(if (entry.isDirectory) "文件夹" else formatSize(entry.sizeBytes), color = MutedColor, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (entry.isDirectory) Icon(Icons.Outlined.ChevronRight, null, tint = MutedColor, modifier = Modifier.size(18.dp))
        else Text(entry.name.substringAfterLast('.', "").uppercase(), fontSize = 10.sp, color = MutedColor)
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 750)
@Composable
private fun BrowserPreview() = DavTheme {
    Surface(color = CanvasColor) {
        BrowserScreen(BrowserState(DemoLibrary.server, DemoLibrary.server.rootUrl, DemoLibrary.list(DemoLibrary.server.rootUrl)), {}, {}, {}, {})
    }
}
