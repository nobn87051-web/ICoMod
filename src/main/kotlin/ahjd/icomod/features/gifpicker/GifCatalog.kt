package ahjd.icomod.features.gifpicker

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.util.AhjLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** A single entry in the server's GIF catalog (mirrors server's [GifEntry] DTO). */
data class GifEntry(val name: String, val hash: String, val bytes: Long)

/**
 * Holds the latest catalog fetched from the IcoMod server.
 * Refresh manually via [refreshAsync] (e.g. on launch and when picker opens).
 */
object GifCatalog {
    private const val TAG = "GifCatalog"
    private val gson = Gson()
    // NORMAL: follow same-protocol AND http→https upgrades. Without this, a
    // Caddy 301 from http://…:8080 to https://… would surface as "HTTP 301"
    // and the picker would render an empty catalog.
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile var entries: List<GifEntry> = emptyList()
        private set

    @Volatile var lastError: String? = null
        private set

    fun byName(name: String): GifEntry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun refreshAsync(): CompletableFuture<Void> = CompletableFuture.runAsync {
        val url = "${ConfigManager.config.serverUrl.trimEnd('/')}/gifs/catalog"
        runCatching {
            AhjLog.info(TAG, "refresh GET {}", url)
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val body = resp.body().orEmpty()
            AhjLog.info(TAG, "refresh response status={} bodyLen={}", resp.statusCode(), body.length)
            if (resp.statusCode() != 200) {
                error("HTTP ${resp.statusCode()} from $url (body: ${body.take(120)})")
            }
            val type = object : TypeToken<List<GifEntry>>() {}.type
            entries = gson.fromJson(body, type) ?: emptyList()
            lastError = null
            AhjLog.info(TAG, "refresh ok entries={}", entries.size)
            // Drop on-disk cache entries that no longer exist on the server.
            val pruned = GifCache.pruneToCatalog(entries.map { it.name }.toHashSet())
            if (pruned > 0) AhjLog.info(TAG, "pruned {} stale cache files after refresh", pruned)
        }.onFailure { ex ->
            val unreachable = ex is java.net.ConnectException
                || ex is java.net.http.HttpConnectTimeoutException
                || ex is java.net.http.HttpTimeoutException
                || ex is java.net.UnknownHostException
                || ex is java.net.NoRouteToHostException
            if (unreachable) {
                AhjLog.warn(TAG, "refresh unreachable url={} reason={}", url, ex.javaClass.simpleName)
                lastError = "server unreachable (${ex.javaClass.simpleName})"
            } else {
                AhjLog.error(TAG, "refresh failed url={}", ex, url)
                lastError = ex.message ?: ex.javaClass.simpleName
            }
            entries = emptyList()
        }
    }
}
