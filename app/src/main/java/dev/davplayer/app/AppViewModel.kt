package dev.davplayer.app

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.davplayer.app.data.*
import dev.davplayer.playback.api.PlaybackRequest
import dev.davplayer.playback.api.ExternalSubtitle
import dev.davplayer.playback.api.PlayerEngine
import dev.davplayer.playback.mpv.MpvPlayerEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class HomeTab { LIBRARY, RECENT, SETTINGS }
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
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionStore = ConnectionStore(application)
    private val repository = WebDavRepository()
    private val preferences = application.getSharedPreferences("player_settings", Application.MODE_PRIVATE)
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(AppState(
        preferHardware = preferences.getBoolean("prefer_hardware", true),
        rememberPosition = preferences.getBoolean("remember_position", true),
    ))
    val state = mutableState.asStateFlow()
    val engine: PlayerEngine = MpvPlayerEngine(application)
    private val messages = Channel<String>(Channel.BUFFERED)
    val notifications = messages.receiveAsFlow()
    private var browsingJob: Job? = null

    init { reloadConnections() }

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

    fun setTab(tab: HomeTab) { mutableState.update { it.copy(tab = tab) } }
    fun openServer(server: ServerConnection) { loadDirectory(server, server.rootUrl) }
    fun refreshDirectory() { state.value.browser?.let { loadDirectory(it.server, it.url) } }

    fun openEntry(entry: MediaEntry) {
        val browser = state.value.browser ?: return
        val server = browser.server
        if (entry.isDirectory) loadDirectory(server, entry.url)
        else if (entry.isVideo) {
            val headers = if (server.username.isNotEmpty() || server.password.isNotEmpty())
                mapOf("Authorization" to Credentials.basic(server.username, server.password, Charsets.UTF_8)) else emptyMap()
            mutableState.update { it.copy(video = VideoTarget(server, entry)) }
            if (server.isDemo) { engine.stop(); return }
            engine.open(PlaybackRequest(
                mediaId = "${server.id}:${entry.url.toHttpUrl().encodedPath}",
                url = entry.url, title = entry.name, headers = headers, preferHardware = state.value.preferHardware,
                externalSubtitles = matchingSubtitles(entry, browser.entries).map {
                    ExternalSubtitle(url = it.url, title = it.name, headers = headers)
                },
            ))
        }
    }

    fun back() {
        if (state.value.video != null) {
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

    override fun onCleared() { engine.release() }
}
