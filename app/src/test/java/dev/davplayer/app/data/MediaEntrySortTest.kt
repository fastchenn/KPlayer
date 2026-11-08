package dev.davplayer.app.data

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MediaEntrySortTest {
    @Test fun mixesChinesePinyinWithEnglishInOneAlphabet() {
        val entries = folders("一拳超人", "Zebra", "虫师", "Banana", "阿基拉", "Dragon", "Apple", "夏目友人帐")
        assertEquals(
            listOf("阿基拉", "Apple", "Banana", "虫师", "Dragon", "夏目友人帐", "一拳超人", "Zebra"),
            entries.sortedByMediaName().map { it.name },
        )
    }

    @Test fun comparesTheWholePinyinNameAndMixedNames() {
        assertEquals(
            listOf("虫师", "虫师A", "虫师B", "虫之歌"),
            folders("虫之歌", "虫师B", "虫师", "虫师A").sortedByMediaName().map { it.name },
        )
    }

    @Test fun keepsFoldersFirstInBothDirections() {
        val entries = folders("一拳超人", "虫师") + files("Zebra.mkv", "Apple.mkv")
        assertEquals(
            listOf("虫师", "一拳超人", "Apple.mkv", "Zebra.mkv"),
            entries.sortedByMediaName().map { it.name },
        )
        assertEquals(
            listOf("一拳超人", "虫师", "Zebra.mkv", "Apple.mkv"),
            entries.sortedByMediaName(descending = true).map { it.name },
        )
    }

    @Test fun sortsEpisodeNumbersNaturallyWithoutIntegerOverflow() {
        val expected = listOf(
            "虫师1", "虫师2", "虫师10",
            "虫师9999999999999999999999999",
            "虫师10000000000000000000000000",
        )
        val entries = folders(*expected.reversed().toTypedArray())
        assertEquals(expected, entries.sortedByMediaName().map { it.name })
        assertEquals(expected.reversed(), entries.sortedByMediaName(true).map { it.name })
    }

    @Test fun handlesTraditionalChineseAndFullWidthLatinAndDigits() {
        assertEquals(
            listOf("Apple", "Ｂanana", "虫師", "龍珠", "一拳超人"),
            folders("一拳超人", "龍珠", "虫師", "Ｂanana", "Apple").sortedByMediaName().map { it.name },
        )
        assertEquals(
            listOf("Movie２", "Movie１０"),
            folders("Movie１０", "Movie２").sortedByMediaName().map { it.name },
        )
    }

    @Test fun ignoresEnglishCaseWithoutDependingOnDeviceLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                listOf("apple", "Banana", "虫师", "INDIGO", "一拳超人"),
                folders("一拳超人", "INDIGO", "虫师", "Banana", "apple").sortedByMediaName().map { it.name },
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun equalPinyinLeadingZerosAndCaseHaveDeterministicTies() {
        val entries = folders("虫师", "chongshi", "CHONGSHI", "虫师02", "虫师2") +
            listOf(MediaEntry("https://nas.example/b/", "同名", true), MediaEntry("https://nas.example/a/", "同名", true))
        val sorted = entries.sortedByMediaName()
        assertEquals(sorted, entries.reversed().sortedByMediaName())
        assertEquals(sorted.reversed(), entries.sortedByMediaName(true))
        assertEquals(listOf("https://nas.example/a/", "https://nas.example/b/"), sorted.filter { it.name == "同名" }.map { it.url })
    }

    @Test fun retainsUnknownCharactersEmptyNamesAndInputData() {
        val entries = folders("", "🦊", "𠮷", "かな", "虫师", "a")
        val before = entries.toList()
        val sorted = entries.sortedByMediaName()
        assertEquals(entries.toSet(), sorted.toSet())
        assertEquals(entries.size, sorted.size)
        assertEquals(before, entries)
        assertEquals(sorted, entries.reversed().sortedByMediaName())
        assertTrue(emptyList<MediaEntry>().sortedByMediaName().isEmpty())
    }

    private fun folders(vararg names: String) = names.mapIndexed { index, name ->
        MediaEntry("https://nas.example/folder/$index/", name, true)
    }

    private fun files(vararg names: String) = names.mapIndexed { index, name ->
        MediaEntry("https://nas.example/file/$index", name, false)
    }
}
