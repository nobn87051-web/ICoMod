package ahjd.icomod.features.update

import ahjd.icomod.util.AhjLog
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a new IcoMod jar and swaps it in.
 *
 * The running jar is locked by the JVM (and two same-id jars in `mods/` crash
 * Fabric), so the swap can't happen at runtime. We stage the download, then a
 * JVM shutdown hook spawns a DETACHED OS process that waits for the JVM to
 * exit (releasing the lock), deletes the old jar, and moves the staged jar
 * into `mods/`. This is the established self-update technique (cf. AutoPlug).
 *
 * Safety:
 *  - The staged jar is verified (size, zip magic, optional SHA-256) BEFORE the
 *    swap is armed, so a partial/corrupt download never replaces the live jar.
 *  - The swap script retries the delete until the lock clears and only moves
 *    the replacement in once the old jar is gone — never leaving two icomod
 *    jars in `mods/`.
 *  - A single shutdown hook reads a volatile [pendingScript]; [cancel] clears
 *    it so an armed swap can be aborted before exit.
 *
 * [removeMod] uses the same detached-delete path with no replacement (Sec 18.3
 * "Remove Mod").
 */
object UpdateInstaller {

    private const val TAG = "Update"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val gameDir: File get() = FabricLoader.getInstance().gameDir.toFile()
    private val modsDir: File get() = File(gameDir, "mods")
    private val stagingDir: File by lazy { File(gameDir, "icomod/update").also { it.mkdirs() } }

    enum class State { IDLE, DOWNLOADING, STAGED, FAILED }

    @Volatile var state: State = State.IDLE
        private set

    /** Download progress 0..1 while [State.DOWNLOADING]; -1 if size unknown. */
    @Volatile var progress: Float = 0f
        private set

    /** True while a download is running or a swap is armed. */
    fun isActive(): Boolean = state == State.DOWNLOADING || state == State.STAGED

    // Bumped by every install() and cancel(). The async download captures its
    // generation and refuses to commit (arm the swap) if it no longer matches —
    // so cancelling mid-download can't be silently undone when the download ends.
    private val installGen = AtomicInteger(0)

    /** The currently-loaded icomod jar, or null if it can't be resolved. */
    private fun currentJar(): File? {
        val origin = FabricLoader.getInstance().getModContainer("icomod").orElse(null)?.origin ?: return null
        return runCatching { origin.paths.firstOrNull()?.toFile() }.getOrNull()
            ?.takeIf { it.isFile && it.extension == "jar" }
    }

    /**
     * Download [jarUrl] to staging, verify it, then arm the on-exit swap.
     * [expectedSha256] (hex), when non-null, must match or the install aborts.
     */
    fun install(jarUrl: String, expectedSha256: String? = null): CompletableFuture<Boolean> {
        // Idempotent: ignore re-clicks while a download/stage is in flight.
        if (state == State.DOWNLOADING || state == State.STAGED) {
            return CompletableFuture.completedFuture(state == State.STAGED)
        }
        val old = currentJar()
        if (old == null) {
            AhjLog.warn(TAG, "cannot resolve running jar; refusing auto-install")
            state = State.FAILED
            return CompletableFuture.completedFuture(false)
        }
        state = State.DOWNLOADING
        progress = 0f
        val gen = installGen.incrementAndGet()
        return CompletableFuture.supplyAsync {
            val name = jarUrl.substringAfterLast('/').ifBlank { "icomod-update.jar" }
            val staged = File(stagingDir, name)
            try {
                streamDownload(jarUrl, staged, expectedSha256, gen)
                if (installGen.get() != gen) {  // cancelled mid-download
                    staged.delete()
                    return@supplyAsync false
                }
                scheduleSwap(old, staged, File(modsDir, name))
                state = State.STAGED
                progress = 1f
                AhjLog.info(TAG, "staged {} -> swap on exit (old={})", staged.name, old.name)
                true
            } catch (t: Throwable) {
                staged.delete()  // never leave a partial/corrupt jar in staging
                if (installGen.get() == gen) {  // a real failure, not a cancel
                    AhjLog.error(TAG, "install failed", t)
                    state = State.FAILED
                }
                false
            }
        }
    }

    /**
     * Stream [jarUrl] to [dest] in chunks so we can report [progress] and hash
     * on the fly, rejecting anything that isn't a complete, intact jar before
     * the swap is armed (zip magic, declared size, optional SHA-256). Aborts if
     * the install generation moves past [gen] (i.e. [cancel] was called).
     */
    private fun streamDownload(jarUrl: String, dest: File, expectedSha256: String?, gen: Int) {
        val req = HttpRequest.newBuilder().uri(URI.create(jarUrl))
            .timeout(Duration.ofSeconds(120)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()} downloading jar")
        val total = resp.headers().firstValueAsLong("content-length").orElse(-1L)
        progress = if (total > 0) 0f else -1f

        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var checkedMagic = false
        resp.body().use { input ->
            dest.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (installGen.get() != gen) error("download cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!checkedMagic && n >= 2) {
                        if (buf[0] != 'P'.code.toByte() || buf[1] != 'K'.code.toByte())
                            error("downloaded file is not a zip/jar (bad magic)")
                        checkedMagic = true
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    read += n
                    if (total > 0) progress = (read.toFloat() / total).coerceIn(0f, 1f)
                }
            }
        }

        if (total >= 0 && read != total)
            error("size mismatch: got $read, expected $total")
        if (read < 4) error("downloaded file is too small to be a jar")

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null) {
            if (!actual.equals(expectedSha256, ignoreCase = true))
                error("SHA-256 mismatch: $actual != $expectedSha256")
            AhjLog.info(TAG, "sha256 verified")
        } else {
            AhjLog.warn(TAG, "no published sha256; relying on TLS + zip-magic check")
        }
    }

    /** Schedule deletion of the running jar on exit, with no replacement. */
    fun removeMod(): Boolean {
        val old = currentJar() ?: return false
        scheduleSwap(old, null, null)
        state = State.STAGED
        AhjLog.info(TAG, "scheduled removal of {} on exit", old.name)
        return true
    }

    /**
     * Delete leftover staged jars / swap scripts from a previous run. A pending
     * swap only ever lives inside a running JVM (shutdown hook), never on disk
     * across launches — so anything found in staging at startup is stale and
     * safe to remove. Call once on client init.
     */
    fun cleanStaleStaging() {
        runCatching {
            stagingDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        }.onFailure { AhjLog.warn(TAG, "staging cleanup failed: {}", it.message ?: it) }
    }

    /** Abort an armed swap/removal before the JVM exits. */
    fun cancel() {
        installGen.incrementAndGet()  // invalidate any in-flight download
        pendingScript = null
        if (state == State.STAGED || state == State.DOWNLOADING) state = State.IDLE
        progress = 0f
        AhjLog.info(TAG, "pending swap cancelled")
    }

    @Volatile private var hookAdded = false
    @Volatile private var pendingScript: File? = null

    private fun scheduleSwap(old: File, staged: File?, target: File?) {
        // (Re)write the swap script with the latest paths; the hook reads the
        // volatile pendingScript at exit, so cancel() can disarm it.
        pendingScript = writeSwapScript(old, staged, target)
        if (!hookAdded) {
            hookAdded = true
            Runtime.getRuntime().addShutdownHook(Thread {
                pendingScript?.let { script -> runCatching { launchDetached(script) } }
            })
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    // Bound the delete-retry so a jar that can NEVER be deleted (ACL, held by
    // another process — not just our JVM lock) can't spin the helper forever.
    private const val MAX_RETRIES = 60  // ~60s at ~1s/try

    private fun writeSwapScript(old: File, staged: File?, target: File?): File {
        val o = old.absolutePath
        return if (isWindows()) {
            val f = File(stagingDir, "icomod-swap.bat")
            val sb = StringBuilder("@echo off\r\nsetlocal\r\nset /a n=0\r\n")
            // Retry the delete until the JVM releases the lock; only move the
            // replacement in once the old jar is gone, so we never leave two
            // icomod jars in mods/. Give up after MAX_RETRIES.
            // NOTE: deliberately NOT self-deleting the script — a batch that
            // deletes itself mid-run leaves the cmd window open. The leftover
            // script is removed by cleanStaleStaging() on the next launch.
            sb.append(":retry\r\n")
            sb.append("del /f /q \"$o\" >nul 2>&1\r\n")
            sb.append("if not exist \"$o\" goto done\r\n")
            sb.append("set /a n+=1\r\n")
            sb.append("if %n% geq $MAX_RETRIES goto end\r\n")
            sb.append("ping 127.0.0.1 -n 2 >nul\r\n")
            sb.append("goto retry\r\n")
            sb.append(":done\r\n")
            if (staged != null && target != null)
                sb.append("move /y \"${staged.absolutePath}\" \"${target.absolutePath}\" >nul\r\n")
            sb.append(":end\r\n")
            f.writeText(sb.toString())
            f
        } else {
            val d = '$'  // escape shell vars from Kotlin string templates
            val f = File(stagingDir, "icomod-swap.sh")
            val sb = StringBuilder("#!/bin/sh\nn=0\n")
            sb.append("while [ -e \"$o\" ]; do\n")
            sb.append("  rm -f \"$o\"\n")
            sb.append("  [ -e \"$o\" ] || break\n")
            sb.append("  n=${d}((n+1)); [ \"${d}n\" -ge $MAX_RETRIES ] && break\n")
            sb.append("  sleep 1\n")
            sb.append("done\n")
            if (staged != null && target != null)
                sb.append("[ -e \"$o\" ] || mv -f \"${staged.absolutePath}\" \"${target.absolutePath}\"\n")
            // Not self-deleting (see note above); cleanStaleStaging() handles it.
            f.writeText(sb.toString())
            f.setExecutable(true)
            f
        }
    }

    private fun launchDetached(script: File) {
        val pb = if (isWindows()) {
            // Run the .bat through a hidden VBScript shim (window style 0) so NO
            // console window ever appears or lingers — `start /min` left an idle
            // interactive window on some setups. wscript exits immediately; the
            // hidden cmd it spawns is reparented and outlives this JVM.
            val vbs = writeVbsShim(script)
            ProcessBuilder("wscript.exe", "//B", "//Nologo", vbs.absolutePath)
        } else {
            // setsid detaches into its own session so it survives the JVM exit;
            // nohup is the fallback when setsid isn't present.
            val setsid = File("/usr/bin/setsid").exists() || File("/bin/setsid").exists()
            if (setsid) ProcessBuilder("setsid", "sh", script.absolutePath)
            else ProcessBuilder("nohup", "sh", script.absolutePath)
        }
        pb.directory(stagingDir)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.start()  // do NOT waitFor -- it must outlive this JVM
    }

    /** Write a tiny VBScript that runs [bat] fully hidden and returns at once. */
    private fun writeVbsShim(bat: File): File {
        val f = File(stagingDir, "icomod-swap.vbs")
        val p = bat.absolutePath
        // CreateObject("WScript.Shell").Run "cmd /c ""<bat>""", 0, False
        f.writeText("CreateObject(\"WScript.Shell\").Run \"cmd /c \"\"$p\"\"\", 0, False\r\n")
        return f
    }
}
