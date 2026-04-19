# Protocol Specification

> **Status:** DRAFT — replace this banner with a version tag (e.g. `spec-v0.1`)
> once the Coordinator has approved the bundle for handoff to Team B.

This document describes the Bluetooth protocol used by the target devices.
It is a standalone technical reference. It does not cite, quote, or
paraphrase any source code.

---

## 1. Conventions

- All multi-byte integers are little-endian unless stated otherwise.
- Bit 0 refers to the least significant bit of a byte.
- Field widths are expressed in bytes unless the unit is explicitly bits.
- Units (volts, amperes, km/h, °C, etc.) are documented in
  [TERMS.md](TERMS.md).

## 2. BLE Topology

### 2.1 Services and Characteristics

| Role | UUID | Properties | Notes |
| --- | --- | --- | --- |
| _TBD_ | _TBD_ | _TBD_ | _TBD_ |

### 2.2 Connection / Pairing

- _Describe advertising data, connection parameters, MTU expectations, and
  any bonding requirements here._

## 3. Frame Framing

- Preamble / header bytes: _TBD_
- Length field semantics: _TBD_
- Trailer / terminator bytes: _TBD_
- Maximum frame length: _TBD_
- Fragmentation / reassembly rules: _TBD_

## 4. Telemetry Frames

For each supported device family, document:

### 4.1 Family `<name>`

**Identification.** How this family is detected from advertising data or
handshake responses.

**Frame layout.** Offset table:

| Offset | Width | Field | Type | Unit | Scale | Range | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0x00 | 2 | _TBD_ | uint16 LE | — | 1 | 0..65535 | _TBD_ |

**Derived values.** Any values that are not raw fields but are computed from
raw fields (e.g. power = voltage × current). State the formula in plain
mathematical notation.

## 5. Command Frames

For each command supported by the protocol:

### 5.1 Command `<name>`

- **Purpose:** _TBD_
- **Request layout:** offset table as in §4.
- **Response layout:** offset table as in §4, if any.
- **Timing / retry constraints:** _TBD_

## 6. Checksums and CRCs

For each checksum/CRC used:

- **Scope:** which bytes are covered.
- **Algorithm:** define mathematically (polynomial, initial value, XOR-out,
  reflection). If a lookup table is used, state the polynomial the table
  represents; do not transcribe a reference table verbatim.
- **Placement:** where the result is stored in the frame.

## 7. Obfuscation / Cryptographic Steps

For each obfuscation layer:

- **Scope:** which bytes are transformed.
- **Operation:** describe using bitwise/arithmetic math (XOR with rotating
  key, AES-CBC with a fixed IV, etc.).
- **Key / constants:** if constants are observable from public vendor
  documentation, cite the document. If they can only be inferred, mark the
  entry as ambiguous (§8) and describe the observable constraints.

## 8. Ambiguities and Open Questions

Track entries in the format:

- **A-001** — _Short title._
  - **Observation:** what was seen on the wire or in documentation.
  - **Uncertainty:** what is not yet known.
  - **Safe default:** recommended behavior for Team B until resolved.

## 9. Versioning

The specification is versioned by Git tag. Breaking changes increment the
minor version (e.g. `spec-v0.1` → `spec-v0.2`). Editorial changes increment a
patch suffix (e.g. `spec-v0.1.1`). See [CHANGELOG.md](CHANGELOG.md).
