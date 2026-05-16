package ahjd.icomod.features.chatmode

enum class ChatMode(val displayName: String, val icon: String) {
    NORMAL("Normal", "N"),
    GENIUS("Genius", "G"),
    BRIISH("Bri'ish", "B"),
    CHINESE("Chinese", "中");

    fun next(): ChatMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}
