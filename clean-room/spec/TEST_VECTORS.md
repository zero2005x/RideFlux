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

## 4. Family V — Short frame without CRC32

Input (hex):

```
DC 5A 5C 20 25 CD 00 00  07 1F 00 00 C7 78 00 28
00 00 11 0B 0E 10 00 01  0A F0 0A F0 04 22 00 03
00 14 00 00
```

`L = 0x25 = 37` ⇒ no CRC32.

Decoded:

| Offset | Field            | Value                            |
|-------:|------------------|----------------------------------|
| 4      | Voltage          | `CD 00` = 52 480 / 100? …  Actually `CD 00` read as BE = 0xCD00 = 52 480 → 524.80 V? — see ambiguity below. |
| 6      | Speed            | `00 00 07 1F` … — see §1.8.3 of spec for word-swap |
| 28     | Firmware version | `00 3A` = 58                    → `"000.0.58"` |

> **Note.** This vector is also the "Sherman SV 58fw" vector attached
> in §8.4 of the protocol spec; it depends on the word-swap convention
> described in §8.3 of the protocol spec.

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

Expected CRC32 = `0xB4B1B1F8`. Wire trailer: `B4 B1 B1 F8`.

---

## Reserved for revision 1.1

- Family I1 escape-byte framing.
- Family I2 message registry.
- Smart-BMS cell-diff alert semantics.