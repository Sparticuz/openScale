package com.health.openscale.core.service

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scales.*
import com.health.openscale.core.data.*
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleConnectorT9140Test {
    @Test fun reconnectPreservesPendingFinalThroughSaveFacade() = exerciseSwitch(sameAddress = true)
    @Test fun deviceSwitchPreservesPendingFinalThroughSaveFacade() = exerciseSwitch(sameAddress = false)
    @Test fun queuedCompleteReadingIsDrainedWithoutDuplicatePersistence() =
        exerciseSwitch(sameAddress = true, completeReading = true)

    private fun exerciseSwitch(sameAddress: Boolean, completeReading: Boolean = false) = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = RoomTestSupport.inMemory(app)
        val repo = RoomTestSupport.repositoryFor(db)
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = RoomTestSupport.settingsFacadeFor(settingsScope,
            File(app.cacheDir, "connector-${System.nanoTime()}.preferences_pb"))
        val facades = RoomTestSupport.facadesFor(app, repo, settings)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        val userId = repo.insertUser(User(name = "test", birthDate = 0L,
            gender = GenderType.MALE, heightCm = 175f, activityLevel = ActivityLevel.MODERATE,
            useAssistedWeighing = false)).toInt()
        val profile = ScaleUser(id = userId, bodyHeight = 175f)
        settings.setCurrentUserId(userId)
        // Real handler + adapter callbacks/SharedFlow; only the radio transport is replaced.
        // Frozen protocol clock prevents a timeout from rescuing a broken teardown.
        val handler = EufyT9140Handler { 0L }
        val adapters = Channel<TestAdapter>(Channel.UNLIMITED)
        val created = mutableListOf<TestAdapter>()
        val connector = BleConnector(backgroundScope,
            ScaleFactory(app, settings, facades.measurementFacade, facades.userFacade),
            facades.measurementFacade, { userId }, createCommunicator = {
                TestAdapter(app, settings, facades, handler, profile,
                    StandardTestDispatcher(testScheduler)).also {
                    created += it
                    adapters.trySend(it)
                }
            })
        fun device(address: String) = ScannedDeviceInfo("eufy T9140", address, -50,
            emptyList(), null, isSupported = true)
        try {
            connector.connectToDevice(device("00:00:00:00:00:01"))
            val first = adapters.receive()
            first.allowConnection.complete(Unit)
            first.ready.await()
            runCurrent()
            first.finalWeight()
            assertTrue(facades.measurementFacade.getMeasurementsForUser(userId).first().isEmpty())
            if (completeReading) first.impedance()
            connector.connectToDevice(device(if (sameAddress) "00:00:00:00:00:01" else "00:00:00:00:00:02"))
            val second = adapters.receive()
            // Finish cancellation of the old handler before a new radio link is ready.
            runCurrent()
            second.allowConnection.complete(Unit)
            second.ready.await()
            runCurrent()
            val saved = withContext(Dispatchers.IO) {
                withTimeout(5000) {
                    facades.measurementFacade.getMeasurementsForUser(userId).first { rows ->
                        rows.any { row ->
                            row.values.any { it.type.key == MeasurementType.WEIGHT } &&
                                (!completeReading || row.values.any { it.type.key == MeasurementType.IMPEDANCE })
                        }
                    }
                }
            }
            assertEquals(1, saved.size)
            val weight = saved.single().values.single { it.type.key == MeasurementType.WEIGHT }
            assertEquals(102.1f, weight.value.floatValue!!, 0.001f)
            assertEquals(completeReading,
                saved.single().values.any { it.type.key == MeasurementType.IMPEDANCE })
            // A late duplicate disconnect/cancellation of the old handler must not save again.
            first.close()
            runCurrent()
            assertEquals(1, facades.measurementFacade.getMeasurementsForUser(userId).first().size)
        } finally {
            created.forEach { it.close() }
            created.forEach { it.awaitClosed() }
            backgroundScope.coroutineContext[Job]!!.children.toList().forEach { it.cancelAndJoin() }
            settingsScope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }

    private class TestAdapter(
        app: Application,
        settings: com.health.openscale.core.facade.SettingsFacade,
        facades: RoomTestSupport.Facades,
        handler: EufyT9140Handler,
        private val profile: ScaleUser,
        private val handlerDispatcher: CoroutineDispatcher,
    ) : ModernScaleAdapter(app, settings, facades.measurementFacade, facades.userFacade, handler) {
        val ready = CompletableDeferred<Unit>()
        val allowConnection = CompletableDeferred<Unit>()
        init { selectedUserSnapshot = profile }
        override fun doConnect(address: String, selectedUser: ScaleUser) {
            scope.launch {
            allowConnection.await()
            handler.attach(object : ScaleDeviceHandler.Transport {
                override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
                override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
                override fun read(service: UUID, characteristic: UUID) = Unit
                override fun disconnect() = Unit
                override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
            }, appCallbacks, object : ScaleDeviceHandler.DriverSettings {
                override fun getInt(key: String, default: Int) = default
                override fun putInt(key: String, value: Int) = Unit
                override fun getString(key: String, default: String?) = default
                override fun putString(key: String, value: String) = Unit
                override fun remove(key: String) = Unit
            }, object : ScaleDeviceHandler.DataProvider {
                override fun currentUser() = profile
                override fun usersForDevice() = listOf(profile)
                override fun lastMeasurementFor(userId: Int) = null
            }, CoroutineScope(scope.coroutineContext + handlerDispatcher))
            handler.handleConnected(profile)
            ready.complete(Unit)
            }
        }
        fun finalWeight() = handler.handleNotification(
            UUID.fromString("4143f6b2-5300-4900-4700-414943415245"),
            byteArrayOf(0xac.toByte(), 2, 3, 0xfd.toByte(), 0, 0, 0xca.toByte(), 0xca.toByte()))
        fun impedance() = handler.handleNotification(
            UUID.fromString("4143f6b2-5300-4900-4700-414943415245"),
            byteArrayOf(0xac.toByte(), 2, 0xfd.toByte(), 1, 2, 0xb3.toByte(), 0xcb.toByte(), 0x7e))
        suspend fun awaitClosed() = scope.coroutineContext[Job]!!.join()
        override fun doDisconnect() = Unit
        @Composable override fun DeviceConfigurationUi() = Unit
    }
}
