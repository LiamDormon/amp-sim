package org.ampsim.metrics

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Appends [MetricsSnapshot] rows to a CSV file while enabled (Settings' "Log
 * Metrics to File" toggle). Aggregate columns only for now — a per-unit
 * column set would need either an awkward fixed-width schema or a long/tidy
 * format that multiplies row count by chain length; revisit if per-unit
 * export is actually requested.
 *
 * Mirrors [org.ampsim.persistence.FileSystemProfileRepository]'s
 * single-writer-dispatcher/`runCatching`-and-log-to-stderr conventions, but
 * appends to a growing log rather than atomically replacing a whole
 * document — a metrics log is never meant to be fully rewritten.
 */
class MetricsFileLogger(private val file: File = defaultLogFile()) {
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var writer: BufferedWriter? = null

    suspend fun start() = withContext(ioDispatcher) {
        runCatching {
            file.parentFile?.mkdirs()
            val isNew = !file.exists()
            writer = BufferedWriter(FileWriter(file, true))
            if (isNew) {
                writer?.appendLine("timestampMs,totalCpuPercent,chainLatencyMs,bufferLatencyMs,heapUsedMb,heapMaxMb")
                writer?.flush()
            }
        }.onFailure { e -> System.err.println("Failed to open metrics log '${file.path}': ${e.message}") }
        Unit
    }

    suspend fun logRow(snapshot: MetricsSnapshot) = withContext(ioDispatcher) {
        runCatching {
            val w = writer ?: return@runCatching
            w.appendLine(
                "${snapshot.timestampMs}," +
                    "${snapshot.totalCpuLoad * 100}," +
                    "${snapshot.chainLatencyMs}," +
                    "${snapshot.bufferLatencyMs}," +
                    "${snapshot.heapUsedBytes / BYTES_PER_MB}," +
                    "${snapshot.heapMaxBytes / BYTES_PER_MB}"
            )
            w.flush()
        }.onFailure { e -> System.err.println("Failed to write metrics row: ${e.message}") }
        Unit
    }

    suspend fun stop() = withContext(ioDispatcher) {
        runCatching { writer?.close() }.onFailure { e -> System.err.println("Failed to close metrics log: ${e.message}") }
        writer = null
    }

    companion object {
        private const val BYTES_PER_MB = 1_000_000L

        fun getOrCreateLogDir(): File {
            val dir = File("${System.getProperty("user.home")}/.local/share/amp-sim/logs")
            dir.mkdirs()
            return dir
        }

        fun defaultLogFile(): File = File(getOrCreateLogDir(), "metrics.csv")
    }
}
