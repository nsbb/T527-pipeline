package com.t527.smart_service.nlu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.File

class IntentClassifier {
    private val tag = "IntentClassifier"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private var id2label = emptyMap<Int, String>()

    fun init(onnxPath: String, vocabPath: String, labelMapPath: String): Boolean {
        return try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(4)
            session = env!!.createSession(onnxPath, opts)
            tokenizer = WordPieceTokenizer.fromVocabFile(File(vocabPath))
            id2label = loadLabelMap(labelMapPath)
            Log.d(tag, "IntentClassifier init OK: ${id2label.size} intents")
            true
        } catch (e: Exception) {
            Log.e(tag, "IntentClassifier init failed", e)
            false
        }
    }

    fun classify(text: String): String {
        val s = session ?: return "unknown"
        val e = env ?: return "unknown"
        val tok = tokenizer ?: return "unknown"
        if (text.isEmpty()) return "unknown"

        return try {
            val encoded = tok.encode(text)
            val seqLen = encoded.inputIds.size

            val inputIds = Array(1) { LongArray(seqLen) { encoded.inputIds[it].toLong() } }
            val mask = Array(1) { LongArray(seqLen) { encoded.attentionMask[it].toLong() } }

            val inputTensor = OnnxTensor.createTensor(e, inputIds)
            val maskTensor = OnnxTensor.createTensor(e, mask)

            val inputs = mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor)
            val result = s.run(inputs)

            val logits = result[0].value as Array<FloatArray>
            val bestId = logits[0].indices.maxByOrNull { logits[0][it] } ?: 0

            inputTensor.close(); maskTensor.close(); result.close()

            id2label[bestId] ?: "unknown"
        } catch (e: Exception) {
            Log.e(tag, "classify error", e)
            "unknown"
        }
    }

    fun release() {
        try { session?.close() } catch (_: Exception) {}
    }

    private fun loadLabelMap(path: String): Map<Int, String> {
        val json = JSONObject(File(path).readText())
        val id2labelJson = json.getJSONObject("id2label")
        val map = mutableMapOf<Int, String>()
        val keys = id2labelJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key.toInt()] = id2labelJson.getString(key)
        }
        return map
    }
}
