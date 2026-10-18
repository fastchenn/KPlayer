package dev.davplayer.app.data

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = ServerConnection.create("NAS", "https://nas.invalid/dav/", "private-user", "private-password", "server")
    private val record = WatchRecord.create(server, MediaEntry(
        "https://nas.invalid/dav/%E7%94%B5%E5%BD%B1%20%23.mkv?signature=private-token", "电影 #.mkv", false,
        sizeBytes = 1_000_000, modifiedAt = "2026-10-18T10:00:00Z",
    ))!!.copy(positionMs = 12_345, durationMs = 100_000, updatedAtMs = 123_000)

    @Test fun emptyInstallLoadsWithoutCreatingAFile() {
        val file = File(temporary.root, "history.bin")
        assertTrue(WatchHistoryStore(file).load().isEmpty())
        assertFalse(file.exists())
    }

    @Test fun unicodeRecordsRoundTripWithoutPersistingCredentialsOrSignedUrls() {
        val file = File(temporary.root, "history.bin")
        val store = WatchHistoryStore(file)
        store.save(listOf(record))
        assertEquals(record, store.load().single())
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("private-user", "private-password", "private-token", "nas.invalid", "signature=")) assertFalse(bytes.contains(secret))
    }

    @Test fun completedAndUnknownDurationRecordsRoundTrip() {
        val store = WatchHistoryStore(File(temporary.root, "history.bin"))
        val completed = record.copy(completed = true, positionMs = 100_000)
        val unknown = record.copy(relativePath = "unknown.mkv", durationMs = null, sizeBytes = null, modifiedAt = null)
        store.save(listOf(completed, unknown))
        assertEquals(listOf(completed, unknown), store.load())
    }

    @Test fun invalidSaveDoesNotOverwriteThePreviousDocument() {
        val store = WatchHistoryStore(File(temporary.root, "history.bin"))
        store.save(listOf(record))
        try { store.save(listOf(record.copy(name = ""))); fail("Invalid record should fail") }
        catch (_: IOException) { }
        assertEquals(listOf(record), store.load())
    }

    @Test fun aTruncatedFileIsReportedAndRetainedRatherThanReplacedWithEmptyHistory() {
        val file = File(temporary.root, "history.bin")
        val store = WatchHistoryStore(file)
        store.save(listOf(record))
        val truncated = file.readBytes().copyOf(15)
        file.writeBytes(truncated)
        try { store.load(); fail("Corrupt history should fail") } catch (_: IOException) { }
        assertArrayEquals(truncated, file.readBytes())
    }

    @Test fun keepsTheConfiguredRecordBoundAndCanPersistAnEmptyList() {
        val store = WatchHistoryStore(File(temporary.root, "history.bin"))
        store.save(List(WatchHistoryRecorder.MAX_RECORDS + 10) { record.copy(relativePath = "movie-$it.mkv") })
        assertEquals(WatchHistoryRecorder.MAX_RECORDS, store.load().size)
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
