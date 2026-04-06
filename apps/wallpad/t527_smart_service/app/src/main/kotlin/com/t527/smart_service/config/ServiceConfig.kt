package com.t527.smart_service.config

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

data class WakewordConfig(
    val threshold: Float = 0.40f,
    val useEma: Boolean = true,
    val emaAlpha: Float = 0.5f,
    val useNofm: Boolean = true,
    val nofmN: Int = 2,
    val nofmM: Int = 5,
    val cooldownMs: Int = 2000
)

data class VadConfig(
    val threshold: Float = 0.5f,
    val silenceTimeoutMs: Int = 800,
    val maxSpeechMs: Int = 10000,
    val minSpeechSeconds: Float = 0.5f
)

data class AudioConfig(
    val sampleRateMic: Int = 48000,
    val sampleRateModel: Int = 16000,
    val flushAfterWakeMs: Int = 1000
)

data class SttConfig(
    val melScale: Float = 0.025880949571728706f,
    val melZp: Int = 84,
    val seqOut: Int = 76,
    val strideOut: Int = 62
)

data class ServiceConfig(
    val wakeword: WakewordConfig = WakewordConfig(),
    val vad: VadConfig = VadConfig(),
    val audio: AudioConfig = AudioConfig(),
    val stt: SttConfig = SttConfig()
) {
    companion object {
        private const val TAG = "ServiceConfig"
        private const val SDCARD_PATH = "/sdcard/t527_smart_service/config.json"

        fun load(context: Context): ServiceConfig {
            // sdcard 우선, 없으면 assets fallback
            val sdcardFile = File(SDCARD_PATH)
            val jsonStr = if (sdcardFile.exists()) {
                Log.d(TAG, "Loading config from sdcard: $SDCARD_PATH")
                sdcardFile.readText()
            } else {
                Log.d(TAG, "Loading config from assets")
                context.assets.open("config.json").bufferedReader().readText()
            }
            return parse(jsonStr)
        }

        private fun parse(jsonStr: String): ServiceConfig {
            try {
                val json = JSONObject(jsonStr)

                val wk = json.optJSONObject("wakeword")
                val wakeword = WakewordConfig(
                    threshold = wk?.optDouble("threshold", 0.40)?.toFloat() ?: 0.40f,
                    useEma = wk?.optBoolean("use_ema", true) ?: true,
                    emaAlpha = wk?.optDouble("ema_alpha", 0.5)?.toFloat() ?: 0.5f,
                    useNofm = wk?.optBoolean("use_nofm", true) ?: true,
                    nofmN = wk?.optInt("nofm_n", 2) ?: 2,
                    nofmM = wk?.optInt("nofm_m", 5) ?: 5,
                    cooldownMs = wk?.optInt("cooldown_ms", 2000) ?: 2000
                )

                val v = json.optJSONObject("vad")
                val vad = VadConfig(
                    threshold = v?.optDouble("threshold", 0.5)?.toFloat() ?: 0.5f,
                    silenceTimeoutMs = v?.optInt("silence_timeout_ms", 800) ?: 800,
                    maxSpeechMs = v?.optInt("max_speech_ms", 10000) ?: 10000,
                    minSpeechSeconds = v?.optDouble("min_speech_seconds", 0.5)?.toFloat() ?: 0.5f
                )

                val a = json.optJSONObject("audio")
                val audio = AudioConfig(
                    sampleRateMic = a?.optInt("sample_rate_mic", 48000) ?: 48000,
                    sampleRateModel = a?.optInt("sample_rate_model", 16000) ?: 16000,
                    flushAfterWakeMs = a?.optInt("flush_after_wake_ms", 1000) ?: 1000
                )

                val s = json.optJSONObject("stt")
                val stt = SttConfig(
                    melScale = s?.optDouble("mel_scale", 0.025880949571728706)?.toFloat() ?: 0.025880949571728706f,
                    melZp = s?.optInt("mel_zp", 84) ?: 84,
                    seqOut = s?.optInt("seq_out", 76) ?: 76,
                    strideOut = s?.optInt("stride_out", 62) ?: 62
                )

                val config = ServiceConfig(wakeword, vad, audio, stt)
                Log.d(TAG, "Config loaded: wk_th=${wakeword.threshold}, ema=${wakeword.useEma}, nofm=${wakeword.nofmN}/${wakeword.nofmM}")
                return config
            } catch (e: Exception) {
                Log.e(TAG, "Config parse error, using defaults", e)
                return ServiceConfig()
            }
        }
    }
}
