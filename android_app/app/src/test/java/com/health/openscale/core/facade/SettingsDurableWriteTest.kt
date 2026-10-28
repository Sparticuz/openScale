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
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDurableWriteTest {
    @Test fun `durable driver credential survives datastore recreation`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "durable-${System.nanoTime()}.preferences_pb")
        val firstJob = SupervisorJob()
        val first = RoomTestSupport.settingsFacadeFor(CoroutineScope(firstJob + Dispatchers.IO), file)
        assertThat(first.saveStringDurably("ble/test/pairingKey_synthetic", "synthetic-test-value")).isTrue()
        firstJob.cancelAndJoin()
        val nextJob = SupervisorJob()
        try {
            val next = RoomTestSupport.settingsFacadeFor(CoroutineScope(nextJob + Dispatchers.IO), file)
            assertThat(next.observeSetting("ble/test/pairingKey_synthetic", "").first() == "synthetic-test-value").isTrue()
        } finally { nextJob.cancelAndJoin() }
    }
}
