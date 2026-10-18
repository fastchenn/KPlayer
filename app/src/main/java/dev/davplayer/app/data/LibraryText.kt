package dev.davplayer.app.data

/** Content only; line breaks, typography and placement belong to LibraryScreen. */
data class LibraryText(
    val firstLine: String = "把你的媒体库",
    val secondLine: String = "带在身边。",
    val description: String = "连接 WebDAV，从自己的收藏开始。",
) {
    fun validated(): LibraryText {
        val result = LibraryText(firstLine.trim(), secondLine.trim(), description.trim())
        require(result.firstLine.isNotBlank() && result.firstLine.length <= TITLE_LIMIT && result.firstLine.none(::isControl)) { "标题第一行需填写 1–20 个字符，不能换行" }
        require(result.secondLine.isNotBlank() && result.secondLine.length <= TITLE_LIMIT && result.secondLine.none(::isControl)) { "标题第二行需填写 1–20 个字符，不能换行" }
        require(result.description.isNotBlank() && result.description.length <= DESCRIPTION_LIMIT && result.description.none(::isControl)) { "说明文字需填写 1–60 个字符，不能换行" }
        return result
    }

    companion object {
        const val TITLE_LIMIT = 20
        const val DESCRIPTION_LIMIT = 60
        private fun isControl(char: Char) = char.isISOControl() || char == '\u2028' || char == '\u2029'
    }
}
