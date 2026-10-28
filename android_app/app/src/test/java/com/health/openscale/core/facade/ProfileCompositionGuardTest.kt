package com.health.openscale.core.facade

import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import org.junit.Assert.*
import org.junit.Test

class ProfileCompositionGuardTest {
    @Test fun discardsAllProfileEstimatesButKeepsWeightImpedanceAndUnrelatedValues() {
        val keys = listOf(
            MeasurementType.WEIGHT, MeasurementType.IMPEDANCE,
            MeasurementType.BODY_FAT, MeasurementType.WATER, MeasurementType.MUSCLE,
            MeasurementType.BONE, MeasurementType.LBM, MeasurementType.BMR,
            MeasurementType.VISCERAL_FAT, MeasurementType.HEART_RATE,
        )
        val types = keys.mapIndexed { i, key -> MeasurementType(id = i + 1, identity = key.identity) }
        val values = types.map { MeasurementValue(measurementId = 0, typeId = it.id, floatValue = 10f) }
        // Both a changed selected user and a smart-assignment winner reach this seam.
        val actual = ProfileCompositionGuard.forOwner(values, types, profileUserId = 7, ownerId = 8)
        assertEquals(listOf(1, 2, 10), actual.map { it.typeId })
        assertSame(values[0], actual[0])
        assertSame(values[1], actual[1])
        assertSame(values, ProfileCompositionGuard.forOwner(values, types, 7, 7))
        assertSame(values, ProfileCompositionGuard.forOwner(values, types, null, 8))
        assertEquals(actual, ProfileCompositionGuard.forOwner(values, types, 7, 7, weightChanged = true))
    }
}
