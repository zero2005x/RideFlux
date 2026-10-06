package com.rideflux.protocol.familyscooter.xiaomi

/** Owned secret arrays; callers must wipe after use and must not log their contents. */
class MiSetupKeys internal constructor(val token: ByteArray, val didKey: ByteArray) {
    fun wipe() { token.fill(0); didKey.fill(0) }
    override fun toString() = "MiSetupKeys(<redacted>)"
}

class MiLoginKeys internal constructor(
    val devKey: ByteArray,
    val appKey: ByteArray,
    val devIv: ByteArray,
    val appIv: ByteArray,
    val loginInfo: ByteArray,
    val scooterProof: ByteArray,
) {
    fun wipe() {
        listOf(devKey, appKey, devIv, appIv, loginInfo, scooterProof).forEach { it.fill(0) }
    }
    override fun toString() = "MiLoginKeys(<redacted>)"
}

object MiKeys {
    fun setup(sharedSecret: ByteArray): MiSetupKeys {
        require(sharedSecret.size == 32) { "Invalid shared secret length" }
        val output = MiHkdf.derive(sharedSecret, null, "mible-setup-info".toByteArray(Charsets.US_ASCII), 64)
        return try {
            MiSetupKeys(output.copyOfRange(0, 12), output.copyOfRange(28, 44))
        } finally { output.fill(0) }
    }

    fun login(token: ByteArray, appRandom: ByteArray, scooterRandom: ByteArray): MiLoginKeys {
        require(token.size == 12 && appRandom.size == 16 && scooterRandom.size == 16) {
            "Invalid login input length"
        }
        val salt = appRandom + scooterRandom
        val reverseSalt = scooterRandom + appRandom
        return try {
            val output = MiHkdf.derive(token, salt, "mible-login-info".toByteArray(Charsets.US_ASCII), 64)
            try { loginKeys(output, salt, reverseSalt) } finally { output.fill(0) }
        } finally { salt.fill(0); reverseSalt.fill(0) }
    }

    private fun loginKeys(output: ByteArray, salt: ByteArray, reverseSalt: ByteArray): MiLoginKeys {
        val dev = output.copyOfRange(0, 16)
        val app = output.copyOfRange(16, 32)
        val devIv = output.copyOfRange(32, 36)
        val appIv = output.copyOfRange(36, 40)
        var loginInfo: ByteArray? = null
        return try {
            loginInfo = MiHkdf.hmac(app, salt)
            MiLoginKeys(dev, app, devIv, appIv, loginInfo, MiHkdf.hmac(dev, reverseSalt))
        } catch (error: Exception) {
            listOf(dev, app, devIv, appIv).forEach { it.fill(0) }
            loginInfo?.fill(0)
            throw error
        }
    }
}
