package com.health.openscale.core.bluetooth.libs

import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.junit.Assert.*
import org.junit.Test

class EufyT9140ProtocolTest {
    @Test fun capturedFinalBeforeImpedanceProducesOneCompleteReading() {
        val p = EufyT9140Protocol()
        assertTrue(p.feed(hex("ac0203fd0000caca"), 0).isEmpty())
        assertTrue(p.feed(hex("ac02fd000000cbc8ac0203fd0000caca"), 100).isEmpty())
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, 691)),
            p.feed(hex("ac02fd0102b3cb7eac0203fd0000caca"), 200))
        assertTrue(p.flush(5000).isEmpty())
        assertTrue(p.feed(hex("ac0203fd0000caca"), 5100).isEmpty())
    }

    @Test fun secondCapturedSessionHasSameWeightAndDifferentImpedance() {
        val p = EufyT9140Protocol()
        p.feed(hex("ac0203fd0000cacaac02fd0102b3cb7e"), 0)
        p.reset()
        p.feed(hex("ac0203f80000cec9ac0203fd0000cece"), 100)
        p.feed(hex("ac0203fd0000cacaac02fd000000cbc8"), 200)
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, 671)),
            p.feed(hex("ac02fd01029fcb6aac0203fd0000caca"), 300))
    }

    @Test fun allSplitBoundariesAndNoiseResynchronize() {
        val data = hex("ac0203fd0000cacaac02fd0102b3cb7e")
        for (split in 0..data.size) {
            val p = EufyT9140Protocol()
            val actual = p.feed(data.copyOfRange(0, split), 0) +
                p.feed(data.copyOfRange(split, data.size), 1)
            assertEquals("split $split", listOf(EufyT9140Protocol.Reading(102.1f, 691)), actual)
        }
        val p = EufyT9140Protocol()
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, 691)),
            p.feed(ByteArray(100000) { 0xac.toByte() } + hex("ac0203fd0000cacb") + data, 0))
        assertTrue(p.bufferedBytes < 8)
    }

    @Test fun missingOrInvalidImpedanceFallsBackOnceWithoutAcceptingLateData() {
        for (raw in listOf("ac02fd010000cbc9", "ac02fd01ffffcbc7")) {
            val p = EufyT9140Protocol()
            p.feed(hex("ac0203fd0000caca" + raw), 0)
            assertTrue(p.flush(2999).isEmpty())
            assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, null)), p.flush(3000))
            assertTrue(p.flush(4000).isEmpty())
            assertTrue(p.feed(hex("ac02fd0102b3cb7eac0203fd0000caca"), 4100).isEmpty())
        }
    }

    @Test fun progressStartsIndependentSameWeightSessionAndDoesNotReuseImpedance() {
        val p = EufyT9140Protocol()
        p.feed(hex("ac0203fd0000cacaac02fd0102b3cb7e"), 0)
        p.feed(hex("ac0203fd0000ceceac0203fd0000caca"), 100)
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, null)), p.flush(3100))
    }

    @Test fun resetDropsPartialFrameAndPendingFinal() {
        val p = EufyT9140Protocol()
        p.feed(hex("ac0203fd0000cacaac02fd01"), 0)
        p.reset()
        assertTrue(p.feed(hex("02b3cb7e"), 1).isEmpty())
        assertTrue(p.flush(10000).isEmpty())
    }

    @Test fun impedanceBeforeFinalAndQuietGapAreSupported() {
        val p = EufyT9140Protocol()
        p.feed(hex("ac02fd0102b3cb7e"), 0)
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, 691)), p.feed(hex("ac0203fd0000caca"), 1))
        p.feed(hex("ac0203fd0000caca"), 20000)
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, 671)), p.feed(hex("ac02fd01029fcb6a"), 20001))
    }

    @Test fun partialFramesExpireAcrossQuietGapEvenBeforeAnyCompleteFrame() {
        val p = EufyT9140Protocol()
        p.feed(hex("ac02fd01"), 0)
        assertTrue(p.feed(hex("02b3cb7eac0203fd0000caca"), 20000).isEmpty())
        assertEquals(listOf(EufyT9140Protocol.Reading(102.1f, null)), p.flush(23000))
    }
}
