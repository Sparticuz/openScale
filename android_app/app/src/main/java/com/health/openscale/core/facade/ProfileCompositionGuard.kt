package com.health.openscale.core.facade

import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue

/** Drops local profile estimates after reassignment or a change to their input weight. */
internal object ProfileCompositionGuard {
    private val estimatedKeys = setOf(
        MeasurementType.BODY_FAT, MeasurementType.WATER, MeasurementType.MUSCLE,
        MeasurementType.BONE, MeasurementType.LBM, MeasurementType.BMR,
        MeasurementType.VISCERAL_FAT,
    )

    fun forOwner(
        values: List<MeasurementValue>,
        types: List<MeasurementType>,
        profileUserId: Int?,
        ownerId: Int,
        weightChanged: Boolean = false,
    ): List<MeasurementValue> {
        if (profileUserId == null || (profileUserId == ownerId && !weightChanged)) return values
        val estimatedIds = types.filter { it.key in estimatedKeys }.map { it.id }.toSet()
        return values.filterNot { it.typeId in estimatedIds }
    }
}
