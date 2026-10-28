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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.OmronWlpFrame
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OmronEvolvHandlerTest {
    private val user = ScaleUser(id = 7)
    private val stored = mutableMapOf("pairingKey_00:11:22:33:44:55" to "000102030405060708090a0b0c0d0e0f") // synthetic
    private val writes = mutableListOf<Pair<UUID, ByteArray>>()
    private val published = mutableListOf<ScaleMeasurement>()
    private var disconnected = false
    private var saveResult = true
    private var uncancellableSave = false
    private var receipt: CompletableDeferred<Boolean>? = null
    private var settingWriteSuccess = true
    private var failSettingPrefix: String? = null
    private val settings = object : ScaleDeviceHandler.DriverSettings {
        override fun getString(key: String, default: String?) = stored[key] ?: default
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) = Unit
        override fun putString(key: String, value: String) { stored[key] = value }
        override suspend fun putStringDurably(key: String, value: String): Boolean {
            if (!settingWriteSuccess || failSettingPrefix?.let { key.startsWith(it) } == true) return false
            stored[key] = value
            return true
        }
        override fun remove(key: String) { stored.remove(key) }
    }
    private val transport = object : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) { writes += characteristic to payload }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() { disconnected = true }
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        override fun deviceAddress() = "00:11:22:33:44:55"
    }
    private val callbacks = object : ScaleDeviceHandler.Callbacks {
        override fun onPublish(measurement: ScaleMeasurement) { error("Unconfirmed publication is not safe for ACK") }
        override suspend fun onPublishConfirmed(measurement: ScaleMeasurement): Boolean {
            published += measurement
            return if (uncancellableSave) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                receipt?.await() ?: saveResult
            } else receipt?.await() ?: saveResult
        }
        override fun resolveString(resId: Int, vararg args: Any) = ""
    }
    private val data = object : ScaleDeviceHandler.DataProvider {
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
    private fun response(type: Int, address: Int = 0, payload: ByteArray = byteArrayOf(), result: Int = 0): ByteArray {
        val head = byteArrayOf((payload.size + 8).toByte(), (type shr 8).toByte(), type.toByte(),
            (address shr 8).toByte(), address.toByte(), payload.size.toByte()) + payload + result.toByte()
        return head + OmronWlpFrame.bcc(head)
    }
    private fun reply(h: OmronEvolvHandler, type: Int, address: Int = 0, payload: ByteArray = byteArrayOf(), result: Int = 0) {
        OmronWlpFrame.toChannelChunks(response(type, address, payload, result)).forEachIndexed { i, chunk ->
            h.handleNotification(OmronWlcHandler.CHR_RX[i], chunk)
        }
    }
    private fun start(h: OmronEvolvHandler) {
        h.handleConnected(user)
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, ByteArray(17).also { it[0] = 0x81.toByte() })
        reply(h, OmronWlpFrame.TYPE_START)
    }
    private fun metadata(pointer: Int = 1, pending: Int = 1) = byteArrayOf(0, pointer.toByte(), 0, 0, 0, pending.toByte(), 0x80.toByte(), 0)
    private fun clock(h: OmronEvolvHandler, meta: ByteArray = metadata()) {
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, meta)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0274, ByteArray(10))
        reply(h, OmronWlpFrame.TYPE_WRITE, 0x029a)
    }
    private fun record(second: Int = 30) = byteArrayOf(80, 95, 24, 70, 0x0b, 0xad.toByte(), 0x0b, (0x40 + second).toByte(), 0, 0, 0, 0, 0, 0)
    private fun commands(): List<ByteArray> {
        val chunks = writes.filter { it.first != OmronWlcHandler.CHR_UNLOCK }.map { it.second }
        val result = mutableListOf<ByteArray>()
        var frame = byteArrayOf()
        for (chunk in chunks) {
            frame += chunk
            if (frame.size >= (frame[0].toInt() and 255)) { result += frame; frame = byteArrayOf() }
        }
        return result
    }
    private fun lastAddress(): Int = commands().last().let { ((it[3].toInt() and 255) shl 8) or (it[4].toInt() and 255) }
    private fun ackWrites() = commands().count { it[2] == 0xc0.toByte() && it[3] == 2.toByte() && it[4] == 0x86.toByte() }

    @Test fun `pending sync waits for durable receipt then rereads metadata before acknowledging`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h)
        assertThat(lastAddress()).isEqualTo(0x0260)
        clock(h)
        assertThat(lastAddress()).isEqualTo(0x02ac)
        receipt = CompletableDeferred()
        reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record())
        runCurrent()
        assertThat(published).hasSize(1)
        assertThat(ackWrites()).isEqualTo(0)
        receipt!!.complete(true)
        runCurrent()
        assertThat(lastAddress()).isEqualTo(0x0260)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, metadata())
        assertThat(ackWrites()).isEqualTo(1)
        reply(h, OmronWlpFrame.TYPE_WRITE, 0x0286)
        reply(h, OmronWlpFrame.TYPE_END)
        assertThat(disconnected).isTrue()
        assertThat(published.single().userId).isEqualTo(7)
        assertThat(published.single()[OmronEvolvHandler.SYSTOLIC]).isEqualTo(120)
        assertThat(published.single().hasWeight()).isFalse()
    }
    @Test fun `every decoded status including zero participates in receipt before ACK`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        for ((second, flags) in listOf(30 to 0, 31 to 0xc0)) {
            start(h); clock(h)
            reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record(second).also {
                it[4] = (it[4].toInt() or flags).toByte()
            })
            runCurrent()
            val m = published.last()
            assertThat(m.values).hasSize(5)
            assertThat(m.values.entries.single { it.key.identity == "ble.blood_pressure.movement" }.value)
                .isEqualTo(if (flags == 0) 0 else 1)
            assertThat(m.values.entries.single { it.key.identity == "ble.blood_pressure.irregular_heartbeat" }.value)
                .isEqualTo(if (flags == 0) 0 else 1)
            h.handleDisconnected()
        }
    }
    @Test fun `failed persistence never acknowledges pending records`() = runTest {
        saveResult = false
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h); clock(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record())
        runCurrent()
        assertThat(disconnected).isTrue()
        assertThat(ackWrites()).isEqualTo(0)
    }

    @Test fun `cancelled receipt times out without ACK and reconnect ignores late save completion`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h); clock(h)
        receipt = CompletableDeferred()
        reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record()); runCurrent()
        receipt!!.cancel(); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        assertThat(ackWrites()).isEqualTo(0)
        assertThat(disconnected).isTrue()
        disconnected = false
        uncancellableSave = true
        receipt = CompletableDeferred()
        start(h); clock(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record()); runCurrent()
        val oldReceipt = receipt!!
        h.handleDisconnected()
        start(h)
        oldReceipt.complete(true); runCurrent()
        assertThat(lastAddress()).isEqualTo(0x0260)
        assertThat(ackWrites()).isEqualTo(0)
        assertThat(disconnected).isFalse()
    }

    @Test fun `wrapped pending range is saved sequentially and new measurement prevents ACK`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h); clock(h, metadata(2, 4))
        for ((n, slot) in listOf(98, 99, 0, 1).withIndex()) {
            val address = 0x02ac + slot * 14
            assertThat(lastAddress()).isEqualTo(address)
            reply(h, OmronWlpFrame.TYPE_READ, address, record(30 + n)); runCurrent()
        }
        assertThat(published).hasSize(4)
        assertThat(lastAddress()).isEqualTo(0x0260)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, metadata(3, 5))
        assertThat(disconnected).isTrue()
        assertThat(ackWrites()).isEqualTo(0)
    }

    @Test fun `invalid or unset historic timestamp remains pending despite setting future clock`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h); clock(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record().also { it[4] = 0 })
        runCurrent()
        assertThat(published).isEmpty()
        assertThat(disconnected).isTrue()
        assertThat(ackWrites()).isEqualTo(0)
    }

    @Test fun `no pending readings synchronizes clock without history reads or counter write`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h); clock(h, metadata(10, 0))
        assertThat(commands().last()[1]).isEqualTo(15.toByte())
        reply(h, OmronWlpFrame.TYPE_END)
        assertThat(disconnected).isTrue()
        assertThat(published).isEmpty()
        assertThat(ackWrites()).isEqualTo(0)
    }

    @Test fun `explicit setup generates and stores device key before programming then verifies unlock`() = runTest {
        stored.clear()
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user)
        assertThat(writes).isEmpty()
        stored["pairOnNextConnection"] = "yes"
        disconnected = false
        h.handleConnected(user); runCurrent()
        assertThat(writes.single().second[0]).isEqualTo(2.toByte())
        assertThat(stored["pairOnNextConnection"]).isEmpty()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x82.toByte(), 0)); runCurrent()
        val candidate = OmronEvolvHandler.parseKey(stored["pairingKey_00:11:22:33:44:55"])!!
        assertThat(candidate.size).isEqualTo(16)
        assertThat(writes.last().second.contentEquals(OmronWlpFrame.writeKey(candidate))).isTrue()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x80.toByte(), 0))
        assertThat(writes.last().second.contentEquals(OmronWlpFrame.unlock(candidate))).isTrue()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x81.toByte(), 0))
        assertThat(commands().single().contentEquals(OmronWlpFrame.startTransmission())).isTrue()
    }

    @Test fun `retained pairing state observes consumption and failed setup can rearm`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        assertThat(h.pairingArmed).isFalse()
        assertThat(h.togglePairing()).isTrue()
        // The retained UI reads this same snapshot state; consuming authorization must
        // invalidate its read without recreating the handler or composition.
        var invalidated = false
        val observer = androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
        observer.start()
        observer.observeReads(Unit, { _: Unit -> invalidated = true }) {
            assertThat(h.pairingArmed).isTrue()
        }
        h.handleConnected(user); runCurrent()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertThat(invalidated).isTrue()
        observer.stop()
        assertThat(h.pairingArmed).isFalse()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0, 0))
        assertThat(disconnected).isTrue()
        assertThat(h.togglePairing()).isTrue()
        assertThat(h.pairingArmed).isTrue()
        assertThat(stored[OmronEvolvHandler.PAIR_SETTING]).isEqualTo("yes")
    }
    @Test fun `failed durable key save never programs device`() = runTest {
        stored.clear(); stored["pairOnNextConnection"] = "yes"
        failSettingPrefix = "pairingKey_"
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user); runCurrent()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x82.toByte(), 0)); runCurrent()
        assertThat(writes).hasSize(1)
        assertThat(writes.single().second[0]).isEqualTo(2.toByte())
        assertThat(disconnected).isTrue()
    }

    @Test fun `failed pairing authorization persistence sends no commands and failed programming retains retry key`() = runTest {
        stored["pairOnNextConnection"] = "yes"
        settingWriteSuccess = false
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user); runCurrent()
        assertThat(writes).isEmpty()
        settingWriteSuccess = true; disconnected = false
        h.handleConnected(user); runCurrent()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x82.toByte(), 0)); runCurrent()
        val candidate = stored["pairingKey_00:11:22:33:44:55"]
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x80.toByte(), 1))
        assertThat(disconnected).isTrue()
        h.handleConnected(user)
        assertThat(writes.last().second[0]).isEqualTo(1.toByte())
        assertThat(stored["pairingKey_00:11:22:33:44:55"] == candidate).isTrue()
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x81.toByte(), 1))
        assertThat(writes.count { it.second[0] == 2.toByte() }).isEqualTo(1)
    }

    @Test fun `keys are scoped to physical device and rejected unlock does not implicitly replace key`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(object : ScaleDeviceHandler.Transport by transport {
            override fun deviceAddress() = "00:11:22:33:44:66"
        }, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user)
        assertThat(writes).isEmpty()
        h.attach(transport, callbacks, settings, data, backgroundScope)
        h.handleConnected(user)
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x81.toByte(), 1))
        assertThat(writes).hasSize(1)
        assertThat(disconnected).isTrue()
    }

    @Test fun `invalid clock checksum and invalid metadata never produce settings writes`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, metadata())
        reply(h, OmronWlpFrame.TYPE_READ, 0x0274, ByteArray(10).also { it[9] = 1 })
        assertThat(disconnected).isTrue()
        assertThat(commands().none { it[2] == 0xc0.toByte() }).isTrue()
        disconnected = false
        start(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, metadata(101, 1))
        assertThat(disconnected).isTrue()
        assertThat(commands().none { it[2] == 0xc0.toByte() }).isTrue()
    }

    @Test fun `malformed frames stale addresses and unrelated traffic cannot advance or extend deadline`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h); runCurrent()
        advanceTimeBy(4_000)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0261, metadata())
        val bad = response(OmronWlpFrame.TYPE_READ, 0x0260, metadata()).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        h.handleNotification(OmronWlcHandler.CHR_RX[0], bad)
        h.handleNotification(UUID(0, 0), byteArrayOf(0))
        assertThat(lastAddress()).isEqualTo(0x0260)
        advanceTimeBy(4_001); runCurrent()
        assertThat(disconnected).isTrue()
        assertThat(published).isEmpty()
    }

    @Test fun `initial subscription allowance is bounded and disconnect discards partial response`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user); runCurrent()
        advanceTimeBy(10_801); runCurrent()
        assertThat(disconnected).isFalse()
        advanceTimeBy(8_000); runCurrent()
        assertThat(disconnected).isTrue()
        disconnected = false
        start(h)
        val frame = response(OmronWlpFrame.TYPE_READ, 0x0260, metadata())
        h.handleNotification(OmronWlcHandler.CHR_RX[0], frame.copyOfRange(0, 8))
        h.handleDisconnected()
        start(h)
        h.handleNotification(OmronWlcHandler.CHR_RX[1], frame.copyOfRange(8, frame.size))
        assertThat(lastAddress()).isEqualTo(0x0260)
        h.handleDisconnected(); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertThat(disconnected).isFalse()
    }

    @Test fun `missing GATT channels fail before any writes`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(object : ScaleDeviceHandler.Transport by transport {
            override fun hasCharacteristic(service: UUID, characteristic: UUID) = false
        }, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user)
        assertThat(writes).isEmpty()
        assertThat(disconnected).isTrue()
    }

    @Test fun `queued conservative subscription setup does not prematurely time out first unlock`() = runTest {
        val queue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        backgroundScope.launch { for (op in queue) op() }
        val queued = object : ScaleDeviceHandler.Transport by transport {
            override fun setNotifyOn(service: UUID, characteristic: UUID) {
                queue.trySend { kotlinx.coroutines.delay(2_160) }
            }
            override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
                queue.trySend { transport.write(service, characteristic, payload, withResponse) }
            }
        }
        val h = OmronEvolvHandler().also { it.attach(queued, callbacks, settings, data, backgroundScope) }
        h.handleConnected(user); runCurrent()
        advanceTimeBy(10_800); runCurrent()
        assertThat(disconnected).isFalse()
        assertThat(writes.single().first).isEqualTo(OmronWlcHandler.CHR_UNLOCK)
        h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x81.toByte(), 0))
        runCurrent(); advanceTimeBy(8_001); runCurrent()
        assertThat(disconnected).isTrue()
    }

    @Test fun `reset cancels ACK already queued in physical pacing delay before reconnect`() = runTest {
        val fence = QueuedWriteSession()
        val queue = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var pause: CompletableDeferred<Unit>? = null
        backgroundScope.launch { for (op in queue) op() }
        val queued = object : ScaleDeviceHandler.Transport by transport {
            override fun cancelPendingWrites() { fence.invalidate() }
            override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
                val token = fence.token()
                queue.trySend { fence.write(token, { pause?.await() }) {
                    transport.write(service, characteristic, payload, withResponse)
                } }
            }
        }
        val h = OmronEvolvHandler().also { it.attach(queued, callbacks, settings, data, backgroundScope) }
        start(h); clock(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x02ac, record()); runCurrent()
        pause = CompletableDeferred()
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, metadata()); runCurrent()
        // Protocol failure resets before the asynchronous platform disconnect callback.
        reply(h, OmronWlpFrame.TYPE_WRITE, 0x0286, result = 1)
        start(h)
        pause.complete(Unit); runCurrent()
        assertThat(ackWrites()).isEqualTo(0)
        assertThat(lastAddress()).isEqualTo(0x0260)
    }
    @Test fun `rejected clock write never reads or acknowledges records`() = runTest {
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, settings, data, backgroundScope) }
        start(h)
        reply(h, OmronWlpFrame.TYPE_READ, 0x0260, metadata())
        reply(h, OmronWlpFrame.TYPE_READ, 0x0274, ByteArray(10))
        reply(h, OmronWlpFrame.TYPE_WRITE, 0x029a, result = 1)
        assertThat(disconnected).isTrue()
        assertThat(published).isEmpty()
        assertThat(ackWrites()).isEqualTo(0)
    }

    @Test fun `registry recognizes only known Evolv profile`() {
        for (name in listOf("BLESmart_0000011F001122334455", "BP7000", "hem-7600t")) {
            val device = ScannedDeviceInfo(name, "00:11:22:33:44:55", 0, emptyList(), null)
            assertThat(ScaleFactory.createHandlers().first { it.supportFor(device) != null }).isInstanceOf(OmronEvolvHandler::class.java)
        }
        for (name in listOf("", "EVOLV", "HEM-7322T", "BLEsmart_00000221001122334455", "BLESmart_0000011Fnot-an-address")) {
            assertThat(OmronEvolvHandler.matches(name)).isFalse()
        }
    }

}

/** Actual handler -> connector -> facade -> Room receipt -> counter ACK, including flags. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class OmronEvolvDurableFlagsTest {
    @Test fun `all decoded fields are durable and a status-only collision cannot ACK`() = kotlinx.coroutines.runBlocking {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = com.health.openscale.testutil.RoomTestSupport.inMemory(app)
        val repo = com.health.openscale.testutil.RoomTestSupport.repositoryFor(db)
        val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        repo.insertAllMeasurementTypes(com.health.openscale.core.data.MeasurementType.seedRows())
        val settings = com.health.openscale.testutil.RoomTestSupport.settingsFacadeFor(ioScope,
            java.io.File(app.cacheDir, "evolv-flags-${System.nanoTime()}.preferences_pb"))
        val facades = com.health.openscale.testutil.RoomTestSupport.facadesFor(app, repo, settings)
        val uid = repo.insertUser(com.health.openscale.core.data.User(name = "Synthetic", birthDate = 0,
            gender = com.health.openscale.core.data.GenderType.MALE, heightCm = 175f,
            activityLevel = com.health.openscale.core.data.ActivityLevel.MODERATE, useAssistedWeighing = false)).toInt()
        val events = kotlinx.coroutines.flow.MutableSharedFlow<com.health.openscale.core.bluetooth.BluetoothEvent>(extraBufferCapacity = 16)
        val comm = object : com.health.openscale.core.bluetooth.ScaleCommunicator {
            override val isConnecting = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val isConnected = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun connect(address: String) = Unit
            override fun disconnect() = Unit
            override fun requestMeasurement() = Unit
            @androidx.compose.runtime.Composable override fun DeviceConfigurationUi() = Unit
            override fun getEventsFlow() = events
            override suspend fun processUserInteractionFeedback(
                interactionType: com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType,
                appUserId: Int, feedbackData: Any) = Unit
        }
        val device = ScannedDeviceInfo("Synthetic", "00:11:22:33:44:55", 0, emptyList(), null, isSupported = true)
        val connector = com.health.openscale.core.service.BleConnector(ioScope,
            ScaleFactory(app, settings, facades.measurementFacade, facades.userFacade), facades.measurementFacade,
            { uid }, { comm })
        val writes = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val receipts = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val failures = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val stored = mutableMapOf("pairingKey_00:11:22:33:44:55" to "000102030405060708090a0b0c0d0e0f")
        val driver = object : ScaleDeviceHandler.DriverSettings {
            override fun getString(key: String, default: String?) = stored[key] ?: default
            override fun getInt(key: String, default: Int) = default
            override fun putInt(key: String, value: Int) = Unit
            override fun putString(key: String, value: String) { stored[key] = value }
            override fun remove(key: String) { stored.remove(key) }
        }
        val transport = object : ScaleDeviceHandler.Transport {
            override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
            override fun read(service: UUID, characteristic: UUID) = Unit
            override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
                if (characteristic != OmronWlcHandler.CHR_UNLOCK) writes += payload
            }
            override fun disconnect() = Unit
            override fun deviceAddress() = device.address
            override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
        }
        val user = ScaleUser(id = uid)
        val data = object : ScaleDeviceHandler.DataProvider {
            override fun currentUser() = user
            override fun usersForDevice() = listOf(user)
            override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
        }
        val callbacks = object : ScaleDeviceHandler.Callbacks {
            override fun onPublish(measurement: ScaleMeasurement) { error("must confirm") }
            override fun onError(resId: Int, t: Throwable?, vararg args: Any) { failures.trySend(Unit) }
            override fun resolveString(resId: Int, vararg args: Any) = ""
            override suspend fun onPublishConfirmed(measurement: ScaleMeasurement): Boolean {
                val receipt = CompletableDeferred<Boolean>()
                events.emit(com.health.openscale.core.bluetooth.BluetoothEvent.MeasurementReceived(measurement, device.address, receipt))
                val saved = receipt.await()
                receipts.send(saved)
                return saved
            }
        }
        val h = OmronEvolvHandler().also { it.attach(transport, callbacks, driver, data, ioScope) }
        fun reply(type: Int, address: Int = 0, payload: ByteArray = byteArrayOf()) {
            val head = byteArrayOf((payload.size + 8).toByte(), (type shr 8).toByte(), type.toByte(),
                (address shr 8).toByte(), address.toByte(), payload.size.toByte()) + payload + 0.toByte()
            OmronWlpFrame.toChannelChunks(head + OmronWlpFrame.bcc(head)).forEachIndexed { i, bytes ->
                h.handleNotification(OmronWlcHandler.CHR_RX[i], bytes)
            }
        }
        val metadata = byteArrayOf(0xc0.toByte(), 1, 0x80.toByte(), 0, 0, 1, 0x80.toByte(), 0)
        suspend fun sync(flags: Int): Boolean {
            writes.clear()
            h.handleConnected(user)
            h.handleNotification(OmronWlcHandler.CHR_UNLOCK, byteArrayOf(0x81.toByte(), 0))
            reply(OmronWlpFrame.TYPE_START)
            reply(OmronWlpFrame.TYPE_READ, 0x0260, metadata)
            reply(OmronWlpFrame.TYPE_READ, 0x0274, ByteArray(10))
            reply(OmronWlpFrame.TYPE_WRITE, 0x029a)
            reply(OmronWlpFrame.TYPE_READ, 0x02ac,
                byteArrayOf(80, 95, 24, 70, (0x0b or flags).toByte(), 0xad.toByte(), 0x0b, 0x5e, 0, 0, 0, 0, 0, 0))
            val confirmed = kotlinx.coroutines.withTimeout(5000) { receipts.receive() }
            if (!confirmed) kotlinx.coroutines.withTimeout(5000) { failures.receive() }
            // Wait until the continuation has requested verification or reset on failure.
            kotlinx.coroutines.withTimeout(5000) {
                while (confirmed && synchronized(writes) { writes.count {
                    it.size >= 6 && it[2] == 0.toByte() && it[4] == 0x60.toByte() } < 2 }) kotlinx.coroutines.delay(1)
            }
            reply(OmronWlpFrame.TYPE_READ, 0x0260, metadata)
            // The handler monitor serializes the save continuation and this response.
            return synchronized(writes) { writes.any {
                it.size >= 6 && it[2] == 0xc0.toByte() && it[4] == 0x86.toByte() } }
        }
        try {
            connector.connectToDevice(device)
            kotlinx.coroutines.withTimeout(5000) { events.subscriptionCount.first { it > 0 } }
            assertThat(sync(0)).isTrue()
            assertThat(sync(0)).isTrue()
            assertThat(sync(0x80)).isFalse() // movement only
            assertThat(sync(0x40)).isFalse() // irregular heartbeat only
            assertThat(sync(0xc0)).isFalse()
            val saved = repo.getMeasurementsWithValuesForUser(uid).first().single()
            assertThat(saved.measurement.userId).isEqualTo(uid)
            assertThat(saved.values).hasSize(5)
            val byIdentity = saved.values.associate { it.type.identity to it.value.intValue }
            assertThat(byIdentity[OmronEvolvHandler.SYSTOLIC.identity]).isEqualTo(120)
            assertThat(byIdentity[OmronEvolvHandler.DIASTOLIC.identity]).isEqualTo(80)
            assertThat(byIdentity[com.health.openscale.core.data.MeasurementType.HEART_RATE.identity]).isEqualTo(70)
            assertThat(byIdentity[OmronEvolvHandler.MOVEMENT.identity]).isEqualTo(0)
            assertThat(byIdentity[OmronEvolvHandler.IRREGULAR_HEARTBEAT.identity]).isEqualTo(0)
            val expected = java.util.Calendar.getInstance().apply { clear(); set(2024, 1, 29, 13, 45, 30) }.timeInMillis
            assertThat(saved.measurement.timestamp).isEqualTo(expected)
        } finally {
            h.handleDisconnected()
            connector.close()
            ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.close()
        }
    }
}
