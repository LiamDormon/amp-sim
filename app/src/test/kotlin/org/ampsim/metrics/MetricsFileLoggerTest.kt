package org.ampsim.metrics

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MetricsFileLoggerTest {

    @TempDir
    lateinit var tempDir: File

    // totalCpuLoad/heap values are chosen so every arithmetic step in
    // MetricsFileLogger.logRow (times 100, integer MB division) lands on a
    // value with an exact float/double representation — otherwise a binary
    // floating-point rounding artifact (e.g. "42.000004") could make an
    // exact string-equality assertion on the written row flaky.
    private fun snapshot(timestampMs: Long = 1_000L) = MetricsSnapshot(
        timestampMs = timestampMs,
        totalCpuLoad = 0.5f,
        perUnitCpuLoad = emptyMap(),
        chainLatencyMs = 3.5,
        bufferLatencyMs = 10.5,
        heapUsedBytes = 50_000_000L,
        heapMaxBytes = 200_000_000L
    )

    @Test
    fun startWritesAHeaderRowToANewFile() = runBlocking {
        val file = File(tempDir, "metrics.csv")
        val logger = MetricsFileLogger(file)

        logger.start()
        logger.stop()

        val lines = file.readLines()
        assertEquals(1, lines.size)
        assertEquals("timestampMs,totalCpuPercent,chainLatencyMs,bufferLatencyMs,heapUsedMb,heapMaxMb", lines[0])
    }

    @Test
    fun logRowAppendsAFormattedDataRow() = runBlocking {
        val file = File(tempDir, "metrics.csv")
        val logger = MetricsFileLogger(file)

        logger.start()
        logger.logRow(snapshot())
        logger.stop()

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals("1000,50.0,3.5,10.5,50,200", lines[1])
    }

    @Test
    fun multipleRowsAppendInOrder() = runBlocking {
        val file = File(tempDir, "metrics.csv")
        val logger = MetricsFileLogger(file)

        logger.start()
        logger.logRow(snapshot(timestampMs = 1L))
        logger.logRow(snapshot(timestampMs = 2L))
        logger.stop()

        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("1,"))
        assertTrue(lines[2].startsWith("2,"))
    }

    @Test
    fun restartingAfterStopAppendsRatherThanOverwritingOrRepeatingTheHeader() = runBlocking {
        val file = File(tempDir, "metrics.csv")
        val logger = MetricsFileLogger(file)

        logger.start()
        logger.logRow(snapshot(timestampMs = 1L))
        logger.stop()

        logger.start()
        logger.logRow(snapshot(timestampMs = 2L))
        logger.stop()

        val lines = file.readLines()
        assertEquals(3, lines.size, "expected one header + two data rows, got: $lines")
        assertEquals("timestampMs,totalCpuPercent,chainLatencyMs,bufferLatencyMs,heapUsedMb,heapMaxMb", lines[0])
    }

    @Test
    fun logRowBeforeStartDoesNotThrow() = runBlocking {
        val file = File(tempDir, "metrics.csv")
        val logger = MetricsFileLogger(file)

        logger.logRow(snapshot())

        assertTrue(!file.exists() || file.readLines().isEmpty())
    }
}
