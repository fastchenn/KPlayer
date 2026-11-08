package dev.davplayer.app.data

import java.text.Normalizer
import java.util.Locale
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * One alphabet for Latin names and Chinese pinyin on every supported Android version.
 * Keys are computed once per entry, rather than transliterating inside each comparison.
 * Unlisted characters are retained; polyphonic characters use the dictionary's first reading.
 */
internal fun List<MediaEntry>.sortedByMediaName(descending: Boolean = false): List<MediaEntry> {
    val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    val keys = HashMap<String, String>()
    return map { entry ->
        entry to keys.getOrPut(entry.name) { mediaNameKey(entry.name, format) }
    }.sortedWith(Comparator { a, b ->
        // Reversing the name order must never put files above folders.
        val kind = b.first.isDirectory.compareTo(a.first.isDirectory)
        if (kind != 0) kind else {
            val left = if (descending) b else a
            val right = if (descending) a else b
            compareNaturalNames(left.second, right.second).takeIf { it != 0 }
                ?: left.first.name.compareTo(right.first.name, ignoreCase = true).takeIf { it != 0 }
                ?: left.first.name.compareTo(right.first.name).takeIf { it != 0 }
                ?: left.first.url.compareTo(right.first.url)
        }
    }).map { it.first }
}

private fun mediaNameKey(name: String, format: HanyuPinyinOutputFormat): String {
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return buildString {
        for (character in normalized) {
            val pinyin = PinyinHelper.toHanyuPinyinStringArray(character, format)
                ?.firstOrNull { it != "none" && it.isNotEmpty() }
            append(pinyin ?: character.toString())
        }
    }
}

/** Compare digit runs by magnitude without parsing into a fixed-size integer. */
private fun compareNaturalNames(left: String, right: String): Int {
    var l = 0
    var r = 0
    while (l < left.length && r < right.length) {
        if (left[l] in '0'..'9' && right[r] in '0'..'9') {
            var lEnd = l
            var rEnd = r
            while (lEnd < left.length && left[lEnd] in '0'..'9') lEnd++
            while (rEnd < right.length && right[rEnd] in '0'..'9') rEnd++
            while (l < lEnd && left[l] == '0') l++
            while (r < rEnd && right[r] == '0') r++
            val lengthOrder = (lEnd - l).compareTo(rEnd - r)
            if (lengthOrder != 0) return lengthOrder
            while (l < lEnd && r < rEnd) {
                val order = left[l++].compareTo(right[r++])
                if (order != 0) return order
            }
            l = lEnd
            r = rEnd
        } else {
            val order = left[l++].compareTo(right[r++])
            if (order != 0) return order
        }
    }
    return (left.length - l).compareTo(right.length - r)
}
