package ahjd.icomod.features.gifpicker

import ahjd.icomod.util.AhjLog
import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.w3c.dom.NamedNodeMap
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.function.Supplier
import javax.imageio.ImageIO

private const val TAG = "GifThumbnail"

/**
 * Per-frame texture record. Width/height are NATIVE pixel dimensions of the decoded
 * frame (bounded by [GifThumbnail.MAX_SOURCE_DIM]). Callers scale at draw time.
 */
data class GifFrame(val id: Identifier, val width: Int, val height: Int, val delayMs: Int)

/** Animated texture: sequence of frames at source resolution. Use [frameAt] to pick the current frame. */
data class CachedTexture(val frames: List<GifFrame>, val width: Int, val height: Int) {
    val totalDurationMs: Int = frames.sumOf { it.delayMs }.coerceAtLeast(1)

    fun frameAt(timeMs: Long): GifFrame {
        if (frames.size == 1) return frames[0]
        val t = (timeMs % totalDurationMs).toInt()
        var acc = 0
        for (f in frames) {
            acc += f.delayMs
            if (t < acc) return f
        }
        return frames.last()
    }

    val id: Identifier get() = frames[0].id
}

/**
 * Decodes ALL frames of a GIF (or single frame for PNG/JPEG) at source resolution
 * (capped to [MAX_SOURCE_DIM] on the longest side) and registers each as a
 * Minecraft texture. Cached by name. Decoding runs on a small fixed thread pool
 * shared across all gifs.
 *
 * Drawing at a smaller display size is the caller's job â€” they scale via matrix.
 * That keeps textures sharp regardless of where they're shown (chat overlay, picker
 * thumb, etc.) without re-decoding per size.
 */
object GifThumbnail {
    /** Cap longest side of decoded frames. 512px is a good "source quality" upper bound â€” keeps memory bounded for long gifs. */
    const val MAX_SOURCE_DIM = 512

    private val cache = ConcurrentHashMap<String, CachedTexture>()
    private val pending = ConcurrentHashMap<String, Boolean>()

    private val decodePool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "icomod-gif-decode").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun get(file: File, name: String): CachedTexture? {
        cache[name]?.let { return it }
        if (pending.putIfAbsent(name, true) != null) return null

        decodePool.submit {
            runCatching {
                val decoded = decodeAllFrames(file)
                if (decoded.isEmpty()) error("no frames decoded")

                // Compute target dims using first frame as the canonical aspect
                val sw = decoded[0].image.width
                val sh = decoded[0].image.height
                val ratio = minOf(1.0, MAX_SOURCE_DIM.toDouble() / maxOf(sw, sh))
                val tw = maxOf(1, (sw * ratio).toInt())
                val th = maxOf(1, (sh * ratio).toInt())

                val pngFrames: List<Pair<ByteArray, Int>> = decoded.map { df ->
                    val out = if (tw == sw && th == sh) df.image else scaleHQ(df.image, tw, th)
                    val baos = ByteArrayOutputStream()
                    ImageIO.write(out, "png", baos)
                    baos.toByteArray() to df.delayMs
                }

                MinecraftClient.getInstance().execute {
                    runCatching {
                        val texMgr = MinecraftClient.getInstance().textureManager
                        val frames = pngFrames.mapIndexed { idx, (bytes, delay) ->
                            val img = NativeImage.read(ByteArrayInputStream(bytes))
                            val texName = "icomod-gif-${sanitize(name)}-${tw}x${th}-f$idx"
                            val tex = NativeImageBackedTexture(Supplier { texName }, img)
                            val id = Identifier.of("icomod", "gif/${sanitize(name)}_${tw}x${th}_f$idx")
                            texMgr.registerTexture(id, tex)
                            GifFrame(id, tw, th, delay)
                        }
                        cache[name] = CachedTexture(frames, tw, th)
                        AhjLog.info(TAG, "Registered gif {} ({}x{} srcâ†’{}x{}, {} frames, {}ms total)",
                            name, sw, sh, tw, th, frames.size, frames.sumOf { it.delayMs })
                    }.onFailure { AhjLog.error(TAG, "Failed to register gif {}", it, name) }
                    pending.remove(name)
                }
            }.onFailure {
                AhjLog.error(TAG, "Failed to decode gif {}", it, name)
                pending.remove(name)
            }
        }
        return null
    }

    private fun scaleHQ(src: BufferedImage, tw: Int, th: Int): BufferedImage {
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(src, 0, 0, tw, th, null)
        g.dispose()
        return out
    }

    private data class DecodedFrame(val image: BufferedImage, val delayMs: Int)

    private fun decodeAllFrames(file: File): List<DecodedFrame> {
        if (file.extension.lowercase() != "gif") {
            val img = ImageIO.read(file) ?: error("ImageIO returned null for $file")
            return listOf(DecodedFrame(img, Int.MAX_VALUE))
        }

        val readers = ImageIO.getImageReadersByFormatName("gif")
        if (!readers.hasNext()) error("no gif reader available")
        val reader = readers.next()

        ImageIO.createImageInputStream(file).use { stream ->
            reader.input = stream
            val n = reader.getNumImages(true)
            if (n <= 0) error("gif reports zero frames")

            val first = reader.read(0)
            val canvasW = first.width
            val canvasH = first.height
            val canvas = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)
            val g = canvas.createGraphics()
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, canvasW, canvasH)
            g.composite = AlphaComposite.SrcOver

            val frames = mutableListOf<DecodedFrame>()
            for (i in 0 until n) {
                val frame = reader.read(i)
                val meta = reader.getImageMetadata(i)
                val (delayMs, disposal, x, y) = readGifMeta(meta)

                val before = if (disposal == 3) {
                    val copy = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)
                    val gc = copy.createGraphics(); gc.drawImage(canvas, 0, 0, null); gc.dispose()
                    copy
                } else null

                g.drawImage(frame, x, y, null)

                val snapshot = BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB)
                val gs = snapshot.createGraphics(); gs.drawImage(canvas, 0, 0, null); gs.dispose()
                frames += DecodedFrame(snapshot, delayMs)

                when (disposal) {
                    2 -> {
                        val gc = canvas.createGraphics()
                        gc.composite = AlphaComposite.Src
                        gc.color = Color(0, 0, 0, 0)
                        gc.fillRect(x, y, frame.width, frame.height)
                        gc.dispose()
                    }
                    3 -> {
                        val gc = canvas.createGraphics()
                        gc.composite = AlphaComposite.Src
                        gc.drawImage(before!!, 0, 0, null)
                        gc.dispose()
                    }
                }
            }
            g.dispose()
            return frames
        }
    }

    private fun readGifMeta(meta: javax.imageio.metadata.IIOMetadata): GifMetaTuple {
        var delayMs = 100
        var disposal = 0
        var x = 0
        var y = 0
        val root = meta.getAsTree(meta.nativeMetadataFormatName) ?: return GifMetaTuple(delayMs, disposal, x, y)
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeName) {
                "GraphicControlExtension" -> {
                    val attrs: NamedNodeMap = child.attributes
                    attrs.getNamedItem("delayTime")?.nodeValue?.toIntOrNull()?.let { delayMs = it * 10 }
                    attrs.getNamedItem("disposalMethod")?.nodeValue?.let { disposal = disposalCode(it) }
                }
                "ImageDescriptor" -> {
                    val attrs: NamedNodeMap = child.attributes
                    attrs.getNamedItem("imageLeftPosition")?.nodeValue?.toIntOrNull()?.let { x = it }
                    attrs.getNamedItem("imageTopPosition")?.nodeValue?.toIntOrNull()?.let { y = it }
                }
            }
        }
        if (delayMs <= 0) delayMs = 100
        return GifMetaTuple(delayMs, disposal, x, y)
    }

    private data class GifMetaTuple(val delayMs: Int, val disposal: Int, val x: Int, val y: Int)

    private fun disposalCode(s: String): Int = when (s) {
        "restoreToBackgroundColor" -> 2
        "restoreToPrevious" -> 3
        "doNotDispose" -> 1
        else -> 0
    }

    private fun sanitize(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
}
