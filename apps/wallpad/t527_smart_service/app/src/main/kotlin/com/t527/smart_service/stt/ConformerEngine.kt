package com.t527.smart_service.stt

import android.util.Log
import com.t527.wav2vecdemo.conformer.AwConformerJni

class ConformerEngine(private val jni: AwConformerJni) {
    private val tag = "ConformerEngine"
    private var ready = false

    fun init(nbPath: String): Boolean {
        return try {
            val ok = jni.init(nbPath)
            ready = ok
            Log.d(tag, "Conformer init: $ok")
            ok
        } catch (e: Exception) {
            Log.e(tag, "Conformer init error", e)
            false
        }
    }

    fun computeMel(audio16k: FloatArray, melScale: Float, melZp: Int): ByteArray? {
        if (!ready) return null
        return try {
            jni.computeMel(audio16k, melScale, melZp)
        } catch (e: Exception) {
            Log.e(tag, "computeMel error", e)
            null
        }
    }

    fun runUint8(mel: ByteArray): IntArray? {
        if (!ready) return null
        return try {
            jni.runUint8(mel)
        } catch (e: Exception) {
            Log.e(tag, "runUint8 error", e)
            null
        }
    }

    fun release() {
        if (ready) {
            jni.release()
            ready = false
        }
    }
}
