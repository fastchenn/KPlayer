package dev.davplayer.app.data

/** Explicit offline UI fixtures; this server is never persisted or requested over the network. */
object DemoLibrary {
    val server = ServerConnection("demo", "示例媒体库", "https://demo.invalid/dav/", isDemo = true)
    private val root = server.rootUrl
    fun list(url: String): List<MediaEntry> = when (url.removePrefix(root)) {
        "" -> listOf(
            MediaEntry(root + "movies/", "电影", true),
            MediaEntry(root + "series/", "剧集", true),
            MediaEntry(root + "documentaries/", "纪录片", true),
            MediaEntry(root + "sample.mkv", "沿着海岸线 · 示例影片.mkv", false, 2_864_234_496),
        )
        "movies/" -> listOf(
            MediaEntry(root + "movies/coast.mkv", "沿着海岸线.mkv", false, 2_864_234_496),
            MediaEntry(root + "movies/summer.mp4", "夏日来信.mp4", false, 1_438_654_464),
            MediaEntry(root + "movies/coast.srt", "沿着海岸线.srt", false, 48_920),
        )
        "series/" -> (1..5).map { MediaEntry(root + "series/episode-$it.mkv", "山间慢行 · 第 ${it.toString().padStart(2, '0')} 集.mkv", false, 782_094_336) }
        "documentaries/" -> listOf(MediaEntry(root + "documentaries/forest.mp4", "森林里的四季.mp4", false, 4_302_000_000))
        else -> emptyList()
    }
}
