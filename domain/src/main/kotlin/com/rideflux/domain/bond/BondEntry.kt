package com.rideflux.domain.bond

/**
 * Credential families whose persistent pairing value can be backed up.
 *
 * Only values that are bytes remembered between sessions belong here. The Rokid
 * bridge token (phone <-> glasses) is deliberately not a family: it is a
 * different secret with a different owner and never enters a bond backup.
 */
enum class BondFamily(val id: String, val credentialBytes: Int) {
    /** Xiaomi Mi authentication token (M365 family), 12 bytes, one per scooter. */
    XIAOMI_MI("xiaomi_mi", 12),

    /** Ninebot legacy-crypto app random used for fast re-pairing, 16 bytes. */
    NINEBOT_CRYPTO("ninebot_crypto", 16),
    ;

    companion object {
        fun fromId(id: String): BondFamily? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One scooter's pairing credential. The secret is held privately and handed out only as a
 * copy, so every caller owns (and must wipe) the bytes it asked for. [toString] never prints it.
 */
class BondEntry(
    mac: String,
    val family: BondFamily,
    credential: ByteArray,
    val label: String = "",
    val model: String? = null,
) {
    val mac: String = normalizeMac(mac)
    private val secret: ByteArray = credential.copyOf()

    init {
        require(secret.size == family.credentialBytes) {
            "${family.id} credential must be ${family.credentialBytes} bytes"
        }
        require(label.length <= MAX_TEXT && label.none(Char::isISOControl)) { "Invalid label" }
        require(model == null || (model.length <= MAX_TEXT && model.none(Char::isISOControl))) {
            "Invalid model"
        }
    }

    /** A fresh copy of the secret. The caller must zero it after use. */
    fun credential(): ByteArray = secret.copyOf()

    /** Zero the held secret. The entry is unusable for authentication afterwards. */
    fun wipe() = secret.fill(0)

    fun sameCredential(other: BondEntry): Boolean = secret.contentEquals(other.secret)

    /** Last three bytes of the address only; enough to tell scooters apart on screen. */
    fun maskedMac(): String = "••:••:••:••:" + mac.takeLast(5)

    override fun equals(other: Any?): Boolean =
        other is BondEntry && mac == other.mac && family == other.family &&
            label == other.label && model == other.model && secret.contentEquals(other.secret)

    override fun hashCode(): Int =
        ((mac.hashCode() * 31 + family.hashCode()) * 31 + label.hashCode()) * 31 + secret.contentHashCode()

    override fun toString(): String = "BondEntry(mac=${maskedMac()}, family=${family.id}, credential=***)"

    companion object {
        const val MAX_TEXT = 64
        private val MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        fun normalizeMac(raw: String): String {
            val upper = raw.trim().uppercase()
            require(MAC.matches(upper)) { "Invalid Bluetooth address" }
            return upper
        }
    }
}
