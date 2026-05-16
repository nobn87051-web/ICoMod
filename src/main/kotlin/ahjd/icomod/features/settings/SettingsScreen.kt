package ahjd.icomod.features.settings

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.gifpicker.GifSize
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.CyclingButtonWidget
import net.minecraft.text.Text

class SettingsScreen(private val parent: Screen?) : Screen(Text.literal("IcoMod Settings")) {

    private data class SectionHeader(val y: Int, val label: String)
    private val headers = mutableListOf<SectionHeader>()

    override fun init() {
        headers.clear()
        val cfg = ConfigManager.config
        val cx = width / 2
        val bw = 200
        val bh = 20

        var y = 40

        // â”€â”€ Chat Modes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        headers += SectionHeader(y, "Chat Modes")
        y += 14

        addDrawableChild(
            CyclingButtonWidget.onOffBuilder(cfg.chatModeEnabled)
                .build(cx - bw - 4, y, bw, bh, Text.literal("Chat Modes")) { _, v ->
                    ConfigManager.config.chatModeEnabled = v
                }
        )
        y += 28

        // â”€â”€ GIFs in Chat â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        headers += SectionHeader(y, "GIFs in Chat")
        y += 14

        addDrawableChild(
            CyclingButtonWidget.onOffBuilder(cfg.gifsEnabled)
                .build(cx - bw - 4, y, bw, bh, Text.literal("Show GIFs")) { _, v ->
                    ConfigManager.config.gifsEnabled = v
                }
        )

        var sizeIdx = GifSize.entries.indexOf(GifSize.parse(cfg.gifDefaultSize)).coerceAtLeast(0)
        fun sizeLabel() = Text.literal("Default Size: ${GifSize.entries[sizeIdx].name}")
        addDrawableChild(
            ButtonWidget.builder(sizeLabel()) { btn ->
                sizeIdx = (sizeIdx + 1) % GifSize.entries.size
                ConfigManager.config.gifDefaultSize = GifSize.entries[sizeIdx].name
                btn.message = sizeLabel()
            }
                .dimensions(cx + 4, y, bw, bh)
                .build()
        )
        y += 28

        addDrawableChild(
            CyclingButtonWidget.onOffBuilder(cfg.gifStretch)
                .build(cx - bw - 4, y, bw, bh, Text.literal("Stretch GIFs")) { _, v ->
                    ConfigManager.config.gifStretch = v
                }
        )

        // â”€â”€ Done â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Done")) { close() }
                .dimensions(cx - 100, height - 28, 200, bh)
                .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFF)
        for (h in headers) {
            context.drawTextWithShadow(
                textRenderer,
                Text.literal("â”€â”€ ${h.label} â”€â”€"),
                width / 2 - 204,
                h.y,
                0xAAAAAA
            )
        }
    }

    override fun close() {
        ConfigManager.save()
        client?.setScreen(parent)
    }

    override fun shouldPause() = false
}
