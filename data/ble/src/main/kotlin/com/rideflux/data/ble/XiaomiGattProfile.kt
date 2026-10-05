package com.rideflux.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.io.IOException
import java.util.UUID

/** Strict L2 FE95 authentication + Nordic UART binding, with no family fallback. */
internal class XiaomiGattProfile(
    val upnp: BluetoothGattCharacteristic,
    val avdtp: BluetoothGattCharacteristic,
    val uartWrite: BluetoothGattCharacteristic,
    val uartNotify: BluetoothGattCharacteristic,
) {
    val notifications get() = listOf(upnp, avdtp, uartNotify)
    companion object {
        val UPNP: UUID get() = GattUuids.CHAR_MI_UPNP
        val AVDTP: UUID get() = GattUuids.CHAR_MI_AVDTP
        fun resolve(gatt: BluetoothGatt): XiaomiGattProfile {
            val auth = gatt.getService(GattUuids.SERVICE_FE95) ?: missing()
            val nus = gatt.getService(GattUuids.SERVICE_NUS) ?: missing()
            return XiaomiGattProfile(
                auth.getCharacteristic(UPNP) ?: missing(), auth.getCharacteristic(AVDTP) ?: missing(),
                nus.getCharacteristic(GattUuids.CHAR_NUS_RX) ?: missing(),
                nus.getCharacteristic(GattUuids.CHAR_NUS_TX) ?: missing(),
            ).also { profile ->
                profile.notifications.forEach {
                    if (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) missing()
                }
                listOf(profile.upnp, profile.avdtp, profile.uartWrite).forEach(::writeType)
            }
        }
        fun writeType(characteristic: BluetoothGattCharacteristic): Int = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> missing()
        }
        private fun missing(): Nothing = throw IOException("Required Xiaomi GATT profile is absent")
    }
}
