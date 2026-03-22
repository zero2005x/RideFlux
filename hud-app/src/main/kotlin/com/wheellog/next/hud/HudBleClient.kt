package com.wheellog.next.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BLE Central client that scans for the phone's RideFlux GATT Server,
 * connects, subscribes to notifications, and decodes incoming telemetry.
 *
 * **Pairing isolation**: The phone includes a persistent 4-byte instance ID
 * in its BLE advertisement as manufacturer-specific data (company 0xFFFF).
 * On the first successful connection, the HUD saves that ID. Subsequent
 * scans only accept advertisements carrying the saved ID, preventing
 * cross-talk when multiple phone+glasses setups coexist.
 */
@SuppressLint("MissingPermission")
class HudBleClient(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FF10-0000-1000-8000-00805F9B34FB")
        val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("0000FF11-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val RECONNECT_DELAY_MS = 3000L
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val COMPANY_ID = 0xFFFF
        private const val PREFS_NAME = "hud_pairing"
        private const val KEY_PAIRED_ID = "paired_instance_id"
        private const val TAG = "HudBleClient"

        /**
         * Decode the compact payload from the phone's GATT characteristic.
         * Layout: [speed 4B float][battery 1B][temp 4B float][alertMask 4B int][useMetric 1B]
         */
        internal fun decodePayload(bytes: ByteArray): HudData {
            if (bytes.size < 13) return HudData()
            val buffer = ByteBuffer.wrap(bytes)
            val speed = buffer.getFloat()
            val battery = buffer.get().toInt() and 0xFF
            val temp = buffer.getFloat()
            val alert = buffer.getInt()
            val useMetric = if (bytes.size >= 14) (buffer.get().toInt() != 0) else true
            return HudData(
                speedKmh = speed,
                batteryPercent = battery,
                temperatureC = temp,
                alertMask = alert,
                useMetric = useMetric,
            )
        }

        /**
         * Parse manufacturer-specific bytes into a hex instance ID string.
         * Returns null if input is null or shorter than 4 bytes.
         */
        internal fun parseInstanceId(mfgData: ByteArray?): String? {
            if (mfgData == null || mfgData.size < 4) return null
            return mfgData.take(4).joinToString("") { "%02x".format(it) }
        }

        /** Extract the 4-byte instance ID from manufacturer-specific data in a scan result. */
        private fun extractInstanceId(result: ScanResult): String? {
            val mfgData = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return null
            return parseInstanceId(mfgData)
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _hudData = MutableStateFlow(HudData())
    val hudData: StateFlow<HudData> = _hudData

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    /** The instance ID of the phone we're currently connecting to (for saving after success). */
    private var pendingInstanceId: String? = null

    /** Whether this HUD has a saved paired phone ID. */
    val hasPairedPhone: Boolean get() = prefs.getString(KEY_PAIRED_ID, null) != null

    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private val scanTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            stopScan()
            _connectionState.value = "Retrying scan..."
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (gatt == null) startScan()
            }, 1_000L)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scannedId = extractInstanceId(result)
            val savedId = prefs.getString(KEY_PAIRED_ID, null)

            if (savedId != null) {
                // We have a paired phone — only accept its ID
                if (scannedId != savedId) return // Skip this device, keep scanning
                Log.d(TAG, "Matched paired phone: $savedId")
            } else {
                // First time — accept any RideFlux phone, save its ID after connection
                Log.d(TAG, "No paired phone, will pair with: $scannedId")
            }

            pendingInstanceId = scannedId
            stopScan()
            _connectionState.value = "Connecting..."
            gatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = "Scan failed ($errorCode)"
            isScanning = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _connectionState.value = "Connected"
                    g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _connectionState.value = "Disconnected"
                    gatt?.close()
                    gatt = null
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(SERVICE_UUID) ?: return
            val char = service.getCharacteristic(TELEMETRY_CHAR_UUID) ?: return

            // Enable notifications
            g.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }

            // Connection fully established — save the phone's instance ID for future pairing
            val id = pendingInstanceId
            if (id != null && prefs.getString(KEY_PAIRED_ID, null) == null) {
                prefs.edit().putString(KEY_PAIRED_ID, id).apply()
                Log.d(TAG, "Paired with phone instance: $id")
            }

            _connectionState.value = "Receiving"
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == TELEMETRY_CHAR_UUID) {
                val value = characteristic.value ?: return
                _hudData.value = decodePayload(value)
            }
        }
    }

    /** Start scanning for the phone's GATT server. */
    fun startScan() {
        if (isScanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return

        _connectionState.value = "Scanning..."
        isScanning = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)

        scanTimeoutHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    /** Stop any ongoing scan. */
    fun stopScan() {
        if (!isScanning) return
        scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    /** Disconnect and clean up resources. */
    fun disconnect() {
        stopScan()
        scanTimeoutHandler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = "Disconnected"
    }

    /**
     * Forget the currently paired phone.
     * The next scan will accept any RideFlux phone and re-pair.
     */
    fun forgetPairedPhone() {
        prefs.edit().remove(KEY_PAIRED_ID).apply()
        pendingInstanceId = null
        Log.d(TAG, "Paired phone forgotten — will pair with next phone found")
    }

    private fun scheduleReconnect() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (gatt == null) {
                startScan()
            }
        }, RECONNECT_DELAY_MS)
    }
}
