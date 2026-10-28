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
package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.OmronEvolvLib
import com.health.openscale.core.bluetooth.libs.OmronWlpFrame
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.*
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** Native Evolv setup and pending-only transfer. Key replacement is explicitly armed in settings;
 * subscriptions let Android/the existing GATT stack perform BLE bonding (not application pairing).
 * Every pending record must be durably confirmed before a fresh metadata check permits ACK.
 */
class OmronEvolvHandler : ScaleDeviceHandler() {
    companion object {
        val SYSTOLIC = MeasurementType.deviceInt("blood_pressure.systolic",
            R.string.measurement_type_systolic, UnitType.MMHG,
            icon = MeasurementTypeIcon.IC_M_BLOOD_PRESSURE, color = 0xFFC62828.toInt())
        val DIASTOLIC = MeasurementType.deviceInt("blood_pressure.diastolic",
            R.string.measurement_type_diastolic, UnitType.MMHG,
            icon = MeasurementTypeIcon.IC_M_BLOOD_PRESSURE, color = 0xFF1565C0.toInt())
        val MOVEMENT = MeasurementType.deviceInt("blood_pressure.movement",
            R.string.evolv_movement, enabled = false, isInternal = true)
        val IRREGULAR_HEARTBEAT = MeasurementType.deviceInt("blood_pressure.irregular_heartbeat",
            R.string.evolv_irregular_heartbeat, enabled = false, isInternal = true)
        internal const val PAIR_SETTING = "pairOnNextConnection"
        internal const val RESPONSE_TIMEOUT_MS = 8_000L
        // Five serialized Conservative subscriptions can each wait 2000 ms + 160 ms gap.
        // Only initial unlock includes that bounded setup allowance; later commands keep 8 s.
        private const val INITIAL_UNLOCK_TIMEOUT_MS = 5 * (2_000L + 160L) + RESPONSE_TIMEOUT_MS
        private val service = OmronWlcHandler.SVC_OMRON_WLP
        private val unlock = OmronWlcHandler.CHR_UNLOCK
        private val tx = OmronWlcHandler.CHR_TX
        private val rx = OmronWlcHandler.CHR_RX

        internal fun matches(name: String): Boolean {
            val n = name.trim().lowercase(Locale.ROOT)
            return n in setOf("bp7000", "hem-7600t") ||
                Regex("blesmart_0000011f[0-9a-f]{12}").matches(n)
        }
        internal fun parseKey(hex: String?): ByteArray? {
            if (hex == null || !Regex("[0-9a-fA-F]{32}").matches(hex)) return null
            return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }

    private enum class Phase { IDLE, PREPARE_PAIR, ENTER_KEY, SAVE_KEY, WRITE_KEY, UNLOCK,
        START, METADATA, CLOCK_READ, CLOCK_WRITE, READ, SAVING, VERIFY_METADATA, ACK, END }
    private var phase = Phase.IDLE
    private var generation = 0L
    private var watchdog: Job? = null
    private var work: Job? = null
    private var sessionUserId = -1
    private var sessionKeySetting = ""
    private var pendingKey: ByteArray? = null
    private var metadata = byteArrayOf()
    private var slots = emptyList<Int>()
    private var cursor = 0
    private var expectedAddress = 0
    private var expectedSize = 0
    private val lanes = arrayOfNulls<ByteArray>(4)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (!matches(device.name)) return null
        val caps = setOf(DeviceCapability.HISTORY_READ, DeviceCapability.TIME_SYNC)
        return DeviceSupport("Omron Evolv BP7000", caps, caps, TuningProfile.Conservative)
    }

    // Handler-owned observable state survives retained settings composition. Null reads
    // durable initial authorization until either the UI or connection changes it.
    private var pairingState by mutableStateOf<Boolean?>(null)
    internal val pairingArmed: Boolean
        get() = pairingState ?: (settingsGetString(PAIR_SETTING) == "yes")

    internal suspend fun togglePairing(): Boolean {
        val next = !pairingArmed
        if (!settingsPutStringDurably(PAIR_SETTING, if (next) "yes" else "")) return false
        pairingState = next
        return true
    }

    @Composable
    override fun DeviceConfigurationUi() {
        val uiScope = rememberCoroutineScope()
        val armed = pairingArmed
        var failed by remember { mutableStateOf(false) }
        Column {
            Text(stringResource(R.string.evolv_setup_description))
            Button(onClick = {
                uiScope.launch {
                    try {
                        failed = !togglePairing()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                }
            }) { Text(stringResource(if (armed) R.string.evolv_pair_armed else R.string.evolv_pair_button)) }
            if (failed) Text(stringResource(R.string.evolv_setup_save_failed))
        }
    }

    @Synchronized
    override fun onConnected(user: ScaleUser) {
        reset()
        if (user.id <= 0) { fail(R.string.evolv_save_failed); return }
        sessionUserId = user.id
        val session = generation
        val address = deviceAddress()
        if (address.isNullOrBlank()) { fail(R.string.evolv_setup_required); return }
        sessionKeySetting = "pairingKey_${address.uppercase(Locale.ROOT)}"
        val pairingRequested = settingsGetString(PAIR_SETTING) == "yes"
        pairingState = pairingRequested
        val key = parseKey(settingsGetString(sessionKeySetting))
        if (!pairingRequested && key == null) { fail(R.string.evolv_setup_required); return }
        if (!(rx + tx + unlock).all { hasCharacteristic(service, it) }) {
            key?.fill(0)
            fail(R.string.bt_error_omron_service_missing)
            return
        }
        // Blessed handles authentication/bonding requested by the protected subscriptions.
        setNotifyOn(service, unlock)
        rx.forEach { setNotifyOn(service, it) }
        if (pairingRequested) {
            key?.fill(0)
            phase = Phase.PREPARE_PAIR
            armWatchdog(INITIAL_UNLOCK_TIMEOUT_MS)
            work = scope.launch {
                try {
                    // Consume authorization durably before issuing any key-changing operation.
                    check(settingsPutStringDurably(PAIR_SETTING, ""))
                    inSession(session) {
                        pairingState = false
                        ensureActive()
                        phase = Phase.ENTER_KEY
                        writeTo(service, unlock, OmronWlpFrame.enterKeyProgramming())
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { inSession(session) { fail(R.string.evolv_setup_save_failed) } }
            }
        } else {
            phase = Phase.UNLOCK
            armWatchdog(INITIAL_UNLOCK_TIMEOUT_MS)
            writeTo(service, unlock, OmronWlpFrame.unlock(key!!))
            key.fill(0)
        }
    }

    @Synchronized
    override fun onDisconnected() = reset()

    private fun handleUnlock(bytes: ByteArray) {
        if (bytes.size !in 2..17) return
        val ack = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        when (phase) {
            Phase.ENTER_KEY -> {
                if (ack != OmronWlpFrame.ACK_KEY_PROGRAMMING) { fail(R.string.bt_error_omron_pairing_mode_required); return }
                phase = Phase.SAVE_KEY
                armWatchdog(30_000)
                val session = generation
                val keySetting = sessionKeySetting
                work = scope.launch {
                    val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    try {
                        val encoded = key.joinToString("") { "%02x".format(it.toInt() and 255) }
                        // A failed or ambiguous programming outcome retains this durable candidate for retry.
                        check(settingsPutStringDurably(keySetting, encoded))
                        inSession(session) {
                            ensureActive()
                            pendingKey = key.copyOf()
                            phase = Phase.WRITE_KEY
                            armWatchdog()
                            writeTo(service, unlock, OmronWlpFrame.writeKey(key))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { inSession(session) { fail(R.string.evolv_setup_save_failed) } }
                    finally { key.fill(0) }
                }
            }
            Phase.WRITE_KEY -> {
                if (ack != OmronWlpFrame.ACK_KEY_WRITTEN) { fail(R.string.bt_error_omron_pairing_failed); return }
                // Confirm that the programmed key actually unlocks, not just the write response.
                phase = Phase.UNLOCK
                armWatchdog()
                writeTo(service, unlock, OmronWlpFrame.unlock(checkNotNull(pendingKey)))
                pendingKey?.fill(0)
                pendingKey = null
            }
            Phase.UNLOCK -> {
                if (ack != OmronWlpFrame.ACK_UNLOCKED) { fail(R.string.evolv_unlock_failed); return }
                phase = Phase.START
                send(OmronWlpFrame.startTransmission())
            }
            else -> Unit
        }
    }

    @Synchronized
    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (phase == Phase.IDLE) return
        if (characteristic == unlock) { handleUnlock(data); return }
        val lane = rx.indexOf(characteristic)
        if (lane < 0) return
        val frame = assemble(lane, data) ?: return
        if ((frame[5].toInt() and 255) != frame.size - 8) return
        val response = OmronWlpFrame.parseResponse(frame) ?: return
        val readPhase = phase in setOf(Phase.METADATA, Phase.CLOCK_READ, Phase.READ, Phase.VERIFY_METADATA)
        val writePhase = phase == Phase.CLOCK_WRITE || phase == Phase.ACK
        if (readPhase && (response.type != OmronWlpFrame.TYPE_READ || response.address != expectedAddress)) return
        if (writePhase && (response.type != OmronWlpFrame.TYPE_WRITE || response.address != expectedAddress)) return
        if (!readPhase && !writePhase &&
            !(phase == Phase.START && response.type == OmronWlpFrame.TYPE_START) &&
            !(phase == Phase.END && response.type == OmronWlpFrame.TYPE_END)) return
        if (response.result != 0 || (readPhase && response.data.size != expectedSize)) {
            fail(R.string.evolv_transfer_failed); return
        }
        try {
            when (phase) {
                Phase.START -> read(Phase.METADATA, OmronEvolvLib.SETTINGS_READ, 8)
                Phase.METADATA -> {
                    slots = OmronEvolvLib.pendingSlots(response.data)
                    metadata = response.data.copyOf()
                    read(Phase.CLOCK_READ, OmronEvolvLib.SETTINGS_READ + OmronEvolvLib.CLOCK_OFFSET, 10)
                }
                Phase.CLOCK_READ -> {
                    val payload = OmronEvolvLib.clockPayload(response.data)
                    writeSettings(Phase.CLOCK_WRITE, OmronEvolvLib.SETTINGS_WRITE + OmronEvolvLib.CLOCK_OFFSET, payload)
                }
                Phase.CLOCK_WRITE -> nextRecord()
                Phase.READ -> {
                    val record = OmronEvolvLib.decodeRecord(response.data)
                        ?: run { fail(R.string.evolv_invalid_record); return }
                    phase = Phase.SAVING
                    armWatchdog(30_000)
                    val measurement = ScaleMeasurement(userId = sessionUserId, dateTime = record.timestamp).also {
                        it[SYSTOLIC] = record.systolic
                        it[DIASTOLIC] = record.diastolic
                        it[MeasurementType.HEART_RATE] = Bpm(record.pulse)
                        // Explicit zero means "not detected"; the positive-quantity setter
                        // deliberately drops zero, so preserve these reported states in the map.
                        it.values[MOVEMENT] = if (record.movement) 1 else 0
                        it.values[IRREGULAR_HEARTBEAT] = if (record.irregularHeartbeat) 1 else 0
                    }
                    val session = generation
                    work = scope.launch {
                        try {
                            check(publishConfirmed(measurement))
                            inSession(session) {
                                ensureActive()
                                cursor++
                                nextRecord()
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { inSession(session) { fail(R.string.evolv_save_failed) } }
                    }
                }
                Phase.VERIFY_METADATA -> {
                    // No blind counter reset after a new measurement/ring advance during import.
                    if (!metadata.contentEquals(response.data)) { fail(R.string.evolv_pending_changed); return }
                    writeSettings(Phase.ACK, OmronEvolvLib.SETTINGS_WRITE, OmronEvolvLib.acknowledgment(response.data))
                }
                Phase.ACK -> end()
                Phase.END -> {
                    userInfo(R.string.evolv_sync_complete, cursor)
                    reset()
                    requestDisconnect()
                }
                else -> Unit
            }
        } catch (_: IllegalArgumentException) { fail(R.string.evolv_metadata_invalid) }
    }

    private fun nextRecord() {
        if (cursor < slots.size) {
            read(Phase.READ, OmronEvolvLib.RECORD_BASE + slots[cursor] * OmronEvolvLib.RECORD_SIZE, OmronEvolvLib.RECORD_SIZE)
        } else if (slots.isNotEmpty()) {
            read(Phase.VERIFY_METADATA, OmronEvolvLib.SETTINGS_READ, 8)
        } else end()
    }

    private fun end() { phase = Phase.END; send(OmronWlpFrame.endTransmission()) }
    private fun read(next: Phase, address: Int, size: Int) {
        phase = next; expectedAddress = address; expectedSize = size
        send(OmronWlpFrame.readEeprom(address, size))
    }
    private fun writeSettings(next: Phase, address: Int, payload: ByteArray) {
        phase = next; expectedAddress = address
        send(OmronEvolvLib.settingsWrite(address, payload))
    }

    /** Ordered notify lanes. Identical repeats are harmless; a new lane zero starts a fresh frame.
     * No notification can extend the transaction deadline; unrelated traffic cannot keep it alive.
     */
    private fun assemble(lane: Int, bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > OmronWlpFrame.CHANNEL_WIDTH) { lanes.fill(null); return null }
        if (lane == 0) {
            if (lanes[0]?.contentEquals(bytes) == true) return null
            lanes.fill(null)
        } else {
            if (lanes[0] == null) return null
            if (lanes[lane]?.contentEquals(bytes) == true) return null
            if ((0 until lane).any { lanes[it]?.size != 16 }) { lanes.fill(null); return null }
        }
        lanes[lane] = bytes.copyOf()
        val length = lanes[0]!![0].toInt() and 255
        if (length !in 8..64) { lanes.fill(null); return null }
        val count = OmronWlpFrame.channelsForFrame(length)
        if ((0 until count).any { lanes[it] == null }) return null
        val combined = (0 until count).flatMap { lanes[it]!!.asList() }.toByteArray()
        lanes.fill(null)
        return if (combined.size >= length) combined.copyOf(length) else null
    }

    private fun send(command: ByteArray) {
        lanes.fill(null)
        armWatchdog()
        OmronWlpFrame.toChannelChunks(command).forEachIndexed { i, chunk -> writeTo(service, tx[i], chunk) }
    }
    private fun armWatchdog(timeoutMs: Long = RESPONSE_TIMEOUT_MS) {
        watchdog?.cancel()
        val session = generation
        watchdog = scope.launch {
            delay(timeoutMs)
            inSession(session) { fail(R.string.bt_error_omron_timeout) }
        }
    }
    // BLE callbacks and adapter-scope save/timeout continuations may run on different threads.
    // Never let a cancelled session's late continuation mutate a new session or enqueue an ACK.
    private inline fun inSession(session: Long, action: () -> Unit) = synchronized(this) {
        if (session == generation && phase != Phase.IDLE) action()
    }
    @Synchronized
    private fun fail(message: Int) { userError(message); reset(); requestDisconnect() }
    @Synchronized
    private fun reset() {
        cancelPendingWrites()
        generation++
        watchdog?.cancel(); watchdog = null
        work?.cancel(); work = null
        pendingKey?.fill(0); pendingKey = null
        phase = Phase.IDLE
        cursor = 0
        sessionUserId = -1
        sessionKeySetting = ""
        metadata = byteArrayOf()
        slots = emptyList()
        lanes.fill(null)
    }
}
