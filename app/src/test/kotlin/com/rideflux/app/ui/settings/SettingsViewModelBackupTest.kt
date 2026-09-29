/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.settings

import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.rideflux.app.backup.TripBackupManager
import com.rideflux.domain.ride.ImportResult
import com.rideflux.domain.settings.AppSettings
import com.rideflux.domain.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelBackupTest {
    private val resolver = mockk<ContentResolver>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val backup = mockk<TripBackupManager>()
    private val models = ViewModelStore()
    private lateinit var model: SettingsViewModel
    private val uri = Uri.parse("content://rideflux.test/backup")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.settings } returns MutableStateFlow(AppSettings())
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getContentResolver(): ContentResolver = resolver
        }
        model = SettingsViewModel(context, settings, backup)
        models.put("settings", model)
    }

    @After
    fun tearDown() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun exportReportsSuccessAndOpenFailure() = runBlocking {
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns output
        coEvery { backup.exportData(output) } returns 2
        val success = async(start = CoroutineStart.UNDISPATCHED) { model.backupEvent.first() }
        model.exportBackup(uri)
        assertEquals(BackupUiEvent.ExportSuccess(2), withTimeout(5_000) { success.await() })

        every { resolver.openOutputStream(uri) } throws IOException("storage unavailable")
        val error = async(start = CoroutineStart.UNDISPATCHED) { model.backupEvent.first() }
        model.exportBackup(uri)
        val event = withTimeout(5_000) { error.await() }
        assertTrue(event is BackupUiEvent.ExportError)
        assertTrue((event as BackupUiEvent.ExportError).message.contains("storage unavailable"))
    }

    @Test
    fun importReportsResultAndManagerFailure() = runBlocking {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val imported = ImportResult(tripsImported = 1, tripsSkipped = 0, samplesImported = 2)
        every { resolver.openInputStream(uri) } returns input
        coEvery { backup.importData(input, true) } returns imported
        val success = async(start = CoroutineStart.UNDISPATCHED) { model.backupEvent.first() }
        model.importBackup(uri, replaceAll = true)
        assertEquals(BackupUiEvent.ImportSuccess(imported), withTimeout(5_000) { success.await() })

        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(4))
        coEvery { backup.importData(any(), true) } throws IOException("invalid backup")
        val error = async(start = CoroutineStart.UNDISPATCHED) { model.backupEvent.first() }
        model.importBackup(uri, replaceAll = true)
        val event = withTimeout(5_000) { error.await() }
        assertTrue(event is BackupUiEvent.ImportError)
        assertTrue((event as BackupUiEvent.ImportError).message.contains("invalid backup"))
    }

    @Test
    fun bridgeAndAlertChoicesReachSettingsRepository() {
        model.setBridgeAutostart(true)
        model.setStandbyLowLatency(true)
        model.setPreferredGlassesMac("AA:BB:CC:DD:EE:FF")
        model.setAlertsEnabled(false)
        model.setSpeedLimit(35f)
        coVerify { settings.setBridgeAutostart(true) }
        coVerify { settings.setBridgeStandbyAdvertiseLowLatency(true) }
        coVerify { settings.setPreferredGlassesMac("AA:BB:CC:DD:EE:FF") }
        coVerify { settings.setAlertsEnabled(false) }
        coVerify { settings.setSpeedLimitKmh(35f) }
    }
}
