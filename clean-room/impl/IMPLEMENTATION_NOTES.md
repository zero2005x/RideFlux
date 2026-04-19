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
