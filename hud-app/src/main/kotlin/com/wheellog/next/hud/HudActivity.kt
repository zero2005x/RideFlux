package com.wheellog.next.hud

import android.Manifest
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Full-screen activity for the Rokid RV101 AR glasses.
 * Keeps the screen on, launches BLE scan on permission grant, and renders the HUD.
 */
class HudActivity : ComponentActivity() {

    private lateinit var bleClient: HudBleClient
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _glassBattery = MutableStateFlow(0)
    val glassBattery: StateFlow<Int> = _glassBattery

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            bleClient.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bleClient = HudBleClient(applicationContext)

        startGlassBatteryMonitor()

        setContent {
            HudOverlayScreen(
                hudDataFlow = bleClient.hudData,
                connectionStateFlow = bleClient.connectionState,
                glassBatteryFlow = glassBattery,
            )
        }

        requestBlePermissions()
    }

    override fun onDestroy() {
        bleClient.disconnect()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startGlassBatteryMonitor() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        activityScope.launch {
            while (true) {
                _glassBattery.value =
                    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        .coerceIn(0, 100)
                delay(30_000L)
            }
        }
    }

    private fun requestBlePermissions() {
        val needed = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            bleClient.startScan()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
