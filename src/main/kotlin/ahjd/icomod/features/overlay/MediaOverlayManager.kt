package ahjd.icomod.features.overlay

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.util.AhjLog
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

private const val TAG = "MediaOverlay"

/**
 * Owns the on-disk media folder, the per-file entry list, and writes to the
 * persisted [OverlayState] map in [ConfigManager.config].
 *
 * Folder layout:
 * ```
 * <gameDir>/icomod/overlay-media/
 *     foo.png, bar.gif, video.mp4, ...
 *     .trash/         <-- deleted files moved here (recoverable)
 * ```
 *
 * Entry list is recomputed by [rescan]. Scanning runs on the calling thread;
 * decoding stays cheap because we only read image headers for dimensions —
 * pixel decode is deferred to [ahjd.icomod.features.gifpicker.GifThumbnail]
 * at render time.
 *
 * Supported extensions:
 *  - `png`, `jpg`, `jpeg` -> [Kind.IMAGE]  (static; decoded via GifThumbnail)
 *  - `gif`                -> [Kind.GIF]    (animated; decoded via GifThumbnail)
 *  - `mp4`                -> [Kind.VIDEO_PLACEHOLDER]  (renders as a labeled
 *    card; no decoder shipped yet — see spec §4)
 */
object MediaOverlayManager {

    val folder: File = FabricLoader.getInstance()
        .gameDir.resolve("icomod/overlay-media").toFile()
    private val trash: File = File(folder, ".trash")

    enum class Kind { IMAGE, GIF, VIDEO_PLACEHOLDER }

    data class Entry(
        val file: File,
        val name: String,
        val kind: Kind,
        val origW: Int,
        val origH: Int,
    )

    @Volatile private var entries: List<Entry> = emptyList()

    /**
     * Filenames shipped inside the jar at
     * `assets/icomod/overlay-defaults/<name>` that get auto-deployed to the
     * user's overlay folder on first run. Each name is deployed at most once
     * — deletion is tracked in [ahjd.icomod.config.ModConfig.overlayDefaultsDeployed]
     * so users can permanently remove a default without it reappearing
     * after each restart.
     */
    private val BUNDLED_DEFAULTS = listOf("THIS.png")

    /** Trash retention. Files in `.trash/` older than this are deleted. */
    private val TRASH_TTL_MS = 24L * 60 * 60 * 1000  // 1 day

    fun init() {
        folder.mkdirs()
        deployBundledDefaults()
        purgeExpiredTrash()
        rescan()
    }

    /**
     * Copy each name in [BUNDLED_DEFAULTS] from the jar to [folder] iff it
     * hasn't been deployed before. Tracking lives in
     * [ahjd.icomod.config.ModConfig.overlayDefaultsDeployed]; once a name is
     * recorded there it never gets re-extracted, even if the user deletes
     * the on-disk copy. To restore a default, the user clears the entry
     * from the config or just drops a matching file in the folder.
     */
    private fun deployBundledDefaults() {
        val cfg = ConfigManager.config
        var dirty = false
        for (name in BUNDLED_DEFAULTS) {
            if (name in cfg.overlayDefaultsDeployed) continue
            val target = File(folder, name)
            // If the file already exists from a previous install pre-tracking,
            // just mark it deployed and move on. Don't overwrite a possibly
            // user-edited copy.
            if (target.exists()) {
                cfg.overlayDefaultsDeployed.add(name)
                dirty = true
                continue
            }
            val resPath = "assets/icomod/overlay-defaults/$name"
            val stream = MediaOverlayManager::class.java.classLoader
                .getResourceAsStream(resPath)
            if (stream == null) {
                AhjLog.warn(TAG, "bundled default missing from jar: {}", name)
                continue
            }
            runCatching {
                stream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                cfg.overlayDefaultsDeployed.add(name)
                dirty = true
                AhjLog.info(TAG, "deployed default {} -> {}", name, target.absolutePath)
            }.onFailure {
                AhjLog.error(TAG, "failed to deploy default {}", it, name)
            }
        }
        if (dirty) ConfigManager.save()
    }

    @Synchronized
    fun rescan() {
        if (!folder.exists()) folder.mkdirs()
        val files = folder.listFiles { f -> f.isFile } ?: emptyArray()
        val list = mutableListOf<Entry>()
        for (file in files.sortedBy { it.name.lowercase() }) {
            val ext = file.extension.lowercase()
            val kind = when (ext) {
                "png", "jpg", "jpeg" -> Kind.IMAGE
                "gif"                -> Kind.GIF
                "mp4"                -> Kind.VIDEO_PLACEHOLDER
                else                 -> continue
            }
            val (w, h) = when (kind) {
                Kind.VIDEO_PLACEHOLDER -> 320 to 180
                else                   -> probeImageDims(file)
            }
            list += Entry(file, file.name, kind, w, h)
        }
        entries = list

        // Prune state entries whose file no longer exists. Don't drop hidden
        // flag for files that still exist but are merely renamed — there's no
        // way to tell, and the user re-positioning is cheaper than guessing.
        val cfg = ConfigManager.config
        val present = list.mapTo(mutableSetOf()) { it.name }
        val removed = cfg.overlayStates.keys.toList().filter { it !in present }
        if (removed.isNotEmpty()) {
            removed.forEach { cfg.overlayStates.remove(it) }
            ConfigManager.save()
        }

        AhjLog.info(TAG, "scan: {} entries ({} pruned)", list.size, removed.size)
    }

    fun all(): List<Entry> = entries

    /**
     * Entries sorted so older-touched render first and newer-touched render
     * last (= on top). Used by both the renderer and the input hit-test so
     * the visual stack and the click stack stay in sync.
     */
    fun allByStack(): List<Entry> {
        val cfg = ConfigManager.config
        return entries.sortedBy { cfg.overlayStates[it.name]?.lastTouched ?: 0L }
    }

    /** Bump the touch counter so this entry renders above the others next frame. */
    fun touch(name: String) {
        state(name).lastTouched = System.nanoTime()
    }

    fun state(name: String): OverlayState {
        val cfg = ConfigManager.config
        return cfg.overlayStates.getOrPut(name) { OverlayState() }
    }

    fun setHidden(name: String, hidden: Boolean) {
        state(name).hidden = hidden
        ConfigManager.save()
    }

    fun setPos(name: String, x: Int, y: Int) {
        val s = state(name)
        s.x = x; s.y = y
        ConfigManager.save()
    }

    /**
     * Set position without writing config to disk. Used by the drag tick loop
     * (~20Hz) to avoid hammering the filesystem. Call [persist] when the hot
     * loop ends (e.g. on mouse release) to commit the final position.
     */
    fun setPosTransient(name: String, x: Int, y: Int) {
        val s = state(name)
        s.x = x; s.y = y
    }

    /** Force a config write. Pair with [setPosTransient]. */
    fun persist() {
        ConfigManager.save()
    }

    fun setScale(name: String, scale: Double) {
        state(name).scale = scale.coerceIn(0.1, 5.0)
        ConfigManager.save()
    }

    fun resetScale(name: String) {
        state(name).scale = 1.0
        ConfigManager.save()
    }

    /**
     * Move file to `overlay-media/.trash/<yyyyMMdd-HHmmss>-<name>`. Always
     * timestamped so repeat deletions of the same filename never collide.
     * Recoverable by the user via file manager until the 1-day TTL elapses.
     *
     * Also kicks [purgeExpiredTrash] so the trash folder doesn't grow
     * unbounded — every deletion is also a cleanup opportunity.
     */
    @Synchronized
    fun delete(name: String) {
        val entry = entries.find { it.name == name } ?: return
        trash.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val target = File(trash, "$stamp-$name")
        val moved = runCatching { entry.file.renameTo(target) }.getOrDefault(false)
        if (moved) {
            // Force lastModified to "now" — `renameTo` preserves the original
            // mtime, which could be ancient (e.g. a downloaded asset). TTL
            // measures time-in-trash, not file age.
            target.setLastModified(System.currentTimeMillis())
        } else {
            AhjLog.warn(TAG, "failed to move {} to trash; leaving in place", name)
        }
        purgeExpiredTrash()
        ConfigManager.config.overlayStates.remove(name)
        ConfigManager.save()
        rescan()
    }

    /**
     * Delete any file in `.trash/` whose lastModified is older than
     * [TRASH_TTL_MS]. Called on init and on every user-initiated delete.
     * Safe to call when the folder doesn't exist (no-op).
     */
    private fun purgeExpiredTrash() {
        if (!trash.exists()) return
        val cutoff = System.currentTimeMillis() - TRASH_TTL_MS
        val expired = trash.listFiles { f -> f.isFile && f.lastModified() < cutoff }
            ?: return
        for (f in expired) {
            if (f.delete()) AhjLog.info(TAG, "trash purge: {}", f.name)
            else AhjLog.warn(TAG, "trash purge failed: {}", f.name)
        }
    }

    /**
     * Read image header for original pixel dimensions without decoding pixels.
     * Falls back to 256x256 if the file can't be probed (e.g. malformed).
     */
    private fun probeImageDims(file: File): Pair<Int, Int> {
        return runCatching {
            ImageIO.createImageInputStream(file).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return@use 256 to 256
                val reader = readers.next()
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrDefault(256 to 256)
    }
}
