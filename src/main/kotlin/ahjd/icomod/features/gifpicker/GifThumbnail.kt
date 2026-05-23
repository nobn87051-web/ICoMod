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
 * Drawing at a smaller display size is the caller's job — they scale via matrix.
 * That keeps textures sharp regardless of where they're shown (chat overlay,
 * picker thumb, etc.) without re-decoding per size.
 *
 * Performance notes (see commit history for the original slow path):
 *  - All CPU-heavy work (ImageIO decode, scaling, BufferedImage → NativeImage
 *    pixel copy) happens on [decodePool], never the main render thread.
 *  - Texture registration is chunked across multiple main-thread ticks so a
 *    long GIF doesn't freeze rendering for hundreds of ms on first display.
 */
object GifThumbnail {
    /** Cap longest side of decoded frames. 512px is a good "source quality" upper bound — keeps memory bounded for long gifs. */
    const val MAX_SOURCE_DIM = 512

    /** How many frames to register per main-thread tick. Small enough that any single tick stays sub-millisecond on the GPU upload path. */
    private const val REGISTER_BATCH = 4

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

                val sw = decoded[0].image.width
                val sh = decoded[0].image.height
                val ratio = minOf(1.0, MAX_SOURCE_DIM.toDouble() / maxOf(sw, sh))
                val tw = maxOf(1, (sw * ratio).toInt())
                val th = maxOf(1, (sh * ratio).toInt())

                val nativeFrames: List<Pair<NativeImage, Int>> = decoded.map { df ->
                    val src = if (tw == sw && th == sh) df.image else scaleHQ(df.image, tw, th)
                    bufferedImageToNativeImage(src) to df.delayMs
                }

                registerInBatches(name, nativeFrames, tw, th)
            }.onFailure {
                AhjLog.error(TAG, "Failed to decode gif {}", it, name)
                pending.remove(name)
            }
        }
        return null
    }

    /**
     * Recursive batched registration. Each call registers up to [REGISTER_BATCH]
     * frames on the next main-thread tick, then schedules itself for the next
     * batch. The last batch publishes the [CachedTexture] and clears the pending
     * flag.
     */
    private fun registerInBatches(
        name: String,
        nativeFrames: List<Pair<NativeImage, Int>>,
        tw: Int,
        th: Int,
    ) {
        val sanitized = sanitize(name)
        val registered = mutableListOf<GifFrame>()
        val total = nativeFrames.size
        val mc = MinecraftClient.getInstance()

        fun submitBatch(start: Int) {
            mc.execute {
                runCatching {
                    val texMgr = mc.textureManager
                    val end = minOf(start + REGISTER_BATCH, total)
                    for (i in start until end) {
                        val (img, delay) = nativeFrames[i]
                        val texName = "icomod-gif-$sanitized-${tw}x${th}-f$i"
                        val tex = NativeImageBackedTexture(Supplier { texName }, img)
                        val id = Identifier.of("icomod", "gif/${sanitized}_${tw}x${th}_f$i")
                        texMgr.registerTexture(id, tex)
                        registered += GifFrame(id, tw, th, delay)
                    }
                    if (end < total) {
                        submitBatch(end)
                    } else {
                        cache[name] = CachedTexture(registered, tw, th)
                        AhjLog.info(
                            TAG, "Registered gif {} ({}x{} src→{}x{}, {} frames, {}ms total)",
                            name, nativeFrames[0].first.width, nativeFrames[0].first.height,
                            tw, th, registered.size, registered.sumOf { it.delayMs }
                        )
                        pending.remove(name)
                    }
                }.onFailure {
                    AhjLog.error(TAG, "Failed to register gif {}", it, name)
                    pending.remove(name)
                }
            }
        }
        submitBatch(0)
    }

    /**
     * Convert a [BufferedImage] directly to a [NativeImage] without the
     * BufferedImage → PNG → NativeImage round-trip the old code did.
     *
     * [BufferedImage.getRGB] normalises any source pixel layout to ARGB
     * (`0xAARRGGBB`). [NativeImage] in 1.21.x expects ABGR byte order in
     * little-endian memory, i.e. packed-int `0xAABBGGRR`. The loop swaps the
     * R and B bytes accordingly. If you ever see colours render with red and
     * blue swapped, suspect this conversion first.
     */
    private fun bufferedImageToNativeImage(buf: BufferedImage): NativeImage {
        val w = buf.width
        val h = buf.height
        val pixels = IntArray(w * h)
        buf.getRGB(0, 0, w, h, pixels, 0, w)
        val ni = NativeImage(w, h, false)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = pixels[idx++]
                val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val abgr = (a shl 24) or (b shl 16) or (g shl 8) or r
                ni.setColor(x, y, abgr)
            }
        }
        return ni
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
