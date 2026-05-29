package ahjd.icomod.features.update

import ahjd.icomod.util.AhjLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Checks GitHub Releases for a newer IcoMod jar.
 *
 * Source is the repo in `fabric.mod.json` (`contact.sources`). We pull the
 * release LIST (not just /latest) so we can count how many releases are newer
 * than the running version — Sec 18 distinguishes "1 behind" (soft prompt) from
 * "2+ behind" (mandatory).
 */
object UpdateChecker {

    private const val TAG = "Update"
    private const val REPO = "nobn87051-web/ICoMod"
    private const val PER_PAGE = 30
    private const val MAX_PAGES = 5  // cap the paged scan at 150 releases
    private fun releasesUrl(page: Int) =
        "https://api.github.com/repos/$REPO/releases?per_page=$PER_PAGE&page=$page"

    private val gson = Gson()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class Result(
        val current: String,
        val latest: String,
        val jarUrl: String?,
        /** Expected SHA-256 (hex) of [jarUrl], if the release published one. */
        val sha256: String?,
        /** How many releases are strictly newer than [current]. */
        val behind: Int,
    ) {
        val updateAvailable: Boolean get() = behind > 0 && jarUrl != null
        val mandatory: Boolean get() = behind >= 2
    }

    private data class Asset(
        val name: String = "",
        @SerializedName("browser_download_url") val url: String = "",
    )

    private data class Release(
        @SerializedName("tag_name") val tag: String = "",
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    fun currentVersion(): String =
        FabricLoader.getInstance().getModContainer("icomod")
            .map { it.metadata.version.friendlyString }
            .orElse("0.0.0")

    private fun fetch(url: String): String {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "IcoMod-Updater")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()} from GitHub")
        return resp.body()
    }

    private fun isJar(a: Asset): Boolean =
        a.name.endsWith(".jar", true) && !a.name.contains("sources", true)

    /** Look for a sibling `<jar>.sha256` asset and return its hex digest, or null. */
    private fun shaFor(jar: Asset, assets: List<Asset>): String? {
        val sidecar = assets.firstOrNull { it.name.equals("${jar.name}.sha256", true) }
            ?: assets.firstOrNull { it.name.equals("${jar.name.removeSuffix(".jar")}.sha256", true) }
            ?: return null
        return runCatching {
            // Digest files are tiny; first whitespace-delimited token is the hex.
            fetch(sidecar.url).trim().substringBefore(' ').takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        }.getOrNull()
    }

    fun checkAsync(): CompletableFuture<Result?> = CompletableFuture.supplyAsync {
        val current = currentVersion()
        runCatching {
            // Page through releases so the "behind" count is accurate past the
            // first 30. Stop when a page is short (last page) or the cap is hit.
            val releases = buildList {
                for (page in 1..MAX_PAGES) {
                    val batch = gson.fromJson(fetch(releasesUrl(page)), Array<Release>::class.java)
                        ?.toList() ?: emptyList()
                    addAll(batch)
                    if (batch.size < PER_PAGE) break
                }
            }.filter { !it.draft && !it.prerelease && it.tag.isNotBlank() }
            if (releases.isEmpty()) return@supplyAsync Result(current, current, null, null, 0)

            val cur = SemVer.parse(current)
            val newer = releases.filter { SemVer.parse(it.tag) > cur }
            val latest = releases.maxByOrNull { SemVer.parse(it.tag) } ?: releases.first()

            // The update target is the NEWEST release that actually ships a jar.
            // Falling back past a jar-less newest release keeps both the update
            // path and the mandatory gate working when assets are missing.
            val target = newer.sortedByDescending { SemVer.parse(it.tag) }
                .firstOrNull { rel -> rel.assets.any(::isJar) }
            val jarAsset = target?.assets?.firstOrNull(::isJar)
            val jar = jarAsset?.url
            val sha = if (jarAsset != null) shaFor(jarAsset, target.assets) else null

            AhjLog.info(TAG, "current={} latest={} behind={} jar={} sha={}",
                current, latest.tag, newer.size, jar, sha != null)
            Result(current, SemVer.parse(latest.tag).toString(), jar, sha, newer.size)
        }.getOrElse {
            AhjLog.warn(TAG, "update check failed: {}", it.message ?: it.javaClass.simpleName)
            null
        }
    }
}

/** Minimal numeric semver for release-tag comparison. Tolerates a leading `v`. */
data class SemVer(val parts: List<Int>) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parse(raw: String): SemVer {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val nums = cleaned.split('.', '-', '+')
                .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            return SemVer(if (nums.isEmpty()) listOf(0) else nums)
        }
    }
}
