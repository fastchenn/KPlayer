package dev.davplayer.app.data

import org.junit.Assert.*
import org.junit.Test

class LibraryTextTest {
    @Test fun defaultsRetainTheOriginalThreeTextContents() {
        assertEquals(LibraryText(), LibraryText().validated())
        assertEquals("把你的媒体库", LibraryText().firstLine)
        assertEquals("带在身边。", LibraryText().secondLine)
        assertEquals("连接 WebDAV，从自己的收藏开始。", LibraryText().description)
    }

    @Test fun userCanChangeOnlyPlainTextContentsWithinEachFixedField() {
        val edited = LibraryText(" 我的收藏 ", " 随身相伴 ", " 从喜爱的影片开始。 ").validated()
        assertEquals("我的收藏", edited.firstLine)
        assertEquals("随身相伴", edited.secondLine)
        assertEquals("从喜爱的影片开始。", edited.description)
    }

    @Test fun rejectsEmptyOverlongAndMultilineText() {
        val invalid = listOf(
            LibraryText(firstLine = " "), LibraryText(secondLine = "x".repeat(LibraryText.TITLE_LIMIT + 1)),
            LibraryText(description = "x".repeat(LibraryText.DESCRIPTION_LIMIT + 1)),
            LibraryText(firstLine = "第一行\n额外行"), LibraryText(secondLine = "第二行\r额外行"),
            LibraryText(description = "说明\u2028另一行"), LibraryText(description = "说明\t缩进"),
        )
        invalid.forEach { value ->
            try { value.validated(); fail("Invalid text should fail") } catch (_: IllegalArgumentException) { }
        }
    }
}
