package dev.davplayer.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
private val PlayerActionSize = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    target: VideoTarget,
    engine: PlayerEngine,
    state: PlaybackState,
    onBack: () -> Unit,
    onLoadEpisodeDirectory: suspend (VideoTarget) -> Unit,
    onSelectEpisode: (String) -> Unit,
) {
    val ready = engine.availability is EngineAvailability.Available && !target.server.isDemo
    val controllable = ready && state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED, PlaybackPhase.BUFFERING, PlaybackPhase.ENDED)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by remember(target.entry.url) { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var episodesOpen by rememberSaveable { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    val boost = remember(engine, target.entry.url) { HoldToDoubleSpeed(engine) }
    val swipe = remember(engine, target.entry.url) { SwipeToSeek(engine) }
    val seekPreview = swipe.preview
    val lifecycleOwner = LocalLifecycleOwner.current
    var gesturesEnabled by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val canBoost = !locked && ready && engine.capabilities.speed &&
        state.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING)
    val playing = state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING
    val canSwipeSeek = !locked && controllable && engine.capabilities.seek && state.seekable && (state.durationMs ?: 0) > 0

    PlayerWindowMode(fullscreen)
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
    val controlsBusy = menuOpen || episodesOpen || seeking || boost.active || seekPreview != null
    LaunchedEffect(fullscreen, locked, controlsVisible, controlsInteraction, controlsBusy, playing) {
        if (fullscreen && controlsVisible && (locked || playing) && !controlsBusy) {
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
    fun closeEpisodes() {
        episodesOpen = false
        controlsInteraction++
    }
    fun toggleLock() {
        boost.stop()
        swipe.cancel()
        locked = !locked
        controlsVisible = !locked
    }
    BackHandler(fullscreen) { if (!locked) leaveFullscreen() }

    if (episodesOpen) EpisodePicker(
        target = target,
        fullscreen = fullscreen,
        onLoadDirectory = onLoadEpisodeDirectory,
        onDismiss = ::closeEpisodes,
        onSelect = { entry ->
            closeEpisodes()
            onSelectEpisode(entry.url)
        },
    )

    Scaffold(
        containerColor = PlayerBackground,
        contentColor = Color.White,
        contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!fullscreen) TopAppBar(
                title = {
                    Column {
                        Text(target.entry.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(target.server.name, fontSize = 11.sp, color = MutedColor)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack, colors = playerIconButtonColors()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回目录") } },
                actions = { Tag("mpv"); Spacer(Modifier.width(16.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PlayerBackground),
            )
        },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val landscape = maxWidth > maxHeight
            val panelPadding = if (maxWidth < 360.dp) 12.dp else 24.dp
            // Keep the Surface at the same composition position when changing screen modes.
            Column(Modifier.fillMaxSize()) {
                PlayerVideo(
                    target = target, engine = engine, state = state, ready = ready, landscape = landscape,
                    fullscreen = fullscreen, canBoost = canBoost, canSeek = canSwipeSeek,
                    gesturesEnabled = gesturesEnabled, locked = locked,
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
                            .padding(horizontal = panelPadding, vertical = if (landscape) 8.dp else 22.dp),
                    ) {
                        if (!landscape) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(target.entry.name.substringBeforeLast('.'), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(10.dp))
                                Tag(if (target.server.isDemo) "示例" else "WebDAV", if (target.server.isDemo) WarmColor else MintColor)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("${target.entry.name.substringAfterLast('.').uppercase()}  ·  ${formatSize(target.entry.sizeBytes)}", color = MutedColor, fontSize = 11.sp)
                            Spacer(Modifier.height(18.dp))
                        }
                        PlayerProgress(engine, state, controllable, onSeeking = { seeking = it })
                        CacheSpeed(state, Modifier.padding(top = 4.dp))
                        PlayerTransport(engine, state, controllable, compact = landscape, modifier = Modifier.fillMaxWidth())
                        if (!landscape) HorizontalDivider(color = LineColor)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PlayerMediaOptions(engine, state, controllable, onMenuOpen = { menuOpen = it },
                                modifier = Modifier.weight(1f))
                            PlayerViewButtons(fullscreen = false, onEpisodes = { episodesOpen = true },
                                onFullscreen = { fullscreen = true; controlsVisible = true })
                        }
                    }
                }
            }
            if (fullscreen) {
                if (controlsVisible && !boost.active && seekPreview == null) {
                    if (!locked) Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .75f), Color.Transparent)))
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = ::leaveFullscreen, colors = playerIconButtonColors()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "退出全屏") }
                        Text(target.entry.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        CacheSpeed(state, Modifier.padding(horizontal = 8.dp))
                    }
                    val bottomGradient = if (locked) Modifier else Modifier.background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))),
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .then(bottomGradient)
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Bottom))
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        if (!locked) PlayerProgress(engine, state, controllable, compact = true,
                            onSeeking = { seeking = it; controlsInteraction++ })
                        FullscreenControls(
                            engine, state, controllable, locked,
                            onLock = ::toggleLock,
                            onExit = ::leaveFullscreen,
                            onEpisodes = { episodesOpen = true },
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
private fun CacheSpeed(state: PlaybackState, modifier: Modifier = Modifier) {
    val text = buildString {
        append("缓存速度 ${formatSize(state.networkBytesPerSecond.coerceAtLeast(0))}/s")
        state.cacheProgress?.let { progress ->
            append(" · 已存 ${formatSize(progress.storedBytes)}")
            if (progress.reachedEnd) append(" · 全片已缓存")
        }
    }
    Text(text, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun PlayerVideo(
    target: VideoTarget, engine: PlayerEngine, state: PlaybackState, ready: Boolean, landscape: Boolean,
    fullscreen: Boolean, canBoost: Boolean, canSeek: Boolean, gesturesEnabled: Boolean, locked: Boolean,
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
                if (!locked) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = engine::play) { Text("重试") }
                }
            }
            else -> Unit
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlayerProgress(
    engine: PlayerEngine, state: PlaybackState, controllable: Boolean,
    compact: Boolean = false, onSeeking: (Boolean) -> Unit,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentSeeking by rememberUpdatedState(onSeeking)
    DisposableEffect(Unit) { onDispose { currentSeeking(false) } }
    val duration = state.durationMs?.takeIf { it > 0 }
    val positionFraction = if (duration != null) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val trackColor = MaterialTheme.colorScheme.outline
    val seekEnabled = controllable && engine.capabilities.seek && state.seekable && duration != null
    val interactionSource = remember { MutableInteractionSource() }
    val sliderColors = SliderDefaults.colors(
        thumbColor = MintColor,
        disabledThumbColor = MintColor.copy(alpha = .38f),
        inactiveTrackColor = Color.Transparent,
    )
    Box(Modifier.fillMaxWidth().height(if (compact) 28.dp else 36.dp), contentAlignment = Alignment.Center) {
        Slider(
            value = dragFraction ?: positionFraction,
            onValueChange = { dragFraction = it; onSeeking(true) },
            onValueChangeFinished = {
                dragFraction?.let { engine.seekTo((it * (duration ?: 0)).toLong()) }
                dragFraction = null
                onSeeking(false)
            },
            enabled = seekEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = sliderColors,
            interactionSource = interactionSource,
            // Only the visual thumb shrinks; Slider retains its accessible touch target.
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = sliderColors,
                    enabled = seekEnabled,
                    thumbSize = DpSize(3.dp, 20.dp),
                )
            },
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
private fun FullscreenControls(
    engine: PlayerEngine,
    state: PlaybackState,
    controllable: Boolean,
    locked: Boolean,
    onLock: () -> Unit,
    onExit: () -> Unit,
    onEpisodes: () -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    onInteraction: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val mediaWidth = 228.dp
        val singleRow = maxWidth >= 640.dp
        if (locked) {
            // Leave the unlock button where it was before the other controls disappeared.
            Box(Modifier.fillMaxWidth().height(if (singleRow) PlayerActionSize else PlayerActionSize * 2)) {
                PlaybackLockButton(locked = true, onClick = onLock,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = PlayerActionSize * 2))
            }
            return@BoxWithConstraints
        }
        val splitMediaWidth = minOf(mediaWidth, (maxWidth - PlayerActionSize * 3).coerceAtLeast(0.dp))
        Column(Modifier.fillMaxWidth()) {
            if (!singleRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                PlayerMediaOptions(engine, state, controllable, onMenuOpen,
                    Modifier.width(splitMediaWidth))
                PlayerViewButtons(fullscreen = true, onEpisodes = onEpisodes, onFullscreen = onExit, onLock = onLock)
            }
            // Transport is centered in the full viewport, independently of option widths.
            Box(Modifier.fillMaxWidth().height(PlayerActionSize)) {
                if (singleRow) {
                    PlayerMediaOptions(engine, state, controllable, onMenuOpen,
                        Modifier.align(Alignment.CenterStart).width(mediaWidth))
                    PlayerViewButtons(fullscreen = true, onEpisodes = onEpisodes, onFullscreen = onExit,
                        modifier = Modifier.align(Alignment.CenterEnd), onLock = onLock)
                }
                PlayerTransport(engine, state, controllable, compact = true,
                    modifier = Modifier.align(Alignment.Center), onInteraction = onInteraction)
            }
        }
    }
}

@Composable
private fun playerIconButtonColors() = IconButtonDefaults.iconButtonColors(
    contentColor = MintColor,
    disabledContentColor = MintColor.copy(alpha = .38f),
)

@Composable
private fun PlayerTransport(
    engine: PlayerEngine, state: PlaybackState, controllable: Boolean, compact: Boolean,
    modifier: Modifier = Modifier, onInteraction: () -> Unit = {},
) {
    Row(modifier.padding(vertical = if (compact) 0.dp else 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onInteraction(); engine.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) }, enabled = controllable && engine.capabilities.seek && state.seekable, colors = playerIconButtonColors()) { Icon(Icons.Outlined.Replay10, "后退十秒") }
        Spacer(Modifier.width(if (compact) 12.dp else 26.dp))
        val playing = state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.BUFFERING
        FilledIconButton(onClick = { onInteraction(); if (playing) engine.pause() else engine.play() }, enabled = controllable, modifier = Modifier.size(if (compact) 44.dp else 58.dp)) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(30.dp))
        }
        Spacer(Modifier.width(if (compact) 12.dp else 26.dp))
        IconButton(onClick = { onInteraction(); engine.seekTo(state.positionMs + 10_000) }, enabled = controllable && engine.capabilities.seek && state.seekable, colors = playerIconButtonColors()) { Icon(Icons.Outlined.Forward10, "前进十秒") }
    }
}

@Composable
private fun PlayerMediaOptions(
    engine: PlayerEngine, state: PlaybackState, controllable: Boolean,
    onMenuOpen: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var speedMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<TrackType?>(null) }
    val currentMenuOpen by rememberUpdatedState(onMenuOpen)
    LaunchedEffect(speedMenu, trackMenu) { currentMenuOpen(speedMenu || trackMenu != null) }
    DisposableEffect(Unit) { onDispose { currentMenuOpen(false) } }
    BoxWithConstraints(modifier) {
        val labelWidth = with(LocalDensity.current) { 37.sp.toDp() }
        val showLabels = maxWidth / 3 >= 24.dp + labelWidth
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MediaOptionButton(Icons.Outlined.Speed, "${state.speed}×", "倍速 ${state.speed}×",
                    showLabels, controllable && engine.capabilities.speed, onClick = { speedMenu = true })
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        DropdownMenuItem(text = { Text("${speed}×${if (speed == state.speed) " ✓" else ""}") },
                            onClick = { engine.setSpeed(speed); speedMenu = false })
                    }
                }
            }
            listOf(TrackType.AUDIO, TrackType.SUBTITLE).forEach { type ->
                val tracks = state.tracks.filter { it.type == type }
                val audio = type == TrackType.AUDIO
                val title = if (audio) "音轨" else "字幕"
                Box(Modifier.weight(1f)) {
                    MediaOptionButton(if (audio) Icons.Outlined.Audiotrack else Icons.Outlined.Subtitles,
                        title, title, showLabels, controllable && tracks.isNotEmpty(), onClick = { trackMenu = type })
                    DropdownMenu(expanded = trackMenu == type, onDismissRequest = { trackMenu = null },
                        modifier = Modifier.heightIn(max = 240.dp)) {
                        DropdownMenuItem(text = { Text("关闭${if (tracks.none { it.selected }) " ✓" else ""}") },
                            onClick = { engine.selectTrack(type, null); trackMenu = null })
                        tracks.forEach { track ->
                            DropdownMenuItem(text = { Text(track.label + if (track.selected) " ✓" else "") },
                                onClick = { engine.selectTrack(type, track.id); trackMenu = null })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaOptionButton(
    icon: ImageVector,
    label: String,
    description: String,
    showLabel: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(2.dp)) {
        Icon(icon, if (showLabel) null else description, Modifier.size(if (showLabel) 17.dp else 22.dp))
        if (showLabel) {
            Spacer(Modifier.width(4.dp))
            Text(label, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun PlayerViewButtons(
    fullscreen: Boolean,
    onEpisodes: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onLock: (() -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (onLock != null) PlaybackLockButton(locked = false, onClick = onLock)
        IconButton(onClick = onEpisodes, modifier = Modifier.size(PlayerActionSize), colors = playerIconButtonColors()) {
            Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, "选集")
        }
        IconButton(onClick = onFullscreen, modifier = Modifier.size(PlayerActionSize), colors = playerIconButtonColors()) {
            Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                if (fullscreen) "退出全屏" else "全屏播放")
        }
    }
}

@Composable
private fun PlaybackLockButton(locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(PlayerActionSize),
        colors = if (locked) IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = .65f), contentColor = MintColor,
        ) else playerIconButtonColors(),
    ) {
        Icon(
            if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            if (locked) "解锁播放控件" else "锁定播放控件",
        )
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
