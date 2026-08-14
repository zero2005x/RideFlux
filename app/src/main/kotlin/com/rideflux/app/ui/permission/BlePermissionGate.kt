/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Runtime-permission gate for BLE features.
 *
 * Android 12 (API 31) replaced [Manifest.permission.ACCESS_FINE_LOCATION]
 * as the scan-time permission with the new
 * [Manifest.permission.BLUETOOTH_SCAN] /
 * [Manifest.permission.BLUETOOTH_CONNECT] pair. Both need to be
 * requested at runtime or the platform rejects
 * `BluetoothLeScanner.startScan` and `BluetoothDevice.connectGatt`
 * with a `SecurityException: Need … permission for … registerScanner`.
 *
 * This gate decides which permissions to ask for based on
 * [Build.VERSION.SDK_INT] and either renders [content] once they are
 * all granted or a small rationale screen with a "Grant permissions"
 * button that re-launches the system dialog.
 */
@Composable
fun BlePermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val required = remember { requiredBlePermissions() }

    var granted by remember {
        mutableStateOf(
            required.all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // Re-evaluate on every resume so that permissions granted (or
    // revoked) in system Settings while the app is backgrounded are
    // reflected in the UI immediately.
    LifecycleResumeEffect(Unit) {
        granted = required.all {
            ContextCompat.checkSelfPermission(context, it) ==
                PackageManager.PERMISSION_GRANTED
        }
        onPauseOrDispose { }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = required.all { result[it] == true }
    }

    var hasRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!granted && !hasRequested) {
            hasRequested = true
            launcher.launch(required)
        }
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Bluetooth permission required",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "RideFlux needs Bluetooth scan and connect permissions " +
                    "to find and talk to your wheel.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
            Button(onClick = { launcher.launch(required) }) {
                Text("Grant permissions")
            }
            // If the user permanently denied the prompt ("Don't ask
            // again"), re-launching it is a no-op — offer the system
            // settings page instead so they can still grant manually.
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Open settings")
            }
        }
    }
}

private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // BLUETOOTH_ADVERTISE is needed by the phone↔glasses bridge
        // (BridgeServer.startAdvertising) on Android 12+. Requesting
        // it together with SCAN/CONNECT lets the user grant the whole
        // Nearby-devices group in one prompt.
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        // API 26..30: BLUETOOTH / BLUETOOTH_ADMIN are install-time;
        // the only runtime gate is fine location for scan results.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
