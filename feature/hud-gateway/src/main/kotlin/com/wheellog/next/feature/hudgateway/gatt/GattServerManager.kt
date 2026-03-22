package com.wheellog.next.feature.hudgateway.gatt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import com.wheellog.next.domain.model.AlertFlag
import com.wheellog.next.domain.model.HudPayload
import com.wheellog.next.domain.repository.HudGatewayRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE GATT Server that advertises a custom service.
 * Rokid AR glasses (or any BLE central) can connect and read the telemetry
 * characteristic which is updated via [pushPayload].
 */
@Singleton
class GattServerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : HudGatewayRepository {

    companion object {
        /** Custom service UUID for WheelLog HUD. */
        val SERVICE_UUID: UUID = UUID.fromString("0000FF10-0000-1000-8000-00805F9B34FB")
        /** Characteristic carrying compacted telemetry payload. */
        val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("0000FF11-0000-1000-8000-00805F9B34FB")
        /** Client Characteristic Configuration Descriptor — required for BLE notifications. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /**
         * BLE Company ID for manufacturer-specific data.
         * 0xFFFF is reserved for internal/experimental use per Bluetooth SIG.
         */
        const val COMPANY_ID = 0xFFFF
        private const val PREFS_NAME = "rideflux_gatt"
        private const val KEY_INSTANCE_ID = "instance_id"

        /**
         * Encode [HudPayload] into a compact byte array (14 bytes).
         * Layout: [speed 4B float][battery 1B][temp 4B float][alertMask 4B int][useMetric 1B]
         */
        internal fun encodePayload(payload: HudPayload): ByteArray {
            val alertMask = payload.alertFlags.fold(0) { acc, flag ->
                acc or (1 shl flag.ordinal)
            }
            return ByteBuffer.allocate(14)
                .putFloat(payload.speedKmh)
                .put(payload.batteryPercent.coerceIn(0, 255).toByte())
                .putFloat(payload.temperatureC)
                .putInt(alertMask)
                .put(if (payload.useMetric) 1.toByte() else 0.toByte())
                .array()
        }

        /** Encode a 4-byte ID to an 8-char lowercase hex string. */
        internal fun encodeIdHex(id: ByteArray): String =
            id.joinToString("") { "%02x".format(it) }

        /** Decode an 8-char hex string to a 4-byte array. Returns null if format is invalid. */
        internal fun decodeIdHex(hex: String): ByteArray? {
            if (hex.length != 8) return null
            return try {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    /**
     * A persistent 4-byte unique ID for this phone instance.
     * Included in BLE advertising manufacturer-specific data so that
     * HUD glasses can distinguish this phone from others nearby.
     */
    val instanceId: ByteArray = getOrCreateInstanceId(context)

    private fun getOrCreateInstanceId(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_INSTANCE_ID, null)
        if (stored != null) {
            val decoded = decodeIdHex(stored)
            if (decoded != null) return decoded
        }
        val id = ByteArray(4)
        SecureRandom().nextBytes(id)
        prefs.edit().putString(KEY_INSTANCE_ID, encodeIdHex(id)).apply()
        return id
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _isAdvertising = MutableStateFlow(false)
    override val isAdvertising: Flow<Boolean> = _isAdvertising

    private val _connectedDeviceCount = MutableStateFlow(0)
    override val connectedDeviceCount: Flow<Int> = _connectedDeviceCount

    private var gattServer: BluetoothGattServer? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevices.add(device)
            } else {
                connectedDevices.remove(device)
            }
            _connectedDeviceCount.value = connectedDevices.size
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == TELEMETRY_CHAR_UUID) {
                @SuppressLint("MissingPermission")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    characteristic.value ?: ByteArray(0),
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID && responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value,
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startAdvertising() {
        if (_isAdvertising.value) return

        // Guard: on Android 12+ the BLUETOOTH_ADVERTISE runtime permission must be granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val adapter = bluetoothManager.adapter ?: return

        // Open GATT server
        gattServer = bluetoothManager.openGattServer(context, gattCallback)

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        telemetryCharacteristic = BluetoothGattCharacteristic(
            TELEMETRY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // CCCD descriptor is required for BLE notification subscription
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        telemetryCharacteristic!!.addDescriptor(cccd)
        service.addCharacteristic(telemetryCharacteristic)
        gattServer?.addService(service)

        // Start BLE advertising
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(COMPANY_ID, instanceId)
            .build()

        adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopAdvertising() {
        val adapter = bluetoothManager.adapter
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)

        gattServer?.close()
        gattServer = null
        telemetryCharacteristic = null
        connectedDevices.clear()
        _connectedDeviceCount.value = 0
        _isAdvertising.value = false
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override suspend fun pushPayload(payload: HudPayload) {
        val char = telemetryCharacteristic ?: return
        val bytes = encodePayload(payload)
        char.value = bytes

        // Notify all connected devices
        for (device in connectedDevices.toSet()) {
            gattServer?.notifyCharacteristicChanged(device, char, false)
        }
    }

}
