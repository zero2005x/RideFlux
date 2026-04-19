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

## Inmotion — Families I1 & I2

All vectors below are plain hex, MSB-first byte order on the wire.
Spaces are for readability only and are not transmitted. The
checksum column shows the **unescaped** CHECK value; where the
transmitted frame contains escape bytes, CHECK is still transmitted
verbatim (see §2.6.1 / §2.7.1).

### I1.1 — Body escape rule

Unstuffed body of 4 bytes: `AA 55 A5 7E`

Expected on-wire encoding (before CHECK / trailer):

```
A5 AA   A5 55   A5 A5   7E
```

Each of `0xAA`, `0x55`, `0xA5` is prefixed with a single `0xA5`
escape marker; the final `0x7E` is unaffected. A decoder must
consume the escape marker and append only the following byte
(literal), including when that following byte is itself `0xA5`.

### I1.2 — Checksum worked example (sum mod 256)

Body (unescaped, 16 bytes):

```
0D 01 55 0F   01 00 00 00   00 00 00 00   08 05 00 00
```

Sum of bytes = `0x0D + 0x01 + 0x55 + 0x0F + 0x01 + 0x08 + 0x05` =
`0x80`.

CHECK = `0x80`.

### I1.3 — Headlight ON (CAN-ID `0x0F55010D`)

Unstuffed body (16 B):

```
0D 01 55 0F   01 00 00 00   00 00 00 00   08 05 00 00
```

Full on-wire frame (preamble · escaped body · CHECK · trailer):

```
AA AA   0D 01   A5 55   0F   01 00 00 00 00 00 00 00   08 05 00 00   80   55 55
```

(The single `0x55` at body offset 2 is escape-encoded as `A5 55`;
everything else is literal. CHECK `0x80` and trailer `55 55` are
transmitted unescaped.)

### I1.4 — Wheel calibration (CAN-ID `0x0F550119`)

Unstuffed body:

```
19 01 55 0F   32 54 76 98   00 00 00 00   08 05 00 00
```

Sum mod 256 = `0x1F`.

On-wire:

```
AA AA   19 01   A5 55   0F   32 54 76 98 00 00 00 00   08 05 00 00   1F   55 55
```

### I1.5 — PIN "000000" (CAN-ID `0x0F550307`)

DATA-8 = ASCII `"000000" 00 00` = `30 30 30 30 30 30 00 00`.

Unstuffed body:

```
07 03 55 0F   30 30 30 30   30 30 00 00   08 05 00 00
```

Sum mod 256 = `0x9B`.

On-wire:

```
AA AA   07 03   A5 55   0F   30 30 30 30 30 30 00 00   08 05 00 00   9B   55 55
```

### I1.6 — Fast-telemetry remote-frame query (CAN-ID `0x0F550113`)

Unstuffed body (TYPE = `0x01` = remote frame, DATA = all-ones):

```
13 01 55 0F   FF FF FF FF   FF FF FF FF   08 05 00 01
```

Sum mod 256 = `0x7E`.

On-wire:

```
AA AA   13 01   A5 55   0F   FF FF FF FF FF FF FF FF   08 05 00 01   7E   55 55
```

The device answers with a data frame whose LEN = `0xFE` and whose
EX-DATA carries the 80-byte telemetry record of §3.5.2.

### I1.7 — Alert record decode (CAN-ID `0x0F780101`)

Observed DATA-8 payload:

```
06 00 07 D0 00 00 2E E0
```

Decode per §4.3.2:

| Field      | Bytes         | Value                        |
|------------|---------------|------------------------------|
| `alertId`  | `06`          | `0x06` → tilt-back event     |
| `aValue1`  | `07 D0`       | `2000` ⇒ limit = `2000/1000 = 2.00` |
| `aValue2`  | `00 00 2E E0` | `12000` ⇒ `a_speed_kmh = |12000/3812| × 3.6 ≈ 11.33 km/h` |

Human text: *“Tilt-back at speed 11.33 km/h, limit 2.00.”*

### I1.8 — State word → mode string

Legacy V10S telemetry, state word = `0x00000001`:

- Low nibble = `0x1` → `Drive` (§4.3.1 legacy table).

Modern V10F telemetry, state word = `0x00000021`:

- High nibble = `0x2` → `Drive`; `(state & 0x0F) = 0x1` → suffix
  `" - Engine off"`. Final string: `"Drive - Engine off"`.

---

### I2.1 — Minimal request: get car type

Logical: FLAGS = `0x11`, CMD = `0x02`, DATA = `01`.

Pre-check bytes: `11 02 02 01`
CHECK = `0x11 XOR 0x02 XOR 0x02 XOR 0x01 = 0x10`.

On-wire:

```
AA AA   11 02 02 01   10
```

(No trailer; the receiver finalises the frame after `LEN + 3 = 5`
escape-decoded bytes following the preamble.)

### I2.2 — Minimal request: real-time telemetry

Logical: FLAGS = `0x14`, CMD = `0x04`, DATA empty.

Pre-check: `14 01 04`
CHECK = `0x14 XOR 0x01 XOR 0x04 = 0x11`.

On-wire: `AA AA 14 01 04 11`.

### I2.3 — Set max speed to 40 km/h (sub-cmd `0x21`)

`maxKmh × 100 = 4000 = 0x0FA0` (U16BE → `hi=0x0F, lo=0xA0`).

Logical: FLAGS = `0x14`, CMD = `0x60`, DATA = `21 0F A0`.

Pre-check: `14 04 60 21 0F A0`
CHECK = `14^04^60^21^0F^A0`:

```
14 ^ 04 = 10
10 ^ 60 = 70
70 ^ 21 = 51
51 ^ 0F = 5E
5E ^ A0 = FE
```

CHECK = `0xFE`.

On-wire: `AA AA 14 04 60 21 0F A0 FE`.

### I2.4 — Power-off stage 1 (§10.4.8)

Logical: FLAGS = `0x11`, CMD = `0x03`, DATA = `81 00`.

Pre-check: `11 03 03 81 00`
CHECK = `11^03^03^81^00`:

```
11 ^ 03 = 12
12 ^ 03 = 11
11 ^ 81 = 90
90 ^ 00 = 90
```

CHECK = `0x90`.

On-wire: `AA AA 11 03 03 81 00 90`.

### I2.5 — Headlight on (V11 ≥ 1.4, sub-cmd `0x50`)

Logical: FLAGS = `0x14`, CMD = `0x60`, DATA = `50 01`.

Pre-check: `14 03 60 50 01`
CHECK = `0x14 XOR 0x03 XOR 0x60 XOR 0x50 XOR 0x01 = 0x26`.

On-wire: `AA AA 14 03 60 50 01 26`.

### I2.6 — State-byte decode (§4.3.3)

Suppose telemetry produces `stateA = 0x41`, `stateB = 0x05`.

| Byte | Bits                                   | Meaning                           |
|------|----------------------------------------|-----------------------------------|
| A    | `01000001` → `0..2 = 001`, `6 = 1`     | PC-mode = `Drive`, motor active   |
| B    | `00000101` → `0 = 1`, `2 = 1`          | Headlight on; lifted detected     |

Convenience status string: `"Active Lifted"`.

### I2.7 — Error-bitmap decode (§4.3.4)

Observed seven-byte error block starting at the bitmap offset:

```
E0 E1 E2 E3 E4 E5 E6  =  00 00 00 44 00 00 00
```

Decode:

- `E3 = 0x44 = 0100 0100`
  - bits `2..3 = 01` → `over_bus_current = 1` (informational)
  - bit `6 = 1` → `mos_temp` over-temperature

Result: two simultaneous faults — *bus-current warning, level 1* and
*MOS-FET over-temperature*. All other bits are clear.

### I2.8 — Escape rule (§2.7.3)

Unstuffed pre-check sequence `FLAGS=0x14, LEN=0x02, CMD=0xAA`
(a deliberately adversarial CMD value):

- Pre-check XOR → `14 ^ 02 ^ AA = 0xBC`
- `0xAA` in the body triggers escape: transmit `A5 AA`
- `0xBC` as CHECK is transmitted in the clear
- `0x55` is **not** escaped in I2 (would have been in I1)

On-wire:

```
AA AA   14 02   A5 AA   BC
```

(Note: this is a framing-only example; `CMD = 0xAA` is not a
registered I2 command and devices will reject it.)

- Family I1 escape-byte framing.
- Family I2 message registry.
- Smart-BMS cell-diff alert semantics.