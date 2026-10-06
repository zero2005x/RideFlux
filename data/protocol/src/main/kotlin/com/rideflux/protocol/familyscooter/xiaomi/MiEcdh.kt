package com.rideflux.protocol.familyscooter.xiaomi

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

object MiEcdh {
    private val parameters: ECParameterSpec get() = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun publicRaw(key: ECPublicKey): ByteArray {
        val raw = coordinate(key.w.affineX) + coordinate(key.w.affineY)
        decodePublic(raw)
        require(key.params.order == parameters.order && key.params.curve == parameters.curve) {
            "Invalid public key curve"
        }
        return raw
    }

    fun decodePublic(raw: ByteArray): ECPublicKey {
        require(raw.size == 64) { "Invalid public key length" }
        val spec = parameters
        val x = BigInteger(1, raw.copyOfRange(0, 32))
        val y = BigInteger(1, raw.copyOfRange(32, 64))
        val p = (spec.curve.field as java.security.spec.ECFieldFp).p
        require(x < p && y < p && y.multiply(y).mod(p) ==
            x.pow(3).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(p)) { "Invalid public key point" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec)) as ECPublicKey
    }

    fun sharedSecret(privateKey: PrivateKey, peerRaw: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(decodePublic(peerRaw), true)
        }.generateSecret().also { require(it.size == 32) { "Invalid shared secret length" } }

    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        require(bytes.size <= 33) { "Invalid coordinate length" }
        return ByteArray(32).apply {
            val skip = if (bytes.size == 33) 1 else 0
            bytes.copyInto(this, 32 - (bytes.size - skip), skip)
        }
    }
}
