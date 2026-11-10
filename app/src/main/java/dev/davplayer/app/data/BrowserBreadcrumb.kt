package dev.davplayer.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class BrowserBreadcrumb(val name: String, val url: String)

/** Only expose ancestors inside this connection, preserving encoded folder names. */
internal fun browserBreadcrumbs(server: ServerConnection, directoryUrl: String): List<BrowserBreadcrumb> {
    val root = server.rootUrl.toHttpUrl()
    val directory = directoryUrl.toHttpUrl()
    if (!isWithinRoot(root, directory)) return emptyList()
    val rootDepth = root.pathSegments.dropLastWhile { it.isEmpty() }.size
    val segments = directory.pathSegments.dropLastWhile { it.isEmpty() }
    return buildList {
        add(BrowserBreadcrumb("根目录", root.toString()))
        for (index in rootDepth until segments.size) {
            val ancestor = directory.newBuilder()
                .encodedPath("/" + directory.encodedPathSegments.take(index + 1).joinToString("/") + "/")
                .query(null).fragment(null).build()
            add(BrowserBreadcrumb(segments[index], ancestor.toString()))
        }
    }
}
