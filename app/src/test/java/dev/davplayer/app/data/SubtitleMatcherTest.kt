package dev.davplayer.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SubtitleMatcherTest {
    private fun entry(name: String, directory: String = "https://nas.example/dav/电影/", folder: Boolean = false) =
        MediaEntry(directory.toHttpUrl().newBuilder().addPathSegment(name).build().toString(), name, folder)

    @Test fun discoversAllSupportedSidecarsFromTheCompleteDirectory() {
        val video = entry("电影 第1集.mkv")
        val subtitles = listOf("srt", "ass", "ssa", "vtt").map { entry("电影 第1集.$it") }
        val listing = listOf(video, entry("其他电影.srt"), entry("电影 第1集.txt")) + subtitles
        assertEquals(subtitles.toSet(), matchingSubtitles(video, listing).toSet())
    }

    @Test fun handlesCaseDotsSpacesAndEscapedCharactersWithoutChangingUrls() {
        val video = entry("中文 100% #a.b.MKV")
        val subtitle = entry("中文 100% #a.b.SRT")
        assertEquals(listOf(subtitle), matchingSubtitles(video, listOf(subtitle)))
        assertTrue(subtitle.url.contains("%25"))
        assertTrue(subtitle.url.contains("%23"))
    }

    @Test fun rejectsUnrelatedNamesAndOtherDirectories() {
        val video = entry("Episode.01.mp4")
        val same = entry("episode.01.srt")
        val rejected = listOf(
            entry("Episode.01.trailer.srt"), entry("Episode.010.srt"), entry("Episode.01.srt", folder = true),
            entry("Episode.01.srt", "https://nas.example/dav/other/"),
            entry("Episode.01.srt", "https://other.example/dav/电影/"),
            entry("Episode.01.srt", "http://nas.example/dav/电影/"),
            entry("Episode.01.srt", "https://nas.example:8443/dav/电影/"),
            entry("Episode.01.srt", "https://user:secret@nas.example/dav/电影/"),
            MediaEntry("file:///Episode.01.srt", "Episode.01.srt", false),
        )
        assertEquals(listOf(same), matchingSubtitles(video, rejected + same))
    }

    @Test fun preservesSignedQueriesAndDeduplicatesRepeatedListingEntries() {
        val video = entry("movie.mp4")
        val sub = entry("movie.srt").let { it.copy(url = it.url + "?sign=a%2Bb%2Fc%3D") }
        assertEquals(listOf(sub), matchingSubtitles(video, listOf(sub, sub)))
    }

    @Test fun ignoresNonVideoSelectionsAndMissingSidecars() {
        assertTrue(matchingSubtitles(entry("movie.srt"), listOf(entry("movie.ass"))).isEmpty())
        assertTrue(matchingSubtitles(entry("movie.mp4"), emptyList()).isEmpty())
    }

    @Test fun matchesTheReportedAnimeSimplifiedAndTraditionalVariants() {
        val base = "[UHA-WINGS＆RATH][Sword Art Online - Alicization][01][BDRIP 1920x1080 x264 FLACx2]"
        val video = entry("$base.mkv")
        val subtitles = listOf("sc", "tc", "chs", "cht").map { entry("$base.$it.ass") }
        val nextEpisode = entry(base.replace("[01]", "[02]") + ".sc.ass")
        assertEquals(subtitles.toSet(), matchingSubtitles(video, subtitles + video + nextEpisode).toSet())
    }

    @Test fun matchesTheReportedRelifeNameWithTheVideoExtensionRetained() {
        val name = "02 (ReLife TV) 02. Communication Skills Zero.mkv"
        val video = entry(name)
        val subtitles = listOf(entry("$name.ass"), entry("$name.chs.ass"), entry("$name.tc.srt"))
        assertEquals(subtitles.toSet(), matchingSubtitles(video, subtitles).toSet())
    }

    @Test fun acceptsCommonLanguageAndAccessibilityTags() {
        val video = entry("Movie.mkv")
        val subtitles = listOf("sc", "tc", "chs", "cht", "zh", "zh-CN", "zh-TW", "zh-Hans", "zh-Hant",
            "en", "eng", "en-US", "ja", "jpn", "ko", "forced", "en.SDH", "zh-Hans.forced",
            "chs&eng", "cht+eng", "简体双语", "繁體中文").map { entry("Movie.$it.ass") }
        assertEquals(subtitles.toSet(), matchingSubtitles(video, subtitles).toSet())
    }

    @Test fun acceptsDelimitedTagsWithoutTreatingTheVideoNameAsARegularExpression() {
        val base = "A [01] (TV) + 100% #a.b"
        val video = entry("$base.mp4")
        val subtitles = listOf(".sc", "_CHS", "-cht", " tc", "[简体]", "(繁体)", "【chs】", ".sc.default")
            .map { entry("$base$it.srt") }
        assertEquals(subtitles.toSet(), matchingSubtitles(video, subtitles).toSet())
    }

    @Test fun doesNotMistakeOtherEpisodesOrUnrelatedSuffixesForLanguageTags() {
        val video = entry("Episode.01.mkv")
        val unrelated = listOf("Episode.010.sc.ass", "Episode.01.02.ass", "Episode.01.1080p.chs.ass",
            "Episode.01.trailer.ass", "Episode.01.extra.chs.ass", "Episode.01sc.ass", "Episode.01.mkv2.ass",
            "Episode.01..ass", "OtherEpisode.01.tc.ass").map { entry(it) }
        assertTrue(matchingSubtitles(video, unrelated).isEmpty())
    }

    @Test fun supportsTaggedNamesForEverySubtitleFormatAndPreservesTheirSignedUrls() {
        val video = entry("中文 文件.MKV")
        val subtitles = listOf("ass", "ssa", "srt", "vtt").flatMap { extension ->
            listOf(entry("中文 文件.SC.$extension"), entry("中文 文件.MKV.CHT.$extension"))
        }.map { it.copy(url = it.url + "?sign=a%2Bb%2Fc%3D") }
        assertEquals(subtitles.toSet(), matchingSubtitles(video, subtitles + subtitles).toSet())
        assertTrue(matchingSubtitles(video, subtitles.map {
            it.copy(url = it.url.replace("nas.example/dav/", "other.example/dav/"))
        }).isEmpty())
    }
}
