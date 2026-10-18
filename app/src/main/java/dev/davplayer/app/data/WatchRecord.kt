package dev.davplayer.app.data

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** No credentials, origin addresses, query tokens or request headers are stored here. */
data class WatchRecord(
    val serverId: String,
    val connectionKey: String,
    val relativePath: String,
    val name: String,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val updatedAtMs: Long = 0,
    val completed: Boolean = false,
    val sizeBytes: Long? = null,
    val modifiedAt: String? = null,
) {
    val id: String = digest("$serverId\u0000$connectionKey\u0000$relativePath")
    val resumePositionMs: Long get() = if (completed || durationMs?.let { positionMs >= it } == true) 0 else positionMs

    fun matches(server: ServerConnection): Boolean = !server.isDemo && server.id == serverId && connectionKey == connectionKey(server)

    fun resolve(server: ServerConnection): String? {
        if (!matches(server) || relativePath.isEmpty() || relativePath.startsWith('/') || relativePath.length > 8192) return null
        val root = server.rootUrl.toHttpUrl()
        val result = root.newBuilder().encodedPath(root.encodedPath + relativePath).query(null).fragment(null).build()
        return result.takeIf { isWithinRoot(root, it) && it.encodedPath.removePrefix(root.encodedPath) == relativePath }?.toString()
    }

    fun sameFile(entry: MediaEntry): Boolean =
        (sizeBytes == null || entry.sizeBytes == null || sizeBytes == entry.sizeBytes) &&
            (modifiedAt == null || entry.modifiedAt == null || modifiedAt == entry.modifiedAt)

    companion object {
        fun create(server: ServerConnection, entry: MediaEntry): WatchRecord? {
            if (server.isDemo || !entry.isVideo) return null
            val root = server.rootUrl.toHttpUrl()
            val url = entry.url.toHttpUrlOrNull() ?: return null
            if (!root.sameOrigin(url) || !isWithinRoot(root, url)) return null
            val relative = url.encodedPath.removePrefix(root.encodedPath)
            return WatchRecord(server.id, connectionKey(server), relative, entry.name,
                sizeBytes = entry.sizeBytes, modifiedAt = entry.modifiedAt).takeIf { it.resolve(server) != null }
        }

        private fun connectionKey(server: ServerConnection) = digest("${server.rootUrl}\u0000${server.username}")
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
