package com.rideflux.domain.bond

/** The payload is not a valid `rideflux-bond/v1` document. */
class BondFormatException(message: String) : Exception(message)

/** Entries read from a payload, plus how many belonged to a family this build cannot use. */
class BondPayload(val entries: List<BondEntry>, val skippedUnsupported: Int) {
    fun wipe() = entries.forEach(BondEntry::wipe)
}

/**
 * Strict reader/writer for the `rideflux-bond/v1` JSON document.
 *
 * A small hand-written codec instead of a JSON library: `:domain` has no JSON dependency, the
 * schema is tiny, and it lets the credential travel as bytes. `credentialHex` is encoded from and
 * decoded into byte arrays directly, so the secret never exists as a `String` that would linger
 * until garbage collection.
 */
object BondPayloadCodec {
    const val SCHEMA = "rideflux-bond/v1"
    const val MAX_ENTRIES = 64
    private const val MAX_DEPTH = 6

    /** Serialise [entries]. The result holds secrets: the caller must zero it. */
    fun write(entries: List<BondEntry>, createdAt: String): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "Too many entries" }
        val out = SecretBuffer()
        try {
            out.ascii("{\"schema\":").string(SCHEMA).ascii(",\"createdAt\":").string(createdAt)
            out.ascii(",\"entries\":[")
            entries.forEachIndexed { index, entry ->
                if (index > 0) out.byte(','.code)
                writeEntry(out, entry)
            }
            out.ascii("]}")
            return out.toByteArray()
        } finally {
            out.wipe()
        }
    }

    private fun writeEntry(out: SecretBuffer, entry: BondEntry) {
        out.ascii("{\"mac\":").string(entry.mac).ascii(",\"family\":").string(entry.family.id)
        out.ascii(",\"credentialHex\":\"")
        val secret = entry.credential()
        try {
            secret.forEach(out::hexByte)
        } finally {
            secret.fill(0)
        }
        out.ascii("\",\"label\":").string(entry.label)
        entry.model?.let { out.ascii(",\"model\":").string(it) }
        out.byte('}'.code)
    }

    /** Parse [bytes]; the caller still owns (and should zero) [bytes]. */
    fun read(bytes: ByteArray): BondPayload = Reader(bytes).document()

    private class Text(val start: Int, val end: Int, val escaped: Boolean)

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun document(): BondPayload {
            ws()
            expect('{')
            var schema: String? = null
            var parsed: BondPayload? = null
            members { key ->
                when (key) {
                    "schema" -> schema = string().text()
                    "entries" -> parsed = entries()
                    else -> skipValue(1)
                }
            }
            ws()
            if (pos != b.size) fail("Trailing data")
            if (schema != SCHEMA) fail("Unsupported schema")
            return parsed ?: fail("Missing entries")
        }

        private inline fun members(each: (String) -> Unit) {
            ws()
            if (peek() == '}'.code) { pos++; return }
            while (true) {
                ws()
                val key = string().text()
                ws(); expect(':'); ws()
                each(key)
                ws()
                when (next()) {
                    ','.code -> Unit
                    '}'.code -> return
                    else -> fail("Expected , or }")
                }
            }
        }

        private fun entries(): BondPayload {
            expect('[')
            val result = ArrayList<BondEntry>()
            var skipped = 0
            try {
                ws()
                if (peek() == ']'.code) { pos++ } else {
                    while (true) {
                        ws()
                        if (result.size + skipped >= MAX_ENTRIES) fail("Too many entries")
                        val entry = entry()
                        if (entry == null) skipped++ else result += entry
                        ws()
                        when (next()) {
                            ','.code -> Unit
                            ']'.code -> break
                            else -> fail("Expected , or ]")
                        }
                    }
                }
                if (result.map(BondEntry::mac).toSet().size != result.size) fail("Duplicate address")
                return BondPayload(result, skipped)
            } catch (e: Exception) {
                result.forEach(BondEntry::wipe)
                throw e
            }
        }

        /** Returns null for a family this build does not know (kept out, counted as skipped). */
        private fun entry(): BondEntry? {
            expect('{')
            var mac: String? = null
            var familyId: String? = null
            var secret: ByteArray? = null
            var label = ""
            var model: String? = null
            try {
                members { key ->
                    when (key) {
                        "mac" -> mac = string().text()
                        "family" -> familyId = string().text()
                        "credentialHex" -> { secret?.fill(0); secret = hex(string()) }
                        "label" -> label = string().text()
                        "model" -> model = string().text()
                        else -> skipValue(1)
                    }
                }
                val family = BondFamily.fromId(familyId ?: fail("Entry without family")) ?: return null
                return try {
                    BondEntry(mac ?: fail("Entry without address"), family,
                        secret ?: fail("Entry without credential"), label, model)
                } catch (e: IllegalArgumentException) {
                    fail(e.message ?: "Invalid entry")
                }
            } finally {
                secret?.fill(0)
            }
        }

        private fun hex(text: Text): ByteArray {
            if (text.escaped || (text.end - text.start) % 2 != 0) fail("Invalid credential")
            val out = ByteArray((text.end - text.start) / 2)
            for (i in out.indices) {
                val hi = nibble(b[text.start + 2 * i].toInt())
                val lo = nibble(b[text.start + 2 * i + 1].toInt())
                if (hi < 0 || lo < 0) { out.fill(0); fail("Invalid credential") }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private fun nibble(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }

        /** Reads a string token without allocating it; [text] decodes it on demand. */
        private fun string(): Text {
            expect('"')
            val start = pos
            var escaped = false
            while (true) {
                when (next()) {
                    '"'.code -> return Text(start, pos - 1, escaped)
                    '\\'.code -> { escaped = true; next() }
                    in 0..0x1f -> fail("Control character in string")
                }
            }
        }

        private fun Text.text(): String {
            val raw = String(b, start, end - start, Charsets.UTF_8)
            return if (escaped) unescape(raw) else raw
        }

        private fun unescape(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i++]
                if (c != '\\') { sb.append(c); continue }
                when (val e = raw.getOrNull(i++) ?: fail("Bad escape")) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val code = raw.substring(i, minOf(i + 4, raw.length)).toIntOrNull(16)
                        if (code == null || i + 4 > raw.length) fail("Bad escape")
                        sb.append(code.toChar()); i += 4
                    }
                    else -> fail("Bad escape")
                }
            }
            return sb.toString()
        }

        private fun skipValue(depth: Int) {
            if (depth > MAX_DEPTH) fail("Nested too deeply")
            when (peek()) {
                '"'.code -> string()
                '{'.code -> { pos++; members { skipValue(depth + 1) } }
                '['.code -> skipArray(depth)
                else -> skipLiteral()
            }
        }

        private fun skipArray(depth: Int) {
            pos++
            ws()
            if (peek() == ']'.code) { pos++; return }
            while (true) {
                ws(); skipValue(depth + 1); ws()
                when (next()) {
                    ','.code -> Unit
                    ']'.code -> return
                    else -> fail("Expected , or ]")
                }
            }
        }

        private fun skipLiteral() {
            val start = pos
            while (pos < b.size && b[pos].toInt().toChar() in "-+.eE0123456789truefalsn") pos++
            if (pos == start) fail("Unexpected token")
        }

        private fun ws() {
            while (pos < b.size && (b[pos].toInt() == 0x20 || b[pos].toInt() in 0x09..0x0d)) pos++
        }

        private fun peek(): Int = if (pos < b.size) b[pos].toInt() and 0xff else fail("Unexpected end")
        private fun next(): Int = peek().also { pos++ }
        private fun expect(c: Char) { if (next() != c.code) fail("Expected $c") }
        private fun fail(message: String): Nothing = throw BondFormatException(message)
    }
}

/** Growable byte buffer that can be zeroed, used so secrets are never copied into a String. */
internal class SecretBuffer {
    private var data = ByteArray(256)
    private var size = 0

    fun byte(value: Int): SecretBuffer {
        if (size == data.size) grow()
        data[size++] = value.toByte()
        return this
    }

    fun ascii(text: String): SecretBuffer { text.forEach { byte(it.code) }; return this }

    fun hexByte(value: Byte) {
        byte(HEX[(value.toInt() shr 4) and 0xf].code)
        byte(HEX[value.toInt() and 0xf].code)
    }

    /** A JSON string literal (quotes included), UTF-8 encoded. */
    fun string(text: String): SecretBuffer {
        byte('"'.code)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '"'.code || cp == '\\'.code -> { byte('\\'.code); byte(cp) }
                cp < 0x20 -> ascii("\\u%04x".format(cp))
                cp < 0x80 -> byte(cp)
                else -> String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    .forEach { byte(it.toInt() and 0xff) }
            }
        }
        byte('"'.code)
        return this
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    fun wipe() { data.fill(0); size = 0 }

    private fun grow() {
        val bigger = data.copyOf(data.size * 2)
        data.fill(0)
        data = bigger
    }

    private companion object { const val HEX = "0123456789abcdef" }
}
