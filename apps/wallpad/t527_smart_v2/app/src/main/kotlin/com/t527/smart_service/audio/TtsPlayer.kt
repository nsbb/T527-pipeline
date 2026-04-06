package com.t527.smart_service.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.util.Log

class TtsPlayer(private val context: Context) {
    private val tag = "TtsPlayer"
    private var player: MediaPlayer? = null

    fun play(ttsKey: String, onComplete: (() -> Unit)? = null): Boolean {
        stop()
        val extensions = listOf("mp3", "wav", "ogg")
        for (ext in extensions) {
            val path = "tts/$ttsKey.$ext"
            try {
                val afd: AssetFileDescriptor = context.assets.openFd(path)
                player = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    prepare()
                    setOnCompletionListener {
                        it.release()
                        player = null
                        onComplete?.invoke()
                    }
                    start()
                }
                Log.d(tag, "Playing: $path")
                return true
            } catch (_: Exception) {
                // try next extension
            }
        }
        Log.d(tag, "No TTS file for: $ttsKey")
        onComplete?.invoke()
        return false
    }

    fun stop() {
        try {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
            player = null
        } catch (_: Exception) {}
    }

    fun release() = stop()
}
