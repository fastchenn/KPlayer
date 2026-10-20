package dev.davplayer.playback.mpv

import android.content.Context
import android.view.Surface
import dev.davplayer.playback.api.MediaTrack
import dev.davplayer.playback.api.TrackType
import `is`.xyz.mpv.MPVLib

/** The pinned upstream JNI bridge owns one global mpv_handle per process. */
internal class NativeMpvClient(
    private val context: Context,
    private val onProperty: (String, Any?) -> Unit,
    private val onEvent: (Int) -> Unit,
) : MPVLib.EventObserver {
    private var created = false
    private var seekCacheDisabled = false

    fun initialize(hardware: Boolean, paused: Boolean, speed: Float, cacheEnabled: Boolean = true) {
        check(owner == null) { "A libmpv session is already active" }
        owner = this
        try {
            MPVLib.create(context.applicationContext)
            created = true
            val options = mapOf(
                "msg-level" to "all=no", "config" to "no", "load-scripts" to "no",
                "ytdl" to "no", "access-references" to "no", "autoload-files" to "no",
                "profile" to "fast", "vo" to "gpu", "gpu-context" to "android", "opengl-es" to "yes",
                "ao" to "audiotrack,opensles", "hwdec" to if (hardware) "mediacodec,mediacodec-copy" else "no",
                "idle" to "yes", "force-window" to "no", "keep-open" to "yes",
                "pause" to if (paused) "yes" else "no", "speed" to speed.toString(),
                "cache" to if (cacheEnabled) "yes" else "no", "demuxer-max-bytes" to "67108864", "demuxer-max-back-bytes" to "16777216",
                "network-timeout" to "30", "save-position-on-quit" to "no", "http-proxy" to "",
                "input-default-bindings" to "no", "osc" to "no", "osd-level" to "0",
            )
            options.forEach { (name, value) ->
                check(MPVLib.setOptionString(name, value) >= 0) { "Unsupported libmpv option: $name" }
            }
            MPVLib.addObserver(this)
            MPVLib.init()
            for (name in listOf("time-pos", "duration", "speed", "demuxer-cache-time", "demuxer-cache-duration")) {
                MPVLib.observeProperty(name, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            }
            for (name in listOf("pause", "paused-for-cache", "eof-reached", "seekable")) MPVLib.observeProperty(name, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun attach(surface: Surface) {
        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("force-window", "yes")
    }

    fun detach() {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }

    fun size(width: Int, height: Int) { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    fun load(url: String, startMs: Long) {
        if (startMs > 0) MPVLib.setOptionString("start", (startMs / 1000.0).toString())
        MPVLib.command(arrayOf("loadfile", url, "replace"))
    }
    fun pause(paused: Boolean) { MPVLib.setPropertyBoolean("pause", paused) }
    fun seek(positionMs: Long) {
        // Force a real byte seek even when the target is in mpv's RAM cache. The disk
        // cache serves existing bytes locally; the next HTTP request anchors read-ahead.
        seekCacheDisabled = true
        MPVLib.setPropertyString("demuxer-seekable-cache", "no")
        MPVLib.command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute+exact"))
    }
    fun playbackRestarted() {
        if (seekCacheDisabled) {
            MPVLib.setPropertyString("demuxer-seekable-cache", "auto")
            seekCacheDisabled = false
        }
    }
    fun timelineSample(playing: Boolean, speed: Float): PlaybackTimelineSample {
        val seeking = MPVLib.getPropertyBoolean("seeking") == true
        // Paused/no-op seeks must not leave cache range reporting disabled indefinitely.
        if (seekCacheDisabled && !seeking) playbackRestarted()
        val position = MPVLib.getPropertyDouble("time-pos")?.let(MpvPlaybackState::secondsToMillis)
        val origin = MPVLib.getPropertyDouble("demuxer-start-time")?.takeIf { it.isFinite() }?.let { (it * 1000).toLong() } ?: 0
        return PlaybackTimelineSample(position, playing && !seeking, speed, origin)
    }
    fun speed(speed: Float) { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    fun volume(percent: Double) { MPVLib.setPropertyDouble("volume", percent) }
    fun eof() = MPVLib.getPropertyBoolean("eof-reached") == true
    fun seekableRangesJson(): String? = if (created) MPVLib.getPropertyString("demuxer-cache-state") else null
    fun addSubtitle(url: String, title: String) {
        // The pinned JNI command bridge is synchronous. mpv's async prefix keeps
        // remote subtitle probing off the UI thread; track-list reports completion.
        MPVLib.command(arrayOf("async", "sub-add", url, "auto", "$title（外挂）"))
    }
    fun select(type: TrackType, id: String?) { MPVLib.setPropertyString(if (type == TrackType.AUDIO) "aid" else "sid", id ?: "no") }

    fun tracks(): List<MediaTrack> = buildList {
        val count = (MPVLib.getPropertyInt("track-list/count") ?: 0).coerceIn(0, 256)
        repeat(count) { index ->
            val base = "track-list/$index"
            val type = when (MPVLib.getPropertyString("$base/type")) {
                "audio" -> TrackType.AUDIO
                "sub" -> TrackType.SUBTITLE
                else -> return@repeat
            }
            val id = MPVLib.getPropertyInt("$base/id")?.toString() ?: return@repeat
            val language = MPVLib.getPropertyString("$base/lang")
            val title = MPVLib.getPropertyString("$base/title")
            add(MediaTrack(id, type, listOfNotNull(title, language).filter(String::isNotBlank).joinToString(" · ")
                .ifEmpty { "${if (type == TrackType.AUDIO) "音轨" else "字幕"} $id" }, language,
                MPVLib.getPropertyBoolean("$base/selected") == true,
                MPVLib.getPropertyBoolean("$base/external") == true))
        }
    }

    fun close() {
        MPVLib.removeObserver(this)
        if (created) { MPVLib.destroy(); created = false }
        if (owner === this) owner = null
    }

    // Callbacks come from the native event thread. The owner posts them to main;
    // never destroy/join the native event thread from inside these callbacks.
    override fun eventProperty(property: String) = onProperty(property, null)
    override fun eventProperty(property: String, value: Long) = onProperty(property, value)
    override fun eventProperty(property: String, value: Boolean) = onProperty(property, value)
    override fun eventProperty(property: String, value: String) = onProperty(property, value)
    override fun eventProperty(property: String, value: Double) = onProperty(property, value)
    override fun event(eventId: Int) = onEvent(eventId)

    companion object { private var owner: NativeMpvClient? = null }
}
