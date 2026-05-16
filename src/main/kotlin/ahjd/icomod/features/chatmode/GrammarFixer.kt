package ahjd.icomod.features.chatmode

object GrammarFixer {

    fun fix(text: String): String {
        var r = text.trim()
        if (r.isEmpty()) return r
        r = collapseWhitespace(r)
        r = addMissingApostrophes(r)
        r = fixContractionCase(r)
        r = fixStandaloneI(r)
        r = fixPunctuationSpacing(r)
        r = capitalizeSentences(r)
        r = ensureTerminalPunctuation(r)
        return r
    }

    private fun collapseWhitespace(text: String): String =
        text.replace(Regex(" {2,}"), " ")

    // No-apostrophe contractions where the unapostrophed form is not a valid English word
    // Deliberately excludes ambiguous ones: its, were, well, wed, ill, hes, shes
    // (those have non-contraction meanings that would get clobbered)
    private val APOSTROPHE_FIXES = listOf(
        "dont"     to "don't",
        "wont"     to "won't",
        "cant"     to "can't",
        "isnt"     to "isn't",
        "arent"    to "aren't",
        "wasnt"    to "wasn't",
        "werent"   to "weren't",
        "shouldnt" to "shouldn't",
        "couldnt"  to "couldn't",
        "wouldnt"  to "wouldn't",
        "didnt"    to "didn't",
        "doesnt"   to "doesn't",
        "hasnt"    to "hasn't",
        "havent"   to "haven't",
        "hadnt"    to "hadn't",
        "im"       to "I'm",
        "ive"      to "I've",
        "id"       to "I'd", // collides with noun "ID" but contraction is far more common in chat
        "youre"    to "you're",
        "youve"    to "you've",
        "youll"    to "you'll",
        "youd"     to "you'd",
        "theyre"   to "they're",
        "theyve"   to "they've",
        "theyll"   to "they'll",
        "theyd"    to "they'd",
        "weve"     to "we've",
        "thats"    to "that's",
        "whats"    to "what's",
        "wheres"   to "where's",
        "whens"    to "when's",
        "hows"     to "how's"
    )

    private fun addMissingApostrophes(text: String): String {
        var r = text
        for ((from, to) in APOSTROPHE_FIXES) {
            r = r.replace(Regex("\\b${Regex.escape(from)}\\b", RegexOption.IGNORE_CASE)) { m ->
                // Preserve capitalisation pattern of first letter
                if (m.value.first().isUpperCase()) to.replaceFirstChar { it.uppercaseChar() } else to
            }
        }
        return r
    }

    // Fix already-apostrophed but lowercased "i'm", "i've", "i'll", "i'd"
    private fun fixContractionCase(text: String) = text
        .replace(Regex("\\bi'm\\b",  RegexOption.IGNORE_CASE), "I'm")
        .replace(Regex("\\bi've\\b", RegexOption.IGNORE_CASE), "I've")
        .replace(Regex("\\bi'll\\b", RegexOption.IGNORE_CASE), "I'll")
        .replace(Regex("\\bi'd\\b",  RegexOption.IGNORE_CASE), "I'd")

    private fun fixStandaloneI(text: String): String =
        text.replace(Regex("\\bi\\b"), "I")

    private fun fixPunctuationSpacing(text: String): String {
        var r = text
        r = r.replace(Regex(" +([,!?;:])"), "$1")             // no space before , ! ? ; :
        r = r.replace(Regex(",(?=[^\\s])"), ", ")              // single space after comma
        r = r.replace(Regex("\\.(?!\\.| |$)"), ". ")           // space after period (skip ...)
        r = r.replace(Regex("\\?(?=[^\\s])"), "? ")            // space after ?
        r = r.replace(Regex("!(?=[^\\s])"), "! ")              // space after !
        return r.trim()
    }

    // Capitalize first character of the message and the first character following . ! or ?
    private fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text)
        sb[0] = sb[0].uppercaseChar()
        val re = Regex("([.!?]+\\s+)([a-z])")
        return re.replace(sb.toString()) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }
    }

    private fun ensureTerminalPunctuation(text: String): String {
        if (text.isEmpty()) return text
        return if (text.last() !in setOf('.', '!', '?') && !text.endsWith("..."))
            "$text." else text
    }
}
