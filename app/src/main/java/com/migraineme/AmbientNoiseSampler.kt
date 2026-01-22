package com.migraineme

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.ln
import kotlin.math.sqrt

data class AmbientNoiseMetrics(
    val lMean: Double,
    val lP90: Double,
    val lMax: Double,
    val frames: Int
)

/**
 * Records short PCM audio and computes loudness metrics WITHOUT storing audio.
 *
 * We use log-RMS per frame:
 * - lMean = mean(logRms)
 * - lP90  = 90th percentile(logRms)
 * - lMax  = max(logRms)
 *
 * This is NOT calibrated dB SPL; it's a stable relative measure per device.
 */
object AmbientNoiseSampler {

    fun capture(durationMs: Long = 60_000L): AmbientNoiseMetrics {
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, sampleRate * 2) // ~1 second buffer (16-bit mono)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return AmbientNoiseMetrics(lMean = 0.0, lP90 = 0.0, lMax = 0.0, frames = 0)
        }

        val readBuf = ShortArray(bufferSize / 2)

        val frameMs = 200L
        val frameSamples = (sampleRate * frameMs / 1000).toInt()
        val frameBuf = ShortArray(frameSamples)
        var frameFill = 0

        val frameAcc = ArrayList<Double>(512)

        recorder.startRecording()
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < durationMs) {
            val read = recorder.read(readBuf, 0, readBuf.size)
            if (read <= 0) continue

            var i = 0
            while (i < read) {
                val take = minOf(read - i, frameSamples - frameFill)
                for (k in 0 until take) {
                    frameBuf[frameFill + k] = readBuf[i + k]
                }
                frameFill += take
                i += take

                if (frameFill == frameSamples) {
                    frameAcc.add(computeLogRms(frameBuf))
                    frameFill = 0
                }
            }
        }

        runCatching { recorder.stop() }
        runCatching { recorder.release() }

        if (frameAcc.isEmpty()) {
            return AmbientNoiseMetrics(lMean = 0.0, lP90 = 0.0, lMax = 0.0, frames = 0)
        }

        frameAcc.sort()
        val p90Index = ((frameAcc.size - 1) * 0.90).toInt().coerceIn(0, frameAcc.size - 1)

        val lP90 = frameAcc[p90Index]
        val lMax = frameAcc.last()
        val lMean = frameAcc.average()

        return AmbientNoiseMetrics(lMean = lMean, lP90 = lP90, lMax = lMax, frames = frameAcc.size)
    }

    private fun computeLogRms(frame: ShortArray): Double {
        var sumSq = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sumSq += v * v
        }
        val meanSq = sumSq / frame.size.toDouble()
        val rms = sqrt(meanSq)

        // Avoid ln(0)
        val eps = 1.0
        return ln(rms + eps)
    }
}
