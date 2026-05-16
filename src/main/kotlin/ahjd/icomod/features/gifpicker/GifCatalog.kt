package ahjd.icomod.features.gifpicker

import ahjd.icomod.config.ConfigManager
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
    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    @Volatile var entries: List<GifEntry> = emptyList()
        private set

    @Volatile var lastError: String? = null
        private set

    fun byName(name: String): GifEntry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun refreshAsync(): CompletableFuture<Void> = CompletableFuture.runAsync {
        runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${ConfigManager.config.serverUrl.trimEnd('/')}/gifs/catalog"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()}")
            val type = object : TypeToken<List<GifEntry>>() {}.type
            entries = gson.fromJson(resp.body(), type) ?: emptyList()
            lastError = null
        }.onFailure {
            lastError = it.message
            entries = emptyList()
        }
    }
}
