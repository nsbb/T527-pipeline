package com.t527.smart_service.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

class SileroVad {
    private val tag = "SileroVad"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var state = Array(2) { Array(1) { FloatArray(128) } }

    fun init(onnxPath: String): Boolean {
        return try {
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(onnxPath)
            Log.d(tag, "Silero VAD init OK")
            true
        } catch (e: Exception) {
            Log.e(tag, "Silero VAD init failed", e)
            false
        }
    }

    fun process(audio512: FloatArray): Float {
        val s = session ?: return 0f
        val e = env ?: return 0f
        return try {
            val audioInput = Array(1) { FloatArray(512) }
            System.arraycopy(audio512, 0, audioInput[0], 0, minOf(audio512.size, 512))

            val inputTensor = OnnxTensor.createTensor(e, audioInput)
            val stateTensor = OnnxTensor.createTensor(e, state)
            val srTensor = OnnxTensor.createTensor(e, longArrayOf(16000))

            val inputs = mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)
            val result = s.run(inputs)

            val output = result[0].value as Array<FloatArray>
            val prob = output[0][0]
            state = result[1].value as Array<Array<FloatArray>>

            inputTensor.close(); stateTensor.close(); srTensor.close(); result.close()
            prob
        } catch (e: Exception) {
            Log.e(tag, "VAD error", e)
            0f
        }
    }

    fun resetState() {
        state = Array(2) { Array(1) { FloatArray(128) } }
    }

    fun release() {
        try {
            session?.close()
            env?.close()
        } catch (e: Exception) {
            Log.e(tag, "Release error", e)
        }
    }
}
