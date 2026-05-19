package ahjd.icomod.util

import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Project-local logger. Writes to .minecraft/icomod/logs/icomod.log with sync flush per
 * line so nothing is lost on a hard crash. Also mirrors to stdout so it shows in the
 * normal Minecraft console.
 *
 * Usage: AhjLog.info("GifCache", "downloading {}", name) â€” `{}` is replaced positionally.
 */
object AhjLog {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val writer: PrintWriter by lazy {
        val dir = FabricLoader.getInstance().gameDir.resolve("icomod/logs").toFile()
        dir.mkdirs()
        val file = File(dir, "icomod.log")
        // Append, autoFlush=true so each println is fsynced to the OS buffer immediately.
        PrintWriter(file.outputStream().apply { /* no-op */ }.bufferedWriter(), true)
    }

    fun info(tag: String, msg: String, vararg args: Any?) = log("INFO", tag, msg, null, args)
    fun warn(tag: String, msg: String, vararg args: Any?) = log("WARN", tag, msg, null, args)
    fun error(tag: String, msg: String, t: Throwable? = null, vararg args: Any?) = log("ERROR", tag, msg, t, args)

    private fun log(level: String, tag: String, msg: String, t: Throwable?, args: Array<out Any?>) {
        val rendered = renderTemplate(msg, args)
        val line = "${LocalDateTime.now().format(fmt)} [$level] [$tag] $rendered"
        synchronized(this) {
            writer.println(line)
            if (t != null) t.printStackTrace(writer)
            writer.flush()
        }
        println(line)
        if (t != null) t.printStackTrace()
    }

    private fun renderTemplate(msg: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return msg
        val sb = StringBuilder(msg.length + 16)
        var i = 0
        var argIdx = 0
        while (i < msg.length) {
            if (i + 1 < msg.length && msg[i] == '{' && msg[i + 1] == '}' && argIdx < args.size) {
                sb.append(args[argIdx++])
                i += 2
            } else {
                sb.append(msg[i]); i++
            }
        }
        return sb.toString()
    }
}
