package ahjd.icomod.features.chatmode

/**
 * Walks an English message and, after every 2nd English word, appends a
 * 4-character snippet pulled from the bundled classical Chinese poem corpus.
 *
 *   "hello world how are you today" → "hello world 床前明月 how are 千山鸟飞 you today 春风又绿"
 *
 * The snippets rotate per pair via `ChinesePoems.randomSnippet()`, so the same
 * message produces different output each time. Punctuation, numbers, and any
 * existing CJK characters in the input are passed through unchanged.
 */
object ChineseInjector {

    private val WORD = Regex("[A-Za-z][A-Za-z']*")

    fun inject(message: String): String {
        if (message.isBlank()) return message

        val matches = WORD.findAll(message).toList()
        if (matches.size < 2) return message

        // Rough budget: every 2nd word emits a 2-5 char snippet plus a leading space.
        val out = StringBuilder(message.length + (matches.size / 2) * 6)
        var cursor = 0
        var wordsSincePair = 0

        for (m in matches) {
            out.append(message, cursor, m.range.last + 1)
            cursor = m.range.last + 1
            wordsSincePair++

            if (wordsSincePair == 2) {
                ChinesePoems.randomSnippet()?.let { out.append(' ').append(it) }
                wordsSincePair = 0
            }
        }
        if (cursor < message.length) out.append(message, cursor, message.length)
        return out.toString()
    }
}
