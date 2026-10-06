package com.rideflux.domain.bond

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BondPayloadCodecTest {
    private val hex12 = "000102030405060708090a0b"
    private val hex16 = "000102030405060708090a0b0c0d0e0f"

    private fun entry(mac: String, label: String = "", model: String? = null) =
        BondEntry(mac, BondFamily.XIAOMI_MI, ByteArray(12) { it.toByte() }, label, model)

    private fun read(json: String) = BondPayloadCodec.read(json.toByteArray(Charsets.UTF_8))

    private fun document(vararg entries: String, extra: String = "") =
        """{"schema":"rideflux-bond/v1"$extra,"entries":[${entries.joinToString(",")}]}"""

    private fun item(mac: String = "AA:BB:CC:DD:EE:FF", family: String = "xiaomi_mi", hex: String = hex12,
                     extra: String = "") =
        """{"mac":"$mac","family":"$family","credentialHex":"$hex","label":"x"$extra}"""

    private fun assertMalformed(json: String) {
        assertThrows(BondFormatException::class.java) { read(json) }
    }

    @Test fun `round trip keeps every field including unicode and escapes`() {
        val label = "Mi \"Pro\" \\ 小米 🛴"
        val written = BondPayloadCodec.write(
            listOf(entry("AA:BB:CC:DD:EE:FF", label, "M365"),
                BondEntry("11:22:33:44:55:66", BondFamily.NINEBOT_CRYPTO, ByteArray(16) { it.toByte() })),
            "2026-10-05T00:00:00Z")
        val back = BondPayloadCodec.read(written)
        assertEquals(0, back.skippedUnsupported)
        assertEquals(label, back.entries[0].label)
        assertEquals("M365", back.entries[0].model)
        assertEquals(BondFamily.NINEBOT_CRYPTO, back.entries[1].family)
        assertArrayEquals(ByteArray(16) { it.toByte() }, back.entries[1].credential())
        back.wipe()
        assertArrayEquals(ByteArray(12), back.entries[0].credential())
    }

    @Test fun `the document contains only the documented keys`() {
        val text = String(BondPayloadCodec.write(listOf(entry("AA:BB:CC:DD:EE:FF", "a", "m")), "t"))
        val keys = Regex("\"(\\w+)\":").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("schema", "createdAt", "entries", "mac", "family", "credentialHex", "label", "model"), keys)
        assertTrue(text.contains(hex12))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("rokid", ignoreCase = true))
    }

    @Test fun `unknown families are skipped and unknown keys are ignored`() {
        val parsed = read(document(
            item(family = "future_family", hex = "abcd"),
            item(extra = ""","note":{"a":[1,2.5e3,true,false,null,"s",{"k":[]}]},"n":-1"""),
            extra = ""","generator":"x","count":3""",
        ))
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.skippedUnsupported)
    }

    @Test fun `an empty entry list and extra whitespace are fine`() {
        assertEquals(0, read(document()).entries.size)
        assertEquals(1, read(" \n{ \"schema\" : \"rideflux-bond/v1\" , \"entries\" : [ ${item()} ] }\t").entries.size)
    }

    @Test fun `escapes in text fields are decoded`() {
        val parsed = read(document(
            """{"mac":"AA:BB:CC:DD:EE:FF","family":"xiaomi_mi","credentialHex":"$hex12","label":"aé\/\\\""}""",
        ))
        assertEquals("aé/\\\"", parsed.entries.single().label)
    }

    @Test fun `hex credentials accept upper case and the last duplicate wins`() {
        val upper = read(document(item(hex = hex12.uppercase())))
        assertArrayEquals(ByteArray(12) { it.toByte() }, upper.entries[0].credential())
        val twice = read(document("""{"mac":"AA:BB:CC:DD:EE:FF","family":"xiaomi_mi","credentialHex":"${"ff".repeat(12)}","credentialHex":"$hex12"}"""))
        assertArrayEquals(ByteArray(12) { it.toByte() }, twice.entries[0].credential())
    }

    @Test fun `structurally broken documents are rejected`() {
        assertMalformed("")
        assertMalformed("[]")
        assertMalformed("""{"schema":"rideflux-bond/v1"}""")
        assertMalformed("""{"schema":"other/v9","entries":[]}""")
        assertMalformed("""{"entries":[]}""")
        assertMalformed(document() + " x")
        assertMalformed("""{"schema":"rideflux-bond/v1","entries":[""")
        assertMalformed("""{"schema":"rideflux-bond/v1","entries":[${item()}""")
        assertMalformed("""{"schema":"rideflux-bond/v1","entries":[${item()};]}""")
        assertMalformed("""{"schema":"rideflux-bond/v1" "entries":[]}""")
        assertMalformed("""{"schema":"rideflux-bond/v1","entries":{}}""")
        assertMalformed("""{"schema":"rideflux-bond/v1","entries":[],}""")
        assertMalformed("""{"schema":"rideflux-bond/v1","entries":[],"x":}""")
    }

    @Test fun `bad strings and nesting are rejected`() {
        assertMalformed(document(item().replace("\"x\"", "\"a\u0001b\"")))
        assertMalformed(document(item().replace("\"x\"", "\"bad\\q\"")))
        assertMalformed(document(item().replace("\"x\"", "\"bad\\u12\"")))
        assertMalformed(document(item().replace("\"x\"", "\"bad\\uZZZZ\"")))
        assertMalformed(document(item().replace("\"x\"", "\"unterminated")))
        val deep = "[".repeat(10) + "]".repeat(10)
        assertMalformed(document(item(extra = ""","d":$deep""")))
    }

    @Test fun `entries with missing or invalid fields are rejected`() {
        assertMalformed(document("""{"family":"xiaomi_mi","credentialHex":"$hex12"}"""))
        assertMalformed(document("""{"mac":"AA:BB:CC:DD:EE:FF","credentialHex":"$hex12"}"""))
        assertMalformed(document("""{"mac":"AA:BB:CC:DD:EE:FF","family":"xiaomi_mi"}"""))
        assertMalformed(document(item(mac = "nope")))
        assertMalformed(document(item(hex = hex16)))
        assertMalformed(document(item(hex = hex12.dropLast(1))))
        assertMalformed(document(item(hex = "zz" + hex12.drop(2))))
        assertMalformed(document(item(hex = "00\\u0030" + hex12.drop(4))))
        assertMalformed(document(item(extra = ""","label":"${"x".repeat(65)}"""")))
    }

    @Test fun `duplicate addresses and too many entries are rejected`() {
        assertMalformed(document(item(), item()))
        val many = (0 until BondPayloadCodec.MAX_ENTRIES + 1).map {
            item(mac = "AA:BB:CC:DD:%02X:%02X".format(it / 256, it % 256))
        }
        assertMalformed(document(*many.toTypedArray()))
        val allowed = many.take(BondPayloadCodec.MAX_ENTRIES)
        assertEquals(BondPayloadCodec.MAX_ENTRIES, read(document(*allowed.toTypedArray())).entries.size)
    }

    @Test fun `writing more than the maximum is refused`() {
        val tooMany = (0..BondPayloadCodec.MAX_ENTRIES).map { entry("AA:BB:CC:DD:%02X:%02X".format(it / 256, it % 256)) }
        assertThrows(IllegalArgumentException::class.java) { BondPayloadCodec.write(tooMany, "t") }
        assertNull(BondPayload(emptyList(), 0).entries.firstOrNull())
    }

    @Test fun `a large label does not break the buffer growth`() {
        val long = "é".repeat(BondEntry.MAX_TEXT)
        val written = BondPayloadCodec.write(List(40) { entry("AA:BB:CC:DD:00:%02X".format(it), long) }, "t")
        assertEquals(40, BondPayloadCodec.read(written).entries.size)
    }
}
