# Test Vectors

> These vectors are intended to lock in the bit-level interpretation
> of the specification. Each group can be reproduced independently
> from the field definitions in `PROTOCOL_SPEC.md`.

## 1. Family G — Live telemetry, type `0x00`

Input frame (hex, 24 bytes):

```
55 AA 19 F0 00 00 00 00  00 00 01 2C FD CA 00 01
FF F8 00 18 5A 5A 5A 5A
```

Decoded fields (battery class 0, linear battery curve):

| Field           | Raw (big-endian)     | Value                       |
|-----------------|----------------------|-----------------------------|
| Voltage         | `19 F0` = 6640       | 66.40 V                     |
| Speed           | `00 00` = 0 (S)      | 0.0 km/h                    |
| Trip distance   | `00 00 00 00`        | 0 m                         |
| Phase current   | `01 2C` = 300 (S)    | 3.00 A                      |
| IMU raw         | `FD CA` = −566 (S)   | ((−566/340)+36.53) = 34.87 °C |
| Hardware PWM    | `00 01` = 1 (S)      | 0.10 %                      |
| Type            | `00`                 | Live telemetry              |
| Battery         | (linear: (6640−5290)/13) | 103 → clamp to 100       |

## 2. Family G — Settings frame, type `0x04`

Input:

```
55 AA 00 0A 4A 12 48 00  1C 20 00 2A 00 03 00 07
00 08 04 18 5A 5A 5A 5A
```

Decoded:

| Field             | Raw                                  | Value             |
|-------------------|--------------------------------------|-------------------|
| Total distance    | `00 0A 4A 12` = 674 322               | 674 322 m         |
| Settings          | `48 00`                              | pedals = 2 (`010`→inverted `2−2=0`?); speed-alarm = 0; roll = 0; miles = 0 |
| Auto-off          | `1C 20` = 7200                        | 7200 s            |
| Tiltback          | `00 2A` = 42                          | 42 km/h           |
| LED mode          | `00`                                  | 0                 |
| Alert bitmap      | `07` = `0000 0111`                    | bits 0, 1, 2 set → WheelAlarm, SpeedAlarmL2, SpeedAlarmL1 (per §4.1) |
| Light mode        | `08 & 0x03` = 0                       | off               |

## 3. Family K — Live page A (`0xA9`)

Input (little-endian bytes in payload):

```
AA 55 F0 19 E8 03 10 27  00 00 2C 01 A0 0F 05 E0
A9 00 5A 5A
```

Decoded:

| Field           | Raw (LE)          | Value                      |
|-----------------|-------------------|----------------------------|
| Voltage         | `F0 19` = 0x19F0 = 6640 | 66.40 V               |
| Speed           | `E8 03` = 1000    | 10.00 km/h                 |
| Total distance  | `10 27 00 00` = 10 000 | 10 000 m              |
| Current         | `2C 01` = 300     | 3.00 A                     |
| Temperature     | `A0 0F` = 4000    | 40.00 °C                   |
| Mode marker     | `E0`              | present                    |
| Mode enum       | `05`              | 5                          |
| Command         | `A9`              | live page A                |

## 4. Family V — 100 V Sherman frame without CRC32

Input (hex, 42 bytes — `L = 0x25 = 37` ⇒ frame size `5 + L = 42` and
`L ≤ 38` ⇒ **no CRC32 trailer**):

```
DC 5A 5C 20 25 CD 00 00  07 1F 00 00 C7 78 00 28
00 00 11 0B 0E 10 00 01  0A F0 0A F0 04 22 00 03
00 14 00 00 00 00 00 00  00 00
```

The final six bytes (offsets 36..41) are reserved per §3.3 and are
zeroed here.

Decoded (offsets are frame-absolute per §3.3; integers are big-endian
unless noted):

| Offset | Field            | Raw             | Value                         |
|-------:|------------------|-----------------|-------------------------------|
| 4      | Voltage          | `25 CD` = 9677  | 96.77 V                       |
| 6      | Speed            | `00 00` = 0     | 0.0 km/h                      |
| 8      | Trip distance    | `07 1F 00 00` (word-swapped) | 1823 m           |
| 12     | Total distance   | `C7 78 00 28` (word-swapped) | 2 672 504 m      |
| 16     | Phase current    | `00 00` = 0     | 0.00 A                        |
| 18     | Temperature      | `11 0B` = 4363  | 43.63 °C                      |
| 20     | Auto-off delay   | `0E 10` = 3600  | 3600 s                        |
| 22     | Charge mode      | `00 01` = 1     | charging                      |
| 28     | Firmware version | `04 22` = 1058  | `"001.0.58"` (major.minor.patch per §8.4) |
| 30     | Pedals mode      | `00 03` = 3     | 3                             |
| 32     | Pitch angle      | `00 14` = 20    | 0.20 °                        |

> **Note.** This vector pins the word-swap convention described in
> §8.3 and the firmware-version decoding in §8.4 of the protocol spec.

## 5. Family N1 — Checksum derivation

Pre-checksum bytes:

```
03 09 01 10 0E
```

| Step                 | Value          |
|----------------------|----------------|
| Σ bytes              | 0x03+0x09+0x01+0x10+0x0E = 0x2B = 43 |
| Σ XOR 0xFFFF         | 0xFFD4 = 65 492 |
| AND 0xFFFF           | 0xFFD4          |
| CHK_LO               | 0xD4            |
| CHK_HI               | 0xFF            |

So the checksum trailer is `D4 FF`.

## 6. Family N2 — XOR obfuscation round-trip

Keystream:

```
γ = 01 02 03 04 05 06 07 08  09 0A 0B 0C 0D 0E 0F 10
```

Plain payload (after the `55 AA` prefix):

```
03 09 01 10 0E D4 FF
```

Obfuscated (starting at position 1, index 0 of γ applies to pos 1):

| Position | Plain | γ index | γ value | Obfuscated |
|---------:|:-----:|:-------:|:-------:|:----------:|
| 0        | 03    | —       | —       | 03         |
| 1        | 09    | 0       | 01      | 08         |
| 2        | 01    | 1       | 02      | 03         |
| 3        | 10    | 2       | 03      | 13         |
| 4        | 0E    | 3       | 04      | 0A         |
| 5        | D4    | 4       | 05      | D1         |
| 6        | FF    | 5       | 06      | F9         |

Wire: `55 AA 03 08 03 13 0A D1 F9`. Applying the same operation again
reproduces the plaintext.

## 7. Family V CRC32

Compute "CRC-32/ISO-HDLC" (poly `0xEDB88320`, init/xorout `0xFFFFFFFF`,
reflected in/out) over the 15-byte input:

```
DC 5A 5C 20 0A 01 02 03  04 05 06 07 08 09 0A
```

Expected CRC32 = `0x08009D77`. Wire trailer: `08 00 9D 77`.

> This value is the output of the standard CRC-32/ISO-HDLC algorithm
> (polynomial `0xEDB88320`, init/xorout `0xFFFFFFFF`, reflected in/out)
> as implemented by `java.util.zip.CRC32`. Revision 1.0 of this file
> originally stated `0xB4B1B1F8`; that value has been corrected in the
> 2026-04 errata.

---

## Reserved for revision 1.1

- Family I1 escape-byte framing.
- Family I2 message registry.
- Smart-BMS cell-diff alert semantics.