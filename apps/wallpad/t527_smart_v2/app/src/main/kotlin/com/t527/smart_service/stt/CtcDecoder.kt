package com.t527.smart_service.stt

import android.util.Log
import org.json.JSONObject
import java.io.File

class CtcDecoder {
    private val tag = "CtcDecoder"
    private val vocab = mutableMapOf<Int, String>()
    private val blankId = 2048
    private val fillers = setOf(" 음", " 어", " 네", " 응", " 아", " 예")

    fun loadVocab(jsonPath: String): Boolean {
        return try {
            val json = JSONObject(File(jsonPath).readText())
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                vocab[key.toInt()] = json.getString(key)
            }
            Log.d(tag, "Loaded vocab: ${vocab.size} tokens")
            true
        } catch (e: Exception) {
            Log.e(tag, "loadVocab error", e)
            false
        }
    }

    fun decode(argmaxIds: IntArray): String {
        // CTC: collapse duplicates, remove blanks
        val collapsed = mutableListOf<Int>()
        var prev = -1
        for (id in argmaxIds) {
            if (id != prev && id != blankId) collapsed.add(id)
            prev = id
        }

        val sb = StringBuilder()
        for (id in collapsed) {
            val token = vocab[id] ?: continue
            if (token.startsWith("▁")) {
                sb.append(" ").append(token.substring(1))
            } else {
                sb.append(token)
            }
        }

        var text = sb.toString().trim()

        // 간투어 후처리
        for (filler in fillers) {
            if (text.endsWith(filler.trim())) {
                text = text.dropLast(filler.trim().length).trim()
            }
        }

        return text
    }
}
