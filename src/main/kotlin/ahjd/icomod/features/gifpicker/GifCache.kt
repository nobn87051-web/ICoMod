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
private const val FAILURE_COOLDOWN_MS = 30_000L

/**
 * On-disk cache for GIF file bytes. Files live at .minecraft/ahjdmod/cache/gifs/{name}.
 * Cache entries are hash-validated against the catalog; mismatched files re-download.
 */
object GifCache {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cacheDir: File = FabricLoader.getInstance().gameDir
        .resolve("ahjdmod/cache/gifs").toFile().also { it.mkdirs() }

    // Tracks in-flight downloads so concurrent requests for the same file collapse into one
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<File?>>()
    // Per-name "don't retry before" deadlines. The chat renderer calls ensureLocalAsync
    // every render frame; without this, a misconfigured serverUrl spams ~60 failed
    // downloads/sec until the user notices.
    private val nextRetryAt = ConcurrentHashMap<String, Long>()

    /**
     * Returns the local file for [entry], downloading if missing or hash-mismatched.
     * Async: completes off the main thread.
     */
    fun ensureLocalAsync(entry: GifEntry): CompletableFuture<File?> {
        val until = nextRetryAt[entry.name]
        if (until != null && System.currentTimeMillis() < until) {
            return CompletableFuture.completedFuture(null)
        }
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
                }.onFailure { ex ->
                    // Connect/timeout failures aren't bugs — server is just unreachable.
                    // Log them as warnings without a stack trace and let the cooldown
                    // path take it from here.
                    when (ex) {
                        is java.net.ConnectException,
                        is java.net.http.HttpConnectTimeoutException,
                        is java.net.http.HttpTimeoutException,
                        is java.net.UnknownHostException,
                        is java.net.NoRouteToHostException ->
                            AhjLog.warn(TAG, "download unreachable name={} reason={}", entry.name, ex.javaClass.simpleName)
                        else ->
                            AhjLog.error(TAG, "download failed name={}", ex, entry.name)
                    }
                }.getOrNull()
            }.whenComplete { f, t ->
                if (t != null) {
                    AhjLog.error(TAG, "future failed name={} (cooldown {}ms)", t, entry.name, FAILURE_COOLDOWN_MS)
                    nextRetryAt[entry.name] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
                    inFlight.remove(entry.name)
                } else if (f == null) {
                    AhjLog.warn(TAG, "future returned null name={} (cooldown {}ms)", entry.name, FAILURE_COOLDOWN_MS)
                    nextRetryAt[entry.name] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
                    inFlight.remove(entry.name)
                } else {
                    AhjLog.info(TAG, "future done name={} file={}", entry.name, f.absolutePath)
                    nextRetryAt.remove(entry.name)
                    // Keep resolved future in the map so subsequent getNow() returns instantly
                }
            }
        }
    }

    /**
     * Deletes cached files whose name is no longer in the catalog. Called
     * after every successful [GifCatalog.refreshAsync] so removed/renamed
     * server-side GIFs don't linger on disk forever.
     */
    fun pruneToCatalog(validNames: Set<String>): Int {
        val files = cacheDir.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (!f.isFile) continue
            if (f.name in validNames) continue
            // Don't touch a file we're actively writing to.
            if (inFlight.containsKey(f.name)) continue
            if (f.delete()) {
                removed++
                AhjLog.info(TAG, "pruned stale cache file name={}", f.name)
            } else {
                AhjLog.warn(TAG, "failed to prune cache file name={}", f.name)
            }
            // Cooldown entries for deleted files are no longer meaningful.
            nextRetryAt.remove(f.name)
        }
        if (removed > 0) AhjLog.info(TAG, "prune removed {} stale cache files", removed)
        return removed
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
