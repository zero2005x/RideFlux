package com.wheellog.next.data.protocol

import com.wheellog.next.domain.model.WheelBleProfile
import com.wheellog.next.domain.model.WheelType

/**
 * Known BLE service/characteristic UUIDs for each EUC brand.
 */
object WheelProfiles {

    /** Generic HM-10 / FFE0 profile used by KingSong, Begode, Gotway, Veteran. */
    private const val FFE0_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private const val FFE1_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"

    /** Inmotion v1 specific UUIDs — notify on FFE4, write on FFE9. */
    private const val INMOTION_V1_NOTIFY_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private const val INMOTION_V1_NOTIFY_CHAR = "0000ffe4-0000-1000-8000-00805f9b34fb"
    private const val INMOTION_V1_WRITE_SERVICE = "0000ffe5-0000-1000-8000-00805f9b34fb"
    private const val INMOTION_V1_WRITE_CHAR = "0000ffe9-0000-1000-8000-00805f9b34fb"

    /** Nordic UART Service (NUS) used by Inmotion v2 and Ninebot Z. */
    private const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private const val NUS_TX_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    private const val NUS_RX_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    private val profiles = mapOf(
        WheelType.KINGSONG to WheelBleProfile(
            wheelType = WheelType.KINGSONG,
            serviceUuid = FFE0_SERVICE,
            notifyCharacteristicUuid = FFE1_CHARACTERISTIC,
            writeCharacteristicUuid = FFE1_CHARACTERISTIC,
        ),
        WheelType.BEGODE to WheelBleProfile(
            wheelType = WheelType.BEGODE,
            serviceUuid = FFE0_SERVICE,
            notifyCharacteristicUuid = FFE1_CHARACTERISTIC,
            writeCharacteristicUuid = FFE1_CHARACTERISTIC,
        ),
        WheelType.GOTWAY to WheelBleProfile(
            wheelType = WheelType.GOTWAY,
            serviceUuid = FFE0_SERVICE,
            notifyCharacteristicUuid = FFE1_CHARACTERISTIC,
            writeCharacteristicUuid = FFE1_CHARACTERISTIC,
        ),
        WheelType.VETERAN to WheelBleProfile(
            wheelType = WheelType.VETERAN,
            serviceUuid = FFE0_SERVICE,
            notifyCharacteristicUuid = FFE1_CHARACTERISTIC,
            writeCharacteristicUuid = FFE1_CHARACTERISTIC,
        ),
        WheelType.INMOTION to WheelBleProfile(
            wheelType = WheelType.INMOTION,
            serviceUuid = INMOTION_V1_NOTIFY_SERVICE,
            notifyCharacteristicUuid = INMOTION_V1_NOTIFY_CHAR,
            writeCharacteristicUuid = INMOTION_V1_WRITE_CHAR,
        ),
        WheelType.INMOTION_V2 to WheelBleProfile(
            wheelType = WheelType.INMOTION_V2,
            serviceUuid = NUS_SERVICE,
            notifyCharacteristicUuid = NUS_TX_CHARACTERISTIC,
            writeCharacteristicUuid = NUS_RX_CHARACTERISTIC,
        ),
        WheelType.NINEBOT to WheelBleProfile(
            wheelType = WheelType.NINEBOT,
            serviceUuid = NUS_SERVICE,
            notifyCharacteristicUuid = NUS_TX_CHARACTERISTIC,
            writeCharacteristicUuid = NUS_RX_CHARACTERISTIC,
        ),
        WheelType.NINEBOT_Z to WheelBleProfile(
            wheelType = WheelType.NINEBOT_Z,
            serviceUuid = NUS_SERVICE,
            notifyCharacteristicUuid = NUS_TX_CHARACTERISTIC,
            writeCharacteristicUuid = NUS_RX_CHARACTERISTIC,
        ),
    )

    fun profileFor(wheelType: WheelType): WheelBleProfile? = profiles[wheelType]
}
