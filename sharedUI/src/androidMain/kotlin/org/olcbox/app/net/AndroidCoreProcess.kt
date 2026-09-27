package org.olcbox.app.net

import android.content.Context
import java.io.File
import java.util.ArrayDeque

/**
 * Spawns and supervises a bundled core binary (sing-box / xray) on Android by
 * exec'ing it from nativeLibraryDir in SOCKS mode — the v2rayNG pattern. The core
 * binary is packaged as `lib<name>.so` in jniLibs and, with useLegacyPackaging,
 * extracted to nativeLibraryDir where it is executable. The existing
 * hev-socks5-tunnel bridge points at the core's SOCKS port; this class only owns
 * the child process.
 *
 * minSdk is 23, so this deliberately uses only API-23-safe process APIs:
 * `Process.destroy()` / `exitValue()` — NOT `toHandle()`, `descendants()`,
 * `destroyForcibly()`, `isAlive()`, or `waitFor(timeout, unit)` (all API 26+).
 * `destroy()` sends SIGTERM, which the Go cores handle by exiting cleanly.
 */
internal class AndroidCoreProcess(
    private val context: Context,
    /** The packaged `lib<name>.so` filename in nativeLibraryDir. */
    private val soName: String,
    private val label: String,
    private val argv: (bin: String, config: String) -> List<String>,
) {
    @Volatile private var process: Process? = null

    private fun binaryPath(): File =
        File(context.applicationInfo.nativeLibraryDir, soName)

    private fun workDir(): File = File(context.cacheDir, "olcbox-$label")

    private fun deleteRawArtifacts() {
        val dir = workDir()
        File(dir, "config.json").delete()
        File(dir, "$label.log").delete()
        dir.delete() // Only remove our empty directory; never recurse into other cache files.
    }

    @Synchronized
    fun start(configJson: String) {
        stop()
        val dir = workDir().apply { mkdirs() }
        val config = File(dir, "config.json")
        val log = File(dir, "$label.log")
        try {
            config.writeText(configJson)
            process = ProcessBuilder(argv(binaryPath().absolutePath, config.absolutePath))
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        } catch (e: Exception) {
            deleteRawArtifacts() // Also remove a partial config when exec or writing failed.
            throw e
        }
    }

    @Synchronized
    fun stop() {
        val p = process
        process = null
        try {
            p?.destroy() // SIGTERM — the Go cores exit cleanly on it
        } finally {
            // stdout is redirected to this file, not the app log. Unlink both
            // raw artifacts even when the process already exited or failed to start.
            deleteRawArtifacts()
        }
    }

    /** API-23-safe liveness: exitValue() throws while the process is still running. */
    fun isRunning(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /**
     * What the core said, for the app log when it fails to come up.
     *
     * Everything the core writes lands in a file inside the app's private cache,
     * which on a production build only root can read — so the one place that knows
     * why a connection failed was reachable by nobody who ever hit the failure. A
     * user could report "it does not connect" and nothing else, which is exactly
     * what happened, twice, and cost a day each time.
     *
     * Whether the file is empty matters as much as what is in it: a core that wrote
     * nothing at all did not get far enough to complain, which points at the exec
     * rather than at the config.
     */
    fun diagnostics(maxLines: Int = 12, includeRawLogs: Boolean = true): String {
        val log = File(workDir(), "$label.log")
        val state = process?.let { p ->
            try {
                "exited with code ${p.exitValue()}"
            } catch (_: IllegalThreadStateException) {
                "still running"
            }
        } ?: "was never started"

        // Subscription profiles may make Xray print arbitrary keys/URLs in
        // startup and traffic errors. Do not even read their raw log tail.
        if (!includeRawLogs) return "$label $state; raw diagnostics withheld"

        val tail = try {
            readLogTail(log, maxLines)
        } catch (e: Exception) {
            listOf("(could not read ${log.name}: ${e.message})")
        }

        return if (tail.isEmpty()) {
            "$label $state and wrote nothing to ${log.name}"
        } else {
            "$label $state; last ${tail.size} line(s):\n" + tail.joinToString("\n")
        }
    }

    companion object {
        /** Reads arbitrarily large logs with memory bounded by [maxLines]. */
        internal fun readLogTail(log: File, maxLines: Int): List<String> {
            if (!log.isFile || maxLines <= 0) return emptyList()
            val tail = ArrayDeque<String>(maxLines)
            log.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (tail.size == maxLines) tail.removeFirst()
                    tail.addLast(line)
                }
            }
            return tail.toList()
        }
    }
}
