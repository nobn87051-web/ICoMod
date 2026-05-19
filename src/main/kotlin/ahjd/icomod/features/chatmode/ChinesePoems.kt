package ahjd.icomod.features.chatmode

import com.google.gson.Gson
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import kotlin.random.Random

/**
 * Offline corpus of classical Chinese poems. The bundled JSON is extracted to
 * `<gameDir>/icomod/poems/chinese-poems.json` on first launch so users can
 * curate it. No network access — everything is shipped inside the jar.
 *
 * Each poem is stored as a single CJK-only string (punctuation stripped) so a
 * fixed-length window can be sliced at any offset without crossing a
 * punctuation gap.
 */
object ChinesePoems {

    private const val MIN_LEN = 2
    private const val MAX_LEN = 5
    private val rng = Random.Default
    @Volatile private var poems: List<String> = emptyList()

    fun init() {
        val dir = File(FabricLoader.getInstance().gameDir.toFile(), "icomod/poems")
        dir.mkdirs()
        val file = File(dir, "chinese-poems.json")

        if (!file.exists()) {
            javaClass.getResourceAsStream("/icomod/poems/chinese-poems.json")
                ?.use { file.writeBytes(it.readBytes()) }
        }
        if (!file.exists()) return

        val arr = runCatching {
            Gson().fromJson(file.readText(Charsets.UTF_8), Array<String>::class.java)
        }.getOrNull() ?: return

        poems = arr.filter { it.length >= MIN_LEN }
    }

    /**
     * Picks a random window of 2-5 characters from a random poem.
     * Window length is per-call so messages get varied rhythm.
     */
    fun randomSnippet(): String? {
        val pool = poems
        if (pool.isEmpty()) return null
        val poem = pool[rng.nextInt(pool.size)]
        val maxLen = minOf(MAX_LEN, poem.length)
        val len = rng.nextInt(MIN_LEN, maxLen + 1)
        val start = rng.nextInt(poem.length - len + 1)
        return poem.substring(start, start + len)
    }
}
