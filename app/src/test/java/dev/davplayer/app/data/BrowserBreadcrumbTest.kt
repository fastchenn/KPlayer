package dev.davplayer.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class BrowserBreadcrumbTest {
    private val server = ServerConnection.create("NAS", "https://nas.example/dav/媒体库/", "", "")

    @Test fun nestedFoldersExposeEveryAncestorWithinConfiguredRoot() {
        val current = server.rootUrl.toHttpUrl().newBuilder()
            .addPathSegment("动漫").addPathSegment("虫师 S1+S2").addPathSegment("").build()
        val crumbs = browserBreadcrumbs(server, current.toString())
        assertEquals(listOf("根目录", "动漫", "虫师 S1+S2"), crumbs.map { it.name })
        assertEquals(server.rootUrl, crumbs.first().url)
        assertEquals(current.toString(), crumbs.last().url)
        assertEquals(listOf("dav", "媒体库", "动漫", ""), crumbs[1].url.toHttpUrl().pathSegments)
        assertTrue(crumbs.all { isWithinRoot(server.rootUrl.toHttpUrl(), it.url.toHttpUrl()) })
    }

    @Test fun rootWithOrWithoutTrailingSlashHasOneCrumb() {
        assertEquals(listOf(BrowserBreadcrumb("根目录", server.rootUrl)), browserBreadcrumbs(server, server.rootUrl))
        assertEquals(browserBreadcrumbs(server, server.rootUrl), browserBreadcrumbs(server, server.rootUrl.trimEnd('/')))
    }

    @Test fun encodedSlashesPercentSignsAndReservedCharactersStayInTheirFolder() {
        val current = server.rootUrl.toHttpUrl().newBuilder()
            .addPathSegment("A/B %2F #?+").addPathSegment("100% 中文").addPathSegment("").build()
        val crumbs = browserBreadcrumbs(server, current.toString())
        assertEquals(listOf("根目录", "A/B %2F #?+", "100% 中文"), crumbs.map { it.name })
        assertEquals(current.toString(), crumbs.last().url)
        assertEquals("A/B %2F #?+", crumbs[1].url.toHttpUrl().pathSegments[2])
        assertEquals(4, crumbs[1].url.toHttpUrl().pathSegments.size)
    }

    @Test fun linksDropQueryAndFragmentAndHandleMissingTrailingSlash() {
        val current = server.rootUrl.toHttpUrl().newBuilder().addPathSegment("虫师")
            .query("view=files").fragment("top").build()
        val crumb = browserBreadcrumbs(server, current.toString()).last()
        assertEquals("虫师", crumb.name)
        assertNull(crumb.url.toHttpUrl().query)
        assertNull(crumb.url.toHttpUrl().fragment)
        assertTrue(crumb.url.endsWith('/'))
    }

    @Test fun pathsOutsideConnectionDoNotExposeNavigationTargets() {
        for (url in listOf("https://other.example/dav/媒体库/", "http://nas.example/dav/媒体库/",
            "https://nas.example/dav/", "https://nas.example/dav/媒体库-other/")) {
            assertTrue(url, browserBreadcrumbs(server, url).isEmpty())
        }
    }

    @Test fun serverRootAtSlashAndRepeatedFolderNamesHaveDistinctTargets() {
        val rootServer = ServerConnection.create("NAS", "https://nas.example/", "", "")
        val crumbs = browserBreadcrumbs(rootServer, "https://nas.example/series/series/")
        assertEquals(listOf("根目录", "series", "series"), crumbs.map { it.name })
        assertEquals(listOf("https://nas.example/", "https://nas.example/series/", "https://nas.example/series/series/"), crumbs.map { it.url })
    }
}
