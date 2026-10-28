package com.health.openscale.core.bluetooth.scales

import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class EufyT9140HandlerTest {
    private val service = UUID.fromString("4143f6b0-5300-4900-4700-414943415245")
    private val notify = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun profile() = ScaleUser(id = 7, bodyHeight = 175f, birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -41) }.time)

    @Test fun recognizesOnlyT9140AndDoesNotClaimHistoryOrOtherFamilies() {
        val h = EufyT9140Handler()
        val support = h.supportFor(device("eufy T9140 V2", service))!!
        assertTrue(DeviceCapability.BODY_COMPOSITION in support.implemented)
        assertFalse(DeviceCapability.HISTORY_READ in support.implemented)
        for (name in listOf("eufy T9148", "eufy T9130", "eufy T91400", "MGB")) {
            assertNull(h.supportFor(device(name, service)))
        }
    }

    @Test fun capturedFramesMapToCorrectSemanticUnitsAndPublishOnlyOnce() = runTest {
        val s = setup(profile())
        s.h.handleConnected(s.user)
        assertEquals(listOf(service to notify), s.transport.notifications)
        assertEquals(0, s.transport.writes)
        s.h.handleNotification(UUID.randomUUID(), hex("ac0203fd0000caca"))
        s.h.handleNotification(notify, hex("ac0203fd0000caca"))
        assertTrue(s.published.isEmpty())
        s.h.handleNotification(notify, hex("ac02fd0102b3cb7eac0203fd0000caca"))
        val m = s.published.single()
        assertEquals(7, m.userId)
        assertEquals(7, m.compositionProfileUserId)
        assertEquals(102.1f, m[MeasurementType.WEIGHT]!!.value, 0.001f)
        assertEquals(691f, m[MeasurementType.IMPEDANCE]!!.value, 0f)
        assertEquals(32.3f, m[MeasurementType.BODY_FAT]!!.value, 0.001f)
        assertEquals(49.4f, m[MeasurementType.WATER]!!.value, 0.001f)
        assertEquals(37.1f, m[MeasurementType.MUSCLE]!!.value, 0.001f)
        assertEquals(8f, m[MeasurementType.BONE]!!.value, 0.001f)
        assertEquals(69.1f, m[MeasurementType.LBM]!!.value, 0.001f)
        assertEquals(3075.4f, m[MeasurementType.BMR]!!.value, 0.001f)
        assertEquals(16.6f, m[MeasurementType.VISCERAL_FAT]!!, 0.001f)
        advanceTimeBy(4000)
        s.h.handleNotification(notify, hex("ac0203fd0000caca"))
        assertEquals(1, s.published.size)
        s.h.handleDisconnected()
    }

    @Test fun timeoutAndDisconnectGiveWeightOnlyAndResetBetweenConnections() = runTest {
        val s = setup(profile())
        s.h.handleConnected(s.user)
        s.h.handleNotification(notify, hex("ac0203fd0000caca"))
        advanceTimeBy(3001)
        assertEquals(1, s.published.size)
        assertNull(s.published.single()[MeasurementType.BODY_FAT])
        s.h.handleDisconnected()
        s.h.handleConnected(s.user)
        s.h.handleNotification(notify, hex("ac0203fd0000cacaac02fd01"))
        s.h.handleDisconnected()
        assertEquals(2, s.published.size)
        s.h.handleConnected(s.user)
        s.h.handleNotification(notify, hex("ac0203fd0000cacaac02fd01029fcb6a"))
        assertEquals(3, s.published.size)
        assertEquals(671f, s.published.last()[MeasurementType.IMPEDANCE]!!.value, 0f)
        s.h.handleDisconnected()
        advanceTimeBy(5000)
        assertEquals(3, s.published.size)
    }

    @Test fun absentOrInvalidProfileStillPublishesWeightAndRawImpedanceWithoutComposition() = runTest {
        for (user in listOf(ScaleUser(), profile().copy(bodyHeight = Float.NaN))) {
            val s = setup(user)
            s.h.handleConnected(user)
            s.h.handleNotification(notify, hex("ac0203fd0000cacaac02fd0102b3cb7e"))
            val m = s.published.single()
            assertNotNull(m[MeasurementType.WEIGHT])
            assertNotNull(m[MeasurementType.IMPEDANCE])
            assertNull(m[MeasurementType.BODY_FAT])
            assertNull(m[MeasurementType.BONE])
            s.h.handleDisconnected()
        }
    }

    @Test fun missingCharacteristicDoesNotSubscribeOrWrite() = runTest {
        val s = setup(profile())
        s.transport.available = false
        s.h.handleConnected(s.user)
        assertTrue(s.transport.notifications.isEmpty())
        assertEquals(0, s.transport.writes)
    }

    @Test fun scopeCancellationPreservesPendingFinalWithoutADisconnectCallback() = runTest {
        val s = setup(profile())
        s.h.handleConnected(s.user)
        s.h.handleNotification(notify, hex("ac0203fd0000caca"))
        backgroundScope.coroutineContext.cancelChildren()
        runCurrent()
        assertEquals(102.1f, s.published.single()[MeasurementType.WEIGHT]!!.value, 0.001f)
        s.h.handleDisconnected()
        assertEquals(1, s.published.size)
    }

    private data class Setup(val h: EufyT9140Handler, val user: ScaleUser, val transport: RecordingTransport, val published: MutableList<ScaleMeasurement>)
    private fun TestScope.setup(user: ScaleUser): Setup {
        val h = EufyT9140Handler { testScheduler.currentTime }
        val transport = RecordingTransport()
        val published = mutableListOf<ScaleMeasurement>()
        h.attach(transport, object : ScaleDeviceHandler.Callbacks {
            override fun onPublish(measurement: ScaleMeasurement) { published += measurement.snapshot() }
            override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
        }, object : ScaleDeviceHandler.DriverSettings {
            override fun getInt(key: String, default: Int) = default
            override fun putInt(key: String, value: Int) = Unit
            override fun getString(key: String, default: String?) = default
            override fun putString(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
        }, object : ScaleDeviceHandler.DataProvider {
            override fun currentUser() = user
            override fun usersForDevice() = listOf(user)
            override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
        }, backgroundScope)
        return Setup(h, user, transport, published)
    }

    private class RecordingTransport : ScaleDeviceHandler.Transport {
        var available = true
        var writes = 0
        val notifications = mutableListOf<Pair<UUID, UUID>>()
        override fun setNotifyOn(service: UUID, characteristic: UUID) { notifications += service to characteristic }
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) { writes++ }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = available
    }
}
