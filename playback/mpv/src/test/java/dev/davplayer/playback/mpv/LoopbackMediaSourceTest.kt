package dev.davplayer.playback.mpv

import dev.davplayer.playback.api.PlaybackRequest
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LoopbackMediaSourceTest {
    private val server = MockWebServer()
    private val consumer = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown(); consumer.connectionPool.evictAll() }

    private fun request(path: String = "/中文 影片.mkv", headers: Map<String, String> = mapOf("Authorization" to "Basic private-credential")) = PlaybackRequest(
        mediaId = "test", url = server.url(path).toString(), title = "中文 影片.mkv",
        headers = headers,
    )

    @Test fun streamsRangesWithoutExposingServerCredentials() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/12")
            .setHeader("Accept-Ranges", "bytes").setBody("4567"))
        LoopbackMediaSource(request()).use { source ->
            assertFalse(source.url.contains("private-credential"))
            assertFalse(source.url.contains("中文"))
            consumer.newCall(Request.Builder().url(source.url).header("Range", "bytes=4-7").build()).execute().use {
                assertEquals(206, it.code)
                assertEquals("bytes 4-7/12", it.header("Content-Range"))
                assertEquals("4567", it.body!!.string())
                assertNull(it.header("Authorization"))
            }
            val remote = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Basic private-credential", remote.getHeader("Authorization"))
            assertEquals("bytes=4-7", remote.getHeader("Range"))
            assertEquals("identity", remote.getHeader("Accept-Encoding"))
            assertEquals(server.url("/中文 影片.mkv").encodedPath, remote.requestUrl!!.encodedPath)
        }
    }

    @Test fun sameOriginRedirectKeepsRangeAndAuthentication() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/actual.mp4"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("ok"))
        LoopbackMediaSource(request()).use { source ->
            consumer.newCall(Request.Builder().url(source.url).header("Range", "bytes=9-").build()).execute().use {
                assertEquals("ok", it.body!!.string())
            }
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/actual.mp4", second.path)
            assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
            assertEquals("bytes=9-", second.getHeader("Range"))
        }
    }

    @Test fun crossOriginRedirectReadsSignedUrlAndStripsPrivateHeaders() {
        MockWebServer().use { destination ->
            destination.start()
            val signed = destination.url("/video.mp4").newBuilder().addQueryParameter("sign", "a+b/c==").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", signed))
            destination.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/12").setBody("4567"))
            val errors = CopyOnWriteArrayList<String>()
            val privateHeaders = mapOf("Authorization" to "Basic private-credential", "Cookie" to "session=private",
                "X-Api-Key" to "private-key", "Referer" to "https://dav.invalid/private", "User-Agent" to "KPlayer-test")
            LoopbackMediaSource(request(headers = privateHeaders), onError = errors::add).use { source ->
                consumer.newCall(Request.Builder().url(source.url).header("Range", "bytes=4-7").header("If-Range", "\"etag\"").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("4567", it.body!!.string())
                    assertEquals("bytes 4-7/12", it.header("Content-Range"))
                }
                val original = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("Basic private-credential", original.getHeader("Authorization"))
                assertEquals("session=private", original.getHeader("Cookie"))
                val redirected = destination.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(signed, redirected.requestUrl)
                for (name in listOf("Authorization", "Cookie", "X-Api-Key", "Referer")) assertNull(redirected.getHeader(name))
                assertEquals("KPlayer-test", redirected.getHeader("User-Agent"))
                assertEquals("bytes=4-7", redirected.getHeader("Range"))
                assertEquals("\"etag\"", redirected.getHeader("If-Range"))
                assertTrue(errors.isEmpty())
            }
        }
    }

    @Test fun crossOriginRedirectPreservesHeadForEverySupportedRedirectStatus() {
        MockWebServer().use { destination ->
            destination.start()
            LoopbackMediaSource(request()).use { source ->
                for (status in listOf(301, 302, 303, 307, 308)) {
                    server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", destination.url("/video.mp4")))
                    destination.enqueue(MockResponse().setHeader("Content-Length", "123456"))
                    consumer.newCall(Request.Builder().url(source.url).head().build()).execute().use {
                        assertEquals(200, it.code)
                        assertEquals("123456", it.header("Content-Length"))
                        assertEquals("", it.body!!.string())
                    }
                    assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
                    val remote = destination.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("HEAD", remote.method)
                    assertNull(remote.getHeader("Authorization"))
                }
            }
        }
    }

    @Test fun everyRangeReadObtainsAFreshSignedLinkFromTheAuthenticatedOrigin() {
        MockWebServer().use { destination ->
            destination.start()
            LoopbackMediaSource(request()).use { source ->
                for (start in listOf(0, 2)) {
                    val signed = destination.url("/video.mp4?sign=token-$start")
                    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", signed))
                    destination.enqueue(MockResponse().setResponseCode(206).setBody("ok"))
                    val range = "bytes=$start-${start + 1}"
                    consumer.newCall(Request.Builder().url(source.url).header("Range", range).build()).execute().use {
                        assertEquals("ok", it.body!!.string())
                    }
                    assertEquals("Basic private-credential", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                    val remote = destination.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals(signed, remote.requestUrl)
                    assertNull(remote.getHeader("Authorization"))
                    assertEquals(range, remote.getHeader("Range"))
                }
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun privateHeadersStayRemovedThroughFurtherRedirectsAndAReturnToTheOrigin() {
        MockWebServer().use { destination ->
            destination.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/first")))
            destination.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/second?sign=next"))
            destination.enqueue(MockResponse().setResponseCode(308).setHeader("Location", server.url("/return?sign=final")))
            server.enqueue(MockResponse().setBody("ok"))
            LoopbackMediaSource(request(headers = mapOf("Authorization" to "Basic private-credential", "Cookie" to "session=private"))).use { source ->
                consumer.newCall(Request.Builder().url(source.url).header("Range", "bytes=0-").build()).execute().use {
                    assertEquals("ok", it.body!!.string())
                }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                val returned = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/return?sign=final", returned.path)
                for (remote in listOf(destination.takeRequest(2, TimeUnit.SECONDS)!!, destination.takeRequest(2, TimeUnit.SECONDS)!!, returned)) {
                    assertNull(remote.getHeader("Authorization"))
                    assertNull(remote.getHeader("Cookie"))
                    assertEquals("bytes=0-", remote.getHeader("Range"))
                }
            }
        }
    }

    @Test fun inheritedCookieJarAndAuthenticatorCannotSendCredentialsToTheDirectLink() {
        MockWebServer().use { destination ->
            destination.start()
            val cookieReads = java.util.concurrent.atomic.AtomicInteger()
            val authentications = java.util.concurrent.atomic.AtomicInteger()
            val shared = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    cookieReads.incrementAndGet()
                    return listOf(Cookie.Builder().name("session").value("private").domain(url.host).build())
                }
            }).authenticator { _, _ -> authentications.incrementAndGet(); null }.build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/requires-auth")))
            destination.enqueue(MockResponse().setResponseCode(401))
            val errors = CopyOnWriteArrayList<String>()
            try { LoopbackMediaSource(request(), shared, errors::add).use { source ->
                consumer.newCall(Request.Builder().url(source.url).build()).execute().use { assertEquals(401, it.code) }
                val remote = destination.takeRequest(2, TimeUnit.SECONDS)!!
                assertNull(remote.getHeader("Authorization"))
                assertNull(remote.getHeader("Cookie"))
                assertEquals(0, cookieReads.get())
                assertEquals(0, authentications.get())
                assertTrue(errors.single().contains("直链认证失败"))
            } } finally { shared.connectionPool.evictAll() }
        }
    }

    @Test fun rejectsRedirectUrlsContainingCredentials() {
        MockWebServer().use { destination ->
            destination.start()
            val unsafe = destination.url("/video.mp4").newBuilder().username("private-user").password("private-password").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", unsafe))
            val errors = CopyOnWriteArrayList<String>()
            LoopbackMediaSource(request(), onError = errors::add).use { source ->
                consumer.newCall(Request.Builder().url(source.url).build()).execute().use { assertEquals(502, it.code) }
                assertEquals(0, destination.requestCount)
                assertTrue(errors.single().contains("认证信息"))
                assertFalse(errors.single().contains("private-password"))
            }
        }
    }

    @Test fun redirectLoopsFailWithABoundedSafeDiagnostic() {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
        val errors = CopyOnWriteArrayList<String>()
        LoopbackMediaSource(request(), onError = errors::add).use { source ->
            consumer.newCall(Request.Builder().url(source.url).build()).execute().use { assertEquals(502, it.code) }
            assertEquals(6, server.requestCount)
            assertTrue(errors.single().contains("次数过多"))
        }
    }

    @Test fun httpsRedirectCannotDowngradeToPlainHttp() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", server.url("/insecure").toString()).body("".toResponseBody()).build()
        }.build()
        val errors = CopyOnWriteArrayList<String>()
        val secure = PlaybackRequest("test", "https://secure.invalid/video.mp4", "video.mp4")
        LoopbackMediaSource(secure, fake, errors::add).use { source ->
            consumer.newCall(Request.Builder().url(source.url).build()).execute().use { assertEquals(502, it.code) }
            assertEquals(1, calls.get())
            assertEquals(0, server.requestCount)
            assertTrue(errors.single().contains("HTTPS 降级"))
        }
    }

    @Test fun acceptsHeadWithoutSendingABody() {
        server.enqueue(MockResponse().setHeader("Content-Length", "123456").setHeader("Accept-Ranges", "bytes"))
        LoopbackMediaSource(request()).use { source ->
            consumer.newCall(Request.Builder().url(source.url).head().build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("123456", it.header("Content-Length"))
                assertEquals("", it.body!!.string())
            }
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun decodesChunkedTransferAndClosesTheLocalStream() {
        server.enqueue(MockResponse().setChunkedBody("video-data", 2))
        LoopbackMediaSource(request()).use { source ->
            consumer.newCall(Request.Builder().url(source.url).build()).execute().use {
                assertNull(it.header("Transfer-Encoding"))
                assertEquals("video-data", it.body!!.string())
            }
        }
    }

    @Test fun rejectsUnknownTokensAndWriteMethodsWithoutContactingServer() {
        LoopbackMediaSource(request()).use { source ->
            consumer.newCall(Request.Builder().url(source.url.replace("/media.", "/other.")).build()).execute().use {
                assertEquals(404, it.code)
            }
            consumer.newCall(Request.Builder().url(source.url).delete().build()).execute().use { assertEquals(405, it.code) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun authenticationFailureProducesASafeMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private server details"))
        val errors = CopyOnWriteArrayList<String>()
        LoopbackMediaSource(request(), onError = errors::add).use { source ->
            consumer.newCall(Request.Builder().url(source.url).build()).execute().use { assertEquals(401, it.code) }
            assertEquals(listOf("视频认证失败，请检查账号和密码"), errors.toList())
        }
    }

    @Test fun closeCancelsAnAlreadyStreamingRemoteBody() {
        val remoteCall = AtomicReference<Call>()
        val upstream = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { remoteCall.set(call) }
        }).build()
        server.enqueue(MockResponse().setBody("a".repeat(100)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val source = LoopbackMediaSource(request(), upstream)
        try {
            val local = java.net.URI(source.url)
            Socket(local.host, local.port).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write("GET ${local.rawPath} HTTP/1.1\r\nHost: ${local.host}\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    assertTrue(byte >= 0)
                    tail = (tail + byte.toChar()).takeLast(4)
                }
                assertEquals('a'.code, input.read()) // The upstream body is now being streamed.
                source.close()
                assertTrue(remoteCall.get().isCanceled())
            }
        } finally { source.close(); upstream.connectionPool.evictAll() }
    }
}
