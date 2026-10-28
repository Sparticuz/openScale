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
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QueuedWriteSessionTest {
    @Test fun `reset cancels queued chunks and pacing cannot leak writes into reconnect`() = runTest {
        val session = QueuedWriteSession()
        val old = session.token()
        val paced = CompletableDeferred<Unit>()
        val physical = mutableListOf<String>()
        val first = launch { session.write(old, { paced.await() }) { physical += "old-key" } }
        runCurrent()
        session.invalidate()
        paced.complete(Unit)
        first.join()
        session.write(old, {}) { physical += "old-ACK-lane-0" }
        session.write(old, {}) { physical += "old-ACK-lane-1" }
        session.write(old, {}) { physical += "old-clock" }
        session.write(session.token(), {}) { physical += "new-connection" }
        assertThat(physical).containsExactly("new-connection")
    }
    @Test fun `reset between split chunks suppresses remainder only`() = runTest {
        val session = QueuedWriteSession()
        val token = session.token()
        val physical = mutableListOf<Int>()
        session.write(token, {}) { physical += 0 }
        session.invalidate()
        session.write(token, {}) { physical += 1 }
        assertThat(physical).containsExactly(0)
    }
}
