package dev.davplayer.app.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded, versioned local document. A failed write leaves the previous file intact. */
class WatchHistoryStore(private val file: File) {
    fun load(): List<WatchRecord> {
        if (!file.exists()) return emptyList()
        if (file.length() > 32 * 1024 * 1024) throw IOException("Invalid history size")
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != 1) throw IOException("Unknown history format")
            val count = input.readInt()
            if (count !in 0..WatchHistoryRecorder.MAX_RECORDS) throw IOException("Invalid history count")
            return List(count) {
                val record = WatchRecord(
                    serverId = input.readUTF(), connectionKey = input.readUTF(), relativePath = input.readUTF(), name = input.readUTF(),
                    positionMs = input.readLong(), durationMs = input.readLong().takeIf { it > 0 },
                    updatedAtMs = input.readLong(), completed = input.readBoolean(),
                    sizeBytes = input.readLong().takeIf { it >= 0 }, modifiedAt = input.readUTF().ifEmpty { null },
                )
                validate(record)
                record
            }.distinctBy { it.id }.sortedByDescending { it.updatedAtMs }
        }
    }

    fun save(records: List<WatchRecord>) {
        val selected = records.take(WatchHistoryRecorder.MAX_RECORDS)
        selected.forEach(::validate)
        val directory = file.absoluteFile.parentFile!!
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create history directory")
        val temporary = File.createTempFile("watch-history-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(MAGIC); output.writeInt(1); output.writeInt(selected.size)
                selected.forEach { record ->
                    output.writeUTF(record.serverId); output.writeUTF(record.connectionKey); output.writeUTF(record.relativePath); output.writeUTF(record.name)
                    output.writeLong(record.positionMs); output.writeLong(record.durationMs ?: -1)
                    output.writeLong(record.updatedAtMs); output.writeBoolean(record.completed)
                    output.writeLong(record.sizeBytes ?: -1); output.writeUTF(record.modifiedAt.orEmpty())
                }
                output.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    private fun validate(record: WatchRecord) {
        if (record.serverId.length !in 1..256 || !record.connectionKey.matches(Regex("[a-f0-9]{64}")) ||
            record.relativePath.length !in 1..8192 || record.relativePath.startsWith('/') || record.name.length !in 1..2048 ||
            record.positionMs < 0 || record.updatedAtMs < 0 || (record.durationMs != null && record.durationMs <= 0) ||
            (record.modifiedAt?.length ?: 0) > 1024
        ) throw IOException("Invalid history record")
    }

    companion object { private const val MAGIC = 0x4b504831 }
}
