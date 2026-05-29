package ahjd.icomod.features.emote

import ahjd.icomod.util.AhjLog
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.component.DataComponentTypes
import net.minecraft.screen.ScreenHandler

/**
 * Scans Wynncraft's `/emote` GUI for the available emotes.
 *
 * The GUI items are `minecraft:potion`s named "<X> Emote"; the actual command
 * argument lives in a lore line `Command: /emote <arg>`. Non-emote items
 * (Scrap/Sort buttons, player-inventory cosmetics) have no such lore line, so
 * matching `/emote <arg>` cleanly isolates real emotes.
 *
 * Flow: [startScan] sends `/emote`, then [tick] polls the opened container each
 * client tick until its slots populate, parses the emote args, stores them via
 * [EmoteCatalog.setList], and closes the GUI.
 */
object EmoteScanner {

    private const val TAG = "Emote"
    private const val TIMEOUT_TICKS = 120  // ~6s
    private val EMOTE_RE = Regex("/emote\\s+(\\S+)", RegexOption.IGNORE_CASE)

    @Volatile private var scanning = false
    private var waited = 0

    fun isScanning(): Boolean = scanning

    fun startScan() {
        val handler = MinecraftClient.getInstance().player?.networkHandler ?: return
        handler.sendChatCommand("emote")
        scanning = true
        waited = 0
        AhjLog.info(TAG, "scan started (sent /emote)")
    }

    fun tick(mc: MinecraftClient) {
        if (!scanning) return
        if (++waited > TIMEOUT_TICKS) {
            scanning = false
            AhjLog.warn(TAG, "scan timed out with no emote GUI")
            return
        }
        val screen = mc.currentScreen as? HandledScreen<*> ?: return
        val found = parse(screen.screenHandler)
        if (found.isEmpty()) return  // not populated yet; keep polling

        EmoteCatalog.setList(found)
        scanning = false
        mc.player?.closeHandledScreen()
        AhjLog.info(TAG, "scanned {} emotes: {}", found.size, found.joinToString())
    }

    private fun parse(handler: ScreenHandler): List<String> {
        val out = LinkedHashSet<String>()
        for (slot in handler.slots) {
            val stack = slot.stack
            if (stack.isEmpty) continue
            val lore = stack.get(DataComponentTypes.LORE) ?: continue
            for (line in lore.lines()) {
                val m = EMOTE_RE.find(line.string) ?: continue
                out.add(m.groupValues[1].lowercase())
            }
        }
        return out.toList()
    }
}
