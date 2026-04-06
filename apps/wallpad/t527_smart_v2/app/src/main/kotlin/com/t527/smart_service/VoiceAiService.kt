package com.t527.smart_service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.t527.smart_service.audio.AudioCapture
import com.t527.smart_service.audio.TtsPlayer
import com.t527.smart_service.config.ServiceConfig
import com.t527.smart_service.nlu.IntentClassifier
import com.t527.smart_service.nlu.IntentResponse
import com.t527.smart_service.nlu.ParameterExtractor
import com.t527.smart_service.stt.ConformerEngine
import com.t527.smart_service.stt.CtcDecoder
import com.t527.smart_service.ui.OverlayManager
import com.t527.smart_service.vad.SileroVad
import com.t527.smart_service.wakeword.WakewordEngine
import com.t527.smart_service.wakeword.WakewordPostProcessor
import com.t527.wav2vecdemo.conformer.AwConformerJni
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class VoiceAiService : Service() {
    companion object {
        private const val TAG = "VoiceAiService"
        private const val CHANNEL_ID = "voice_ai_channel"
        const val ACTION_MIC_GRANTED = "com.t527.smart_service.ACTION_MIC_GRANTED"
        const val ACTION_START_STT = "com.t527.smart_service.ACTION_START_STT"
        const val ACTION_RELOAD_CONFIG = "com.t527.smart_service.ACTION_RELOAD_CONFIG"
    }

    private lateinit var config: ServiceConfig
    private lateinit var overlay: OverlayManager
    private var jni: AwConformerJni? = null
    private var wakeEngine: WakewordEngine? = null
    private var wakePostProc: WakewordPostProcessor? = null
    private var vad: SileroVad? = null
    private var conformer: ConformerEngine? = null
    private var decoder: CtcDecoder? = null
    private var classifier: IntentClassifier? = null
    private var audio: AudioCapture? = null
    private var ttsPlayer: TtsPlayer? = null

    @Volatile private var running = false
    @Volatile private var sttRequested = false
    private var micGranted = false
    private var pipelineJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        createNotificationChannel()
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("음성 AI 서비스")
            .setContentText("'하이 원더' 대기 중")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        config = ServiceConfig.load(this)
        overlay = OverlayManager(this)
        ttsPlayer = TtsPlayer(this)
        initModels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIC_GRANTED -> {
                Log.d(TAG, "MIC_GRANTED received")
                if (!micGranted && running) {
                    micGranted = true
                    startPipeline()
                    overlay.show("마이크 획득 - VT 시작")
                }
            }
            ACTION_START_STT -> {
                Log.d(TAG, "STT requested via BR")
                sttRequested = true
            }
            ACTION_RELOAD_CONFIG -> {
                Log.d(TAG, "Config reload requested")
                config = ServiceConfig.load(this)
                wakePostProc = WakewordPostProcessor(config.wakeword)
                overlay.show("Config 리로드 완료")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        running = false
        scope.cancel()
        audio?.stop()
        wakeEngine?.release()
        conformer?.release()
        vad?.release()
        classifier?.release()
        ttsPlayer?.release()
    }

    // --- Init ---

    private fun initModels() {
        try {
            val confNb = copyAsset("models/Conformer", "network_binary.nb")
            val confVocab = copyAsset("models/Conformer", "vocab_correct.json")
            val wkNb = copyAsset("models/Wakeword", "network_binary.nb")
            val wkMeta = copyAsset("models/Wakeword", "nbg_meta.json")
            val vadOnnx = copyAsset("models/VAD", "silero_vad.onnx")
            val nluOnnx = copyAsset("models/KoElectra", "koelectra.onnx")
            val nluVocab = copyAsset("models/KoElectra", "vocab.txt")
            val nluLabels = copyAsset("models/KoElectra", "label_map.json")

            AwConformerJni.initNpu()
            jni = AwConformerJni()

            conformer = ConformerEngine(jni!!).also { it.init(confNb!!) }
            decoder = CtcDecoder().also { it.loadVocab(confVocab!!) }
            wakeEngine = WakewordEngine(jni!!).also { it.init(wkNb!!, wkMeta!!) }
            wakePostProc = WakewordPostProcessor(config.wakeword)
            vad = SileroVad().also { it.init(vadOnnx!!) }
            classifier = IntentClassifier().also { it.init(nluOnnx!!, nluVocab!!, nluLabels!!) }

            running = true
            overlay.show("음성 AI 서비스 대기 중")
            Log.d(TAG, "All models loaded OK")
        } catch (e: Exception) {
            Log.e(TAG, "Model init failed", e)
            overlay.show("모델 초기화 실패: ${e.message}")
        }
    }

    // --- Pipeline ---

    private fun startPipeline() {
        audio = AudioCapture(config.audio.sampleRateMic, config.audio.sampleRateModel)
        if (!audio!!.start()) {
            overlay.show("마이크 열기 실패")
            return
        }

        pipelineJob = scope.launch {
            Log.d(TAG, "Pipeline loop started")
            val wkWindowSamples = config.audio.sampleRateMic * 3 / 2  // 1.5s @ mic rate
            val wkHopSamples = config.audio.sampleRateMic / 2         // 0.5s hop
            val ringBuffer = ShortArray(wkWindowSamples)
            var ringPos = 0
            var bufferFull = false

            while (running && isActive) {
                // BR trigger
                if (sttRequested) {
                    sttRequested = false
                    Log.d(TAG, "BR trigger: running STT")
                    overlay.show("STT 시작...")
                    runStt()
                    ringBuffer.fill(0); ringPos = 0; bufferFull = false
                    continue
                }

                // VT: read 0.5s audio
                val chunk = ShortArray(wkHopSamples)
                val read = audio!!.readShort(chunk, wkHopSamples)
                if (read <= 0) continue

                for (i in 0 until read) {
                    ringBuffer[ringPos % wkWindowSamples] = chunk[i]
                    ringPos++
                }
                if (ringPos >= wkWindowSamples) bufferFull = true
                if (!bufferFull) continue

                // Extract ring buffer
                val audio48k = ShortArray(wkWindowSamples)
                val start = ringPos % wkWindowSamples
                for (i in 0 until wkWindowSamples)
                    audio48k[i] = ringBuffer[(start + i) % wkWindowSamples]

                val audio16k = audio!!.resample48to16(audio48k, wkWindowSamples)

                // Wakeword detect + post-processing
                val tWk0 = System.currentTimeMillis()
                val rawProb = wakeEngine!!.detect(audio16k)
                val tWk1 = System.currentTimeMillis()
                val wake = wakePostProc!!.process(rawProb)

                if (wake) {
                    Log.d(TAG, "WAKEWORD! prob=%.2f (%dms)".format(rawProb, tWk1 - tWk0))
                    overlay.show("'하이 원더' 감지!\n(${tWk1 - tWk0}ms, prob=%.2f)".format(rawProb))
                    runStt()
                    wakePostProc!!.reset()
                    ringBuffer.fill(0); ringPos = 0; bufferFull = false
                    delay(500)
                }
            }
            audio?.stop()
            Log.d(TAG, "Pipeline loop stopped")
        }
    }

    private suspend fun runStt() {
        val cap = audio ?: return
        val cfg = config

        // Flush wakeword tail
        cap.flush(cfg.audio.sampleRateMic * cfg.audio.flushAfterWakeMs / 1000)

        vad!!.resetState()
        val speechChunks = mutableListOf<FloatArray>()
        var silenceFrames = 0
        var speechFrames = 0
        val vadFrameSize48k = 512 * 3
        val maxFrames = cfg.vad.maxSpeechMs * cfg.audio.sampleRateMic / 1000 / vadFrameSize48k
        val silenceLimit = cfg.vad.silenceTimeoutMs * cfg.audio.sampleRateModel / 1000 / 512
        var speechStarted = false
        val tSttStart = System.currentTimeMillis()

        // VAD loop
        while (running && speechFrames < maxFrames) {
            val vadChunk = ShortArray(vadFrameSize48k)
            val vadRead = cap.readShort(vadChunk, vadFrameSize48k)
            if (vadRead <= 0) continue

            val vadAudio = cap.resample48to16(vadChunk, vadRead)
            val vadProb = vad!!.process(vadAudio)
            val isSpeech = vadProb >= cfg.vad.threshold

            if (isSpeech) {
                speechStarted = true; silenceFrames = 0
                speechChunks.add(vadAudio); speechFrames++
            } else if (speechStarted) {
                silenceFrames++; speechChunks.add(vadAudio)
                if (silenceFrames >= silenceLimit) break
            } else {
                if (System.currentTimeMillis() - tSttStart > 9000) {
                    Log.d(TAG, "VAD: timeout, no speech")
                    overlay.show("(음성 없음)")
                    return
                }
            }
        }

        if (!speechStarted || speechChunks.isEmpty()) {
            overlay.show("(음성 없음)")
            return
        }

        val totalSamples = speechChunks.size * 512
        val speechOnlyDuration = speechFrames * 512f / cfg.audio.sampleRateModel
        if (speechOnlyDuration < cfg.vad.minSpeechSeconds) {
            Log.d(TAG, "VAD: speech only %.2fs, skipping".format(speechOnlyDuration))
            return
        }

        val fullAudio = FloatArray(totalSamples)
        speechChunks.forEachIndexed { i, chunk ->
            System.arraycopy(chunk, 0, fullAudio, i * 512, minOf(chunk.size, 512))
        }
        val speechDuration = totalSamples.toFloat() / cfg.audio.sampleRateModel
        Log.d(TAG, "VAD: speech %.2fs (%d samples)".format(speechDuration, totalSamples))

        // STT sliding window
        var tMelTotal = 0L; var tNpuTotal = 0L
        val windowSamples = cfg.audio.sampleRateModel * 301 / 100
        val strideSamples = cfg.audio.sampleRateModel * 250 / 100
        val allArgmax = mutableListOf<IntArray>()
        var pos = 0

        while (pos < totalSamples) {
            val end = minOf(pos + windowSamples, totalSamples)
            val chunkAudio = fullAudio.copyOfRange(pos, end)

            val tM0 = System.currentTimeMillis()
            val mel = conformer!!.computeMel(chunkAudio, cfg.stt.melScale, cfg.stt.melZp)
            tMelTotal += System.currentTimeMillis() - tM0

            if (mel != null) {
                val tN0 = System.currentTimeMillis()
                val argmax = conformer!!.runUint8(mel)
                tNpuTotal += System.currentTimeMillis() - tN0
                if (argmax != null) allArgmax.add(argmax)
            }

            if (end >= totalSamples) break
            pos += strideSamples
        }

        // Merge chunks
        val mergedIds = mutableListOf<Int>()
        allArgmax.forEachIndexed { ci, ids ->
            val useFrames = if (ci < allArgmax.size - 1) cfg.stt.strideOut else cfg.stt.seqOut
            for (t in 0 until minOf(useFrames, ids.size)) mergedIds.add(ids[t])
        }

        val text = decoder!!.decode(mergedIds.toIntArray())
        val resultText = text.ifEmpty { "(인식 없음)" }
        Log.d(TAG, "STT: [$resultText] (%.1fs, mel=%dms, npu=%dms, %d chunks)".format(
            speechDuration, tMelTotal, tNpuTotal, allArgmax.size))

        // NLU
        val tNlu0 = System.currentTimeMillis()
        val intent = classifier?.classify(text) ?: "unknown"
        val params = ParameterExtractor.extract(text)
        val tNlu1 = System.currentTimeMillis()
        val response = IntentResponse.getResponse(intent, params)
        val ttsKey = IntentResponse.getTtsKey(intent, params)

        Log.d(TAG, "NLU: [$text] → $intent (${tNlu1 - tNlu0}ms) params=$params")

        overlay.show("$resultText\n→ $intent (${tNlu1 - tNlu0}ms)\n$response")

        // TTS MP3 재생
        ttsPlayer?.play(ttsKey)
    }

    // --- Utils ---

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Voice AI", NotificationManager.IMPORTANCE_LOW)
        ch.description = "음성 AI 백그라운드 서비스"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun copyAsset(dir: String, name: String): String? {
        val out = File(filesDir, "${dir.replace("/", "_")}_$name")
        if (out.exists() && out.length() > 0) return out.absolutePath
        out.parentFile?.mkdirs()
        return try {
            assets.open("$dir/$name").use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(1024 * 1024)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) fos.write(buf, 0, len)
                }
            }
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: $dir/$name", e)
            null
        }
    }
}
