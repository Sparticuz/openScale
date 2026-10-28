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
package com.health.openscale.core.database

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.*
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmedMeasurementTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private var user = 0
    private var type = 0
    @Before fun setup() = runBlocking {
        db = RoomTestSupport.inMemory(ApplicationProvider.getApplicationContext())
        repo = RoomTestSupport.repositoryFor(db)
        user = repo.insertUser(User(name = "Synthetic", birthDate = 0, gender = GenderType.MALE,
            heightCm = 175f, activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false)).toInt()
        type = repo.insertMeasurementType(MeasurementType(identity = "device.test.pressure",
            inputType = InputFieldType.INT, unit = UnitType.MMHG)).toInt()
    }
    @After fun close() = db.close()
    private fun reading(time: Long = 1000) = Measurement(userId = user, timestamp = time)
    private fun value(n: Int = 120) = MeasurementValue(measurementId = 0, typeId = type, intValue = n)
    @Test fun `only exact durable duplicates confirm and collisions are not overwritten`() = runBlocking {
        val id = repo.insertAndConfirmMeasurement(reading(), listOf(value()))
        assertThat(id).isGreaterThan(0)
        assertThat(repo.insertAndConfirmMeasurement(reading(), listOf(value()))).isEqualTo(id)
        assertThat(runCatching { repo.insertAndConfirmMeasurement(reading(), listOf(value(130))) }.isFailure).isTrue()
        assertThat(repo.getValuesForMeasurement(id).first().single().intValue).isEqualTo(120)
    }
    @Test fun `foreign key failure rolls back parent and earlier values allowing retry`() = runBlocking {
        assertThat(runCatching { repo.insertAndConfirmMeasurement(reading(), listOf(value(), value().copy(typeId = 99999))) }.isFailure).isTrue()
        assertThat(repo.getMeasurementsWithValuesForUser(user).first()).isEmpty()
        assertThat(repo.insertAndConfirmMeasurement(reading(), listOf(value()))).isGreaterThan(0)
    }
    @Test fun `ambiguous duplicate values cannot confirm the timestamp slot`() = runBlocking {
        val id = repo.insertAndConfirmMeasurement(reading(), listOf(value()))
        repo.insertMeasurementValue(value(130).copy(measurementId = id))
        assertThat(runCatching { repo.insertAndConfirmMeasurement(reading(), listOf(value())) }.isFailure).isTrue()
    }
    @Test fun `incomplete occupied slot is not a confirmed save`() = runBlocking {
        repo.insertMeasurement(reading())
        assertThat(runCatching { repo.insertAndConfirmMeasurement(reading(), listOf(value())) }.isFailure).isTrue()
    }
}
