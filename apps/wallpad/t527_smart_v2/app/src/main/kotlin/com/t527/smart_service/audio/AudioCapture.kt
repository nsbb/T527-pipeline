package com.t527.smart_service.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioCapture(private val sampleRateMic: Int = 48000, private val sampleRateModel: Int = 16000) {
    private val tag = "AudioCapture"
    private var recorder: AudioRecord? = null

    fun start(): Boolean {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRateMic, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            sampleRateMic * 2 * 2
        )
        return try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateMic, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            recorder?.startRecording()
            Log.d(tag, "AudioCapture started: ${sampleRateMic}Hz, buf=$bufSize")
            true
        } catch (e: Exception) {
            Log.e(tag, "AudioCapture start failed", e)
            false
        }
    }

    fun readShort(buffer: ShortArray, size: Int): Int {
        return recorder?.read(buffer, 0, size) ?: -1
    }

    fun resample48to16(pcm48k: ShortArray, len: Int): FloatArray {
        val newLen = len / 3
        val out = FloatArray(newLen)
        for (i in 0 until newLen) {
            val srcIdx = i * 3.0f
            val idx0 = srcIdx.toInt()
            val frac = srcIdx - idx0
            val idx1 = minOf(idx0 + 1, len - 1)
            out[i] = ((1 - frac) * pcm48k[idx0] + frac * pcm48k[idx1]) / 32768.0f
        }
        return out
    }

    fun flush(samples: Int) {
        val buf = ShortArray(samples)
        recorder?.read(buf, 0, samples)
    }

    fun stop() {
        try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            Log.d(tag, "AudioCapture stopped")
        } catch (e: Exception) {
            Log.e(tag, "AudioCapture stop error", e)
        }
    }
}
