package dev.davplayer.app.data

import java.util.UUID
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerConnection(
    val id: String,
    val name: String,
    val rootUrl: String,
    val username: String = "",
    val password: String = "",
    val isDemo: Boolean = false,
) {
    override fun toString() = "ServerConnection(id=$id, credentials=redacted)"

    companion object {
        fun create(name: String, url: String, username: String, password: String, id: String = UUID.randomUUID().toString()): ServerConnection {
            require(name.trim().isNotEmpty()) { "请给连接起一个名称" }
            val parsed = url.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException("请输入完整的 HTTP 或 HTTPS 地址")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "请在账号和密码栏填写凭据" }
            require(parsed.query == null && parsed.fragment == null) { "服务器地址请使用目录路径，不包含查询参数或片段" }
            val normalized = if (parsed.encodedPath.endsWith('/')) parsed else parsed.newBuilder().addPathSegment("").build()
            return ServerConnection(id, name.trim(), normalized.toString(), username.trim(), password)
        }
    }
}

data class MediaEntry(
    val url: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedAt: String? = null,
) {
    val isVideo: Boolean get() = !isDirectory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions
    companion object {
        private val videoExtensions = setOf("mp4", "mkv", "m4v", "webm", "mov", "avi", "ts", "m2ts", "rmvb", "flv", "mpg", "mpeg", "3gp", "wmv")
    }
}

data class DirectoryListing(val url: String, val entries: List<MediaEntry>)

internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean = scheme == other.scheme && host == other.host && port == other.port

internal fun isWithinRoot(root: HttpUrl, candidate: HttpUrl): Boolean =
    root.sameOrigin(candidate) && (candidate.encodedPath == root.encodedPath.trimEnd('/') || candidate.encodedPath.startsWith(root.encodedPath))
