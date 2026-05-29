package ahjd.icomod.features.sounds

import ahjd.icomod.util.AhjLog
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.sound.OggAudioStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

/**
 * Audio file library + playback for §5 Custom Spell Sounds.
 *
 * - Scans `<gameDir>/icomod/sounds/` for `.ogg` files (only format we
 *   support out of the gate; user wanted raw JavaSound playback and OGG
 *   needs no extra deps because Minecraft already ships [OggAudioStream]
 *   for its own asset pipeline).
 * - `playAsync(filename)` decodes the OGG to PCM via [OggAudioStream] and
 *   pumps it through a dedicated [SourceDataLine] on a small background
 *   pool. Each call gets its own line so overlapping casts don't clip
 *   each other.
 * - Volume is scaled by MC's Players sound-category slider so the user's
 *   in-game volume controls still apply.
 */
object SoundLibrary {

    val folder: File by lazy {
        FabricLoader.getInstance().gameDir.resolve("icomod/sounds").toFile()
            .also { it.mkdirs() }
    }

    @Volatile
    private var files: List<String> = emptyList()

    private val exec = Executors.newCachedThreadPool { r ->
        Thread(r, "icomod-sounds").apply { isDaemon = true }
    }

    fun refresh() {
        folder.mkdirs()
        files = (folder.listFiles { f -> f.isFile && isSupported(f.name) }
            ?: emptyArray())
            .map { it.name }
            .sorted()
        AhjLog.info("SoundLibrary", "scanned {} files", files.size)
    }

    private fun isSupported(name: String): Boolean =
        name.endsWith(".ogg", ignoreCase = true) ||
        name.endsWith(".mp3", ignoreCase = true)

    fun list(): List<String> = files

    /**
     * Cancel token for an in-flight playback. Callers hand this back to
     * [stop] (or call [stop] on it directly) to interrupt the audio
     * thread mid-stream -- used by [ahjd.icomod.features.sounds.SpellSoundManager]
     * to implement "consecutive cast stops the previous instance".
     *
     * Volatile because the decode loop runs on the [exec] pool thread
     * while [stop] is invoked from the network thread.
     */
    class PlaybackHandle internal constructor() {
        @Volatile var line: SourceDataLine? = null
        @Volatile var cancelled: Boolean = false
        fun stop() {
            cancelled = true
            val l = line
            try { l?.stop() } catch (_: Throwable) {}
            try { l?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Fire-and-forget. Returns a handle even if the file is missing
     * (already-cancelled) so callers can unconditionally call .stop()
     * on the previous handle without null-checks.
     *
     * @param extraGain extra linear amplitude multiplier 0..1 applied on top
     *   of the MC master×players sliders. Default 1.0 = use MC sliders only.
     */
    fun playAsync(filename: String, extraGain: Float = 1.0f): PlaybackHandle {
        val handle = PlaybackHandle()
        val file = File(folder, filename)
        if (!file.isFile) {
            AhjLog.warn("SoundLibrary", "missing file {}", filename)
            handle.cancelled = true
            return handle
        }
        val clamped = extraGain.coerceIn(0f, 1f)
        exec.submit { playBlocking(file, clamped, handle) }
        return handle
    }

    /**
     * Play a sound bundled inside the jar (classpath resource), e.g. the DOGGC
     * chest-open sound at `/assets/icomod/sounds/doggcsound.mp3`. Same
     * fire-and-forget contract as [playAsync] but the source is a resource
     * stream, not the user sounds folder — so bundled sounds don't pollute the
     * spell-pairing dropdowns.
     */
    fun playResourceAsync(
        resourcePath: String,
        extraGain: Float = 1.0f,
        speed: Float = 1.0f,
    ): PlaybackHandle {
        val handle = PlaybackHandle()
        val clamped = extraGain.coerceIn(0f, 1f)
        val rate = speed.coerceIn(0.1f, 4.5f)
        exec.submit {
            val ins = SoundLibrary::class.java.getResourceAsStream(resourcePath)
            if (ins == null) {
                AhjLog.warn("SoundLibrary", "missing bundled sound {}", resourcePath)
                handle.cancelled = true
                return@submit
            }
            try {
                ins.use {
                    when {
                        resourcePath.endsWith(".ogg", true) -> playOggStream(it, clamped, handle, rate)
                        resourcePath.endsWith(".mp3", true) -> playMp3Stream(it, clamped, handle, rate)
                        else -> AhjLog.warn("SoundLibrary", "unsupported bundled format {}", resourcePath)
                    }
                }
            } catch (e: Throwable) {
                if (!handle.cancelled) AhjLog.error("SoundLibrary", "bundled playback failed {}", e, resourcePath)
            }
        }
        return handle
    }

    private fun playBlocking(file: File, extraGain: Float, handle: PlaybackHandle) {
        try {
            when {
                file.name.endsWith(".ogg", ignoreCase = true) -> playOgg(file, extraGain, handle)
                file.name.endsWith(".mp3", ignoreCase = true) -> playMp3(file, extraGain, handle)
                else -> AhjLog.warn("SoundLibrary", "unsupported format: {}", file.name)
            }
        } catch (e: Throwable) {
            if (!handle.cancelled) AhjLog.error("SoundLibrary", "playback failed for {}", e, file.name)
        }
    }

    private fun playOgg(file: File, extraGain: Float, handle: PlaybackHandle) =
        file.inputStream().use { playOggStream(it, extraGain, handle) }

    private fun playOggStream(input: InputStream, extraGain: Float, handle: PlaybackHandle, speed: Float = 1.0f) {
        var line: SourceDataLine? = null
        try {
            OggAudioStream(input).use { ogg ->
                    if (handle.cancelled) return
                    // OggAudioStream.format is a fully-formed javax AudioFormat
                    // (PCM signed 16-bit LE). The line ALWAYS opens at the source
                    // sample rate (device-supported); [speed] is applied by
                    // resampling the PCM, never by inflating the line rate — the
                    // latter blows past what the audio device can open.
                    val base = ogg.format
                    val ch = base.channels
                    val info = DataLine.Info(SourceDataLine::class.java, base)
                    val sdl = AudioSystem.getLine(info) as SourceDataLine
                    line = sdl
                    handle.line = sdl
                    sdl.open(base)
                    applyVolume(sdl, extraGain)
                    sdl.start()

                    val phase = doubleArrayOf(0.0)
                    while (!handle.cancelled) {
                        val buf = ogg.read(8192) ?: break
                        val byteCount = buf.remaining()
                        if (byteCount <= 0) continue
                        // Reinterpret the LE byte chunk as interleaved shorts.
                        val shorts = ShortArray(byteCount / 2)
                        for (i in shorts.indices) {
                            val lo = buf.get().toInt() and 0xFF
                            val hi = buf.get().toInt()
                            shorts[i] = ((hi shl 8) or lo).toShort()
                        }
                        writeResampled(sdl, shorts, shorts.size, ch, speed.toDouble(), phase)
                    }
                    if (!handle.cancelled) sdl.drain()
                }
        } finally {
            try { line?.stop() } catch (_: Throwable) {}
            try { line?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * MP3 decode via JLayer. Each frame yields a SampleBuffer of signed
     * 16-bit PCM samples; the AudioFormat (sample rate + channel count) is
     * derived from the first decoded frame's header. We open the line
     * lazily because JLayer doesn't expose this metadata before decode.
     */
    private fun playMp3(file: File, extraGain: Float, handle: PlaybackHandle) =
        file.inputStream().use { playMp3Stream(it, extraGain, handle) }

    private fun playMp3Stream(input: InputStream, extraGain: Float, handle: PlaybackHandle, speed: Float = 1.0f) {
        var line: SourceDataLine? = null
        try {
            BufferedInputStream(input).use { bis ->
                val bitstream = Bitstream(bis)
                val decoder = Decoder()

                val phase = doubleArrayOf(0.0)
                try {
                    while (!handle.cancelled) {
                        val header = bitstream.readFrame() ?: break
                        val sb = decoder.decodeFrame(header, bitstream) as SampleBuffer
                        if (line == null) {
                            // Line ALWAYS opens at the source sample rate (device-
                            // supported). [speed] is applied by resampling below,
                            // not by inflating the line rate.
                            val fmt = AudioFormat(
                                sb.sampleFrequency.toFloat(), 16, sb.channelCount, true, false,
                            )
                            val info = DataLine.Info(SourceDataLine::class.java, fmt)
                            val sdl = AudioSystem.getLine(info) as SourceDataLine
                            sdl.open(fmt)
                            applyVolume(sdl, extraGain)
                            sdl.start()
                            line = sdl
                            handle.line = sdl
                        }
                        writeResampled(line!!, sb.buffer, sb.bufferLength, sb.channelCount, speed.toDouble(), phase)
                        bitstream.closeFrame()
                    }
                    if (!handle.cancelled) line?.drain()
                } finally {
                    try { bitstream.close() } catch (_: Throwable) {}
                }
            }
        } finally {
            try { line?.stop() } catch (_: Throwable) {}
            try { line?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Write interleaved 16-bit PCM [samples] (length [n], [ch] channels) to
     * [line] as little-endian bytes, resampled by [speed] via nearest-sample
     * pick. [speed] > 1 plays faster + higher-pitched (fewer output frames);
     * the line itself stays at the source rate so it's always openable.
     *
     * [phase] carries the fractional source position across calls so chunk/
     * frame boundaries don't click. speed == 1.0 is a fast straight copy.
     */
    private fun writeResampled(
        line: SourceDataLine,
        samples: ShortArray,
        n: Int,
        ch: Int,
        speed: Double,
        phase: DoubleArray,
    ) {
        if (n <= 0 || ch <= 0) return
        val frames = n / ch
        if (frames <= 0) return

        if (speed == 1.0) {
            val out = ByteArray(n * 2)
            var bi = 0
            for (i in 0 until n) {
                val s = samples[i].toInt()
                out[bi++] = (s and 0xFF).toByte()
                out[bi++] = ((s shr 8) and 0xFF).toByte()
            }
            line.write(out, 0, bi)
            return
        }

        // Worst case (speed -> 0.1) ~10x output frames; size generously.
        val estFrames = (frames / speed).toInt() + 2
        val out = ByteArray(estFrames * ch * 2)
        var bi = 0
        var p = phase[0]
        while (p < frames) {
            val base = p.toInt() * ch
            for (c in 0 until ch) {
                if (bi + 1 >= out.size) { line.write(out, 0, bi); bi = 0 }
                val s = samples[base + c].toInt()
                out[bi++] = (s and 0xFF).toByte()
                out[bi++] = ((s shr 8) and 0xFF).toByte()
            }
            p += speed
        }
        phase[0] = p - frames  // carry remainder into the next chunk
        if (bi > 0) line.write(out, 0, bi)
    }

    private fun applyVolume(line: SourceDataLine, extraGain: Float) {
        // Direct JavaSound gain only -- intentionally decoupled from
        // Minecraft's MASTER / PLAYERS sliders so the custom spell sounds
        // respond purely to the mod's own master + per-spell volume.
        // Final amplitude = perSpell * spellMasterVolume (the caller already
        // multiplied those into extraGain).
        val combined = extraGain.coerceIn(0.0001f, 1f)

        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val db = (20.0 * kotlin.math.log10(combined.toDouble())).toFloat()
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
    }
}
