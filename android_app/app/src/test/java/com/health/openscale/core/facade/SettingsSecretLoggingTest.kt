/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.core.facade

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSecretLoggingTest {
    @Test fun `failed settings writes log neither value nor exception payload`() = runBlocking {
        val secret = "000102030405060708090a0b0c0d0e0f" // synthetic
        val store = object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw IOException("cannot persist $secret", IllegalStateException("nested $secret"))
            }
        }
        LogManager.init(ApplicationProvider.getApplicationContext<Application>(), false)
        ShadowLog.clear()
        SettingsFacadeImpl(store).saveSetting("ble/OmronEvolvHandler/existingApplicationKey", secret)
        val logs = ShadowLog.getLogs()
        assertThat(logs.any { it.msg.contains("Failed to save setting") }).isTrue()
        assertThat(logs.joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") })
            .doesNotContain(secret)
        assertThat(logs.filter { it.msg.contains("Failed to save setting") }.all { it.throwable == null }).isTrue()
    }
}
