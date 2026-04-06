package com.t527.smart_service.wakeword

import android.util.Log
import com.t527.wav2vecdemo.conformer.AwConformerJni
import org.json.JSONObject
import java.io.File

class WakewordEngine(private val jni: AwConformerJni) {
    private val tag = "WakewordEngine"
    private var ready = false

    private var inpScale = 0.063f
    private var inpZp = 218
    private var outScale = 0.0077f
    private var outZp = 128

    fun init(nbPath: String, metaPath: String): Boolean {
        return try {
            val ok = jni.initWakeword(nbPath)
            if (ok) loadMeta(metaPath)
            ready = ok
            Log.d(tag, "Wakeword init: $ok")
            ok
        } catch (e: Exception) {
            Log.e(tag, "Wakeword init error", e)
            false
        }
    }

    fun detect(audio16k: FloatArray): Float {
        if (!ready) return 0f
        val mel = jni.computeWakewordMel(audio16k, inpScale, inpZp)
        val probs = jni.runWakeword(mel, outScale, outZp)
        return if (probs != null && probs.size >= 2) probs[1] else 0f
    }

    fun release() {
        if (ready) {
            jni.releaseWakeword()
            ready = false
        }
    }

    private fun loadMeta(metaPath: String) {
        try {
            val json = JSONObject(File(metaPath).readText())
            val inputs = json.getJSONObject("Inputs")
            val inpQ = inputs.getJSONObject(inputs.keys().next()).getJSONObject("quantize")
            inpScale = inpQ.getDouble("scale").toFloat()
            inpZp = inpQ.getInt("zero_point")
            val outputs = json.getJSONObject("Outputs")
            val outQ = outputs.getJSONObject(outputs.keys().next()).getJSONObject("quantize")
            outScale = outQ.getDouble("scale").toFloat()
            outZp = outQ.getInt("zero_point")
        } catch (e: Exception) {
            Log.e(tag, "Meta load failed, using defaults", e)
        }
    }
}
