package dev.davplayer.playback.mpv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.davplayer.playback.api.*
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground player. Commands and published state belong to the main thread. */
class MpvPlayerEngine(context: Context) : PlayerEngine {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    override val id = EngineId.MPV
    override val availability = try {
        MPVLib.ensureLoaded()
        EngineAvailability.Available
    } catch (_: LinkageError) {
        EngineAvailability.Unavailable("此设备无法加载播放组件，请安装匹配设备架构的版本。")
    }
    override val capabilities = PlayerCapabilities(seek = true, speed = true, audioTracks = true, subtitles = true)
    private val reducer = MpvPlaybackState()
    private val mutableState = MutableStateFlow(if (availability is EngineAvailability.Unavailable)
        PlaybackState(phase = PlaybackPhase.UNAVAILABLE, message = availability.reason) else reducer.value)
    override val state = mutableState.asStateFlow()
    private var client: NativeMpvClient? = null
    private var source: LoopbackMediaSource? = null
    private val subtitleSources = mutableListOf<LoopbackMediaSource>()
    private var subtitlesQueued = false
    private var subtitleSelectionChanged = false
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var request: PlaybackRequest? = null
    private var generation = 0L
    private var desiredPause = false
    private var released = false
    private var networkError: String? = null
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { hasFocus = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> client?.volume(20.0)
                AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; client?.volume(100.0) }
            }
        }, main)
        .build()

    override fun open(request: PlaybackRequest) {
        if (released || availability !is EngineAvailability.Available) return
        disposeSession()
        this.request = request
        desiredPause = false
        val speed = request.speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        reducer.prepare(speed)
        publish()
        if (surface?.isValid == true) startSession()
    }

    private fun startSession() {
        if (released || client != null || request == null || surface?.isValid != true) return
        if (!desiredPause && !acquireFocus()) { fail("无法获取音频焦点，请关闭其他正在占用音频的应用后重试"); return }
        val current = request!!
        val session = generation
        try {
            source = LoopbackMediaSource(current, onError = { message ->
                // A probe or an old byte-range request may fail while libmpv recovers.
                // Keep a safe diagnostic; only a native playback failure ends the session.
                main.post { if (!released && generation == session && networkError == null) networkError = message }
            })
            val native = NativeMpvClient(context,
                onProperty = { name, value ->
                    main.post {
                        if (!released && generation == session && client != null) {
                            if (name == "track-list") refreshTracks() else reducer.property(name, value)
                            publish()
                        }
                    }
                },
                onEvent = { event ->
                    main.post {
                        if (!released && generation == session && client != null) {
                            when (event) {
                                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                                    networkError = null
                                    reducer.fileLoaded()
                                    loadExternalSubtitles(current)
                                    refreshTracks()
                                }
                                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                                    reducer.endFile(client!!.eof(), networkError)
                                    if (reducer.value.phase == PlaybackPhase.ERROR) fail(reducer.value.message!!)
                                }
                                MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> fail("播放组件已停止，请重试")
                            }
                            publish()
                        }
                    }
                })
            client = native
            native.initialize(current.preferHardware, desiredPause, reducer.value.speed)
            native.attach(surface!!)
            if (width > 0 && height > 0) native.size(width, height)
            native.load(source!!.url, current.startPositionMs.coerceAtLeast(0))
        } catch (_: Exception) { fail("播放组件初始化失败，请重新打开视频或尝试关闭硬件解码") }
    }

    override fun attachSurface(surface: Surface?) {
        if (released || this.surface === surface) return
        // A Surface can be replaced during rotation without changing the user's pause intent.
        if (this.surface != null && client != null) { client?.pause(true); client?.detach() }
        this.surface = surface
        if (surface != null && surface.isValid) {
            if (client == null) startSession() else { client?.attach(surface); client?.pause(desiredPause) }
        }
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        this.width = width
        this.height = height
        client?.size(width, height)
    }

    override fun play() {
        if (released || availability !is EngineAvailability.Available) return
        if (state.value.phase == PlaybackPhase.ERROR) { request?.let(::open); return }
        if (request == null || !acquireFocus()) return
        desiredPause = false
        if (state.value.phase == PlaybackPhase.ENDED) { client?.seek(0); reducer.property("eof-reached", false) }
        client?.pause(false)
        reducer.property("pause", false)
        publish()
        if (client == null) startSession()
    }

    override fun pause() {
        if (released || request == null) return
        desiredPause = true
        client?.pause(true)
        reducer.property("pause", true)
        publish()
        abandonFocus()
    }

    override fun stop() {
        if (released) return
        request = null
        disposeSession()
        reducer.idle()
        if (availability is EngineAvailability.Available) publish()
    }

    override fun seekTo(positionMs: Long) {
        if (released || !state.value.seekable) return
        client?.seek(positionMs.coerceIn(0, state.value.durationMs ?: Long.MAX_VALUE))
    }

    override fun setSpeed(speed: Float) {
        if (!released && speed.isFinite() && speed in 0.25f..4f) client?.speed(speed)
    }

    override fun selectTrack(type: TrackType, trackId: String?) {
        if (released || (trackId != null && state.value.tracks.none { it.type == type && it.id == trackId })) return
        if (type == TrackType.SUBTITLE) subtitleSelectionChanged = true
        client?.select(type, trackId)
    }

    private fun loadExternalSubtitles(request: PlaybackRequest) {
        if (subtitlesQueued) return
        subtitlesQueued = true
        request.externalSubtitles.distinctBy { it.url }.forEach { subtitle ->
            // Subtitle errors must not end video playback. Each sidecar uses the
            // original WebDAV URL and the same authenticated redirect policy.
            runCatching {
                val source = LoopbackMediaSource(PlaybackRequest(
                    mediaId = request.mediaId, url = subtitle.url, title = subtitle.title, headers = subtitle.headers,
                ))
                subtitleSources.add(source)
                client?.addSubtitle(source.url, subtitle.title)
            }
        }
    }

    private fun refreshTracks() {
        val native = client ?: return
        val tracks = native.tracks()
        if (!subtitleSelectionChanged) {
            tracks.firstOrNull { it.type == TrackType.SUBTITLE && it.external }?.let {
                native.select(TrackType.SUBTITLE, it.id)
                subtitleSelectionChanged = true
            }
        }
        reducer.tracks(native.tracks())
    }

    override fun release() {
        if (released) return
        stop()
        released = true
        surface = null
        main.removeCallbacksAndMessages(null)
    }

    private fun acquireFocus(): Boolean {
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) client?.volume(100.0)
        return hasFocus
    }

    private fun abandonFocus() { audio.abandonAudioFocusRequest(focusRequest); hasFocus = false }

    private fun disposeSession() {
        generation++ // Ignore queued callbacks belonging to the old file.
        networkError = null
        source?.close()
        source = null
        subtitleSources.forEach { it.close() }
        subtitleSources.clear()
        subtitlesQueued = false
        subtitleSelectionChanged = false
        client?.close()
        client = null
        abandonFocus()
    }

    private fun fail(message: String) { disposeSession(); reducer.fail(message); publish() }
    private fun publish() {
        if (reducer.value.phase == PlaybackPhase.ENDED || reducer.value.phase == PlaybackPhase.ERROR) abandonFocus()
        mutableState.value = reducer.value
    }
}
