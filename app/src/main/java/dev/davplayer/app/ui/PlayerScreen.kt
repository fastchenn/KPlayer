package dev.davplayer.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.davplayer.app.VideoTarget
import dev.davplayer.playback.api.*
import kotlinx.coroutines.delay

private val PlayerBackground = Color(0xFF080D0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(target: VideoTarget, engine: PlayerEngine, state: PlaybackState, onBack: () -> Unit) {
    val ready = engine.availability is EngineAvailability.Available && !target.server.isDemo
    val controllable = ready && state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED, PlaybackPhase.BUFFERING, PlaybackPhase.ENDED)
    var fullscreen by rememberSaveable(target.entry.url) { mutableStateOf(false) }
    var fullscreenPortrait by rememberSaveable(target.entry.url) { mutableStateOf(false) }
    var controlsVisible by remember(target.entry.url) { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    val boost = remember(engine, target.entry.url) { HoldToDoubleSpeed(engine) }
    val swipe = remember(engine, target.entry.url) { SwipeToSeek(engine) }
    val seekPreview = swipe.preview
    val lifecycleOwner = LocalLifecycleOwner.current
    var gesturesEnabled by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val canBoost = ready && engine.capabilities.speed &&
        state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING)
    val playing = state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING
    val canSwipeSeek = controllable && engine.capabilities.seek && state.seekable && (state.durationMs ?: 0) > 0

    PlayerWindowMode(fullscreen, fullscreenPortrait)
    DisposableEffect(boost, swipe, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            gesturesEnabled = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                boost.stop()
                swipe.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            boost.stop()
            swipe.cancel()
        }
    }
    LaunchedEffect(fullscreen, controlsVisible, controlsInteraction, menuOpen, seeking, boost.active, seekPreview != null, playing) {
        if (fullscreen && controlsVisible && playing && !menuOpen && !seeking && !boost.active && seekPreview == null) {
            delay(4_000)
            controlsVisible = false
        }
    }
    fun leaveFullscreen() {
        boost.stop()
        swipe.cancel()
        fullscreen = false
        controlsVisible = true
        menuOpen = false
    }
    BackHandler(fullscreen) { leaveFullscreen() }

    Scaffold(
        containerColor = PlayerBackground,
        contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!fullscreen) TopAppBar(
                title = {
                    Column {
                        Text(target.entry.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(target.server.name, fontSize = 11.sp, color = MutedColor)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回目录") } },
                actions = { Tag("mpv"); Spacer(Modifier.width(16.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlayerBackground),
            )
        },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val landscape = maxWidth > maxHeight
            // Keep the Surface at the same composition position when changing screen modes.
            Column(Modifier.fillMaxSize()) {
                PlayerVideo(
                    target = target, engine = engine, state = state, ready = ready, landscape = landscape,
                    fullscreen = fullscreen, canBoost = canBoost, canSeek = canSwipeSeek,
                    gesturesEnabled = gesturesEnabled,
                    onTap = { controlsVisible = !controlsVisible },
                    onHold = boost::start, onRelease = boost::stop,
                    onSeekStart = { swipe.start(it); controlsInteraction++ }, onSeekDrag = swipe::drag,
                    onSeekFinish = swipe::finish, onSeekCancel = swipe::cancel,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (!fullscreen) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(PanelColor, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .padding(horizontal = 24.dp, vertical = if (landscape) 8.dp else 22.dp),
                    ) {
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
                        PlayerProgress(engine, state, controllable, onSeeking = { seeking = it })
                        CacheSpeed(state.networkBytesPerSecond, Modifier.padding(top = 4.dp))
                        PlayerTransport(engine, state, controllable, compact = landscape, modifier = Modifier.fillMaxWidth())
                        if (!landscape) HorizontalDivider(color = LineColor)
                        PlayerOptions(
                            engine, state, controllable, fullscreen = false,
                            onFullscreen = { fullscreenPortrait = false; fullscreen = true; controlsVisible = true },
                            onMenuOpen = { menuOpen = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (fullscreen) {
                if (controlsVisible && !boost.active && seekPreview == null) {
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .75f), Color.Transparent)))
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = ::leaveFullscreen) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "退出全屏") }
                        Text(target.entry.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { boost.stop(); fullscreenPortrait = !fullscreenPortrait; controlsInteraction++ }) {
                            Icon(Icons.Outlined.ScreenRotation, "旋转屏幕方向", Modifier.size(20.dp))
                        }
                        CacheSpeed(state.networkBytesPerSecond, Modifier.padding(horizontal = 8.dp))
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom))
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        PlayerProgress(engine, state, controllable, onSeeking = { seeking = it; controlsInteraction++ })
                        FullscreenControls(
                            engine, state, controllable,
                            onExit = ::leaveFullscreen,
                            onMenuOpen = { menuOpen = it },
                            onInteraction = { controlsInteraction++ },
                        )
                    }
                }
                val gestureLabel = seekPreview?.label ?: if (boost.active) "2X" else null
                if (gestureLabel != null) {
                    Text(
                        text = gestureLabel, color = MintColor, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = .85f), blurRadius = 6f)),
                        modifier = Modifier.align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                            .padding(top = 36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheSpeed(bytesPerSecond: Long, modifier: Modifier = Modifier) {
    Text("缓存速度 ${formatSize(bytesPerSecond.coerceAtLeast(0))}/s", color = MutedColor, fontSize = 11.sp, modifier = modifier)
}

@Composable
private fun PlayerVideo(
    target: VideoTarget, engine: PlayerEngine, state: PlaybackState, ready: Boolean, landscape: Boolean,
    fullscreen: Boolean, canBoost: Boolean, canSeek: Boolean, gesturesEnabled: Boolean,
    onTap: () -> Unit, onHold: () -> Unit, onRelease: () -> Unit,
    onSeekStart: (Float) -> Unit, onSeekDrag: (Float) -> Unit,
    onSeekFinish: () -> Unit, onSeekCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTap by rememberUpdatedState(onTap)
    val currentHold by rememberUpdatedState(onHold)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentSeekStart by rememberUpdatedState(onSeekStart)
    val currentSeekDrag by rememberUpdatedState(onSeekDrag)
    val currentSeekFinish by rememberUpdatedState(onSeekFinish)
    val currentSeekCancel by rememberUpdatedState(onSeekCancel)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (ready) {
            AndroidView(
                factory = { EngineSurfaceView(it, engine) }, modifier = Modifier.fillMaxSize(),
                update = { it.keepScreenOn = state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING },
                onRelease = { it.dispose() },
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Brush.radialGradient(listOf(Color(0xFF1F352B), Color(0xFF0D1712)))))
            Column(Modifier.widthIn(max = 380.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (landscape) 44.dp else 70.dp).background(MintColor.copy(alpha = .09f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = MintColor, modifier = Modifier.size(if (landscape) 27.dp else 40.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("播放界面预览", fontWeight = FontWeight.Medium, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (target.server.isDemo) "这是离线示例，没有可播放的视频。请添加自己的 WebDAV 连接。" else state.message.orEmpty(), color = MutedColor, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        }
        // Vertical swipes are ignored; taps, holds and horizontal seeking are mutually exclusive.
        if (fullscreen && gesturesEnabled) Box(
            Modifier.matchParentSize().pointerInput(canBoost, canSeek) {
                detectPlayerGestures(
                    canBoost = canBoost, canSeek = canSeek,
                    onTap = { currentTap() },
                    onHold = { currentHold() }, onRelease = { currentRelease() },
                    onSeekStart = { currentSeekStart(it) }, onSeekDrag = { currentSeekDrag(it) },
                    onSeekFinish = { currentSeekFinish() }, onSeekCancel = { currentSeekCancel() },
                )
            },
        )
        if (ready) when (state.phase) {
            PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MintColor)
                Spacer(Modifier.height(12.dp))
                Text(if (state.phase == PlaybackPhase.PREPARING) "正在打开视频…" else "正在缓冲…", color = Color.White)
            }
            PlaybackPhase.ERROR -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.message ?: "播放失败", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = engine::play) { Text("重试") }
            }
            else -> Unit
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlayerProgress(engine: PlayerEngine, state: PlaybackState, controllable: Boolean, onSeeking: (Boolean) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentSeeking by rememberUpdatedState(onSeeking)
    DisposableEffect(Unit) { onDispose { currentSeeking(false) } }
    val duration = state.durationMs?.takeIf { it > 0 }
    val positionFraction = if (duration != null) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val trackColor = MaterialTheme.colorScheme.outline
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        Slider(
            value = dragFraction ?: positionFraction,
            onValueChange = { dragFraction = it; onSeeking(true) },
            onValueChangeFinished = {
                dragFraction?.let { engine.seekTo((it * (duration ?: 0)).toLong()) }
                dragFraction = null
                onSeeking(false)
            },
            enabled = controllable && engine.capabilities.seek && state.seekable && duration != null,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                inactiveTrackColor = Color.Transparent,
            ),
            track = {
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    drawRoundRect(color = trackColor,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
                    if (duration != null) for (range in state.cachedRanges) {
                        val start = (range.startMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
                        val end = (range.endMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
                        if (end > start) drawRect(
                            color = Color.White.copy(alpha = 0.65f),
                            topLeft = androidx.compose.ui.geometry.Offset(size.width * start, 0f),
                            size = androidx.compose.ui.geometry.Size(size.width * (end - start), size.height),
                        )
                    }
                    // A thin play-position line keeps cached segments visible on both sides of the thumb.
                    val height = 2.dp.toPx().coerceAtMost(size.height)
                    drawRect(color = MintColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - height) / 2),
                        size = androidx.compose.ui.geometry.Size(size.width * (dragFraction ?: positionFraction), height))
                }
            },
        )
    }
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime(dragFraction?.let { (it * (duration ?: 0)).toLong() } ?: state.positionMs), fontSize = 11.sp, color = MutedColor)
        Spacer(Modifier.weight(1f))
        Text(formatTime(state.durationMs), fontSize = 11.sp, color = MutedColor)
    }
}

@Composable
fun FullscreenControls(
    engine: PlayerEngine,
    state: PlaybackState,
    controllable: Boolean,
    onExit: () -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    onInteraction: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxWidth()) {
            if (!wide) Row(Modifier.align(Alignment.End)) {
                PlayerOptionButtons(engine, state, controllable, true, onExit, onMenuOpen)
            }
            // Transport is centered in the full viewport, independently of option widths.
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                PlayerTransport(engine, state, controllable, compact = true,
                    modifier = Modifier.align(Alignment.Center), onInteraction = onInteraction)
                if (wide) Row(Modifier.align(Alignment.CenterEnd)) {
                    PlayerOptionButtons(engine, state, controllable, true, onExit, onMenuOpen)
                }
            }
        }
    }
}

@Composable
private fun PlayerTransport(
    engine: PlayerEngine, state: PlaybackState, controllable: Boolean, compact: Boolean,
    modifier: Modifier = Modifier, onInteraction: () -> Unit = {},
) {
    Row(modifier.padding(vertical = if (compact) 0.dp else 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onInteraction(); engine.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) }, enabled = controllable && engine.capabilities.seek && state.seekable) { Icon(Icons.Outlined.Replay10, "后退十秒") }
        Spacer(Modifier.width(if (compact) 12.dp else 26.dp))
        val playing = state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING
        FilledIconButton(onClick = { onInteraction(); if (playing) engine.pause() else engine.play() }, enabled = controllable, modifier = Modifier.size(if (compact) 44.dp else 58.dp)) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(30.dp))
        }
        Spacer(Modifier.width(if (compact) 12.dp else 26.dp))
        IconButton(onClick = { onInteraction(); engine.seekTo(state.positionMs + 10_000) }, enabled = controllable && engine.capabilities.seek && state.seekable) { Icon(Icons.Outlined.Forward10, "前进十秒") }
    }
}

@Composable
fun PlayerOptions(
    engine: PlayerEngine, state: PlaybackState, controllable: Boolean, fullscreen: Boolean,
    onFullscreen: () -> Unit, onMenuOpen: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var speedMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<TrackType?>(null) }
    val currentMenuOpen by rememberUpdatedState(onMenuOpen)
    LaunchedEffect(speedMenu, trackMenu) { currentMenuOpen(speedMenu || trackMenu != null) }
    DisposableEffect(Unit) { onDispose { currentMenuOpen(false) } }
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { speedMenu = true }, enabled = controllable && engine.capabilities.speed) {
                Icon(Icons.Outlined.Speed, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp)); Text("${state.speed}×")
            }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    DropdownMenuItem(text = { Text("${speed}×${if (speed == state.speed) " ✓" else ""}") },
                        onClick = { engine.setSpeed(speed); speedMenu = false })
                }
            }
        }
        listOf(TrackType.AUDIO, TrackType.SUBTITLE).forEach { type ->
            val tracks = state.tracks.filter { it.type == type }
            Box {
                TextButton(onClick = { trackMenu = type }, enabled = controllable && tracks.isNotEmpty()) {
                    Icon(if (type == TrackType.AUDIO) Icons.Outlined.Audiotrack else Icons.Outlined.Subtitles, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text(if (type == TrackType.AUDIO) "音轨" else "字幕")
                }
                DropdownMenu(expanded = trackMenu == type, onDismissRequest = { trackMenu = null }, modifier = Modifier.heightIn(max = 240.dp)) {
                    DropdownMenuItem(text = { Text("关闭${if (tracks.none { it.selected }) " ✓" else ""}") },
                        onClick = { engine.selectTrack(type, null); trackMenu = null })
                    tracks.forEach { track ->
                        DropdownMenuItem(text = { Text(track.label + if (track.selected) " ✓" else "") },
                            onClick = { engine.selectTrack(type, track.id); trackMenu = null })
                    }
                }
            }
        }
        IconButton(onClick = onFullscreen) {
            Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (fullscreen) "退出全屏" else "全屏播放")
        }
    }
}

@Composable
private fun PlayerOptionButtons(
    engine: PlayerEngine, state: PlaybackState, controllable: Boolean, fullscreen: Boolean,
    onFullscreen: () -> Unit, onMenuOpen: (Boolean) -> Unit,
) {
    var speedMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<TrackType?>(null) }
    val currentMenuOpen by rememberUpdatedState(onMenuOpen)
    LaunchedEffect(speedMenu, trackMenu) { currentMenuOpen(speedMenu || trackMenu != null) }
    DisposableEffect(Unit) { onDispose { currentMenuOpen(false) } }
    Box {
        TextButton(onClick = { speedMenu = true }, enabled = controllable && engine.capabilities.speed,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Icon(Icons.Outlined.Speed, null, Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp)); Text("${state.speed}×")
        }
        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                DropdownMenuItem(text = { Text("${speed}×${if (speed == state.speed) " ✓" else ""}") },
                    onClick = { engine.setSpeed(speed); speedMenu = false })
            }
        }
    }
    listOf(TrackType.AUDIO, TrackType.SUBTITLE).forEach { type ->
        val tracks = state.tracks.filter { it.type == type }
        Box {
            TextButton(onClick = { trackMenu = type }, enabled = controllable && tracks.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Icon(if (type == TrackType.AUDIO) Icons.Outlined.Audiotrack else Icons.Outlined.Subtitles, null, Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp)); Text(if (type == TrackType.AUDIO) "音轨" else "字幕")
            }
            DropdownMenu(expanded = trackMenu == type, onDismissRequest = { trackMenu = null }, modifier = Modifier.heightIn(max = 240.dp)) {
                DropdownMenuItem(text = { Text("关闭${if (tracks.none { it.selected }) " ✓" else ""}") },
                    onClick = { engine.selectTrack(type, null); trackMenu = null })
                tracks.forEach { track ->
                    DropdownMenuItem(text = { Text(track.label + if (track.selected) " ✓" else "") },
                        onClick = { engine.selectTrack(type, track.id); trackMenu = null })
                }
            }
        }
    }
    IconButton(onClick = onFullscreen, modifier = Modifier.size(44.dp)) {
        Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
            if (fullscreen) "退出全屏" else "全屏播放", Modifier.size(22.dp))
    }
}

// Created exclusively by AndroidView's factory with an engine; never inflated from XML.
@SuppressLint("ViewConstructor")
private class EngineSurfaceView(context: Context, private val engine: PlayerEngine) : SurfaceView(context), SurfaceHolder.Callback {
    init { holder.addCallback(this) }
    override fun surfaceCreated(holder: SurfaceHolder) { engine.attachSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { engine.setSurfaceSize(width, height) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { engine.attachSurface(null) }
    fun dispose() { keepScreenOn = false; holder.removeCallback(this); engine.attachSurface(null) }
}
