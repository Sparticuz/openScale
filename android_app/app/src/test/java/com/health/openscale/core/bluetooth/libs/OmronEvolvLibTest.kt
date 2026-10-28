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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

class OmronEvolvLibTest {
    @Test fun `pending range wraps chronologically and ACK preserves opaque index bytes`() {
        val block = byteArrayOf(0, 2, 0, 0, 0, 4, 0x80.toByte(), 0)
        assertThat(OmronEvolvLib.pendingSlots(block)).containsExactly(98, 99, 0, 1).inOrder()
        assertThat(OmronEvolvLib.acknowledgment(block).toList()).containsExactly(
            0.toByte(), 2.toByte(), 0.toByte(), 0.toByte(), 0x80.toByte(), 0.toByte(), 0x80.toByte(), 0.toByte()).inOrder()
        assertThat(OmronEvolvLib.pendingSlots(byteArrayOf(0, 100, 0, 0, 0x80.toByte(), 0, 0x80.toByte(), 0))).isEmpty()
        assertThat(runCatching { OmronEvolvLib.pendingSlots(block.copyOf().also { it[5] = 101 }) }.isFailure).isTrue()
        assertThat(runCatching { OmronEvolvLib.pendingSlots(block.copyOf().also { it[7] = 1 }) }.isFailure).isTrue()
    }
    @Test fun `status pointer format preserves flags while validating unknown bits`() {
        // Sanitized ring position, same status format as observed firmware.
        val block = byteArrayOf(0xc0.toByte(), 12, 0x80.toByte(), 0, 0, 1, 0x80.toByte(), 0)
        assertThat(OmronEvolvLib.pendingSlots(block)).containsExactly(11)
        assertThat(OmronEvolvLib.acknowledgment(block).take(4)).containsExactlyElementsIn(block.take(4)).inOrder()
        assertThat(OmronEvolvLib.acknowledgment(block).drop(4)).containsExactly(
            0x80.toByte(), 0.toByte(), 0x80.toByte(), 0.toByte()).inOrder()
        assertThat(runCatching { OmronEvolvLib.pendingSlots(block.copyOf().also { it[0] = 0xe0.toByte() }) }.isFailure).isTrue()
    }
    @Test fun `clock rewrite preserves profile prefix and encodes local fields with additive checksum`() {
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 28, 14, 35, 20) }
        val old = byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0, 0, 3) // unset date, valid block checksum
        val payload = OmronEvolvLib.clockPayload(old, now)
        assertThat(payload.toList()).containsExactly(1.toByte(), 2.toByte(), 10.toByte(), 26.toByte(),
            14.toByte(), 28.toByte(), 20.toByte(), 35.toByte(), 0.toByte(), 136.toByte()).inOrder()
        val frame = OmronEvolvLib.settingsWrite(0x029a, payload)
        assertThat(frame.size).isEqualTo(18)
        assertThat(frame.take(6)).containsExactly(18.toByte(), 1.toByte(), 0xc0.toByte(), 2.toByte(), 0x9a.toByte(), 10.toByte()).inOrder()
        assertThat(OmronWlpFrame.bcc(frame)).isEqualTo(0.toByte())
        assertThat(runCatching { OmronEvolvLib.clockPayload(old.copyOf().also { it[9] = 4 }, now) }.isFailure).isTrue()
        assertThat(runCatching { OmronEvolvLib.settingsWrite(0x0000, payload) }.isFailure).isTrue()
    }

    @Test fun `rejects truncated erased zero and invalid calendar records`() {
        assertThat(OmronEvolvLib.decodeRecord(record().copyOf(13))).isNull()
        assertThat(OmronEvolvLib.decodeRecord(record().copyOf(15))).isNull()
        assertThat(OmronEvolvLib.decodeRecord(ByteArray(14))).isNull()
        assertThat(OmronEvolvLib.decodeRecord(ByteArray(14) { 0xff.toByte() })).isNull()
        // Same February 29 on a non-leap year must not normalize to March 1.
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[2] = 23 })).isNull()
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[4] = 0 })).isNull()
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[5] = 0xbf.toByte() })).isNull() // hour 31
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[6] = 0x0f })).isNull() // minute 61
    }

    @Test fun `rejects zero pulse and reversed pressures without imposing clinical normal ranges`() {
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[3] = 0 })).isNull()
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[0] = 0 })).isNull()
        assertThat(OmronEvolvLib.decodeRecord(record().also { it[0] = 125 })).isNull()
        val high = OmronEvolvLib.decodeRecord(record().also { it[0] = 150.toByte(); it[1] = 225.toByte() })!!
        assertThat(high.systolic).isEqualTo(250)
        assertThat(high.diastolic).isEqualTo(150)
    }

    @Test fun `decodes movement irregular heartbeat and device second saturation`() {
        val r = OmronEvolvLib.decodeRecord(record().also { it[4] = 0xcb.toByte(); it[7] = 0x7f })!!
        assertThat(r.movement).isTrue()
        assertThat(r.irregularHeartbeat).isTrue()
        assertThat(Calendar.getInstance().apply { time = r.timestamp }.get(Calendar.SECOND)).isEqualTo(59)
    }
    // Synthetic 120/80, pulse 70, 2024-02-29 13:45:30; no device identity or key.
    private fun record() = byteArrayOf(80, 95, 24, 70, 0x0b, 0xad.toByte(), 0x0b, 0x5e, 0, 0, 0, 0, 0, 0)

    @Test fun `decodes packed pressures pulse and device local leap day`() {
        val r = OmronEvolvLib.decodeRecord(record())!!
        assertThat(r.systolic).isEqualTo(120)
        assertThat(r.diastolic).isEqualTo(80)
        assertThat(r.pulse).isEqualTo(70)
        val c = Calendar.getInstance().apply { time = r.timestamp }
        assertThat(listOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)))
            .containsExactly(2024, 2, 29, 13, 45, 30).inOrder()
    }
}
