package dev.davplayer.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.davplayer.app.VideoTarget
import dev.davplayer.playback.api.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(target: VideoTarget, engine: PlayerEngine, state: PlaybackState, onBack: () -> Unit) {
    val ready = engine.availability is EngineAvailability.Available
    Scaffold(containerColor = Color(0xFF080D0B), topBar = {
        TopAppBar(
            title = { Column {
                Text(target.entry.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(target.server.name, fontSize = 11.sp, color = MutedColor)
            } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回目录") } },
            actions = { Tag("mpv"); Spacer(Modifier.width(16.dp)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF080D0B)),
        )
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val landscape = maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (ready) {
                        AndroidView(factory = { EngineSurfaceView(it, engine) }, modifier = Modifier.fillMaxSize(), onRelease = { it.dispose() })
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Brush.radialGradient(listOf(Color(0xFF1F352B), Color(0xFF0D1712)))))
                        Column(Modifier.widthIn(max = 380.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(if (landscape) 44.dp else 70.dp).background(MintColor.copy(alpha = .09f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.PlayArrow, null, tint = MintColor, modifier = Modifier.size(if (landscape) 27.dp else 40.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("播放界面预览", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(state.message.orEmpty(), color = MutedColor, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(PanelColor, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(horizontal = 24.dp, vertical = if (landscape) 8.dp else 22.dp)) {
                    if (!landscape) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(target.entry.name.substringBeforeLast('.'), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Tag(if (target.server.isDemo) "示例" else "WebDAV", if (target.server.isDemo) WarmColor else MintColor)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${target.entry.name.substringAfterLast('.').uppercase()}  ·  ${formatSize(target.entry.sizeBytes)}", color = MutedColor, fontSize = 11.sp)
                        Spacer(Modifier.height(18.dp))
                    }
                    Slider(value = if ((state.durationMs ?: 0) > 0) (state.positionMs.toFloat() / state.durationMs!!).coerceIn(0f, 1f) else 0f,
                        onValueChange = { engine.seekTo((it * (state.durationMs ?: 0)).toLong()) }, enabled = ready && engine.capabilities.seek && (state.durationMs ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth().height(26.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatTime(state.positionMs), fontSize = 11.sp, color = MutedColor)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(state.durationMs), fontSize = 11.sp, color = MutedColor)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = if (landscape) 0.dp else 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { engine.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) }, enabled = ready && engine.capabilities.seek) { Icon(Icons.Outlined.Replay10, "后退十秒") }
                        Spacer(Modifier.width(26.dp))
                        FilledIconButton(onClick = { if (state.phase == PlaybackPhase.PLAYING) engine.pause() else engine.play() }, enabled = ready, modifier = Modifier.size(if (landscape) 44.dp else 58.dp)) {
                            Icon(if (state.phase == PlaybackPhase.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.phase == PlaybackPhase.PLAYING) "暂停" else "播放", Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(26.dp))
                        IconButton(onClick = { engine.seekTo(state.positionMs + 10_000) }, enabled = ready && engine.capabilities.seek) { Icon(Icons.Outlined.Forward10, "前进十秒") }
                    }
                    if (!landscape) {
                        HorizontalDivider(color = LineColor)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {}, enabled = false) { Icon(Icons.Outlined.Speed, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("倍速") }
                            TextButton(onClick = {}, enabled = false) { Icon(Icons.Outlined.Audiotrack, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("音轨") }
                            TextButton(onClick = {}, enabled = false) { Icon(Icons.Outlined.Subtitles, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("字幕") }
                        }
                    }
                }
            }
        }
    }
}

// Created exclusively by AndroidView's factory with an engine; never inflated from XML.
@SuppressLint("ViewConstructor")
private class EngineSurfaceView(context: Context, private val engine: PlayerEngine) : SurfaceView(context), SurfaceHolder.Callback {
    init { holder.addCallback(this) }
    override fun surfaceCreated(holder: SurfaceHolder) { engine.attachSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { engine.setSurfaceSize(width, height) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { engine.attachSurface(null) }
    fun dispose() { holder.removeCallback(this); engine.attachSurface(null) }
}
