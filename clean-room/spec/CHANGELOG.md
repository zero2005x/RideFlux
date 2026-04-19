# CHANGELOG

All notable changes to this specification are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this specification adheres to Semantic Versioning at the document
level.

## [1.0.0] — Initial release

### Added
- Section 1 — BLE link-layer topologies for two GATT profiles
  (single-characteristic and Nordic-UART-style).
- Section 2 — Framing for five distinct wire formats (G, K, V, N1, N2).
- Section 3 — Payload offset maps for Families G, K, V and N1/N2.
- Section 4 — Alert and status bitmaps.
- Section 5 — XOR-obfuscation keystream algorithm and Family N2 key
  exchange.
- Section 6 — Sum-XOR checksum (N1/N2) and CRC-32/ISO-HDLC (V).
- Section 7 — Family G voltage scaling table and battery curves
  (linear + refined).
- Section 8 — Ambiguity notes (IMU temperature, Family V word-swap,
  Family V version encoding, Family K current sign extension).
- Section 9 — Bootstrap/identification handshakes per family.
- Section 10 — Command references per family.
- Sections 11–12 — Units, conventions, compliance.
- `TERMS.md`, `TEST_VECTORS.md`, `ATTESTATION.md`.

### Known limitations
- Family I1 and Family I2 are acknowledged at the link layer but
  their payload registries are explicitly out of scope for 1.0.
- Family G IMU temperature scaling disambiguation is out-of-band.
- Family V page `0x08` contents are reserved / undefined.
- Family N1 Mini variant's voltage reporting is absent (voltage
  always transmitted as zero).

## [Unreleased]

### Planned
- Family I1 frame definition.
- Family I2 message registry and alert semantics.
- Smart-BMS temperature calibration cross-check.
- Additional test vectors for Family V CRC32-bearing frames.
- Formal definition of Family V page `0x08`.