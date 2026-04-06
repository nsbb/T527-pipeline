package com.t527.smart_service.nlu

import java.io.File

class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val maxLen: Int = 64
) {
    private val unkId = vocab["[UNK]"] ?: 1
    private val clsId = vocab["[CLS]"] ?: 2
    private val sepId = vocab["[SEP]"] ?: 3
    private val padId = vocab["[PAD]"] ?: 0

    data class Encoded(val inputIds: IntArray, val attentionMask: IntArray)

    fun encode(text: String): Encoded {
        val tokens = tokenize(text)
        val maxTokens = maxLen - 2
        val truncated = if (tokens.size > maxTokens) tokens.subList(0, maxTokens) else tokens

        val ids = IntArray(maxLen)
        val mask = IntArray(maxLen)
        var pos = 0

        ids[pos] = clsId; mask[pos] = 1; pos++
        for (token in truncated) {
            ids[pos] = vocab[token] ?: unkId; mask[pos] = 1; pos++
        }
        ids[pos] = sepId; mask[pos] = 1
        return Encoded(ids, mask)
    }

    private fun tokenize(text: String): List<String> {
        return text.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .flatMap { wordPiece(it) }
    }

    private fun wordPiece(word: String): List<String> {
        val tokens = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            while (start < end) {
                val sub = if (start > 0) "##${word.substring(start, end)}" else word.substring(start, end)
                if (vocab.containsKey(sub)) { found = sub; break }
                end--
            }
            if (found == null) { tokens.add("[UNK]"); break }
            tokens.add(found)
            start = end
        }
        return tokens
    }

    companion object {
        fun fromVocabFile(file: File): WordPieceTokenizer {
            val vocab = LinkedHashMap<String, Int>()
            file.bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, line -> vocab[line.trim()] = idx }
            }
            return WordPieceTokenizer(vocab)
        }
    }
}
