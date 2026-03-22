package com.wheellog.next.data.protocol

import com.wheellog.next.data.protocol.decoder.BegodeDecoder
import com.wheellog.next.data.protocol.decoder.FrameDecoder
import com.wheellog.next.data.protocol.decoder.InmotionDecoder
import com.wheellog.next.data.protocol.decoder.InmotionV2Decoder
import com.wheellog.next.data.protocol.decoder.KingSongDecoder
import com.wheellog.next.data.protocol.decoder.NinebotDecoder
import com.wheellog.next.data.protocol.decoder.NinebotZDecoder
import com.wheellog.next.data.protocol.decoder.VeteranDecoder
import com.wheellog.next.domain.model.TelemetryState
import com.wheellog.next.domain.model.WheelBleProfile
import com.wheellog.next.domain.model.WheelType
import com.wheellog.next.domain.protocol.ProtocolDecoder

/**
 * Composite [ProtocolDecoder] that detects the EUC brand from BLE device name
 * and dispatches decoding to brand-specific [FrameDecoder] implementations.
 */
class DefaultProtocolDecoder : ProtocolDecoder {

    private val decoders: Map<WheelType, FrameDecoder> = mapOf(
        WheelType.KINGSONG to KingSongDecoder(),
        WheelType.BEGODE to BegodeDecoder(),
        WheelType.GOTWAY to BegodeDecoder(),
        WheelType.VETERAN to VeteranDecoder(),
        WheelType.INMOTION to InmotionDecoder(),
        WheelType.INMOTION_V2 to InmotionV2Decoder(),
        WheelType.NINEBOT to NinebotDecoder(),
        WheelType.NINEBOT_Z to NinebotZDecoder(),
    )

    override fun detectWheelType(deviceName: String?): WheelType {
        if (deviceName == null) return WheelType.UNKNOWN
        val name = deviceName.uppercase()
        return when {
            name.startsWith("KS-") || name.startsWith("KS_") -> WheelType.KINGSONG

            // Veteran — must check before Begode since ABRAMS also starts with "A"
            name.startsWith("SHERMAN") || name.startsWith("LYNX")
                || name.startsWith("PATTON") || name.startsWith("ABRAMS")
                -> WheelType.VETERAN

            // Begode / Gotway
            name.startsWith("GW-") || name.startsWith("GW_")
                || name.startsWith("GOTWAY") || name.startsWith("BEGODE")
                || name.startsWith("EX") || name.startsWith("RS")
                || name.startsWith("RW") || name.startsWith("MCM")
                || name.startsWith("MTEN") || name.startsWith("NIKOLA")
                || name.startsWith("MONSTER") || name.startsWith("MSUPER")
                || name.startsWith("MSP") || name.startsWith("HERO")
                || name.startsWith("MASTER") || name.startsWith("A2")
                || name.startsWith("T3") || name.startsWith("T4")
                || name.startsWith("S2")
                -> WheelType.BEGODE

            // Inmotion v2 — newer models with specific identifiers
            name.startsWith("V11-") || name.startsWith("V12-") || name.startsWith("V13-")
                || name.startsWith("V14-") || name.startsWith("V11Y") || name.startsWith("V9-")
                || name.startsWith("V12S")
                -> WheelType.INMOTION_V2

            // Inmotion v1 — generic V-prefix short names, INMOTION prefix
            name.startsWith("V") && name.length <= 5 -> WheelType.INMOTION
            name.startsWith("INMOTION") -> WheelType.INMOTION

            // Ninebot Z — Z-series
            (name.startsWith("NINEBOT") || name.startsWith("SEGWAY"))
                && name.contains("Z") -> WheelType.NINEBOT_Z

            // Ninebot — standard
            name.startsWith("NINEBOT") || name.startsWith("SEGWAY") -> WheelType.NINEBOT

            else -> WheelType.UNKNOWN
        }
    }

    override fun bleProfileFor(wheelType: WheelType): WheelBleProfile? =
        WheelProfiles.profileFor(wheelType)

    override fun decode(wheelType: WheelType, bytes: ByteArray): TelemetryState? =
        decoders[wheelType]?.decode(bytes)
}
