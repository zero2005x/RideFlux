package com.rideflux.data.bridge

import com.rideflux.domain.settings.HudLayoutProfile

/** Small, versioned payload that fits the default BLE ATT MTU. */
object HudProfileCodec {
    fun encode(profile: HudLayoutProfile): ByteArray {
        val p = profile.normalized()
        return byteArrayOf(
            0x48, 1,
            p.leftInset.toByte(), p.rightInset.toByte(),
            p.topInset.toByte(), p.bottomInset.toByte(),
            p.offsetX.toByte(), p.offsetY.toByte(),
            p.fontPercent.toByte(), p.visibleItems.toByte(),
        )
    }

    fun decode(bytes: ByteArray): HudLayoutProfile? {
        if (bytes.size != 10 || bytes[0] != 0x48.toByte() || bytes[1] != 1.toByte()) return null
        return HudLayoutProfile(
            leftInset = bytes[2].toInt() and 0xff,
            rightInset = bytes[3].toInt() and 0xff,
            topInset = bytes[4].toInt() and 0xff,
            bottomInset = bytes[5].toInt() and 0xff,
            offsetX = bytes[6].toInt(),
            offsetY = bytes[7].toInt(),
            fontPercent = bytes[8].toInt() and 0xff,
            visibleItems = bytes[9].toInt() and 0xff,
        ).normalized()
    }
}
