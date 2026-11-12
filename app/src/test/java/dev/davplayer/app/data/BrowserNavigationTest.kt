package dev.davplayer.app.data

import dev.davplayer.app.BrowserState
import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationTest {
    private val server = ServerConnection.create("NAS", "https://nas.example/dav/", "", "")
    private fun page(path: String = "", loading: Boolean = false, error: String? = null) = BrowserState(
        server, server.rootUrl + path,
        listOf(MediaEntry(server.rootUrl + path + "video.mkv", "video.mkv", false)),
        loading = loading, error = error,
    )

    @Test fun returningRestoresExactListingAndPageIdentity() {
        val history = BrowserNavigation()
        val root = page()
        val child = page("child/")
        assertNull(history.navigate(root, server, child.url))
        assertEquals(setOf(root.pageId), history.retainedPageIds)
        assertSame(root, history.navigate(child, server, root.url))
        assertTrue(history.retainedPageIds.isEmpty())
    }

    @Test fun breadcrumbJumpDiscardsDescendantsAndKeepsEarlierAncestors() {
        val history = BrowserNavigation()
        val root = page()
        val child = page("child/")
        val deeper = page("child/deeper/")
        val leaf = page("child/deeper/leaf/")
        history.navigate(root, server, child.url)
        history.navigate(child, server, deeper.url)
        history.navigate(deeper, server, leaf.url)
        assertSame(child, history.navigate(leaf, server, child.url))
        assertEquals(setOf(root.pageId), history.retainedPageIds)
        assertSame(root, history.navigate(child, server, root.url))
    }

    @Test fun returningDuringPendingRequestRestoresParent() {
        val history = BrowserNavigation()
        val root = page()
        val pending = page("child/", loading = true)
        history.navigate(root, server, pending.url)
        assertSame(root, history.navigate(pending, server, root.url))
    }

    @Test fun returningAfterFailedRequestRestoresParent() {
        val history = BrowserNavigation()
        val root = page()
        val failed = page("child/", error = "offline")
        history.navigate(root, server, failed.url)
        assertSame(root, history.navigate(failed, server, root.url))
    }

    @Test fun pendingAndFailedPagesAreNeverRetained() {
        val history = BrowserNavigation()
        history.navigate(page(loading = true), server, server.rootUrl + "child/")
        history.navigate(page(error = "offline"), server, server.rootUrl + "child/")
        assertTrue(history.retainedPageIds.isEmpty())
    }

    @Test fun unvisitedParentRequiresLoadingInsteadOfInventingAListing() {
        val history = BrowserNavigation()
        assertNull(history.navigate(page("child/"), server, server.rootUrl))
    }

    @Test fun snapshotsCannotCrossConnectionsEvenWithSameUrlAndId() {
        val history = BrowserNavigation()
        val root = page()
        history.navigate(root, server, server.rootUrl + "child/")
        val changedAccount = ServerConnection.create("NAS", server.rootUrl, "other", "password", server.id)
        assertNull(history.navigate(null, changedAccount, server.rootUrl))
        assertSame(root, history.navigate(null, server, server.rootUrl.trimEnd('/')))
    }

    @Test fun leavingSessionDropsSnapshotsAndStartsWithNewIdentity() {
        val history = BrowserNavigation()
        val root = page()
        history.navigate(root, server, server.rootUrl + "child/")
        history.clear()
        assertTrue(history.retainedPageIds.isEmpty())
        assertNull(history.navigate(null, server, root.url))
        assertNotEquals(root.pageId, page().pageId)
    }
}
