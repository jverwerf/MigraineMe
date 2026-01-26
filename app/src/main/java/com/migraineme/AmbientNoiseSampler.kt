package com.migraineme

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.os.Build
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

    private const val TAG = "AmbientNoiseSampler"

    /**
     * Prefer an unprocessed microphone stream when available (avoids speech-focused DSP that can
     * suppress to zeros on some devices). Fallback to MIC, then (last resort) VOICE_RECOGNITION.
     */
    private fun createAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.MIC)
            // Last resort for devices that refuse MIC/UNPROCESSED.
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }

        var lastErr: Throwable? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord initialized with source=$src")
                    return r
                }
                Log.w(TAG, "AudioRecord not initialized with source=$src")
                runCatching { r.release() }
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "AudioRecord failed with source=$src: ${t.message}")
            }
        }

        throw IllegalStateException("Unable to initialize AudioRecord with available sources", lastErr)
    }

    fun capture(durationMs: Long = 60_000L): AmbientNoiseMetrics {
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBuf, sampleRate * 2) // ~1 second buffer (16-bit mono)

        var recorder: AudioRecord? = null

        try {
            recorder = createAudioRecord(
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                bufferSize = bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize")
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

            var iterationCount = 0
            while (System.currentTimeMillis() - start < durationMs) {
                // Check for thread interruption
                if (Thread.currentThread().isInterrupted) {
                    Log.d(TAG, "Thread interrupted during capture")
                    break
                }

                val read = recorder.read(readBuf, 0, readBuf.size)
                if (read <= 0) {
                    iterationCount++
                    // If we consistently fail to read, don't loop forever
                    if (iterationCount > 100) {
                        Log.w(TAG, "Too many failed reads, aborting")
                        break
                    }
                    continue
                }

                iterationCount = 0 // Reset counter on successful read

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

            if (frameAcc.isEmpty()) {
                Log.d(TAG, "No frames captured")
                return AmbientNoiseMetrics(lMean = 0.0, lP90 = 0.0, lMax = 0.0, frames = 0)
            }

            frameAcc.sort()
            val p90Index = ((frameAcc.size - 1) * 0.90).toInt().coerceIn(0, frameAcc.size - 1)

            val lP90 = frameAcc[p90Index]
            val lMax = frameAcc.last()
            val lMean = frameAcc.average()

            Log.d(TAG, "Captured ${frameAcc.size} frames successfully")
            return AmbientNoiseMetrics(lMean = lMean, lP90 = lP90, lMax = lMax, frames = frameAcc.size)

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - microphone permission denied: ${e.message}")
            return AmbientNoiseMetrics(lMean = 0.0, lP90 = 0.0, lMax = 0.0, frames = 0)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException during recording: ${e.message}")
            return AmbientNoiseMetrics(lMean = 0.0, lP90 = 0.0, lMax = 0.0, frames = 0)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during capture: ${e.message}", e)
            return AmbientNoiseMetrics(lMean = 0.0, lP90 = 0.0, lMax = 0.0, frames = 0)
        } finally {
            // CRITICAL: Always release the recorder, regardless of how we exit
            recorder?.let {
                try {
                    // Only stop if we're still recording
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Error stopping recorder: ${e.message}")
                }

                try {
                    it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing recorder: ${e.message}")
                }
            }
        }
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
