package com.health.openscale.core.bluetooth.libs

import com.health.openscale.core.bluetooth.libs.EufyT9140Calculator.Gender
import com.health.openscale.core.bluetooth.libs.EufyT9140Calculator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EufyT9140CalculatorTest {
    // Literal oracles from the local static transcription/literal evidence,
    // cross-checked with separate decimal algebra, not the calculator under test.
    // Screenshot parity does not establish physical units or clinical accuracy.
    private fun result(
        weight: Float, height: Float, age: Int, gender: Gender, raw: Int = 500,
    ): Result {
        val value = EufyT9140Calculator.calculate(weight, height, age, gender, raw)
        assertNotNull(value)
        return value!!
    }

    private fun assertMetrics(actual: Result, expected: Result) {
        assertEquals("fat %", expected.fatPercent, actual.fatPercent, 0.0001f)
        assertEquals("water %", expected.waterPercent, actual.waterPercent, 0.0001f)
        assertEquals("muscle %", expected.musclePercent, actual.musclePercent, 0.0001f)
        assertEquals("bone %", expected.bonePercent, actual.bonePercent, 0.0001f)
        assertEquals("bone kg", expected.boneKg, actual.boneKg, 0.0001f)
        assertEquals("lean kg", expected.leanKg, actual.leanKg, 0.0001f)
        assertEquals("BMR kcal", expected.bmrKcal, actual.bmrKcal, 0.0001f)
        assertEquals("visceral index", expected.visceralFat, actual.visceralFat, 0.0001f)
    }

    @Test
    fun screenshotMaleBothRawValuesMatchAndBoneIsKilogramsNotPercent() {
        val expected = Result(32.3f, 49.4f, 37.1f, 7.8f, 8.0f, 69.1f, 3075.4f, 16.6f)
        for (raw in listOf(691, 671)) {
            assertMetrics(result(102.1f, 175f, 41, Gender.MALE, raw), expected)
        }
    }

    @Test
    fun maleAlternativeVisceralBranchAndYoungerBoneBranch() {
        assertMetrics(
            result(70f, 180f, 30, Gender.MALE),
            Result(15.6f, 61.6f, 45.0f, 8.1f, 5.7f, 59.1f, 2700.0f, 4.2f),
        )
    }

    @Test
    fun femaleLightProfileUsesFemaleInterceptMuscleBmrAndAlternativeVisceralBranch() {
        assertMetrics(
            result(60f, 165f, 31, Gender.FEMALE),
            Result(23.4f, 55.9f, 35.3f, 6.4f, 3.8f, 46.0f, 2148.8f, 4.9f),
        )
    }

    @Test
    fun femaleHeavyProfileUsesRationalVisceralBranchAndOlderBoneBranch() {
        assertMetrics(
            result(90f, 165f, 41, Gender.FEMALE, 691),
            Result(36.8f, 46.2f, 30.6f, 6.2f, 5.6f, 56.9f, 2448.6f, 10.6f),
        )
    }

    @Test
    fun boneAgeSwitchIncludesFortyInYoungerBranchForBothGenders() {
        assertEquals(7.9f, result(80f, 175f, 40, Gender.MALE).bonePercent, 0.0001f)
        assertEquals(7.4f, result(80f, 175f, 41, Gender.MALE).bonePercent, 0.0001f)
        assertEquals(6.1f, result(60f, 165f, 40, Gender.FEMALE).bonePercent, 0.0001f)
        assertEquals(5.7f, result(60f, 165f, 41, Gender.FEMALE).bonePercent, 0.0001f)
    }

    @Test
    fun massesAreDerivedAfterPercentageRoundingNotFromUnroundedIntermediate() {
        val male = result(70f, 180f, 30, Gender.MALE)
        // Unrounded bone 8.05465% would produce 5.6 kg, not 5.7 kg.
        assertEquals(5.7f, male.boneKg, 0.0001f)
        // At 60 kg the unrounded lean estimate is 53.350625 kg (53.4),
        // but stored fat 11.1% gives 53.34 kg and must round to 53.3.
        assertEquals(53.3f, result(60f, 175f, 41, Gender.MALE, 691).leanKg, 0.0001f)
        // The screenshot bone percentage 7.8 must never be delivered as BONE kg.
        assertEquals(8.0f, result(102.1f, 175f, 41, Gender.MALE, 691).boneKg, 0.0001f)
    }

    @Test
    fun lowerFatUpperWaterUpperMuscleAndLowerVisceralClamps() {
        // Deliberately nonphysiological inputs exercise numeric clamps only.
        assertMetrics(
            result(10f, 300f, 1, Gender.MALE, 1),
            Result(5f, 75f, 85f, 10.7f, 1.1f, 9.5f, 2705.3f, 1f),
        )
    }

    @Test
    fun upperFatLowerWaterUpperBmrAndUpperVisceralClamps() {
        assertMetrics(
            result(500f, 50f, 120, Gender.FEMALE, 65534),
            Result(60f, 25f, 22.2f, 6.2f, 31f, 200f, 4500f, 50f),
        )
    }

    @Test
    fun lowerBoneLowerMuscleAndLowerBmrClamps() {
        assertMetrics(
            result(1f, 50f, 120, Gender.MALE, 65534),
            Result(60f, 25f, 15f, 1f, 0f, 0.4f, 500f, 14.6f),
        )
    }

    @Test
    fun upperBoneClamp() {
        assertEquals(15f, result(500f, 300f, 1, Gender.MALE, 1).bonePercent, 0.0001f)
    }

    @Test
    fun invalidProfileAndSentinelImpedanceReturnNull() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(EufyT9140Calculator.calculate(bad, 175f, 41, Gender.MALE, 691))
            assertNull(EufyT9140Calculator.calculate(102.1f, bad, 41, Gender.MALE, 691))
        }
        for (age in listOf(0, -1, Int.MIN_VALUE)) {
            assertNull(EufyT9140Calculator.calculate(102.1f, 175f, age, Gender.MALE, 691))
        }
        assertNull(EufyT9140Calculator.calculate(102.1f, 175f, 41, null, 691))
        for (raw in listOf(-1, 0, 65535, 65536, Int.MAX_VALUE)) {
            assertNull(EufyT9140Calculator.calculate(102.1f, 175f, 41, Gender.MALE, raw))
        }
    }
}
