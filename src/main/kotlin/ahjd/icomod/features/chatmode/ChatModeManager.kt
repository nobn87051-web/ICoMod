package ahjd.icomod.features.chatmode

import ahjd.icomod.config.ConfigManager
import com.google.gson.Gson
import net.fabricmc.loader.api.FabricLoader
import java.io.File

object ChatModeManager {
    private val gson = Gson()
    private val wordMaps = mutableMapOf<ChatMode, Map<String, String>>()

    var currentMode: ChatMode = ChatMode.NORMAL
        private set

    fun init() {
        currentMode = runCatching {
            ChatMode.valueOf(ConfigManager.config.chatMode)
        }.getOrDefault(ChatMode.NORMAL)
        loadWordMaps()
        ChinesePoems.init()
    }

    fun cycleMode() {
        currentMode = currentMode.next()
        ConfigManager.config.chatMode = currentMode.name
        ConfigManager.save()
    }

    /** Force the chat mode back to NORMAL. Used by the middle-click chat panic-button. */
    fun resetToNormal() {
        if (currentMode == ChatMode.NORMAL) return
        currentMode = ChatMode.NORMAL
        ConfigManager.config.chatMode = currentMode.name
        ConfigManager.save()
    }

    fun applyWordMap(message: String): String {
        val map = wordMaps[currentMode] ?: return message
        var result = message
        map.entries.sortedByDescending { it.key.length }.forEach { (from, to) ->
            result = result.replace(
                Regex("(?<!\\w)${Regex.escape(from)}(?!\\w)", RegexOption.IGNORE_CASE), to
            )
        }
        return result
    }

    private fun loadWordMaps() {
        val mapsDir = File(FabricLoader.getInstance().gameDir.toFile(), "ahjdmod/word-maps")
        mapsDir.mkdirs()

        for (mode in ChatMode.entries) {
            // CHINESE uses the poem corpus, not the word-map pipeline.
            if (mode == ChatMode.NORMAL || mode == ChatMode.CHINESE) continue
            val fileName = "${mode.name.lowercase()}.json"
            val file = File(mapsDir, fileName)

            if (!file.exists()) {
                javaClass.getResourceAsStream("/ahjdmod/word-maps/$fileName")
                    ?.use { file.writeBytes(it.readBytes()) }
            }

            if (file.exists()) {
                @Suppress("UNCHECKED_CAST")
                val map = runCatching {
                    gson.fromJson(file.readText(), Map::class.java) as? Map<String, String>
                }.getOrNull()
                if (map != null) wordMaps[mode] = map
            }
        }
    }
}
