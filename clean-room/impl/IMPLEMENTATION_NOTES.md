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
| `com.rideflux.protocol.familyg`         | Family G (Begode) frame models + decoder              |
| `com.rideflux.protocol.familyk`         | Family K (KingSong) frame models + decoder            |
| `com.rideflux.protocol.testutil` (test) | Hex-literal parser used by JUnit vectors              |

Later revisions will add `familyv`, `familyn1`, `familyn2`, `familyi1`,
`familyi2`, `crc`, and `obfuscation` packages as those families are
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

Not implemented in the initial pass. Family K and Family G both have
command-frame layouts defined in §3.2 / §9.1 / §10 of the spec; they
will be added in a follow-up change once decoder coverage is
complete.

## 5. Checksum / CRC Implementation

Not yet required by Family K (fixed length) or Family G (header /
footer only). Deferred to Family N1/N2 (sum-XOR) and Family V
(CRC-32/ISO-HDLC). When added, the CRC table will be generated at
class-load time from the polynomial in §6.2; no external CRC-table
transcription is permitted.

## 6. Obfuscation / Crypto Implementation

Deferred. Family N2's XOR keystream handshake (§5.3) will live in
`com.rideflux.protocol.obfuscation` and will treat Family N1 as a
trivial all-zeros instance of the same algorithm.

## 7. Test Strategy

Every hex block in `spec/TEST_VECTORS.md` is reproduced verbatim in a
JUnit test. Tests assert on the **raw** scaled integers (e.g.
`voltageHundredthsV == 6640`) *and* on a representative SI unit
accessor, so both the storage and the derived view are pinned.

Negative-path tests assert that `decode()` returns `null` for
malformed headers / footers, and that unknown type codes fall through
to the `Unknown` variant with the payload preserved.

Initial run: 20 tests, 20 passing, 0 failing.

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
