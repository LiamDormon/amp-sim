package org.ampsim.recording

import java.io.File
import javax.sound.sampled.AudioSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavFileWriterTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun headerReportsZeroLengthBeforeAnySamplesAreWritten() {
        val file = File(tempDir, "empty.wav")
        val writer = WavFileWriter(file, sampleRate = 48000)
        writer.close()

        val format = AudioSystem.getAudioFileFormat(file)
        assertEquals(0L, format.frameLength.toLong())
        assertEquals(48000f, format.format.sampleRate)
    }

    @Test
    fun frameCountMatchesSamplesWrittenAfterClose() {
        val file = File(tempDir, "take.wav")
        val writer = WavFileWriter(file, sampleRate = 44100)
        val samples = FloatArray(2000) { (it % 100) / 100f - 0.5f }
        writer.writeSamples(samples, samples.size)
        writer.close()

        val format = AudioSystem.getAudioFileFormat(file)
        assertEquals(samples.size.toLong(), format.frameLength.toLong())
        assertEquals(1, format.format.channels)
        assertEquals(16, format.format.sampleSizeInBits)
    }

    @Test
    fun patchHeaderMidStreamReflectsSamplesWrittenSoFarWithoutClosing() {
        val file = File(tempDir, "mid-stream.wav")
        val writer = WavFileWriter(file, sampleRate = 48000)
        writer.writeSamples(FloatArray(500) { 0f }, 500)
        writer.patchHeader()

        // Not closed yet - a reader opening the file now should still see a
        // correct (non-zero) length, exercising the crash-resilience path.
        val format = AudioSystem.getAudioFileFormat(file)
        assertEquals(500L, format.frameLength.toLong())

        writer.writeSamples(FloatArray(300) { 0f }, 300)
        writer.close()
        val finalFormat = AudioSystem.getAudioFileFormat(file)
        assertEquals(800L, finalFormat.frameLength.toLong())
    }

    @Test
    fun samplesRoundTripThroughPcm16WithinQuantizationTolerance() {
        val file = File(tempDir, "roundtrip.wav")
        val writer = WavFileWriter(file, sampleRate = 48000)
        val original = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 0.25f)
        writer.writeSamples(original, original.size)
        writer.close()

        val audioInputStream = AudioSystem.getAudioInputStream(file)
        val bytes = audioInputStream.readAllBytes()
        audioInputStream.close()
        assertEquals(original.size * 2, bytes.size)

        for (i in original.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val pcm = ((hi shl 8) or lo).toShort()
            val decoded = pcm / 32767f
            assertTrue(
                kotlin.math.abs(decoded - original[i]) < 0.001f,
                "sample $i: expected ~${original[i]}, got $decoded"
            )
        }
    }

    @Test
    fun writeSamplesAfterCloseThrows() {
        val file = File(tempDir, "closed.wav")
        val writer = WavFileWriter(file, sampleRate = 48000)
        writer.close()
        kotlin.test.assertFailsWith<IllegalStateException> {
            writer.writeSamples(floatArrayOf(0f), 1)
        }
    }
}
