package dev.davplayer.playback.mpv

import java.io.File
import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.davplayer.playback.api.PlaybackRequest
import okhttp3.HttpUrl.Companion.toHttpUrl

data class CachedSubtitle(val encodedPath: String, val title: String, val sizeBytes: Long? = null, val modifiedAt: String? = null)
data class CachedMediaInfo(val key: String, val size: Long, val version: String?, val type: String?, val subtitles: List<CachedSubtitle>)

/** A private, bounded LRU of complete video blocks. All file operations run on IO workers. */
class PlaybackDiskCache(
    private val directory: File,
    enabled: Boolean = true,
    maxBytes: Long = 5L * 1024 * 1024 * 1024,
) {
    private val lock = Any()
    private data class Entry(val file: File, val size: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val usage = MutableStateFlow(0L)
    val usedBytes = usage.asStateFlow()
    private val issue = MutableStateFlow<String?>(null)
    val writeIssue = issue.asStateFlow()
    internal fun reportIssue(message: String) { issue.value = message }
    @Volatile var enabled = enabled
        private set
    private var maxBytes = maxBytes.coerceAtLeast(0)
    private var initialized = false
    private var epoch = 0L

    fun configure(enabled: Boolean, maxBytes: Long) = synchronized(lock) {
        initialize()
        if (this.enabled != enabled || this.maxBytes != maxBytes) epoch++
        this.enabled = enabled
        this.maxBytes = maxBytes.coerceAtLeast(0)
        trimTo(this.maxBytes)
    }

    fun refreshUsage() = synchronized(lock) {
        initialized = false
        initialize()
    }

    fun clear() = synchronized(lock) {
        initialize()
        epoch++ // An in-flight download cannot put deleted data back after confirmation.
        var failed = false
        entries.values.toList().forEach { if (it.file.exists() && !it.file.delete()) failed = true }
        directory.listFiles().orEmpty().filter { it.name.endsWith(".media") }.forEach { if (!it.delete()) failed = true }
        initialized = false
        initialize()
        if (failed) throw IOException("Some cache files could not be removed")
        issue.value = null
    }

    internal fun directory(): File = directory

    internal fun generation(): Long = synchronized(lock) { epoch }

    /** Only actual bytes count as a cache hit. A metadata-only entry is insufficient. */
    fun cachedMedia(identity: String): CachedMediaInfo? = synchronized(lock) {
        if (!enabled) return null
        initialize()
        readMedia(namespace(identity))?.takeIf { media -> entries.any { (name, entry) ->
            name.startsWith("${media.key}.") && entry.file.length() > 0
        } }
    }

    fun cachedBytes(identity: String): Long = synchronized(lock) {
        val media = readMedia(namespace(identity)) ?: return 0
        directory.listFiles().orEmpty().filter { it.name.startsWith("${media.key}.") && it.name.endsWith(".blk") }.sumOf { it.length() }
    }

    internal fun readMedia(namespace: String): CachedMediaInfo? = synchronized(lock) {
        val file = File(directory, "$namespace.media")
        if (!file.isFile || file.length() > 1_048_576) return null
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != 0x4b504332) return null
                val key = input.readUTF()
                val size = input.readLong()
                val version = input.readUTF().ifEmpty { null }
                val type = input.readUTF().ifEmpty { null }
                val count = input.readInt()
                if (!key.matches(Regex("[a-f0-9]{64}")) || size <= 0 || count !in 0..64) return null
                val subtitles = List(count) { CachedSubtitle(input.readUTF(), input.readUTF(), input.readLong().takeIf { it > 0 }, input.readUTF().ifEmpty { null }) }
                if (subtitles.any { !it.encodedPath.startsWith('/') || it.encodedPath.length > 8192 || it.title.length > 2048 }) return null
                CachedMediaInfo(key, size, version, type, subtitles)
            }
        } catch (_: IOException) { null }
    }

    internal fun remember(namespace: String, media: CachedMediaInfo, generation: Long): Unit = synchronized(lock) {
        if (!enabled || epoch != generation) return
        var temporary: File? = null
        try {
            initialize()
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create cache directory")
            temporary = File.createTempFile("media-", ".part", directory)
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(0x4b504332); output.writeUTF(media.key); output.writeLong(media.size)
                output.writeUTF(media.version.orEmpty().take(4096)); output.writeUTF(media.type.orEmpty().take(256))
                output.writeInt(media.subtitles.size)
                media.subtitles.forEach { output.writeUTF(it.encodedPath); output.writeUTF(it.title); output.writeLong(it.sizeBytes ?: -1); output.writeUTF(it.modifiedAt.orEmpty().take(4096)) }
            }
            val destination = File(directory, "$namespace.media")
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) { issue.value = "无法写入播放缓存，请检查设备剩余存储空间" }
        finally { temporary?.delete() }
    }

    /** A block may contain a durable prefix; it is useful even before the whole block arrives. */
    internal fun readPrefix(key: String, block: Long, expectedBytes: Int): ByteArray? = synchronized(lock) {
        if (!enabled) return null
        initialize()
        val name = blockName(key, block)
        val file = entries[name]?.file ?: return null
        try {
            if (file.length() !in 1..expectedBytes.toLong()) { remove(name); return null }
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } catch (_: IOException) { remove(name); null }
    }

    /** Append only contiguous bytes. No sparse holes may ever be returned as cached data. */
    internal fun append(key: String, block: Long, position: Int, data: ByteArray, offset: Int, count: Int, generation: Long): Boolean = synchronized(lock) {
        if (!enabled || epoch != generation || count <= 0) return false
        initialize()
        val name = blockName(key, block)
        val file = File(directory, name)
        try {
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create cache directory")
            val old = entries[name]?.size ?: 0
            val length = file.takeIf { it.isFile }?.length() ?: 0
            if (position > length || position.toLong() + count > BLOCK_BYTES) return false
            val overlap = (length - position).coerceIn(0, count.toLong()).toInt()
            val additional = count - overlap
            if (length + additional > maxBytes) { issue.value = "缓存容量不足，部分数据未保存"; return false }
            // Protect this growing block while evicting older blocks.
            val protected = entries.remove(name)
            usage.value = (usage.value - old).coerceAtLeast(0)
            trimTo((maxBytes - length - additional).coerceAtLeast(0))
            if (protected != null) entries[name] = protected
            usage.value += old
            if (usage.value - old + length + additional > maxBytes || directory.usableSpace < additional + 32L * 1024 * 1024)
                throw IOException("Insufficient cache space")
            if (additional > 0) FileOutputStream(file, true).use { stream ->
                stream.write(data, offset + overlap, additional)
                if (file.length() == BLOCK_BYTES.toLong()) stream.fd.sync()
            }
            entries[name] = Entry(file, file.length())
            usage.value = (usage.value - old + file.length()).coerceAtLeast(0)
            issue.value = null
            true
        } catch (_: IOException) {
            issue.value = "无法写入播放缓存，请检查设备剩余存储空间"
            false
        }
    }

    internal fun read(key: String, block: Long, expectedBytes: Int): ByteArray? = synchronized(lock) {
        if (!enabled) return null
        initialize()
        val name = blockName(key, block)
        val file = entries[name]?.file ?: return null
        try {
            if (file.length() != expectedBytes.toLong()) {
                remove(name)
                return null
            }
            val data = file.readBytes()
            if (data.size != expectedBytes) { remove(name); return null }
            file.setLastModified(System.currentTimeMillis())
            data
        } catch (_: IOException) { remove(name); null }
    }

    internal fun put(key: String, block: Long, data: ByteArray, generation: Long) = synchronized(lock) {
        if (!enabled || epoch != generation || data.isEmpty() || data.size > maxBytes) return
        initialize()
        val name = blockName(key, block)
        val destination = File(directory, name)
        var temporary: File? = null
        try {
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create cache directory")
            remove(name)
            if (entries.containsKey(name)) return
            trimTo((maxBytes - data.size).coerceAtLeast(0))
            if (usage.value > maxBytes - data.size) return
            // Do not consume the last free space needed by Android and the rest of the app.
            if (directory.usableSpace < data.size + 32L * 1024 * 1024) throw IOException("Insufficient cache space")
            temporary = File.createTempFile("block-", ".part", directory)
            temporary.writeBytes(data)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            entries[name] = Entry(destination, destination.length())
            usage.value += destination.length()
            issue.value = null
        } catch (_: IOException) {
            issue.value = "无法写入播放缓存，请检查设备剩余存储空间"
        } finally { temporary?.delete() }
    }

    private fun initialize() {
        if (initialized) return
        entries.clear()
        val files = directory.listFiles().orEmpty()
        files.filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
        files.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.[0-9]+\\.blk")) }
            .sortedBy(File::lastModified).forEach { entries[it.name] = Entry(it, it.length()) }
        usage.value = entries.values.sumOf { it.size }
        initialized = true
        trimTo(maxBytes)
    }

    private fun trimTo(limit: Long) {
        for (name in entries.keys.toList()) {
            if (usage.value <= limit) break
            remove(name)
        }
    }

    private fun remove(name: String) {
        val entry = entries[name] ?: return
        val file = entry.file
        if (!file.exists() || file.delete()) {
            entries.remove(name)
            usage.value = (usage.value - entry.size).coerceAtLeast(0)
        }
    }

    private fun blockName(key: String, block: Long): String {
        require(key.matches(Regex("[a-f0-9]{64}")) && block >= 0)
        return "$key.$block.blk"
    }

    companion object {
        internal const val BLOCK_BYTES = 2 * 1024 * 1024
        internal fun namespace(identity: String) = key("media-v3\u0000$identity")
        internal fun namespace(request: PlaybackRequest): String = request.cacheIdentity?.let(::namespace) ?: namespace(
            request.mediaId + "\u0000" + request.url.toHttpUrl().newBuilder().query(null).fragment(null).build() + "\u0000" +
                request.headers.entries.sortedBy { it.key.lowercase() }.joinToString("\u0000") { "${it.key.lowercase()}:${it.value}" })
        internal fun key(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    }
}
