package com.t527.smart_service.wakeword

import com.t527.smart_service.config.WakewordConfig

class WakewordPostProcessor(private val config: WakewordConfig) {
    private var emaValue = -1f
    private val trigBuffer = ArrayDeque<Int>()
    private var cooldownUntil = 0L

    fun process(rawProb: Float): Boolean {
        // Stage 1: EMA smoothing
        var smoothed = rawProb
        if (config.useEma) {
            if (emaValue < 0f) {
                emaValue = rawProb
            } else {
                emaValue = config.emaAlpha * rawProb + (1f - config.emaAlpha) * emaValue
            }
            smoothed = emaValue
        }

        // Stage 2: Threshold
        val hit = smoothed >= config.threshold

        // Stage 3: N-of-M voting
        var fired = hit
        if (config.useNofm) {
            trigBuffer.addLast(if (hit) 1 else 0)
            while (trigBuffer.size > config.nofmM) trigBuffer.removeFirst()
            fired = if (trigBuffer.size < config.nofmM) {
                false
            } else {
                trigBuffer.sum() >= config.nofmN
            }
        }

        // Stage 4: Cooldown
        var wake = fired
        if (fired) {
            val now = System.currentTimeMillis()
            if (now < cooldownUntil) {
                wake = false
            } else {
                cooldownUntil = now + config.cooldownMs
            }
        }

        return wake
    }

    fun reset() {
        emaValue = -1f
        trigBuffer.clear()
    }
}
