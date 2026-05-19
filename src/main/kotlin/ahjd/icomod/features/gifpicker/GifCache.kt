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
// Hard cap on a single downloaded GIF. Keeps a malicious / misconfigured server
// from forcing the client to buffer arbitrarily many bytes into memory before
// the hash check rejects them.
private const val MAX_GIF_BYTES = 8L * 1024 * 1024
// Whitelist for filenames received from the server. Anything outside this set
// is rejected so a hostile catalog cannot escape `cacheDir` via traversal
// (e.g. `..\\foo`) or inject URL/path metacharacters.
private val SAFE_NAME = Regex("^[A-Za-z0-9_-]{1,64}\\.(?i:png|jpe?g|gif)$")

/**
 * On-disk cache for GIF file bytes. Files live at .minecraft/icomod/cache/gifs/{name}.
 * Cache entries are hash-validated against the catalog; mismatched files re-download.
 */
object GifCache {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cacheDir: File = FabricLoader.getInstance().gameDir
        .resolve("icomod/cache/gifs").toFile().also { it.mkdirs() }

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
        if (!SAFE_NAME.matches(entry.name)) {
            AhjLog.warn(TAG, "reject unsafe name from catalog name={}", entry.name)
            return CompletableFuture.completedFuture(null)
        }
        if (entry.bytes < 0 || entry.bytes > MAX_GIF_BYTES) {
            AhjLog.warn(TAG, "reject oversize entry name={} bytes={}", entry.name, entry.bytes)
            return CompletableFuture.completedFuture(null)
        }
        val until = nextRetryAt[entry.name]
        if (until != null && System.currentTimeMillis() < until) {
            return CompletableFuture.completedFuture(null)
        }
        return inFlight.computeIfAbsent(entry.name) {
            AhjLog.info(TAG, "ensureLocal start name={} hash={}", entry.name, entry.hash)
            CompletableFuture.supplyAsync {
                val file = File(cacheDir, entry.name)
                // Defense-in-depth: even though SAFE_NAME rejects traversal,
                // make sure the resolved path is actually inside cacheDir.
                if (file.canonicalFile.parentFile != cacheDir.canonicalFile) {
                    AhjLog.warn(TAG, "reject path escape name={} resolved={}", entry.name, file.absolutePath)
                    return@supplyAsync null
                }
                if (file.exists() && sha256(file) == entry.hash) {
                    AhjLog.info(TAG, "cache hit name={} path={}", entry.name, file.absolutePath)
                    return@supplyAsync file
                }

                runCatching {
                    // Post-Phase-2 the canonical path is `/api/v1/gifs/<name>`,
                    // served by ICoCore through ICoWebsite's proxy. The legacy
                    // `/gifs/<name>` still works as a backward-compat alias but
                    // the typed contract lives at /api/v1.
                    val url = "${ConfigManager.config.serverUrl.trimEnd('/')}/api/v1/gifs/${entry.name}"
                    AhjLog.info(TAG, "downloading name={} url={}", entry.name, url)
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                    val body = resp.body() ?: ByteArray(0)
                    AhjLog.info(TAG, "download response name={} status={} bytes={}", entry.name, resp.statusCode(), body.size)
                    if (resp.statusCode() != 200) return@runCatching null
                    // Cap: refuse to keep a response larger than the advertised
                    // size (with slack for harmless trailers) or our hard ceiling.
                    val cap = minOf(MAX_GIF_BYTES, entry.bytes + 1024L)
                    if (body.size > cap) {
                        AhjLog.warn(TAG, "reject oversize response name={} got={} cap={}", entry.name, body.size, cap)
                        return@runCatching null
                    }
                    // Verify hash on the in-memory bytes before persisting. This
                    // closes the window where a tampered body would be written
                    // to disk and rendered once before the next cache-check
                    // round caught the mismatch.
                    val actual = sha256(body)
                    if (!actual.equals(entry.hash, ignoreCase = true)) {
                        AhjLog.warn(TAG, "reject hash mismatch name={} expected={} got={}", entry.name, entry.hash, actual)
                        return@runCatching null
                    }
                    // Atomic-ish write: stage to .tmp then rename so a crash
                    // mid-write never leaves a half-written file in cache.
                    val tmp = File(cacheDir, "${entry.name}.tmp")
                    tmp.writeBytes(body)
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        if (!tmp.renameTo(file)) {
                            tmp.delete()
                            error("rename ${tmp.name} -> ${file.name} failed")
                        }
                    }
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
