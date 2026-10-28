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
package com.health.openscale.core.facade

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.data.*
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.service.MeasurementEnricher
import com.health.openscale.core.service.TrendCalculator
import com.health.openscale.core.usecase.*
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real BLE facade → transformation → CRUD → in-memory Room, including derived recalculation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BloodPressurePersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var facade: MeasurementFacade
    private lateinit var settings: SettingsFacade
    private var userId = 0
    private var weightId = 0
    private var systolicId = 0
    private var diastolicId = 0
    private var pulseId = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())
        settings = RoomTestSupport.settingsFacadeFor(scope, File(app.cacheDir, "bp-${System.nanoTime()}.preferences_pb"))
        val query = MeasurementQueryUseCases(repo)
        val types = MeasurementTypeCrudUseCases(repo, app)
        val sync = SyncUseCases(app, types)
        val transform = MeasurementTransformationUseCase(settings, UserUseCases(app, repo, settings, sync), query)
        val crud = MeasurementCrudUseCases(app, settings, sync, transform, repo)
        facade = MeasurementFacade(query, MeasurementFilterUseCases(), MeasurementSmoothingUseCases(), transform,
            crud, types, MeasurementEnricher(settings, TrendCalculator()), MeasurementEvaluationUseCases(),
            MeasurementAggregationUseCase(), MeasurementInsightsUseCase(), MeasurementDemoUseCase(query, crud))
        // Test-owned semantic types keep shared persistence independent of any device handler.
        // Display labels are immaterial here; reuse an existing localized custom-type label.
        systolicId = types.resolveOrCreate(MeasurementType.deviceInt("blood_pressure.systolic",
            R.string.measurement_type_custom_default_name, UnitType.MMHG))!!.id
        diastolicId = types.resolveOrCreate(MeasurementType.deviceInt("blood_pressure.diastolic",
            R.string.measurement_type_custom_default_name, UnitType.MMHG))!!.id
        val all = repo.getAllMeasurementTypes().first()
        weightId = all.first { it.key == MeasurementType.WEIGHT }.id
        pulseId = all.first { it.key == MeasurementType.HEART_RATE }.id
        userId = repo.insertUser(User(name = "selected", birthDate = 0L, gender = GenderType.MALE,
            heightCm = 175f, activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false)).toInt()
        val prior = repo.insertMeasurement(Measurement(userId = userId, timestamp = 1000)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = prior, typeId = weightId, floatValue = 75f))
        val waistId = all.first { it.key == MeasurementType.WAIST }.id
        repo.insertMeasurementValue(MeasurementValue(measurementId = prior, typeId = waistId, floatValue = 90f))
        settings.setSmartAssignmentEnabled(true)
        settings.setSmartAssignmentIgnoreOutsideTolerance(true)
        settings.setSelectedBodyFatFormula(BodyFatFormulaOption.US_NAVY)
        settings.setSelectedLbmFormula(LbmFormulaOption.BOER_1984)
    }
    @After fun cleanup() { scope.cancel(); db.close() }

    private fun bp() = listOf(
        MeasurementValue(measurementId = 0, typeId = systolicId, intValue = 120),
        MeasurementValue(measurementId = 0, typeId = diastolicId, intValue = 80),
        MeasurementValue(measurementId = 0, typeId = pulseId, intValue = 70))

    @Test fun `weigh ins still inherit manual body measurements but never old blood pressures`() = runBlocking {
        val prior = repo.insertMeasurement(Measurement(userId = userId, timestamp = 1500)).toInt()
        bp().forEach { repo.insertMeasurementValue(it.copy(measurementId = prior)) }
        val waistId = repo.getAllMeasurementTypes().first().first { it.key == MeasurementType.WAIST }.id
        // T1 has weight + waist; T2 is strictly BP-only; T3 has weight only.
        facade.saveMeasurementFromBleDevice(Measurement(userId = userId, timestamp = 3000),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 76f)))
        val saved = facade.getMeasurementsForUser(userId).first().first { it.measurement.timestamp == 3000L }
        assertThat(saved.values.any { it.value.typeId == systolicId || it.value.typeId == diastolicId }).isFalse()
        assertThat(saved.values.any { it.value.typeId == pulseId }).isFalse()
        assertThat(saved.values.first { it.value.typeId == waistId }.value.floatValue).isEqualTo(90f)
        assertThat(saved.values.first { it.value.typeId == weightId }.value.floatValue).isEqualTo(76f)
    }

    @Test fun `weightless manual body entry remains an eligible inheritance source`() = runBlocking {
        val waistId = repo.getAllMeasurementTypes().first().first { it.key == MeasurementType.WAIST }.id
        val manual = repo.insertMeasurement(Measurement(userId = userId, timestamp = 1500)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = manual, typeId = waistId, floatValue = 92f))
        facade.saveMeasurementFromBleDevice(Measurement(userId = userId, timestamp = 3000),
            listOf(MeasurementValue(measurementId = 0, typeId = weightId, floatValue = 76f)))
        val saved = facade.getMeasurementsForUser(userId).first().first { it.measurement.timestamp == 3000L }
        assertThat(saved.values.first { it.value.typeId == waistId }.value.floatValue).isEqualTo(92f)
    }

    @Test fun `BP history stays on selected user without inheriting body data and repeated imports deduplicate`() = runBlocking {
        val m = Measurement(userId = userId, timestamp = 2000)
        facade.saveMeasurementFromBleDevice(m, bp())
        facade.saveMeasurementFromBleDevice(m, bp())
        val readings = facade.getMeasurementsForUser(userId).first().filter { it.measurement.timestamp == 2000L }
        assertThat(readings).hasSize(1)
        assertThat(readings.single().values.map { it.value.typeId }).containsExactly(systolicId, diastolicId, pulseId)
        assertThat(readings.single().values.first { it.value.typeId == systolicId }.value.intValue).isEqualTo(120)
        assertThat(readings.single().measurement.userId).isEqualTo(userId)
    }

    @Test fun `raw BP repository failure reaches BLE caller`() = runBlocking {
        val failure = runCatching {
            facade.saveMeasurementFromBleDevice(Measurement(userId = userId, timestamp = 2000),
                bp() + MeasurementValue(measurementId = 0, typeId = Int.MAX_VALUE, intValue = 1))
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(failure).isInstanceOf(android.database.sqlite.SQLiteConstraintException::class.java)
    }

    @Test fun `pending assisted weighing cannot replace BP with a negative reference weight`() = runBlocking {
        val reference = User(id = userId, name = "reference", birthDate = 0L, gender = GenderType.MALE,
            heightCm = 175f, activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false)
        facade.setPendingReferenceUserForBle(reference)
        facade.saveMeasurementFromBleDevice(Measurement(userId = userId, timestamp = 2000), bp())
        val saved = facade.getMeasurementsForUser(userId).first().first { it.measurement.timestamp == 2000L }
        assertThat(saved.values.map { it.value.typeId }).containsExactly(systolicId, diastolicId, pulseId)
        Unit
    }
}
