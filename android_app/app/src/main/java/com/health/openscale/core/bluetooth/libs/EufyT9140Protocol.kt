package com.health.openscale.core.bluetooth.libs

/**
 * Live T9140 F6 frames: AC02 followed by six bytes, with sum(bytes 2..6) mod 256
 * at byte 7. Notifications can split or concatenate frames. No history/control decoding.
 * Callers supply monotonic milliseconds and flush even when notifications stop.
 */
class EufyT9140Protocol {
    data class Reading(val weightKg: Float, val rawImpedance: Int?)

    private val window = ArrayList<Int>(8)
    val bufferedBytes: Int get() = window.size
    private var weight: Int? = null
    private var impedance: Int? = null
    private var finalAt: Long? = null
    private var completed = false
    private var lastFrameAt: Long? = null
    private var lastInputAt: Long? = null

    fun reset() {
        window.clear()
        clearSession()
        lastFrameAt = null
        lastInputAt = null
    }

    private fun clearSession() {
        weight = null
        impedance = null
        finalAt = null
        completed = false
    }

    fun feed(data: ByteArray, nowMs: Long): List<Reading> {
        val out = mutableListOf<Reading>()
        out += flush(nowMs)
        if (lastInputAt?.let { nowMs - it >= SESSION_GAP_MS } == true) {
            window.clear()
            clearSession()
            lastFrameAt = null
        }
        if (data.isNotEmpty()) lastInputAt = nowMs
        for (byte in data) {
            window.add(byte.toInt() and 0xff)
            if (window.size < 8) continue
            val valid = window[0] == 0xac && window[1] == 0x02 &&
                ((2..6).sumOf { window[it] } and 0xff) == window[7]
            if (!valid) {
                window.removeAt(0)
                continue
            }
            val b = window.toList()
            window.clear()
            val isWeight = b[4] == 0 && b[5] == 0 && b[6] in listOf(0xca, 0xce)
            val isImpedance = b[2] == 0xfd && b[3] == 1 && b[6] == 0xcb
            if (!isWeight && !isImpedance) continue
            if (lastFrameAt?.let { nowMs - it >= SESSION_GAP_MS } == true) clearSession()
            lastFrameAt = nowMs
            if (isWeight) {
                val raw = (b[2] shl 8) or b[3]
                if (b[6] == 0xce) {
                    // A fresh settling cycle must not inherit a previous result.
                    out += finishPending()
                    clearSession()
                } else if (raw in 1..3000) {
                    if (weight != null && weight != raw) {
                        out += finishPending()
                        clearSession()
                    }
                    if (!completed) {
                        weight = raw
                        if (finalAt == null) finalAt = nowMs
                        if (impedance != null) out += finishPending()
                    }
                }
            } else if (!completed) {
                val raw = (b[4] shl 8) or b[5]
                if (raw in 1..65534) {
                    impedance = raw
                    if (weight != null) out += finishPending()
                }
            }
        }
        return out
    }

    fun flush(nowMs: Long): List<Reading> =
        if (!completed && finalAt?.let { nowMs - it >= IMPEDANCE_WAIT_MS } == true) finishPending()
        else emptyList()

    /** Consume once; callback delivery on disconnect is not a persistence guarantee. */
    fun finishPending(): List<Reading> {
        val rawWeight = weight ?: return emptyList()
        if (completed) return emptyList()
        completed = true
        return listOf(Reading(rawWeight / 10f, impedance))
    }

    companion object {
        const val IMPEDANCE_WAIT_MS = 3000L
        const val SESSION_GAP_MS = 15000L
    }
}
