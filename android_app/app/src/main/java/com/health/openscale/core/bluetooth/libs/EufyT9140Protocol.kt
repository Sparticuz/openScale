package com.health.openscale.core.bluetooth.libs

/**
 * Live AC02 T9140 V2 frames only. This is not the history/configuration protocol.
 * Eight-byte frames use sum(bytes 2..6) modulo 256. Notifications may split or
 * concatenate frames; the rolling window retains at most seven incomplete bytes.
 *
 * A final CA weight often PRECEDES the impedance CB result. Delay publication for
 * up to three seconds, then publish weight alone. Duplicate finals are ignored.
 * Progress CE, a different final weight, reset, or 15 seconds without a recognized
 * frame opens a new session. Identical finals without any of those boundaries are
 * inherently indistinguishable from retransmissions, not proof of a new weigh-in.
 * Callers supply monotonic milliseconds and call flush even when notifications stop.
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
                    // Do not carry impedance or a final into a fresh stepping/settling cycle.
                    out += finish()
                    clearSession()
                } else if (raw in 1..3000) {
                    if (weight != null && weight != raw) {
                        out += finish()
                        clearSession()
                    }
                    if (!completed) {
                        weight = raw
                        if (finalAt == null) finalAt = nowMs
                        if (impedance != null) out += finish()
                    }
                }
            } else if (!completed) {
                val raw = (b[4] shl 8) or b[5]
                if (raw in 1..65534) {
                    impedance = raw
                    if (weight != null) out += finish()
                }
            }
        }
        return out
    }

    fun flush(nowMs: Long): List<Reading> =
        if (!completed && finalAt?.let { nowMs - it >= IMPEDANCE_WAIT_MS } == true) finish()
        else emptyList()

    /** Preserve a pending weight-only reading when the link closes before the timeout. */
    fun finishPending(): List<Reading> = finish()

    private fun finish(): List<Reading> {
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
