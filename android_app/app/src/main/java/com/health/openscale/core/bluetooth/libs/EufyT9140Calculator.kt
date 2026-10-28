package com.health.openscale.core.bluetooth.libs

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * T9140 compatibility estimates, not clinically validated composition measurements.
 *
 * Algebra reconstructed from authorized local static inspection of EufyLife 3.3.12
 * (com.oceanwing.smarthome, versionCode 743), Belter getFat and its storage caller.
 * No vendor code is loaded, linked or executed. This is readable arithmetic, not a
 * bitwise ARM floating-point emulator; native rounding ties may differ.
 * The initial lean-like expression is also disclosed in CN108364690A, claim 10:
 * https://patents.google.com/patent/CN108364690A/zh . That disclosure establishes
 * neither Belter authorship, clinical validation nor licensing/patent clearance.
 *
 * rawImpedance is the transmitted integer, NOT verified ohms. Division by 1000
 * below is an algorithm-specific scale, not evidence of electrical calibration.
 * Percentages are returned as percent points; boneKg and leanKg are kilograms
 * for openScale BONE/LBM, and visceralFat is a dimensionless vendor index.
 */
object EufyT9140Calculator {
    enum class Gender { MALE, FEMALE }

    data class Result(
        val fatPercent: Float,
        val waterPercent: Float,
        val musclePercent: Float,
        val bonePercent: Float,
        val boneKg: Float,
        val leanKg: Float,
        val bmrKcal: Float,
        val visceralFat: Float,
    )

    /**
     * Missing gender, nonpositive age/size, nonfinite size or impedance outside
     * the app's accepted 1..65534 range yield null. These are numeric validity
     * checks, not a claim of a clinically supported age/height/weight range.
     */
    fun calculate(
        weightKg: Float,
        heightCm: Float,
        age: Int,
        gender: Gender?,
        rawImpedance: Int,
    ): Result? {
        if (gender == null || age <= 0 ||
            !weightKg.isFinite() || weightKg <= 0f ||
            !heightCm.isFinite() || heightCm <= 0f ||
            rawImpedance !in 1..65534
        ) return null

        // Match the caller's float input scaling; use double algebra thereafter.
        val h = (heightCm / 100f).toDouble()
        if (h <= 0.0) return null
        val w = weightKg.toDouble()
        val a = age.toDouble()
        val z = (rawImpedance.toFloat() / 1000f).toDouble()
        val male = gender == Gender.MALE
        val leanEstimate = 9.85 * h * h + (if (male) 1.7 else -2.6) +
            0.3737 * w - 0.006 * a - z

        val fat = decimal((100.0 * (1.0 - leanEstimate / w)).coerceIn(5.0, 60.0))
        val water = decimal((73.0 * leanEstimate / w).coerceIn(25.0, 75.0))
        val boneAgeFactor = if (age <= 40) 0.03 else 0.04
        val bone = decimal(((if (male) 6.0 else 5.0) + 0.05 * leanEstimate -
            boneAgeFactor * a).coerceIn(1.0, 15.0))
        val muscle = decimal((100.0 * (7.8 * h + 0.244 * w - 0.098 * a +
            (if (male) 3.3 else -3.3)) / w).coerceIn(15.0, 85.0))
        val bmr = if (male) {
            565.0 * h + 15.9 * w - 9.7 * a + 861.0
        } else {
            752.5 * h + 12.425 * w - 7.3 * a + 388.0
        }
        val visceral = if (male) {
            if (100.0 * h < 1.6 * w + 63.0) {
                380.0 * w / (826.0 * h * h - 40.0 * h + 48.0) - 5.0 + 0.15 * a
            } else {
                w * (0.765 - 0.2 * h) - 14.3 * h + 0.15 * a - 2.9
            }
        } else {
            if (w > 50.0 * h - 13.0) {
                500.0 * w / (1158.0 * h * h + 145.0 * h - 120.0) - 6.0 + 0.07 * a
            } else {
                w * (0.691 - 0.24 * h) - 2.7 * h + 0.07 * a - 10.5
            }
        }
        if (!bmr.isFinite() || !visceral.isFinite()) return null

        // Storage rounds percentages first, THEN derives and rounds masses.
        // Keep those mass operations in float, as in the app's managed caller.
        return Result(
            fatPercent = fat,
            waterPercent = water,
            musclePercent = muscle,
            bonePercent = bone,
            boneKg = decimal((bone / 100f * weightKg).toDouble()),
            leanKg = decimal(((1f - fat / 100f) * weightKg).toDouble()),
            bmrKcal = decimal(bmr.coerceIn(500.0, 4500.0)),
            visceralFat = decimal(visceral.coerceIn(1.0, 50.0)),
        )
    }

    // Unlike kotlin.math.round (ties-to-even), positive decimal ties round up.
    private fun decimal(value: Double): Float =
        BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toFloat()
}
