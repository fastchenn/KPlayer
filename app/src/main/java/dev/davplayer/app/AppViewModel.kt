package dev.davplayer.app

import android.app.Application
import android.annotation.SuppressLint
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.davplayer.app.data.*
import dev.davplayer.playback.api.PlaybackRequest
import dev.davplayer.playback.api.ExternalSubtitle
import dev.davplayer.playback.api.PlayerEngine
import dev.davplayer.playback.mpv.MpvPlayerEngine
import dev.davplayer.playback.mpv.PlaybackDiskCache
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class HomeTab { LIBRARY, RECENT, SETTINGS }
val PlaybackCacheSizesGb = listOf(2, 5, 10, 20, 50)
data class BrowserState(
    val server: ServerConnection,
    val url: String,
    val entries: List<MediaEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
data class VideoTarget(val server: ServerConnection, val entry: MediaEntry)
data class AppState(
    val servers: List<ServerConnection> = emptyList(),
    val initializing: Boolean = true,
    val storageError: Boolean = false,
    val tab: HomeTab = HomeTab.LIBRARY,
    val browser: BrowserState? = null,
    val video: VideoTarget? = null,
    val preferHardware: Boolean = true,
    val rememberPosition: Boolean = true,
    val playbackCacheEnabled: Boolean = true,
    val playbackCacheSizeGb: Int = 5,
    val playbackCacheUsedBytes: Long = 0,
    val playbackCacheReady: Boolean = false,
    val playbackCacheBusy: Boolean = false,
    val playbackCacheError: String? = null,
    val libraryText: LibraryText = LibraryText(),
    val watchHistory: List<WatchRecord> = emptyList(),
    val historyLoading: Boolean = true,
    val historyLoadError: Boolean = false,
    val historySaveError: Boolean = false,
    val historyOpeningId: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionStore = ConnectionStore(application)
    private val repository = WebDavRepository()
    private val preferences = application.getSharedPreferences("player_settings", Application.MODE_PRIVATE)
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(AppState(
        preferHardware = preferences.getBoolean("prefer_hardware", true),
        rememberPosition = preferences.getBoolean("remember_position", true),
        playbackCacheEnabled = preferences.getBoolean("playback_cache_enabled", true),
        playbackCacheSizeGb = preferences.getInt("playback_cache_size_gb", 5).takeIf { it in PlaybackCacheSizesGb } ?: 5,
        libraryText = runCatching {
            LibraryText(
                preferences.getString("library_first_line", null) ?: LibraryText().firstLine,
                preferences.getString("library_second_line", null) ?: LibraryText().secondLine,
                preferences.getString("library_description", null) ?: LibraryText().description,
            ).validated()
        }.getOrDefault(LibraryText()),
    ))
    val state = mutableState.asStateFlow()
    private val diskCache = PlaybackDiskCache(
        File(application.noBackupFilesDir, "playback"),
        enabled = mutableState.value.playbackCacheEnabled,
        maxBytes = mutableState.value.playbackCacheSizeGb * 1024L * 1024 * 1024,
    )
    private val cacheMutations = Mutex()
    val engine: PlayerEngine = MpvPlayerEngine(application, diskCache)
    private val messages = Channel<String>(Channel.BUFFERED)
    val notifications = messages.receiveAsFlow()
    private var browsingJob: Job? = null
    private var historyOpeningJob: Job? = null
    private var historyLoadingJob: Job? = null
    private val historyStore = WatchHistoryStore(File(application.noBackupFilesDir, "watch-history.bin"))
    private val historyWrites = Channel<List<WatchRecord>>(Channel.CONFLATED)
    // This writer drains the final checkpoint even when the ViewModel's own scope is cleared.
    private val historyWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history = WatchHistoryRecorder(persist = { historyWrites.trySend(it) })

    init {
        reloadConnections()
        historyWriter.launch {
            try {
                for (snapshot in historyWrites) {
                    val failed = runCatching { historyStore.save(snapshot) }.isFailure
                    viewModelScope.launch { mutableState.update { it.copy(historySaveError = failed) } }
                }
            } finally { historyWriter.cancel() }
        }
        viewModelScope.launch { history.records.collect { records -> mutableState.update { it.copy(watchHistory = records) } } }
        viewModelScope.launch { engine.state.collect(history::observe) }
        reloadHistory()
        viewModelScope.launch {
            diskCache.usedBytes.collect { bytes -> mutableState.update { it.copy(playbackCacheUsedBytes = bytes) } }
        }
        viewModelScope.launch {
            diskCache.writeIssue.collect { error -> mutableState.update { it.copy(playbackCacheError = error) } }
        }
        applyCacheSettings(refreshUsage = true)
    }

    fun reloadConnections() {
        viewModelScope.launch {
            mutations.withLock {
                mutableState.update { it.copy(initializing = true) }
                try {
                    val servers = withContext(Dispatchers.IO) { connectionStore.load() }
                    mutableState.update { it.copy(servers = servers, initializing = false, storageError = false) }
                } catch (_: Exception) {
                    mutableState.update { it.copy(initializing = false, storageError = true) }
                }
            }
        }
    }

    suspend fun saveConnection(server: ServerConnection) = mutations.withLock {
        check(!state.value.initializing && !state.value.storageError) { "连接配置尚未成功读取，请先重试" }
        val updated = state.value.servers.filterNot { it.id == server.id } + server
        withContext(Dispatchers.IO) { connectionStore.save(updated) }
        mutableState.update { it.copy(servers = updated) }
        messages.send("连接已保存")
    }

    fun deleteConnection(server: ServerConnection) {
        viewModelScope.launch {
            mutations.withLock {
                if (state.value.initializing || state.value.storageError) return@withLock
                val updated = state.value.servers.filterNot { it.id == server.id }
                try {
                    withContext(Dispatchers.IO) { connectionStore.save(updated) }
                    mutableState.update { it.copy(servers = updated) }
                    messages.send("连接已移除")
                } catch (_: Exception) { messages.send("移除失败，原连接仍然保留") }
            }
        }
    }

    fun setTab(tab: HomeTab) {
        if (tab != HomeTab.RECENT) cancelHistoryOpening()
        mutableState.update { it.copy(tab = tab) }
        if (tab == HomeTab.SETTINGS) applyCacheSettings()
    }
    fun openServer(server: ServerConnection) { cancelHistoryOpening(); loadDirectory(server, server.rootUrl) }
    fun refreshDirectory() { state.value.browser?.let { loadDirectory(it.server, it.url) } }

    fun openEntry(entry: MediaEntry) {
        val browser = state.value.browser ?: return
        val server = browser.server
        if (entry.isDirectory) loadDirectory(server, entry.url)
        else if (entry.isVideo) {
            if (state.value.historyLoading) viewModelScope.launch {
                historyLoadingJob?.join()
                if (state.value.browser === browser && state.value.video == null) playVideo(server, entry, browser.entries)
            } else playVideo(server, entry, browser.entries)
        }
    }

    private fun playVideo(server: ServerConnection, entry: MediaEntry, siblings: List<MediaEntry>, explicitStartMs: Long? = null) {
        history.finish(engine.state.value)
        val seed = WatchRecord.create(server, entry)
        val previous = seed?.let { created -> history.records.value.firstOrNull { it.id == created.id } }?.takeIf { it.sameFile(entry) }
        val startMs = explicitStartMs ?: if (state.value.rememberPosition) previous?.resumePositionMs ?: 0 else 0
        val headers = if (server.username.isNotEmpty() || server.password.isNotEmpty())
            mapOf("Authorization" to Credentials.basic(server.username, server.password, Charsets.UTF_8)) else emptyMap()
        mutableState.update { it.copy(video = VideoTarget(server, entry)) }
        if (server.isDemo) { engine.stop(); return }
        val mediaId = "${server.id}:${entry.url.toHttpUrl().encodedPath}"
        if (seed != null) history.start(
            seed.copy(durationMs = previous?.durationMs, positionMs = previous?.positionMs ?: 0, completed = previous?.completed ?: false),
            mediaId, startMs,
        )
        engine.open(PlaybackRequest(
            mediaId = mediaId,
            url = entry.url, title = entry.name, headers = headers, preferHardware = state.value.preferHardware,
            startPositionMs = startMs,
            externalSubtitles = matchingSubtitles(entry, siblings).map {
                ExternalSubtitle(url = it.url, title = it.name, headers = headers, sizeBytes = it.sizeBytes, modifiedAt = it.modifiedAt)
            },
            cacheEnabled = state.value.playbackCacheEnabled,
            cacheIdentity = seed?.id,
            sourceSizeBytes = entry.sizeBytes,
            sourceModifiedAt = entry.modifiedAt,
        ))
    }

    fun back() {
        if (state.value.video != null) {
            history.finish(engine.state.value)
            engine.stop()
            mutableState.update { it.copy(video = null) }
            return
        }
        val browser = state.value.browser ?: return
        if (browser.url.trimEnd('/') == browser.server.rootUrl.trimEnd('/')) {
            browsingJob?.cancel()
            mutableState.update { it.copy(browser = null) }
        } else {
            val current = browser.url.toHttpUrl()
            val index = current.pathSegments.indexOfLast { it.isNotEmpty() }
            val parent = current.newBuilder().removePathSegment(index).build().toString()
            loadDirectory(browser.server, parent)
        }
    }

    fun leaveBrowser() {
        browsingJob?.cancel()
        mutableState.update { it.copy(browser = null) }
    }

    fun setPreferHardware(enabled: Boolean) {
        preferences.edit { putBoolean("prefer_hardware", enabled) }
        mutableState.update { it.copy(preferHardware = enabled) }
    }

    fun setRememberPosition(enabled: Boolean) {
        preferences.edit { putBoolean("remember_position", enabled) }
        mutableState.update { it.copy(rememberPosition = enabled) }
    }

    @SuppressLint("UseKtx")
    suspend fun setLibraryText(text: LibraryText) {
        val validated = text.validated()
        val saved = withContext(Dispatchers.IO) {
            preferences.edit().putString("library_first_line", validated.firstLine)
                .putString("library_second_line", validated.secondLine).putString("library_description", validated.description).commit()
        }
        check(saved) { "保存失败，请稍后重试" }
        mutableState.update { it.copy(libraryText = validated) }
    }

    fun savePlaybackProgress() { history.checkpoint(engine.state.value) }
    fun onBackground() { engine.pause(); savePlaybackProgress() }

    fun reloadHistory() {
        if (historyLoadingJob?.isActive == true) return
        historyLoadingJob = viewModelScope.launch {
            mutableState.update { it.copy(historyLoading = true, historyLoadError = false) }
            try {
                val saved = withContext(Dispatchers.IO) { historyStore.load() }
                history.loaded(saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(historyLoadError = true) } }
            finally { mutableState.update { it.copy(historyLoading = false) } }
        }
    }

    fun retryHistorySave() { history.retrySave() }
    fun removeHistory(record: WatchRecord) { history.remove(record.id) }
    fun clearHistory() { history.clear() }

    private fun cancelHistoryOpening() {
        historyOpeningJob?.cancel()
        mutableState.update { it.copy(historyOpeningId = null) }
    }

    fun locateHistoryFolder(record: WatchRecord) {
        val server = state.value.servers.firstOrNull { record.matches(it) }
        val url = server?.let(record::resolve)?.toHttpUrl()
        if (server == null || url == null) { messages.trySend("原连接已移除或修改，无法定位文件夹"); return }
        val parent = url.newBuilder().encodedPath(url.encodedPath.substringBeforeLast('/') + "/").build().toString()
        cancelHistoryOpening()
        loadDirectory(server, parent)
    }

    fun openHistory(record: WatchRecord) {
        val server = state.value.servers.firstOrNull { record.matches(it) }
        val url = server?.let(record::resolve)?.toHttpUrl()
        if (server == null || url == null) { messages.trySend("原连接已移除或修改，请从媒体库重新打开视频"); return }
        cancelHistoryOpening()
        historyOpeningJob = viewModelScope.launch {
            mutableState.update { it.copy(historyOpeningId = record.id) }
            try {
                val cached = if (state.value.playbackCacheEnabled) withContext(Dispatchers.IO) { diskCache.cachedMedia(record.id) } else null
                if (cached != null) {
                    val entry = MediaEntry(url.toString(), record.name, false, cached.size, cached.version)
                    val root = server.rootUrl.toHttpUrl()
                    val siblings = cached.subtitles.mapNotNull { subtitle ->
                        val target = root.newBuilder().encodedPath(subtitle.encodedPath).query(null).fragment(null).build()
                        if (!isWithinRoot(root, target)) null else MediaEntry(target.toString(), subtitle.title, false, subtitle.sizeBytes, subtitle.modifiedAt)
                    }
                    ensureActive()
                    if (state.value.tab == HomeTab.RECENT && state.value.video == null && state.value.servers.any { record.matches(it) }) {
                        playVideo(server, entry, siblings, if (record.sameFile(entry)) record.resumePositionMs else 0)
                    }
                    return@launch
                }
                val parent = url.newBuilder().encodedPath(url.encodedPath.substringBeforeLast('/') + "/").build().toString()
                val directory = repository.list(server, parent)
                val entry = directory.entries.firstOrNull { it.isVideo && it.url.toHttpUrl().encodedPath == url.encodedPath }
                if (entry == null) { messages.send("视频已移动或删除，请从媒体库重新打开"); return@launch }
                ensureActive()
                if (state.value.tab == HomeTab.RECENT && state.value.video == null && state.value.servers.any { record.matches(it) }) {
                    playVideo(server, entry, directory.entries, if (record.sameFile(entry)) record.resumePositionMs else 0)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { messages.send("无法打开观看记录，请检查服务器连接后重试") }
            finally { if (state.value.historyOpeningId == record.id) mutableState.update { it.copy(historyOpeningId = null) } }
        }
    }

    fun setPlaybackCacheEnabled(enabled: Boolean) {
        preferences.edit { putBoolean("playback_cache_enabled", enabled) }
        mutableState.update { it.copy(playbackCacheEnabled = enabled) }
        applyCacheSettings()
    }

    fun setPlaybackCacheSizeGb(sizeGb: Int) {
        if (sizeGb !in PlaybackCacheSizesGb) return
        preferences.edit { putInt("playback_cache_size_gb", sizeGb) }
        mutableState.update { it.copy(playbackCacheSizeGb = sizeGb) }
        applyCacheSettings()
    }

    private fun applyCacheSettings(refreshUsage: Boolean = false) {
        viewModelScope.launch {
            cacheMutations.withLock {
                mutableState.update { it.copy(playbackCacheBusy = true) }
                try {
                    val settings = state.value
                    withContext(Dispatchers.IO) {
                        diskCache.configure(settings.playbackCacheEnabled, settings.playbackCacheSizeGb * 1024L * 1024 * 1024)
                        if (refreshUsage) diskCache.refreshUsage()
                    }
                    mutableState.update { it.copy(playbackCacheReady = true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    messages.send("无法读取播放缓存，请稍后重试")
                } finally { mutableState.update { it.copy(playbackCacheBusy = false) } }
            }
        }
    }

    fun clearPlaybackCache() {
        if (state.value.playbackCacheBusy) return
        viewModelScope.launch {
            cacheMutations.withLock {
                mutableState.update { it.copy(playbackCacheBusy = true) }
                try {
                    withContext(Dispatchers.IO) { diskCache.clear() }
                    messages.send("播放缓存已清除")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    messages.send("部分缓存未能清除，请稍后重试")
                } finally { mutableState.update { it.copy(playbackCacheBusy = false) } }
            }
        }
    }

    private fun loadDirectory(server: ServerConnection, url: String) {
        browsingJob?.cancel()
        mutableState.update { it.copy(browser = BrowserState(server, url, loading = true)) }
        browsingJob = viewModelScope.launch {
            try {
                val listing = repository.list(server, url)
                ensureActive()
                mutableState.update { it.copy(browser = BrowserState(server, listing.url, listing.entries)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                val message = when (error) {
                    is WebDavException -> error.message ?: "读取目录失败"
                    is java.net.UnknownHostException -> "无法找到服务器，请检查地址和网络"
                    is javax.net.ssl.SSLException -> "服务器证书无法验证，请检查 HTTPS 配置"
                    is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
                    else -> "无法连接服务器，请检查地址、网络和账号信息"
                }
                mutableState.update { it.copy(browser = BrowserState(server, url, error = message)) }
            }
        }
    }

    override fun onCleared() {
        history.finish(engine.state.value)
        historyWrites.close()
        engine.release()
    }
}
