package dev.davplayer.app.data

import org.junit.Assert.*
import org.junit.Test

class WatchRecordTest {
    private fun server(root: String = "https://nas.invalid/dav/", user: String = "user", password: String = "secret") =
        ServerConnection.create("NAS", root, user, password, "server")
    private val entry = MediaEntry("https://nas.invalid/dav/folder/%E7%94%B5%E5%BD%B1%20%23.mkv?sign=private", "电影 #.mkv", false, sizeBytes = 100)

    @Test fun reconstructsAnEncodedRelativePathWithoutReusingQueryTokens() {
        val server = server()
        val record = WatchRecord.create(server, entry)!!
        assertEquals("folder/%E7%94%B5%E5%BD%B1%20%23.mkv", record.relativePath)
        assertEquals("https://nas.invalid/dav/folder/%E7%94%B5%E5%BD%B1%20%23.mkv", record.resolve(server))
    }

    @Test fun changingRootOrAccountCannotResumeAnUnrelatedVideo() {
        val record = WatchRecord.create(server(), entry)!!
        assertFalse(record.matches(server(root = "https://other.invalid/dav/")))
        assertFalse(record.matches(server(root = "https://nas.invalid/another/")))
        assertFalse(record.matches(server(user = "another-user")))
        assertNull(record.resolve(server(user = "another-user")))
        assertTrue(record.matches(server(password = "updated-password")))
    }

    @Test fun rejectsDemoAndOutOfRootEntries() {
        val demo = ServerConnection("demo", "Demo", "https://nas.invalid/dav/", isDemo = true)
        assertNull(WatchRecord.create(demo, entry))
        assertNull(WatchRecord.create(server(), entry.copy(url = "https://other.invalid/dav/video.mkv")))
        assertNull(WatchRecord.create(server(), entry.copy(url = "https://nas.invalid/private/video.mkv")))
    }

    @Test fun restoredPathsCannotEscapeTheConfiguredLibraryRoot() {
        val record = WatchRecord.create(server(), entry)!!
        assertNull(record.copy(relativePath = "../private/video.mkv").resolve(server()))
        assertNull(record.copy(relativePath = "%2e%2e/private/video.mkv").resolve(server()))
        assertNull(record.copy(relativePath = "/private/video.mkv").resolve(server()))
    }

    @Test fun replacedFilesAreDetectedFromSizeOrModificationTime() {
        val record = WatchRecord.create(server(), entry.copy(modifiedAt = "old-time"))!!
        assertTrue(record.sameFile(entry.copy(modifiedAt = "old-time")))
        assertFalse(record.sameFile(entry.copy(sizeBytes = 200, modifiedAt = "old-time")))
        assertFalse(record.sameFile(entry.copy(modifiedAt = "new-time")))
    }
}
