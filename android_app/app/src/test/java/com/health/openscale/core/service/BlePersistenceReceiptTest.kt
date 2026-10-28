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
package com.health.openscale.core.service

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.*
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.data.*
import com.health.openscale.core.database.*
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlePersistenceReceiptTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var connector: BleConnector
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var selected = 0
    private var user = 0
    private val pressure = MeasurementType.deviceInt("test.systolic", R.string.measurement_type_custom_default_name, UnitType.MMHG)
    private val events = MutableSharedFlow<BluetoothEvent>(extraBufferCapacity = 16)
    private val device = ScannedDeviceInfo("Synthetic", "00:00:00:00:00:01", 0, emptyList(), null, isSupported = true)
    private val comm = object : ScaleCommunicator, AutoCloseable {
        override val isConnecting = MutableStateFlow(false)
        override val isConnected = MutableStateFlow(true)
        override fun connect(address: String) = Unit
        override fun disconnect() = Unit
        override fun requestMeasurement() = Unit
        @Composable override fun DeviceConfigurationUi() = Unit
        override fun getEventsFlow() = events.asSharedFlow()
        override suspend fun processUserInteractionFeedback(interactionType: BluetoothEvent.UserInteractionType, appUserId: Int, feedbackData: Any) = Unit
        override fun close() = Unit
    }
    @Before fun setup(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        val settings = RoomTestSupport.settingsFacadeFor(scope, File(app.cacheDir, "receipt-${System.nanoTime()}.preferences_pb"))
        val facades = RoomTestSupport.facadesFor(app, repo, settings)
        user = repo.insertUser(User(name = "Synthetic", birthDate = 0, gender = GenderType.MALE,
            heightCm = 175f, activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false)).toInt()
        selected = user
        connector = BleConnector(scope, ScaleFactory(app, settings, facades.measurementFacade, facades.userFacade),
            facades.measurementFacade, { selected }, { comm })
        connector.connectToDevice(device)
        withTimeout(5000) { events.subscriptionCount.first { it > 0 } }
    }
    @After fun cleanup() { connector.close(); scope.cancel(); db.close() }
    private suspend fun send(time: Long = 1000, value: Int = 120): Boolean {
        val receipt = CompletableDeferred<Boolean>()
        val m = ScaleMeasurement(userId = user, dateTime = Date(time)).also { it[pressure] = value }
        events.emit(BluetoothEvent.MeasurementReceived(m, device.address, receipt))
        return withTimeout(5000) { receipt.await() }
    }
    @Test fun `real connector confirms durable values and exact replay but refuses conflicting duplicate`() = runBlocking {
        assertThat(send()).isTrue()
        assertThat(send()).isTrue()
        assertThat(send(value = 130)).isFalse()
        assertThat(repo.getMeasurementsWithValuesForUser(user).first().single().values.single().value.intValue).isEqualTo(120)
    }
    @Test fun `disabled internal zero status values persist and flag collisions refuse confirmation`() = runBlocking {
        val movement = MeasurementType.deviceInt("test.movement", R.string.measurement_type_custom_default_name,
            enabled = false, isInternal = true)
        val heartbeat = MeasurementType.deviceInt("test.irregular_heartbeat", R.string.measurement_type_custom_default_name,
            enabled = false, isInternal = true)
        suspend fun flagged(time: Long, flag: Int): Boolean {
            val receipt = CompletableDeferred<Boolean>()
            val m = ScaleMeasurement(userId = user, dateTime = Date(time)).also {
                it[pressure] = 120
                // Zero is a reported flag state, not an absent positive-valued quantity.
                it.values[movement] = flag
                it.values[heartbeat] = 0
            }
            events.emit(BluetoothEvent.MeasurementReceived(m, device.address, receipt))
            return withTimeout(5000) { receipt.await() }
        }
        assertThat(flagged(1000, 0)).isTrue()
        assertThat(flagged(1000, 0)).isTrue()
        assertThat(flagged(1000, 1)).isFalse()
        assertThat(flagged(2000, 1)).isTrue()
        val rows = repo.getMeasurementsWithValuesForUser(user).first()
        val ordinary = rows.single { it.measurement.timestamp == 1000L }
        assertThat(ordinary.values).hasSize(3)
        for (key in listOf(movement, heartbeat)) {
            val value = ordinary.values.single { it.type.identity == key.identity }
            assertThat(value.value.intValue).isEqualTo(0)
            assertThat(value.type.isInternal).isTrue()
            assertThat(value.type.isEnabled).isFalse()
        }
        assertThat(rows.single { it.measurement.timestamp == 2000L }.values.single {
            it.type.identity == movement.identity }.value.intValue).isEqualTo(1)
    }
    @Test fun `save accepted before observation teardown finishes durably outside observer job`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lock = scope.launch { db.withTransaction { entered.complete(Unit); release.await() } }
        withTimeout(5000) { entered.await() }
        val receipt = CompletableDeferred<Boolean>()
        events.emit(BluetoothEvent.MeasurementReceived(ScaleMeasurement(userId = user, dateTime = Date(3000)).also { it[pressure] = 120 }, device.address, receipt))
        events.emit(BluetoothEvent.Disconnected(device.address))
        withTimeout(5000) { events.subscriptionCount.first { it == 0 } }
        assertThat(receipt.isCompleted).isFalse()
        release.complete(Unit); lock.join()
        assertThat(withTimeout(5000) { receipt.await() }).isTrue()
        assertThat(repo.getMeasurementsWithValuesForUser(user).first().single().measurement.timestamp).isEqualTo(3000L)
    }

    @Test fun `user switched while accepted save waits for Room is never redirected or confirmed`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lock = scope.launch { db.withTransaction { entered.complete(Unit); release.await() } }
        withTimeout(5000) { entered.await() }
        val receipt = CompletableDeferred<Boolean>()
        events.emit(BluetoothEvent.MeasurementReceived(ScaleMeasurement(userId = user, dateTime = Date(4000)).also { it[pressure] = 120 }, device.address, receipt))
        events.emit(BluetoothEvent.Disconnected(device.address))
        withTimeout(5000) { events.subscriptionCount.first { it == 0 } }
        selected = user + 1
        release.complete(Unit); lock.join()
        assertThat(withTimeout(5000) { receipt.await() }).isFalse()
        assertThat(repo.getMeasurementsWithValuesForUser(user).first()).isEmpty()
    }

    @Test fun `selected user changes do not redirect or confirm incoming observation`() = runBlocking {
        selected = user + 1
        assertThat(send()).isFalse()
        assertThat(repo.getMeasurementsWithValuesForUser(user).first()).isEmpty()
    }
    @Test fun `cancelled receipt does not save or confirm and disconnect leaves replay possible`() = runBlocking {
        val receipt = CompletableDeferred<Boolean>().also { it.cancel() }
        events.emit(BluetoothEvent.MeasurementReceived(ScaleMeasurement(userId = user, dateTime = Date(1000)).also { it[pressure] = 120 }, device.address, receipt))
        assertThat(send(time = 2000)).isTrue() // ordered delivery makes the earlier refusal observable
        assertThat(repo.getMeasurementsWithValuesForUser(user).first().map { it.measurement.timestamp }).containsExactly(2000L)
        events.emit(BluetoothEvent.Disconnected(device.address))
        withTimeout(5000) { events.subscriptionCount.first { it == 0 } }
        connector.connectToDevice(device)
        withTimeout(5000) { events.subscriptionCount.first { it > 0 } }
        assertThat(send(time = 2000)).isTrue()
    }
}
