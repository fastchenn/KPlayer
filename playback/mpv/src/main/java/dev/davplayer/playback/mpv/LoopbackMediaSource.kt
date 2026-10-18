package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.PlaybackRequest
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A per-playback streaming endpoint. libmpv only receives an unguessable loopback URL;
 * server credentials and TLS verification stay in OkHttp. No media is cached on disk.
 * Only GET/HEAD for this exact file are accepted, including byte-range requests.
 */
internal class LoopbackMediaSource(
    private val request: PlaybackRequest,
    client: OkHttpClient = defaultClient,
    private val onError: (String) -> Unit = {},
) : Closeable {
    private val origin = request.url.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty()) { "Credentials must use request headers" }
    }
    // Enforce redirect policy even when an injected/shared client has different defaults.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).build()
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val token = UUID.randomUUID().toString()
    private val extension = request.title.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
    private val path = "/$token/media.$extension"
    val url = "http://127.0.0.1:${server.localPort}$path"
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), {
        Thread(it, "KPlayer-media-stream").apply { isDaemon = true }
    })
    @Volatile private var closed = false

    init {
        thread(name = "KPlayer-media-listener", isDaemon = true) {
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.add(socket)
                if (closed) { sockets.remove(socket); socket.close(); break }
                try {
                    workers.execute {
                        socket.use {
                            try { serve(it) } catch (_: IOException) { /* Consumer disconnected. */ }
                            finally { sockets.remove(it) }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        var remaining = 16 * 1024
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (remaining-- > 0) {
                val byte = input.read()
                if (byte < 0) throw IOException("Incomplete request")
                if (byte == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
                bytes.add(byte.toByte())
            }
            throw IOException("Request headers too large")
        }
        val parts = line().split(' ')
        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
            respond(output, 400); return
        }
        if (parts[1] != path) { respond(output, 404); return }
        if (parts[0] !in setOf("GET", "HEAD")) { respond(output, 405); return }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) { respond(output, 400); return }
            headers[header.substring(0, colon).lowercase(java.util.Locale.ROOT)] = header.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null || (headers["content-length"] ?: "0") != "0") {
            respond(output, 400); return
        }
        val (response, call) = try { fetch(parts[0], headers) } catch (_: IllegalArgumentException) {
            respond(output, 400); return
        } catch (error: IOException) {
            if (!closed) onError(if (error is MediaSourceException) error.message!!
                else "无法读取视频，请检查网络、服务器证书和访问权限")
            respond(output, 502)
            return
        }
        try { response.use {
            if (response.code !in 200..299 && response.code != 416) {
                val directLink = !origin.sameOrigin(response.request.url)
                if (!closed) onError(when (response.code) {
                    401 -> if (directLink) "视频直链认证失败，请重新打开视频或检查服务器的直链配置" else "视频认证失败，请检查账号和密码"
                    403 -> if (directLink) "视频直链访问被拒绝，请重新打开视频或检查服务器的直链配置" else "当前账号没有播放此视频的权限"
                    404 -> "视频已被移动或删除"
                    else -> "读取视频失败（HTTP ${response.code}）"
                })
            }
            val body = response.body
            val length = body?.contentLength() ?: -1
            val text = buildString {
                append("HTTP/1.1 ${response.code} Response\r\nConnection: close\r\nCache-Control: no-store\r\n")
                // HEAD body length in OkHttp is zero; preserve the server's declared length.
                val declared = if (parts[0] == "HEAD") response.header("Content-Length")?.toLongOrNull() else length
                if (declared != null && declared >= 0) append("Content-Length: $declared\r\n")
                for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
                    response.header(name)?.takeIf { value -> value.all { c -> c.code in 32..126 } }
                        ?.let { append("$name: $it\r\n") }
                }
                append("\r\n")
            }
            output.write(text.toByteArray(Charsets.US_ASCII))
            if (parts[0] == "HEAD" || body == null) return
            val source = body.byteStream()
            val buffer = ByteArray(64 * 1024)
            while (!closed) {
                val count = try { source.read(buffer) } catch (_: IOException) {
                    if (!closed) onError("视频连接中断，请检查网络后重试")
                    break
                }
                if (count < 0) break
                // A failed downstream write is a seek/stop, not a remote playback error.
                output.write(buffer, 0, count)
            }
        } } finally { calls.remove(call) }
    }

    private fun fetch(method: String, incoming: Map<String, String>): Pair<Response, Call> {
        var current = origin
        var sendOriginalHeaders = true
        repeat(6) { attempt ->
            if (closed) throw IOException("Stream closed")
            val builder = Request.Builder().url(current).method(method, null)
            request.headers.forEach { (name, value) ->
                val normalized = name.lowercase(java.util.Locale.ROOT)
                // A signed CDN URL is usable without WebDAV credentials. Once a chain
                // leaves the original origin, never reintroduce private headers, even
                // if it redirects back. Keep a custom User-Agent for UA-bound links.
                val publicHeader = normalized in setOf("user-agent", "accept", "accept-language")
                if ((sendOriginalHeaders || publicHeader) && normalized !in setOf(
                        "host", "connection", "content-length", "transfer-encoding", "accept-encoding", "proxy-authorization",
                    )) {
                    builder.header(name, value)
                }
            }
            builder.header("Accept-Encoding", "identity")
            for (name in listOf("range", "if-range", "if-none-match", "if-modified-since")) {
                incoming[name]?.let { builder.header(name, it) }
            }
            val call = client.newCall(builder.build())
            calls.add(call)
            if (closed) call.cancel()
            val response = try { call.execute() } catch (error: IOException) { calls.remove(call); throw error }
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response to call
            try { response.use {
                val destination = response.header("Location")?.let(current::resolve)
                    ?: throw MediaSourceException("视频跳转地址无效或使用了不支持的协议")
                if (destination.username.isNotEmpty() || destination.password.isNotEmpty()) {
                    throw MediaSourceException("视频跳转地址包含不支持的认证信息，请检查服务器的直链配置")
                }
                if (current.isHttps && !destination.isHttps) {
                    throw MediaSourceException("视频直链从 HTTPS 降级到了 HTTP，请检查服务器的直链配置")
                }
                if (attempt == 5) throw MediaSourceException("视频跳转次数过多，请检查服务器的直链配置")
                if (!current.sameOrigin(destination)) sendOriginalHeaders = false
                current = destination
            } } finally { calls.remove(call) }
        }
        throw IOException("Cannot read video")
    }

    private class MediaSourceException(message: String) : IOException(message)

    private fun respond(output: OutputStream, status: Int) {
        output.write("HTTP/1.1 $status Response\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        closed = true
        server.close()
        calls.forEach(Call::cancel)
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port
    }
}
