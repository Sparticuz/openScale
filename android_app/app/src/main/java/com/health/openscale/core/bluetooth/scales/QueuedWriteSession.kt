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

/** Fence at the physical submission boundary, not merely at handler enqueue time.
 * Invalidation and the non-suspending physical write share a lock: after invalidation
 * returns, no old token can submit, even if it was already in the pacing delay.
 * An operation already submitted to the OS cannot be recalled.
 */
internal class QueuedWriteSession {
    private var generation = 0L
    @Synchronized fun token(): Long = generation
    @Synchronized fun invalidate() { generation++ }
    suspend fun write(token: Long, pace: suspend () -> Unit, submit: () -> Unit) {
        synchronized(this) { if (token != generation) return }
        pace()
        synchronized(this) { if (token == generation) submit() }
    }
}
