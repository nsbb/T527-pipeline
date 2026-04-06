package com.t527.smart_service.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayManager(private val context: Context) {
    private val tag = "OverlayManager"
    private val handler = Handler(Looper.getMainLooper())

    fun show(msg: String, durationMs: Long = 5000) {
        handler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = TextView(context).apply {
                    text = msg
                    textSize = 32f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xDD6A0DAD.toInt())
                    setPadding(48, 32, 48, 32)
                    maxLines = 3
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                    y = 300
                }
                wm.addView(tv, params)
                handler.postDelayed({
                    try { wm.removeView(tv) } catch (_: Exception) {}
                }, durationMs)
            } catch (e: Exception) {
                Log.e(tag, "Overlay failed", e)
            }
        }
    }
}
