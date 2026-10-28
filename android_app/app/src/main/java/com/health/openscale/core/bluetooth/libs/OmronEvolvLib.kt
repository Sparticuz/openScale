/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.libs

import java.util.Calendar
import java.util.Date

/** Evolv BP7000 / HEM-7600T EEPROM layout. Device timestamps have no timezone.
 * Protocol reference: userx14/omblepy, hem-7600t profile at 59e6a59 (field positions only).
 * Independent decoder; no upstream implementation or private capture material is embedded.
 */
object OmronEvolvLib {
    const val RECORD_BASE = 0x02ac
    const val RECORD_SIZE = 14
    const val RECORD_COUNT = 100
    const val SETTINGS_READ = 0x0260
    const val SETTINGS_WRITE = 0x0286
    const val CLOCK_OFFSET = 20

    /** Pointer is the next slot to fill (100 and 0 both name the ring boundary).
     * Known high-byte status formats are preserved; unknown formats fail closed.
     * Evolv has one user: never acknowledge data in an unexpected second counter.
     */
    fun pendingSlots(metadata: ByteArray): List<Int> {
        require(metadata.size == 8)
        fun word(i: Int) = ((metadata[i].toInt() and 255) shl 8) or (metadata[i + 1].toInt() and 255)
        val pointer = word(0) and 255
        val count = word(4) and 255
        require(pointer in 0..RECORD_COUNT && count in 0..RECORD_COUNT)
        // Known pointer status formats: plain, sentinel, and captured firmware's 0xc0.
        // These bits are opaque, not part of the ring index, and must survive ACK.
        require(word(0) and 0xff00 in setOf(0, 0x8000, 0xc000))
        require(word(4) and 0x7f00 == 0)
        require(word(6) == 0 || word(6) == 0x8000)
        return List(count) { (pointer - count + it + RECORD_COUNT) % RECORD_COUNT }
    }

    fun acknowledgment(metadata: ByteArray): ByteArray {
        pendingSlots(metadata)
        return metadata.copyOf().also {
            it[4] = 0x80.toByte(); it[5] = 0
            it[6] = 0x80.toByte(); it[7] = 0
        }
    }

    /** Only the two documented Evolv settings blocks may be written by this profile. */
    fun settingsWrite(address: Int, payload: ByteArray): ByteArray {
        require((address == SETTINGS_WRITE && payload.size == 8) ||
            (address == SETTINGS_WRITE + CLOCK_OFFSET && payload.size == 10))
        val frame = byteArrayOf((payload.size + 8).toByte(), 1, 0xc0.toByte(),
            (address shr 8).toByte(), address.toByte(), payload.size.toByte()) + payload + 0.toByte()
        return frame + OmronWlpFrame.bcc(frame)
    }

    /** Read/modify/write the documented ten-byte clock block only. Invalid dates (unset clock)
     * are allowed, invalid block checksums are not. Historic record timestamps are never changed.
     */
    fun clockPayload(existing: ByteArray, now: Calendar = Calendar.getInstance()): ByteArray {
        require(existing.size == 10)
        require((existing.take(9).sumOf { it.toInt() and 255 } and 255) == (existing[9].toInt() and 255))
        require(now.get(Calendar.YEAR) in 2000..2099)
        return existing.copyOf().also {
            it[2] = (now.get(Calendar.MONTH) + 1).toByte()
            it[3] = (now.get(Calendar.YEAR) - 2000).toByte()
            it[4] = now.get(Calendar.HOUR_OF_DAY).toByte()
            it[5] = now.get(Calendar.DAY_OF_MONTH).toByte()
            it[6] = now.get(Calendar.SECOND).toByte()
            it[7] = now.get(Calendar.MINUTE).toByte()
            it[8] = 0
            it[9] = it.take(9).sumOf { b -> b.toInt() and 255 }.toByte()
        }
    }

    data class Record(val timestamp: Date, val systolic: Int, val diastolic: Int,
                      val pulse: Int, val movement: Boolean, val irregularHeartbeat: Boolean)

    fun decodeRecord(bytes: ByteArray): Record? {
        if (bytes.size != RECORD_SIZE) return null
        fun bits(start: Int, count: Int): Int {
            var value = 0
            repeat(count) { i ->
                val bit = start + i
                value = (value shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
            }
            return value
        }
        val dia = bits(0, 8)
        val sys = bits(8, 8) + 25
        val pulse = bits(24, 8)
        // Protocol-width bounds, not clinical classification or a filter for hypertension.
        if (dia !in 1..255 || sys !in 26..280 || sys <= dia || pulse !in 1..255) return null
        val month = bits(34, 4)
        val day = bits(38, 5)
        val hour = bits(43, 5)
        val minute = bits(52, 6)
        // This device encodes seconds 60..63; public Evolv implementations saturate to 59.
        val second = bits(58, 6).coerceAtMost(59)
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val timestamp = runCatching {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(2000 + bits(16, 8), month - 1, day, hour, minute, second)
            }.time
        }.getOrNull() ?: return null
        return Record(timestamp, sys, dia, pulse, bits(32, 1) != 0, bits(33, 1) != 0)
    }
}
