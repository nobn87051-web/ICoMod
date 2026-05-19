package ahjd.icomod.config

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

data class ModConfig(
    var chatMode: String = "NORMAL",
    var chatModeEnabled: Boolean = true,
    var serverUrl: String = "https://icomod.xyz",
    var gifsEnabled: Boolean = true,
    var gifDefaultSize: String = "S",
    var gifStretch: Boolean = false,
)

object ConfigManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance()
        .gameDir.resolve("icomod/config.json").toFile()

    var config = ModConfig()
        private set

    fun load() {
        configFile.parentFile.mkdirs()
        if (configFile.exists()) {
            config = runCatching {
                gson.fromJson(configFile.readText(), ModConfig::class.java)
            }.getOrDefault(ModConfig())
        }
        save()
    }

    fun save() {
        configFile.parentFile.mkdirs()
        configFile.writeText(gson.toJson(config))
    }
}
