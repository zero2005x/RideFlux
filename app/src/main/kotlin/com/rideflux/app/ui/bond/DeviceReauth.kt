/*
 * Copyright (C) 2026 RideFlux project contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rideflux.app.ui.bond

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Asks the user to prove presence with a biometric or the screen lock before secrets leave the
 * app. API 30+ and 29 use the framework [BiometricPrompt] with the screen-lock fallback; API 28
 * has no such fallback, so it uses the system confirm-credential screen.
 */
class DeviceReauth(
    private val context: Context,
    private val launchConfirmCredential: (Intent) -> Unit,
    private val onResult: (Boolean) -> Unit,
) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** False when no screen lock is set: there is nothing to confirm against. */
    fun isAvailable(): Boolean = keyguard?.isDeviceSecure == true

    fun start(title: String, subtitle: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startPrompt(title, subtitle) else {
            @Suppress("DEPRECATION")
            val intent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) onResult(false) else launchConfirmCredential(intent)
        }
    }

    // DeviceReauth serves as an explicit user consent gate before export; exported data is sealed
    // with a user-entered passphrase, so a hardware-backed CryptoObject is not required here.
    @Suppress("kotlin:S6293")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startPrompt(title: String, subtitle: String) {
        val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(), context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) =
                    onResult(true)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
            },
        )
    }
}

@Composable
fun rememberDeviceReauth(onResult: (Boolean) -> Unit): DeviceReauth {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        latest(it.resultCode == Activity.RESULT_OK)
    }
    return remember(context) { DeviceReauth(context, { launcher.launch(it) }) { latest(it) } }
}
