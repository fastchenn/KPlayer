package dev.davplayer.app.data

import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

class WebDavException(message: String) : IOException(message)

class WebDavRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build(),
) {
    suspend fun list(server: ServerConnection, directoryUrl: String): DirectoryListing = withContext(Dispatchers.IO) {
        if (server.isDemo) return@withContext DirectoryListing(directoryUrl, DemoLibrary.list(directoryUrl))
        val root = server.rootUrl.toHttpUrl()
        var current = directoryUrl.toHttpUrl()
        if (!isWithinRoot(root, current)) throw WebDavException("目录不属于当前连接")
        repeat(6) { attempt ->
            val request = Request.Builder().url(current)
                .method("PROPFIND", PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .apply {
                    if (server.username.isNotEmpty() || server.password.isNotEmpty()) {
                        header("Authorization", Credentials.basic(server.username, server.password, Charsets.UTF_8))
                    }
                }.build()
            client.newCall(request).await().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val destination = response.header("Location")?.let(current::resolve)
                        ?: throw WebDavException("服务器返回了无效的跳转地址")
                    if (!isWithinRoot(root, destination)) throw WebDavException("服务器跳转到了连接范围之外，请直接填写目标 WebDAV 地址")
                    if (attempt == 5) throw WebDavException("服务器跳转次数过多")
                    current = destination
                } else {
                    when (response.code) {
                        401 -> throw WebDavException("账号或密码不正确")
                        403 -> throw WebDavException("当前账号没有访问此目录的权限")
                        404 -> throw WebDavException("找不到目录，请检查 WebDAV 路径")
                        405 -> throw WebDavException("此地址未提供 WebDAV 目录服务")
                    }
                    if (response.code != 207) throw WebDavException("读取目录失败（HTTP ${response.code}）")
                    val body = response.body ?: throw WebDavException("服务器返回了空目录响应")
                    val source = body.source()
                    source.request(MAX_XML_BYTES + 1)
                    if (source.buffer.size > MAX_XML_BYTES) throw WebDavException("目录内容过大，请打开更小的子目录")
                    return@withContext DirectoryListing(current.toString(), parseDirectory(body.string(), current, root))
                }
            }
        }
        throw WebDavException("无法打开此目录")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            }
        })
    }

    companion object {
        private const val MAX_XML_BYTES = 4L * 1024 * 1024
        private const val DAV = "DAV:"
        private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        internal fun parseDirectory(xml: String, directory: HttpUrl, root: HttpUrl): List<MediaEntry> {
            if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
                throw WebDavException("目录响应包含不支持的 XML 声明")
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val document = try { builder.parse(InputSource(StringReader(xml))) }
                catch (_: Exception) { throw WebDavException("服务器返回的目录格式无法识别") }
            if (document.documentElement.localName != "multistatus" || document.documentElement.namespaceURI != DAV) {
                throw WebDavException("服务器没有返回 WebDAV 文件列表")
            }
            val results = mutableListOf<MediaEntry>()
            val responses = document.getElementsByTagNameNS(DAV, "response")
            for (index in 0 until responses.length) {
                val element = responses.item(index) as Element
                val href = element.text("href") ?: continue
                val url = directory.resolve(href) ?: continue
                if (!isWithinRoot(root, url) || url.username.isNotEmpty() || url.password.isNotEmpty()) continue
                if (url.encodedPath.trimEnd('/') == directory.encodedPath.trimEnd('/')) continue
                // Only direct children belong to Depth: 1, even if a server returns extra entries.
                val parentPath = directory.encodedPath.trimEnd('/') + "/"
                if (!url.encodedPath.startsWith(parentPath)) continue
                val relative = url.encodedPath.removePrefix(parentPath).trimEnd('/')
                if (relative.isEmpty() || relative.contains('/')) continue
                val propstats = element.getElementsByTagNameNS(DAV, "propstat")
                var properties: Element? = null
                for (p in 0 until propstats.length) {
                    val propstat = propstats.item(p) as Element
                    if (Regex("HTTP/\\S+\\s+200(?:\\s|$)").containsMatchIn(propstat.text("status").orEmpty())) {
                        properties = propstat.getElementsByTagNameNS(DAV, "prop").item(0) as? Element
                        if (properties != null) break
                    }
                }
                val prop = properties ?: continue
                val isDirectory = prop.getElementsByTagNameNS(DAV, "collection").length > 0
                val canonical = if (isDirectory && !url.encodedPath.endsWith('/')) url.newBuilder().addPathSegment("").build() else url
                val name = prop.text("displayname")?.takeIf { it.isNotBlank() }
                    ?: canonical.pathSegments.lastOrNull { it.isNotEmpty() } ?: continue
                results += MediaEntry(canonical.toString(), name, isDirectory, prop.text("getcontentlength")?.toLongOrNull(), prop.text("getlastmodified"))
            }
            return results.distinctBy { it.url }.sortedWith(compareBy<MediaEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

        private fun Element.text(name: String): String? = getElementsByTagNameNS(DAV, name).item(0)?.textContent?.trim()
    }
}
