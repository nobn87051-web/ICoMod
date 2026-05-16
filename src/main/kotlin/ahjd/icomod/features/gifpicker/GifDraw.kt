package ahjd.icomod.features.gifpicker

import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

/**
 * Draws a texture stretched to a target rect. 1.21.x [DrawContext.drawTexture] has no
 * stretching overload, so we matrix-scale around a native-size draw. Used by both the
 * chat overlay and the picker so source-quality textures display sharp at any size.
 */
object GifDraw {
    fun drawScaled(
        ctx: DrawContext,
        id: Identifier,
        x: Int, y: Int,
        dispW: Int, dispH: Int,
        srcW: Int, srcH: Int
    ) {
        val sx = dispW.toFloat() / srcW
        val sy = dispH.toFloat() / srcH

        val matrices = ctx.matrices
        matrices.pushMatrix()
        matrices.translate(x.toFloat(), y.toFloat())
        matrices.scale(sx, sy)

        ctx.drawTexture(
            RenderPipelines.GUI_TEXTURED, id,
            0, 0,
            0f, 0f,
            srcW, srcH,
            srcW, srcH
        )

        matrices.popMatrix()
    }
}
