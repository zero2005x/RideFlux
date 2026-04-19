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


## 1.2.0 — Inmotion completion

- **§2.6** fully rewritten: Family I1 transport, escape rule (3-byte),
  CAN envelope, extended frames, and GATT binding.
- **§2.7 — new**: Family I2 transport, 2-byte escape rule,
  FLAGS / LEN / CMD framing, protocol-version selection for V11.
- **§3.5 — new**: I1 CAN-ID registry; I1 fast / slow EX-DATA maps;
  I2 CMD registry; I2 real-time telemetry (V11 early, V11 ≥1.4,
  V12-HS/HT/PRO, V13, V14, V11Y, V9, V12S); I2 battery / stats /
  settings blobs.
- **§4.3 — new**: I1 work-mode enums (legacy and modern) plus I2
  39-bit error bitmap semantics.
- **§6.4 — new**: I1 additive 8-bit checksum; I2 XOR 8-bit checksum.
- **§8.7 / §8.8 — new**: I1 total-distance per-model scalings; I2
  per-model telemetry offset matrix (marked as observed constraints).
- **§9.5 — new**: I1 PIN bootstrap + polling cadence; I2 three-stage
  identification + 25 ms keep-alive priority order.
- **§10.4 — new**: Full I1 CAN-ID command byte maps (`0x0F550115`
  etc.) and I2 Control-CMD sub-command table, including the
  model-disambiguated sub-commands.
- **§12 compliance**: Added clauses requiring I1/I2 transport and
  checksum conformance.

  ## 1.2.1 — Inmotion §4.3 body + Inmotion test vectors

- **§4.3 — body added** (was announced in 1.2.0 changelog but missing
  from the spec): Family I1 state-word legacy & modern enumerations,
  I1 alert-record format (CAN-ID `0x0F780101`) with per-alertId
  decode table, Family I2 state bytes A / B, and the 56-bit I2
  error bitmap (`E0..E6`, including 2-bit severity fields for bus
  over-current and low-battery).
- **TEST_VECTORS.md**: added Inmotion block with 16 worked examples
  covering I1 escape rule, I1 sum-mod-256 CHECK, real I1 command
  frames (headlight, calibration, PIN, telemetry query), an I1 alert
  decode, I2 XOR-CHECK worked examples (car-type request, real-time
  request, max-speed setter, power-off stage 1, headlight), I2 state
  byte and error-bitmap decodes, and the I2-specific escape rule.