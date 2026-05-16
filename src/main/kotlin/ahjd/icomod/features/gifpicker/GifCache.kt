package ahjd.icomod.features.gifpicker

import ahjd.icomod.config.ConfigManager
import net.fabricmc.loader.api.FabricLoader
import ahjd.icomod.util.AhjLog
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GifCache"

/**
 * On-disk cache for GIF file bytes. Files live at .minecraft/ahjdmod/cache/gifs/{name}.
 * Cache entries are hash-validated against the catalog; mismatched files re-download.
 */
object GifCache {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val cacheDir: File = FabricLoader.getInstance().gameDir
        .resolve("ahjdmod/cache/gifs").toFile().also { it.mkdirs() }

    // Tracks in-flight downloads so concurrent requests for the same file collapse into one
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<File?>>()

    /**
     * Returns the local file for [entry], downloading if missing or hash-mismatched.
     * Async: completes off the main thread.
     */
    fun ensureLocalAsync(entry: GifEntry): CompletableFuture<File?> {
        return inFlight.computeIfAbsent(entry.name) {
            AhjLog.info(TAG, "ensureLocal start name={} hash={}", entry.name, entry.hash)
            CompletableFuture.supplyAsync {
                val file = File(cacheDir, entry.name)
                if (file.exists() && sha256(file) == entry.hash) {
                    AhjLog.info(TAG, "cache hit name={} path={}", entry.name, file.absolutePath)
                    return@supplyAsync file
                }

                runCatching {
                    val url = "${ConfigManager.config.serverUrl.trimEnd('/')}/gifs/${entry.name}"
                    AhjLog.info(TAG, "downloading name={} url={}", entry.name, url)
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                    AhjLog.info(TAG, "download response name={} status={} bytes={}", entry.name, resp.statusCode(), resp.body()?.size ?: -1)
                    if (resp.statusCode() != 200) return@runCatching null
                    file.writeBytes(resp.body())
                    file
                }.onFailure { AhjLog.error(TAG, "download failed name={}", it, entry.name) }.getOrNull()
            }.whenComplete { f, t ->
                if (t != null) {
                    AhjLog.error(TAG, "future failed name={}", t, entry.name)
                    // Allow retry on next call only for failures
                    inFlight.remove(entry.name)
                } else if (f == null) {
                    AhjLog.warn(TAG, "future returned null name={} (allowing retry)", entry.name)
                    inFlight.remove(entry.name)
                } else {
                    AhjLog.info(TAG, "future done name={} file={}", entry.name, f.absolutePath)
                    // Keep resolved future in the map so subsequent getNow() returns instantly
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
