# Implementation Notes

> Authored by Team B. Team B has never viewed the external reference
> project. The only permitted inputs are the tagged specification bundle in
> [../spec/](../spec/) and public vendor / language documentation.

This document records design decisions and rationale for the new
implementation. It must not cite, quote, or paraphrase the reference
project, and it must not reference source-derived identifiers.

## 1. Module Layout

The clean-room code lives under `data/protocol/src/main/kotlin/com/rideflux/protocol/`
so it sits alongside — but is fully separate from — any legacy code
that may exist in the same Gradle module. The `com.rideflux.protocol.*`
package tree is pure Kotlin/JVM and has no Android SDK dependencies.

Current packages:

| Package                                 | Purpose                                               |
|-----------------------------------------|-------------------------------------------------------|
| `com.rideflux.protocol.bytes`           | Endian-aware byte readers (signed / unsigned helpers) |
| `com.rideflux.protocol.familyg`         | Family G (Begode) frame models + decoder + command builder |
| `com.rideflux.protocol.familyk`         | Family K (KingSong) frame models + decoder + command builder |
| `com.rideflux.protocol.familyv`         | Family V (Veteran) frame model + decoder + CRC32      |
| `com.rideflux.protocol.testutil` (test) | Hex-literal parser used by JUnit vectors              |

Later revisions will add `familyn1`, `familyn2`, `familyi1`,
`familyi2`, and `obfuscation` packages as those families are
implemented.

## 2. Parsing Utilities

`com.rideflux.protocol.bytes.ByteReader` exposes `u8`, `u16BE`,
`s16BE`, `u32BE`, `u16LE`, `s16LE`, `u32LE`.

Decisions:

- All readers return JVM primitives (`Int` / `Long`). `Byte` is signed
  on the JVM, so every byte is masked with `0xFF` when an unsigned
  interpretation is desired.
- Signed 16-bit reads are obtained by promoting the unsigned value
  through `toShort().toInt()`, relying on the JVM's built-in sign
  extension. This is simpler and less error-prone than manual masking.
- 32-bit unsigned values are returned as `Long` to preserve the full
  range without depending on Kotlin's still-experimental `ULong`.
- The object is `internal`: it is an implementation detail and should
  not leak onto the public API. Tests live in the same Gradle module
  and therefore retain access.
- No explicit bounds checks are performed; the decoders validate
  frame length *before* indexing, and the JVM throws
  `ArrayIndexOutOfBoundsException` otherwise.

## 3. Decoder Design

Each family has its own top-level `object` decoder with a single
`decode(frame: ByteArray): TFrame?` entry point that returns `null`
on header/footer mismatch so callers can resynchronise.

Telemetry is modelled as a `sealed class` per family:

- `BegodeFrame` → `LiveTelemetry`, `SettingsAndOdometer`, `Unknown`.
- `KingSongFrame` → `LivePageA`, `LivePageB`, `Unknown`.

Every variant is a `data class` holding raw, scaled integer fields
(hundredths of a volt, hundredths of an amp, metres, etc.), plus
convenience properties that expose floating-point SI units. Raw
integer storage keeps the models bit-exact and loss-free; the
convenience accessors isolate the rounding behaviour.

`Unknown` variants preserve the payload verbatim so higher layers can
log and move on, which satisfies the forward-compatibility requirement
in §12.

Ambiguous fields (spec §8.1, MPU6050 vs MPU6500 temperature scaling)
are stored as **raw counts** on the telemetry model; separate helpers
on `BegodeTemperature` perform the conversion. Callers must select
the sensor model out-of-band from the ASCII `MPU*` identifier received
during the §9.1 handshake. The default is deliberately not hard-coded.

Battery curves (§7) are exposed via `BegodeBatteryCurve.linearPercent`
and `refinedPercent`, both operating on the unscaled hundredths-of-a-
volt integer so the UI layer does not have to round-trip through
floats.

## 4. Encoder Design

Two host-to-device command builders have been added:

- `com.rideflux.protocol.familyg.BegodeCommandBuilder` returns ASCII
  byte sequences per §9.1 / §10.1. Commands whose byte layout is
  fully specified (`N`, `V`, `b`, `m`, `g`, `E`/`Q`/`T`,
  `<`/`=`/`>`, `h`/`f`/`s`, `o`/`u`/`i`, `cy`) are exposed as named
  helpers. The two-byte wheel-calibration sequence (`c` then `y`
  ~300 ms later per §10.1) is returned as a single two-byte array;
  the transport layer owns the inter-byte timing.
- `com.rideflux.protocol.familyk.KingSongCommandBuilder` returns the
  fixed 20-byte host-to-device frame per §2.2 / §3.2, with header
  `AA 55` at 0..1, command code at 16, command-specific constant at
  17 (default `0x14`), and tail `5A 5A` at 18..19. A generic
  `command(commandCode, payload, trailerMagic)` factory is exposed
  for cases the spec only outlines, and named helpers cover the
  well-specified commands: `requestSerialNumber` (`0x63`),
  `requestDeviceName` (`0x9B`), `requestAlarmSettings` (`0x98`),
  `beep` (`0x88`), `wheelCalibration` (`0x89`), `powerOff` (`0x40`),
  and `setPedalsMode` (`0x87`, trailer `0x15`, `0xE0` magic at frame
  offset 3). Commands the spec only partially describes
  (`0x85 Set alarm speeds & max speed`, `0x73 Light mode`,
  `0x6C LED mode`, `0x53 Strobe mode`, `0x3F Stand-by delay`,
  `0x8A Charge-up-to / gyro adjust`) are intentionally **not**
  provided as named helpers until the spec documents their payload
  layouts — callers can use the generic factory if they are willing
  to supply the raw bytes.

Tests round-trip every named KingSong helper back through
`KingSongDecoder`: commands whose code is not a known telemetry
variant (the 0x63 / 0x9B / 0x98 / 0x88 / 0x89 / 0x40 set) must decode
to `KingSongFrame.Unknown` with the exact command code preserved and
the default trailer `0x14` visible through the decoder's `subIndex`
field. This asserts encoder / decoder wire consistency.

## 5. Checksum / CRC Implementation

Family V's CRC-32/ISO-HDLC is delegated to `java.util.zip.CRC32` from
the JDK. That class implements the exact variant required by §6.2
(polynomial `0xEDB88320`, init / xorout `0xFFFFFFFF`, reflected input
and output). Using the JDK keeps the code free of any transcribed CRC
table and satisfies the clean-room rule (standard-library code is not
reference-project-derived).

CRC presence on a Family V frame is auto-detected from the buffer
length relative to the `L` byte: a buffer whose length equals
`5 + L + 4` carries a CRC and is validated; a buffer of length
`5 + L` is accepted as CRC-less. For the "once negotiated for the
session" rule (§2.3 / §6.2) the decoder exposes an
`expectCrcAlways: Boolean` parameter so the session layer can force
validation regardless of `L`.

Family N1/N2 sum-XOR checksums remain deferred.

## 6. Obfuscation / Crypto Implementation

Deferred. Family N2's XOR keystream handshake (§5.3) will live in
`com.rideflux.protocol.obfuscation` and will treat Family N1 as a
trivial all-zeros instance of the same algorithm.

## 7. Family V Word-Swap

Spec §8.3 defines a 32-bit word-swap for the two distance fields at
frame offsets 8 and 12. Given wire bytes `b0 b1 b2 b3`, the decoded
integer is `(b2 << 24) | (b3 << 16) | (b0 << 8) | b1`. The decoder
exposes this as a small `wordSwapU32` helper (package-private, `Long`
return type) so future tests can exercise it in isolation without
fabricating entire frames.

## 7. Test Strategy

Every hex block in `spec/TEST_VECTORS.md` is reproduced verbatim in a
JUnit test. Tests assert on the **raw** scaled integers (e.g.
`voltageHundredthsV == 6640`) *and* on a representative SI unit
accessor, so both the storage and the derived view are pinned.

Negative-path tests assert that `decode()` returns `null` for
malformed headers / footers, and that unknown type codes fall through
to the `Unknown` variant with the payload preserved.

Initial run: 20 tests, 20 passing, 0 failing.

Family V follow-up: 8 tests added (vector §4, vector §7, synthetic
CRC round-trip, bad-CRC rejection, `L > 38` requires CRC, bad-magic
rejection, word-swap helper, firmware-version encoding edge cases).
After Family V: 28 tests, 28 passing.

Command-builder follow-up: 18 tests added (8 for
`BegodeCommandBuilder` pinning every ASCII byte sequence in §9.1 /
§10.1, 10 for `KingSongCommandBuilder` covering header/tail/command
code/trailer-magic invariants, payload-size validation, the
`setPedalsMode` frame format with its `0x15` trailer and `0xE0`
magic, a raw-payload round-trip, and an encoder ⇄ decoder
round-trip for every named helper). Module total: 46 tests, 46
passing, 0 failing.

## 8. Spec Changes Initiated by Implementation

During implementation the following ambiguities were reported back to
the spec rather than guessed. No reference-project source was
consulted.

1. **TEST_VECTORS.md §2, alert-bitmap commentary.** The original note
   described `0x07` as "bit 1, bit 2, bit 3 → Speed2, Speed1,
   LowVoltage", which contradicts the bit-0-based table in normative
   §4.1. Commentary was corrected to "bits 0, 1, 2 → WheelAlarm,
   SpeedAlarmL2, SpeedAlarmL1".
2. **PROTOCOL_SPEC.md §3.1.4, LED / alert / light byte offsets.** The
   hex vector in TEST_VECTORS.md §2 placed LED mode at byte 14, alert
   bitmap at byte 15, and light mode at byte 17; the spec table
   previously listed 13 / 14 / 15. The spec table was corrected to
   14 / 15 / 17 and an explicit note was added that bytes 12–13 and
   16 are reserved.
3. **PROTOCOL_SPEC.md §8.3, word-swap example bytes.** The prose
   example stated `00 00 07 1F ⇒ 1823` metres, but applying the
   stated formula to those bytes yields `0x071F0000` (118 947 840),
   not 1823. The real TEST_VECTORS.md §4 hex capture places
   `07 1F 00 00` at frame offset 8 — applying the formula to those
   bytes correctly yields 1823. Commentary was updated to cite the
   correct byte sequence `07 1F 00 00`.
4. **TEST_VECTORS.md §4, decoded-table commentary and frame
   truncation.** Revision 1.0 cited `CD 00` for voltage, `00 00 07 1F`
   for speed, and `00 3A = 58` for firmware — none of which match
   the actual hex at the spec-defined offsets. The hex was also
   truncated to 36 bytes while `L = 0x25 = 37` requires a 42-byte
   frame (5-byte header + 37-byte payload). Fix: the hex was padded
   to 42 bytes with six trailing zero bytes (offsets 36..41 are
   reserved per §3.3) and the decoded table was rewritten to reflect
   the correct frame-absolute offsets and values.
5. **TEST_VECTORS.md §7, CRC-32/ISO-HDLC expected value.** Revision
   1.0 stated `0xB4B1B1F8`. The actual CRC-32/ISO-HDLC (polynomial
   `0xEDB88320`, init/xorout `0xFFFFFFFF`, reflected in/out) of the
   given 15-byte input is `0x08009D77`, as confirmed by
   `java.util.zip.CRC32`. The spec was corrected and an errata note
   was added.
6. **PROTOCOL_SPEC.md §2.2, host-to-device byte 17.** The first
   prose description said byte 17 was a "sub-index" alongside the
   command code, but §3.2's row for `0x87 Set pedals mode` notes
   `@17=0x15`, and other commands leave it at `0x14`. The §2.2
   description was rewritten to clarify that byte 17 is a
   command-specific constant (default `0x14`) and that particular
   commands override it per §3.2. The command builders treat this
   byte as a per-command trailer-magic parameter.

## 9. Build Configuration

A root-level `subprojects { … }` block was added to pin
`sourceCompatibility` / `targetCompatibility` and the Kotlin
`jvmTarget` to JVM 17 for every module. This was necessary because
the only JDK available in the build environment is JDK 25, whose
default Java release exceeds Kotlin 2.0's supported JVM-target
ceiling, producing the "Inconsistent JVM-target compatibility" error.
JVM 17 is supported by both Kotlin 2.0 and the Android Gradle Plugin
in use.
# Implementation Notes

> Authored by Team B. Team B has never viewed the external reference
> project. The only permitted inputs are the tagged specification bundle in
> [../spec/](../spec/) and public vendor / language documentation.

This document records design decisions and rationale for the new
implementation. It must not cite, quote, or paraphrase the reference
project, and it must not reference source-derived identifiers.

## 1. Module Layout

- _Describe the package structure chosen under `com.rideflux.*`._
- _Describe which modules are platform-agnostic Kotlin vs. Android-specific._

## 2. Parsing Utilities

- _Document the byte-reader API (e.g. `readInt16LE`, `readUInt16LE`, etc.)._
- _Document signed/unsigned conversion choices._
- _Document bounds-checking and error-handling strategy._

## 3. Decoder Design

- _How telemetry frames are dispatched to family-specific decoders._
- _How immutable telemetry models are structured._
- _How ambiguous fields flagged in the spec are handled (safe defaults)._

## 4. Encoder Design

- _How command frames are constructed and validated._

## 5. Checksum / CRC Implementation

- _Which algorithm is used, per spec §6._
- _Whether the implementation is table-driven or bit-by-bit, and why._
  Tables, if used, are generated at build time or at initialization from
  the polynomial documented in the spec; they are not transcribed from any
  external source.

## 6. Obfuscation / Crypto Implementation

- _Which library or primitive is used._
- _Key derivation, per spec §7._

## 7. Testing

- _How the JSON/plain-text test vectors are loaded._
- _How edge cases (truncated frames, bad checksums, unknown families) are
  covered beyond the vectors._

## 8. Open Questions for Team A

Raise questions by ID; each question is routed through the Coordinator and
either answered from the spec or escalated to a spec amendment.

- **Q-001** — _Short title._
  - **Context:** _what the implementation needs to know._
  - **Proposed safe default:** _what Team B will implement pending answer._
