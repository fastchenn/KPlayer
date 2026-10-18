package dev.davplayer.app.data

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val subtitleTagSeparators = Regex("""[.\s\p{Z}_\-\[\](){}&+【】（）]+""")
private val subtitleTags = setOf(
    "sc", "tc", "chs", "cht", "zh", "zho", "chi", "cn", "tw", "hk", "sg", "hans", "hant",
    "简", "简体", "简中", "简体中文", "繁", "繁体", "繁中", "繁体中文", "繁體", "繁體中文", "中文",
    "中英", "简英", "繁英", "中日", "简日", "繁日", "双语", "双語", "简体双语", "繁体双语", "简繁",
    "en", "eng", "english", "us", "gb", "au", "ca", "ja", "jp", "jpn", "japanese",
    "ko", "kor", "korean", "fr", "fra", "fre", "french", "de", "deu", "ger", "german",
    "es", "spa", "spanish", "pt", "por", "portuguese", "br", "ru", "rus", "russian",
    "it", "ita", "ar", "ara", "th", "tha", "vi", "vie", "id", "ind", "ms", "msa", "may",
    "nl", "nld", "dut", "pl", "pol", "tr", "tur", "uk", "ukr", "sv", "swe", "fi", "fin",
    "forced", "sdh", "cc", "default", "full", "signs", "songs", "bilingual",
)

/** Anchor the literal full name or stem, then allow only delimited subtitle metadata. */
private fun matchesSubtitleName(videoName: String, subtitleName: String): Boolean {
    val subtitleStem = subtitleName.substringBeforeLast('.')
    return listOf(videoName, videoName.substringBeforeLast('.')).any { base ->
        if (subtitleStem.equals(base, ignoreCase = true)) return@any true
        if (!subtitleStem.startsWith(base, ignoreCase = true)) return@any false
        val suffix = subtitleStem.substring(base.length)
        if (!subtitleTagSeparators.matches(suffix.take(1))) return@any false
        val tags = subtitleTagSeparators.split(suffix).filter(String::isNotEmpty)
        tags.isNotEmpty() && tags.all { it.lowercase(Locale.ROOT) in subtitleTags }
    }
}

/** Use the complete directory listing, independently of the browser's video/search filters. */
internal fun matchingSubtitles(video: MediaEntry, entries: List<MediaEntry>): List<MediaEntry> {
    if (!video.isVideo) return emptyList()
    val videoUrl = video.url.toHttpUrlOrNull() ?: return emptyList()
    val extensions = listOf("ass", "ssa", "srt", "vtt")
    return entries.filter { candidate ->
        val url = candidate.url.toHttpUrlOrNull() ?: return@filter false
        !candidate.isDirectory &&
            candidate.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions &&
            matchesSubtitleName(video.name, candidate.name) &&
            url.username.isEmpty() && url.password.isEmpty() && videoUrl.sameOrigin(url) &&
            videoUrl.encodedPath.substringBeforeLast('/') == url.encodedPath.substringBeforeLast('/')
    }.distinctBy { it.url }.sortedWith(compareBy(
        { extensions.indexOf(it.name.substringAfterLast('.').lowercase(Locale.ROOT)) }, { it.name },
    ))
}
