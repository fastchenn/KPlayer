package dev.davplayer.app.data

import dev.davplayer.app.BrowserState

/** Session-only ancestor snapshots. Returning never needs another directory request. */
internal class BrowserNavigation {
    private val ancestors = mutableListOf<BrowserState>()
    val retainedPageIds: Set<String> get() = ancestors.mapTo(mutableSetOf()) { it.pageId }

    fun navigate(current: BrowserState?, server: ServerConnection, url: String): BrowserState? {
        val target = ancestors.indexOfLast {
            it.server === server && it.url.trimEnd('/') == url.trimEnd('/')
        }
        if (target >= 0) {
            val restored = ancestors[target]
            ancestors.subList(target, ancestors.size).clear()
            return restored
        }
        if (current != null && current.server === server && !current.loading && current.error == null) {
            ancestors += current
        }
        return null
    }

    fun clear() = ancestors.clear()
}
