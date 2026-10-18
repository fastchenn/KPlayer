package dev.davplayer.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class WebDavRepositoryTest {
    private val root = "https://nas.example/dav/".toHttpUrl()

    @Test fun normalizesRootAndPreservesPassword() {
        val connection = ServerConnection.create(" NAS ", "https://nas.example/dav", " user ", " pass ")
        assertEquals("NAS", connection.name)
        assertEquals("https://nas.example/dav/", connection.rootUrl)
        assertEquals("user", connection.username)
        assertEquals(" pass ", connection.password)
        assertFalse(connection.toString().contains("pass"))
    }

    @Test fun rejectsCredentialsInsideUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConnection.create("NAS", "https://user:pass@nas.example/dav/", "", "")
        }
    }

    @Test fun rejectsNonHttpServer() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConnection.create("NAS", "file:///tmp/video", "", "")
        }
    }

    @Test fun decodesNamesOnceAndIgnoresParentEntry() {
        val xml = multistatus(
            entry("/dav/", "<d:collection/>"),
            entry("/dav/%E7%94%B5%E5%BD%B1/", "<d:collection/>"),
            entry("/dav/100%25%20a%23b.mp4", size = "1234"),
            entry("/dav/a%26b.mkv", displayName = "a&amp;b.mkv"),
        )
        val entries = WebDavRepository.parseDirectory(xml, root, root)
        assertEquals(3, entries.size)
        assertEquals("电影", entries.first().name)
        assertTrue(entries.first().isDirectory)
        assertEquals("100% a#b.mp4", entries[1].name)
        assertEquals(1234L, entries[1].sizeBytes)
        assertEquals("a&b.mkv", entries[2].name)
    }

    @Test fun skipsFailedPropertiesExternalOriginsAndGrandchildren() {
        val xml = multistatus(
            entry("https://evil.example/dav/private.mkv"),
            entry("/outside/hidden.mkv"),
            entry("/dav/sub/deeper.mkv"),
            entry("/dav/denied.mkv", status = "403 Forbidden"),
            entry("/dav/ok.mkv"),
        )
        assertEquals(listOf("ok.mkv"), WebDavRepository.parseDirectory(xml, root, root).map { it.name })
    }

    @Test fun rejectsEntityDeclarations() {
        val xml = "<!DOCTYPE x [<!ENTITY file SYSTEM 'file:///etc/passwd'>]>" + multistatus(entry("/dav/ok.mkv"))
        assertThrows(WebDavException::class.java) { WebDavRepository.parseDirectory(xml, root, root) }
    }

    @Test fun rejectsNonDavXml() {
        assertThrows(WebDavException::class.java) { WebDavRepository.parseDirectory("<html/>", root, root) }
    }

    @Test fun onlyIncludesDirectChildrenInCurrentFolder() {
        val directory = root.resolve("movies/")!!
        val xml = multistatus(entry("/dav/movies/", "<d:collection/>"), entry("/dav/movies/film.mkv"), entry("/dav/other.mkv"))
        assertEquals(listOf("film.mkv"), WebDavRepository.parseDirectory(xml, directory, root).map { it.name })
    }

    @Test fun sendsDepthOneAndUtf8BasicAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(entry("/dav/video.mkv"))))
            val connection = ServerConnection.create("test", server.url("/dav/").toString(), "user", "password")
            val files = WebDavRepository().list(connection, connection.rootUrl)
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("1", request.getHeader("Depth"))
            assertEquals(Credentials.basic("user", "password", Charsets.UTF_8), request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("resourcetype"))
            assertEquals("video.mkv", files.entries.single().name)
        }
    }

    @Test fun refusesCrossOriginRedirectWithoutLeakingCredentials() = runBlocking {
        MockWebServer().use { source ->
            MockWebServer().use { other ->
                other.start()
                source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/dav/")))
                val connection = ServerConnection.create("test", source.url("/dav/").toString(), "user", "secret")
                try {
                    WebDavRepository().list(connection, connection.rootUrl)
                    fail("Cross-origin redirect must fail")
                } catch (_: WebDavException) { }
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test fun preservesPropfindDuringSameOriginRedirect() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/dav/folder/"))
            server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(entry("/dav/folder/video.mkv"))))
            val connection = ServerConnection.create("test", server.url("/dav/").toString(), "", "")
            val result = WebDavRepository().list(connection, connection.rootUrl)
            assertEquals("video.mkv", result.entries.single().name)
            assertEquals(server.url("/dav/folder/").toString(), result.url)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
        }
    }

    @Test fun mapsUnauthorizedResponseToUsefulMessage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val connection = ServerConnection.create("test", server.url("/dav/").toString(), "", "")
            try {
                WebDavRepository().list(connection, connection.rootUrl)
                fail("Authentication must fail")
            } catch (failure: WebDavException) { assertEquals("账号或密码不正确", failure.message) }
        }
    }

    @Test fun readsUtf16DirectoryResponses() = runBlocking {
        MockWebServer().use { server ->
            val xml = multistatus(entry("/dav/movie.mkv", displayName = "示例影片.mkv"))
            server.enqueue(MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-16")
                .setBody(Buffer().write(xml.toByteArray(Charsets.UTF_16))))
            val connection = ServerConnection.create("test", server.url("/dav/").toString(), "", "")
            assertEquals("示例影片.mkv", WebDavRepository().list(connection, connection.rootUrl).entries.single().name)
        }
    }

    private fun multistatus(vararg children: String) = "<d:multistatus xmlns:d=\"DAV:\">${children.joinToString("")}</d:multistatus>"
    private fun entry(href: String, resourceType: String = "", size: String = "0", displayName: String? = null, status: String = "200 OK") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>$resourceType</d:resourcetype><d:getcontentlength>$size</d:getcontentlength>${displayName?.let { "<d:displayname>$it</d:displayname>" }.orEmpty()}</d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat></d:response>"""
}
