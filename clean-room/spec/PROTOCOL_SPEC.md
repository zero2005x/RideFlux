# Electric Unicycle BLE Telemetry & Control Protocol Specification

**Version:** 1.0.0  
**Status:** Draft, clean-room specification  
**Scope:** Low-energy Bluetooth protocols used by consumer electric-unicycle
mainboards of the following vendor families:

| Family key | Vendor / marketing names          | Protocol profile |
|------------|-----------------------------------|------------------|
| G          | Begode / Gotway / ExtremeBull     | G-SerialStream   |
| GX         | Begode Extended (dual-BMS models) | G-SerialStream + BMS extension |
| K          | KingSong (all voltage classes)    | K-FixedFrame     |
| V          | Veteran (Sherman / Abrams / Patton / Lynx / Oryx / Nosfet / …) | V-LengthPrefixed |
| N1         | Ninebot One / E+ / S2 / Mini      | N1-CAN-XOR (short) |
| N2         | Ninebot Z / ZT / KickScooter Z    | N2-CAN-XOR (long)  |
| I1         | Inmotion V-series legacy (V5, V8, V10) | I1-Escaped    |
| I2         | Inmotion V-series new (V11, V12, V13, V14) | I2-CAN-like  |

---

## 1. Link Layer & GATT Topology

All families use Bluetooth Low Energy with GATT. Two link-layer profiles
exist: a **single-characteristic duplex** profile (used by G, K, N1, I1) and
a **Nordic-UART-style two-characteristic** profile (used by N2, I2).

### 1.1 Single-characteristic profile

Service UUID and characteristic UUID are 16-bit Bluetooth SIG–style UUIDs
embedded in the standard base UUID:

| Role          | UUID (128-bit form)                         | Properties                  |
|---------------|---------------------------------------------|-----------------------------|
| Primary svc   | `0000ffe0-0000-1000-8000-00805f9b34fb`      | Primary service             |
| Data char.    | `0000ffe1-0000-1000-8000-00805f9b34fb`      | Notify + Write-without-resp |
| CCC descriptor| `00002902-0000-1000-8000-00805f9b34fb`      | Must be set to `01 00`      |

Families G, K, N1 share this layout. Incoming telemetry arrives via
notifications on the data characteristic; commands are sent via
write-without-response on the same characteristic.

Family **I1** uses a split variant of this profile:

| Role          | UUID                                        | Properties                  |
|---------------|---------------------------------------------|-----------------------------|
| Read service  | `0000ffe0-0000-1000-8000-00805f9b34fb`      | Primary                     |
| Notify char.  | `0000ffe4-0000-1000-8000-00805f9b34fb`      | Notify                      |
| Write service | `0000ffe5-0000-1000-8000-00805f9b34fb`      | Primary                     |
| Write char.   | `0000ffe9-0000-1000-8000-00805f9b34fb`      | Write                       |

### 1.2 Nordic-UART-style profile

Used by families **N2** and **I2**:

| Role              | UUID                                        | Properties |
|-------------------|---------------------------------------------|------------|
| Primary service   | `6e400001-b5a3-f393-e0a9-e50e24dcca9e`      | Primary    |
| TX (host→device)  | `6e400002-b5a3-f393-e0a9-e50e24dcca9e`      | Write      |
| RX (device→host)  | `6e400003-b5a3-f393-e0a9-e50e24dcca9e`      | Notify     |
| CCC descriptor    | `00002902-0000-1000-8000-00805f9b34fb`      | `01 00`    |

### 1.3 GATT fragmentation

All families may split logical frames across multiple GATT notifications,
and a single notification may also contain more than one logical frame.
Receivers **must** buffer a continuous byte stream and perform framing at
the application layer as defined in section 2. Receivers **must not**
assume that one notification equals one frame.

---

## 2. Framing

### 2.1 Family G — Serial-byte-stream framing

Frames are 24 bytes, delivered as a bare serial byte stream.

```
Byte:   0   1   2 .. 19   20  21  22  23
        SH1 SH2  PAYLOAD   F1  F1  F1  F1
```

| Offset | Size | Name       | Definition                               |
|-------:|-----:|------------|------------------------------------------|
| 0      | 1    | SH1        | `0x55`                                   |
| 1      | 1    | SH2        | `0xAA`                                   |
| 2–17   | 16   | Data field | Per-type content, big-endian integers    |
| 18     | 1    | Type code  | See §3.1                                 |
| 19     | 1    | Sub-index  | Packet ordinal inside multi-part types   |
| 20–23  | 4    | Footer     | `0x5A 0x5A 0x5A 0x5A`                    |

No checksum is present. Framing is header-driven: the receiver searches
for the byte pair `55 AA` in the stream; everything between that header
and the four-byte `5A 5A 5A 5A` tail is one frame, provided the tail
begins at byte index 20.

**Resynchronisation rule.** After locking onto a header, if any of
bytes 20–23 is not `0x5A`, the frame is discarded and header search
resumes. In addition, two malformed early-termination patterns are
observed in the wild — `55 AA 5A 55 AA …` and `55 AA 5A 5A 55 AA …` —
and are recovered by treating the **last** `55 AA` in such patterns as
the start of a new frame.

### 2.2 Family K — Fixed 20-byte frames

Every frame is exactly 20 bytes, delivered as a single GATT notification:

```
Byte:   0   1   2 .. 15  16   17   18   19
        H1  H2    PAYLOAD  CMD  SUB  T1   T2
```

| Offset | Size | Name          | Definition                                |
|-------:|-----:|---------------|-------------------------------------------|
| 0      | 1    | Header high   | `0xAA` (device→host) / `0xAA` (host→dev: `0xAA 0x55`) |
| 1      | 1    | Header low    | `0x55`                                    |
| 2–15   | 14   | Payload       | Per-command                               |
| 16     | 1    | Command code  | See §3.2                                  |
| 17     | 1    | Sub-index     | Sub-packet index for multi-packet cmds    |
| 18–19  | 2    | Tail          | `0x5A 0x5A`                               |

> **Note on byte order.** The header bytes appear in memory order as
> `AA 55`. Integer fields inside the payload are **little-endian**.

Host-to-device frames use the same 20-byte layout with the inverse
header `AA 55` followed by zeroed payload, command code at offset 16,
sub-index at offset 17, and tail `14 5A 5A` at offsets 17–19.

### 2.3 Family V — Length-prefixed with optional CRC32

Frames begin with a 4-byte magic plus a length byte, followed by a
payload of that length, and optionally a CRC32 trailer.

```
Byte:  0    1    2    3    4    5 .. 4+L    [ CRC ]
       DC   5A   5C   20   L    PAYLOAD      [ 4B ]
```

| Offset | Size | Name   | Definition                                 |
|-------:|-----:|--------|--------------------------------------------|
| 0–2    | 3    | Magic  | `DC 5A 5C`                                 |
| 3      | 1    | Magic4 | `0x20`                                     |
| 4      | 1    | L      | Payload length in bytes (unsigned)         |
| 5..4+L | L    | Payload| Telemetry fields (big-endian unless noted) |
| 5+L..8+L | 4  | CRC32  | See §6.2. Present when `L > 38` or once negotiated. |

Additional early-termination sanity checks are applied at offsets 22, 23
and 30 within the payload (reserved / product-id bytes). A receiver may
use those to detect frames corrupted by packet loss.

### 2.4 Family N1 — Short CAN-like frames with XOR obfuscation

Frames are prefixed with two literal bytes `55 AA`. The first byte after
the prefix is the payload length; the logical frame that follows uses
the layout:

```
LEN  SRC  DST  PARAM  DATA[LEN-2]  CHK_LO  CHK_HI
```

The *entire* frame — **excluding the two-byte prefix** — is XOR-obscured
with a 16-byte rolling keystream *γ* (see §5.1). In family N1 the
keystream is the all-zero vector; obfuscation is therefore an identity,
but the frame structure is preserved for forward compatibility.

| Field   | Size | Meaning                                             |
|---------|-----:|-----------------------------------------------------|
| LEN     | 1    | Length of `DATA` plus 2 (the PARAM and CMD if any)  |
| SRC     | 1    | Source endpoint address                             |
| DST     | 1    | Destination endpoint address                        |
| PARAM   | 1    | Command / parameter identifier (see §3.4)           |
| DATA    | var. | Parameter-dependent payload, **little-endian**      |
| CHK_LO  | 1    | Checksum low byte (see §6.1)                        |
| CHK_HI  | 1    | Checksum high byte                                  |

Endpoint addresses are named `Controller`, `KeyGenerator`, `Host-App`.
Numeric values vary by hardware generation (see §3.4 table).

### 2.5 Family N2 — Long CAN-like frames with XOR obfuscation and session key

Identical framing to N1 except that an additional 1-byte **COMMAND**
field precedes PARAM:

```
LEN  SRC  DST  CMD  PARAM  DATA[LEN]  CHK_LO  CHK_HI
```

Also, the 16-byte keystream *γ* is **not** zero: it is negotiated during
a one-shot handshake executed immediately after link establishment.
See §5 for the key exchange and keystream algorithm.

### 2.6 Family I1 and I2

Families I1 and I2 use escape-byte framing and a length-prefixed
CAN-like frame respectively. Their payload definitions are outside the
scope of this 1.0 document and will be added in a later revision; the
link-layer UUIDs are given in §1.1 / §1.2 for completeness.

---

## 3. Message Types & Payload Maps

### 3.1 Family G payload maps

All integer fields are **big-endian** unless stated otherwise. Signed
values use two's complement.

#### 3.1.1 Type `0x00` — Live telemetry

| Offset | Size | Signed | Name             | Unit / scaling                         |
|-------:|-----:|:------:|------------------|-----------------------------------------|
| 2      | 2    | U      | Voltage          | 1/100 V, nominal reference 67.2 V. Re-scale by battery-class factor (§7). |
| 4      | 2    | S      | Speed            | 1/100 m/s → km/h: `value × 3.6 / 100`  |
| 6      | 4    | U      | Trip distance    | metres (in standard firmware)          |
| 10     | 2    | S      | Phase current    | 1/100 A                                |
| 12     | 2    | S      | IMU raw temp     | Sensor-specific scaling (§8.1)         |
| 14     | 2    | S      | Controller PWM   | 0.01 % units (×10 normalised to 0.1%)  |
| 16–17  | 2    | —      | Reserved / flags | Vendor-specific                        |
| 18     | 1    | —      | Type `0x00`      |                                         |

#### 3.1.2 Type `0x01` — Voltage reference / smart-BMS summary

| Offset | Size | Name                       | Notes                        |
|-------:|-----:|----------------------------|------------------------------|
| 2      | 2    | PWM limit                  | percent × 100                |
| 6      | 2    | True battery voltage       | 1/10 V                       |
| 8      | 2    | BMS current (signed)       | 1/10 A per BMS               |
| 10     | 2    | BMS temp sensor A          | 1/100 °C                     |
| 12     | 2    | BMS temp sensor B          | 1/100 °C                     |
| 14     | 2    | BMS semi-pack voltage      | 1/10 V                       |
| 19     | 1    | BMS index (even=#1, odd=#2; low bit distinguishes upper/lower half of sensors) |

#### 3.1.3 Types `0x02` / `0x03` — Smart-BMS cell voltage page

Carry 8 cells per packet; the byte at offset 19 is the 0-based page
number. Cell voltage = `U16BE(2 + i·2) / 1000.0` V for `i ∈ [0,7]`.
Type `0x02` → first BMS, `0x03` → second BMS.

#### 3.1.4 Type `0x04` — Odometer & settings

| Offset | Size | Name                  | Unit / encoding                      |
|-------:|-----:|-----------------------|--------------------------------------|
| 2      | 4    | Total distance        | metres                               |
| 6      | 2    | Settings bitfield     | See below                            |
| 8      | 2    | Auto-power-off delay  | seconds                              |
| 10     | 2    | Tiltback speed        | km/h (0 = disabled; ≥100 = invalid)  |
| 14     | 1    | LED mode (0x00..0x09) |                                      |
| 15     | 1    | Alert bitmap          | See §4.1                             |
| 17     | 1    | Light mode            | Low 2 bits: 0 off / 1 on / 2 strobe  |

> Bytes 12–13 and 16 are reserved for vendor-specific use and MUST be
> ignored by conformant receivers. Only the low two bits of the light-mode
> byte are significant (`value & 0x03`).

Settings bitfield (U16 big-endian) layout:

| Bits   | Meaning                    |
|-------:|----------------------------|
| 15..13 | Pedals mode (stored inverted: `reported = 2 − value`) |
| 12..10 | Speed-alarm mode           |
| 9..7   | Roll-angle / sensitivity   |
| 0      | Miles-mode flag (1 = miles)|

#### 3.1.5 Type `0x07` — Extended telemetry (optional)

| Offset | Size | Signed | Name                        | Unit      |
|-------:|-----:|:------:|-----------------------------|-----------|
| 2      | 2    | S      | True battery current        | 1/100 A   |
| 6      | 2    | S      | Motor temperature           | 1 °C      |
| 8      | 2    | S      | Hardware PWM                | %, ×100   |

#### 3.1.6 Type `0xFF` — Custom firmware extended parameters

Carries up to 16 single-byte tunables (braking current, rotation control
angle, PID coefficients Kp/Ki/Kd, dynamic compensation, acceleration
compensation, current-loop Kp/Ki for d and q axes). See §3.1.7 table.

#### 3.1.7 Custom-firmware parameter table

| Offset | Field                           | Range      |
|-------:|----------------------------------|------------|
| 2 bit0 | Extreme-mode flag                | 0/1        |
| 3      | Braking current                  | 0..255     |
| 4 bit0 | Rotation-control flag            | 0/1        |
| 5      | Rotation angle (stored value +260°) | 260..515 |
| 6 bit0 | Advanced-settings flag           | 0/1        |
| 7      | Kp                               | 0..255     |
| 8      | Ki                               | 0..255     |
| 9      | Kd                               | 0..255     |
| 10     | Dynamic compensation             | 0..255     |
| 11     | Dynamic compensation filter      | 0..255     |
| 12     | Acceleration compensation        | 0..255     |
| 14..17 | Current-loop Kp_q / Ki_q / Kp_d / Ki_d | 0..255 |

### 3.2 Family K message codes (byte 16)

| Code   | Direction | Purpose                                     |
|:------:|:---------:|---------------------------------------------|
| `0xA9` | dev→host  | Live telemetry page A                       |
| `0xB9` | dev→host  | Live telemetry page B (distance/fan/temp)   |
| `0xBB` | dev→host  | Device name & firmware version             |
| `0xB3` | dev→host  | Serial number                               |
| `0xF5` | dev→host  | CPU load / output PWM                       |
| `0xF6` | dev→host  | Active speed limit                          |
| `0xA4`, `0xB5` | dev→host | Alarm speeds and max speed             |
| `0xF1`, `0xF2` | dev→host | Smart BMS #1 / #2 (paged, sub @ off 17) |
| `0xE1`, `0xE2` | dev→host | BMS serial number                       |
| `0xE5`, `0xE6` | dev→host | BMS firmware                            |
| `0x63` | host→dev  | Request serial number                       |
| `0x9B` | host→dev  | Request device name                         |
| `0x98` | host→dev  | Request alarm settings & max speed          |
| `0x85` | host→dev  | Set alarm speeds & max speed                |
| `0x87` | host→dev  | Set pedals mode (payload @2; @3=`0xE0`;@17=`0x15`) |
| `0x88` | host→dev  | Beep                                        |
| `0x89` | host→dev  | Wheel calibration                           |
| `0x73` | host→dev  | Light mode (`0x12` + mode @ offset 2)       |
| `0x6C` | host→dev  | LED mode                                    |
| `0x53` | host→dev  | Strobe mode                                 |
| `0x40` | host→dev  | Power off                                   |
| `0x8A` | host→dev  | Charge-up-to / gyro adjust                  |
| `0x3F` | host→dev  | Stand-by delay                              |

#### 3.2.1 Family K live page A (`0xA9`)

| Offset | Size | Endian | Name              | Unit       |
|-------:|-----:|:------:|-------------------|------------|
| 2      | 2    | LE     | Voltage           | 1/100 V    |
| 4      | 2    | LE     | Speed             | 0.01 km/h  |
| 6      | 4    | LE     | Total distance    | m          |
| 10     | 2    | LE     | Current           | 1/100 A, signed via hi-byte cast |
| 12     | 2    | LE     | Temperature       | see §8.2   |
| 14     | 1    | —      | Mode enum         | when byte 15 == 0xE0 |
| 15     | 1    | —      | Mode marker       | `0xE0` if present |

#### 3.2.2 Family K live page B (`0xB9`)

| Offset | Size | Endian | Name              | Unit     |
|-------:|-----:|:------:|-------------------|----------|
| 2      | 4    | LE     | Trip distance     | m        |
| 8      | 2    | LE     | Top speed         | 0.01 km/h|
| 12     | 1    | —      | Fan status        | 0/1      |
| 13     | 1    | —      | Charging status   | 0/1      |
| 14     | 2    | LE     | Temperature #2    | see §8.2 |

#### 3.2.3 Battery-class cell counts (Family K)

| Voltage class | Reference pack voltage | Typical series cells |
|--------------:|------------------------|----------------------|
| 67.2 V (base) | 67.2                   | 16                   |
| 84 V          | 82.5                   | 20                   |
| 100 V         | 99.0                   | 24                   |
| 126 V         | 123.75                 | 30                   |
| 151 V         | 148.5                  | 36                   |
| 176 V         | 173.25                 | 42                   |

### 3.3 Family V payload map

All fields are **big-endian** unless otherwise noted; integer offsets
are measured from the start of the frame header (byte 0 = `0xDC`).

| Offset | Size | Signed | Name            | Unit                      |
|-------:|-----:|:------:|-----------------|---------------------------|
| 4      | 2    | U      | Voltage         | 1/100 V                   |
| 6      | 2    | S      | Speed           | 0.01 km/h × 10 = 0.1 km/h-equivalent (see test vectors) |
| 8      | 4    | U*     | Trip distance   | metres, word-swapped 32-bit (§8.3) |
| 12     | 4    | U*     | Total distance  | metres, word-swapped 32-bit |
| 16     | 2    | S      | Phase current   | 1/100 A × 10              |
| 18     | 2    | S      | Temperature     | 1/100 °C                  |
| 20     | 2    | U      | Auto-off delay  | s                         |
| 22     | 2    | U      | Charge mode     | enum                      |
| 24     | 2    | U      | Speed alert     | 1/10 km/h                 |
| 26     | 2    | U      | Speed tiltback  | 1/10 km/h                 |
| 28     | 2    | U      | Firmware ver.   | encoded: `XXX.Y.ZZ`, see §8.4 |
| 30     | 2    | U      | Pedals mode     | enum                      |
| 32     | 2    | S      | Pitch angle     | 1/100 °                   |
| 34     | 2    | U      | Hardware PWM    | 0.01 %                    |

Bytes 46 and beyond carry paged smart-BMS data when `ver/1000 ≥ 5`:

| Page `P` (byte 46) | Contents                                             |
|:------------------:|------------------------------------------------------|
| 0, 4               | BMS summary; two per-BMS currents at offsets 69, 71  |
| 1, 5               | Cells 0..14 @ offset 53, step 2, U16BE signed, ÷1000 V |
| 2, 6               | Cells 15..29 @ offset 53, step 2, U16BE, ÷1000 V    |
| 3, 7               | Cells 30..41 @ offset 59; six temps @ offsets 47..57, signed, ÷100 °C |

Version-to-model mapping:

| `ver/1000` | Model       | Nominal voltage class |
|:----------:|-------------|-----------------------|
| 0, 1       | Sherman     | 100 V                 |
| 2          | Abrams      | 100 V                 |
| 3          | Sherman S   | 100 V                 |
| 4, 7, 43   | Patton / Patton S / NF Aero | 126 V |
| 5, 6, 42, 44 | Lynx / Sherman L / NF Apex / NF Aeon | 151 V |
| 8          | Oryx        | 176 V                 |

### 3.4 Family N1 / N2 command registry

| PARAM | Name                  | Family | Direction  |
|:-----:|-----------------------|:------:|:----------:|
| 0x10  | Serial number (primary) | N1/N2 | both        |
| 0x13  | Serial number (continuation) | N1/N2 | both   |
| 0x16  | Serial number (alternate) | N1/N2 | both    |
| 0x1A  | Firmware version      | N1/N2  | both        |
| 0x22  | Battery level         | N1/N2  | device→host |
| 0x61  | Pitch/roll/yaw angles | N2     | device→host |
| 0x69  | Activation date       | N1/N2  | both        |
| 0xB0..0xBF (step 3) | Live telemetry, sub-pages | N1/N2 | device→host |

Endpoint address table (§2.4 SRC/DST):

| Endpoint     | Default | Ninebot-S2 | Mini |
|--------------|:-------:|:----------:|:----:|
| Controller   | 0x01    | 0x01       | 0x01 |
| KeyGenerator | 0x16    | 0x16       | 0x16 |
| Host-App     | 0x09    | 0x11       | 0x0A |

COMMAND field (N2 only):

| Code | Name    | Meaning                                 |
|:----:|---------|-----------------------------------------|
| 0x01 | Read    | Request a value                         |
| 0x03 | Write   | Set a value                             |
| 0x04 | Get     | Get a multi-byte record                 |
| 0x5B | GetKey  | Request a keystream exchange (see §5)   |

#### 3.4.1 Live-telemetry page `0xB0`

All integers **little-endian**, signed where indicated.

| Offset | Size | Signed | Name             | Unit                              |
|-------:|-----:|:------:|------------------|-----------------------------------|
| 8      | 2    | U      | Battery percent  | 1 %                               |
| 10     | 2    | S      | Speed (standard) | 1/100 m/s → km/h ÷10              |
| 14     | 4    | U      | Total distance   | m                                 |
| 22     | 2    | U      | Temperature      | 1/10 °C                           |
| 24     | 2    | U      | Voltage          | 1/100 V (0 on Mini)               |
| 26     | 2    | S      | Current          | 1/100 A                           |
| 28     | 2    | U      | Speed (S2 variant) | 1/100 km/h                      |

#### 3.4.2 Activation-date encoding

The U16LE activation-date word `D` decodes as:

```
year  = (D >> 9) + 2000
month = (D >> 5) & 0x0F
day   =  D       & 0x1F
```

---

## 4. Alerts & Status Bitmaps

### 4.1 Family G alert byte (type `0x04`, offset 14)

| Bit | Meaning             |
|----:|---------------------|
| 0   | Wheel alarm (generic)|
| 1   | Speed alarm level 2 |
| 2   | Speed alarm level 1 |
| 3   | Low voltage         |
| 4   | Over voltage        |
| 5   | Over temperature    |
| 6   | Hall sensor error   |
| 7   | Transport mode      |

### 4.2 Family V charge-mode enum (offset 22)

Observed values: `0` = idle, `1` = charging, `2` = fully charged. Other
values are reserved.

---

## 5. Obfuscation & Session Key (Families N1 / N2)

### 5.1 Keystream application

Let `γ[0..15]` be a 16-byte keystream. For any frame *excluding* its
two-byte `55 AA` prefix, the byte at position *j* ≥ 1 within the
remaining payload is obscured by XOR:

```
byte[j]  ←  byte[j]  ⊕  γ[(j − 1) mod 16]
```

Position `j = 0` (the LEN byte) is transmitted in the clear. The
operation is symmetric — encryption and decryption are identical.

### 5.2 Family N1

`γ` is fixed to the all-zero 16-byte vector. Obfuscation is therefore
an identity, but the position-0 exemption and 16-byte cycle are
preserved.

### 5.3 Family N2 handshake

After GATT connection and CCC enablement:

1. The host sends a `GetKey` (PARAM `0x5B`) request from endpoint
   `Host-App` to endpoint `KeyGenerator`.
2. The device replies with a response whose DATA field carries a
   16-byte opaque value.
3. The host adopts those 16 bytes as `γ` for **all subsequent traffic**
   in both directions for the remainder of the session.
4. If `γ` has not yet been adopted, the first two frames (the request
   and the response) are exchanged with `γ = 00…00` (identity).

`γ` resets to all zeros on link loss.

**Cryptographic strength.** This is an XOR obfuscation with a
session-fixed 128-bit keystream supplied verbatim by the device.
It is not cryptographically secure and should not be relied on for
confidentiality.

---

## 6. Checksums & CRCs

### 6.1 Family N1 / N2 frame checksum

Let `B = [LEN, SRC, DST, (CMD,) PARAM, DATA[0..n−1]]` be the
pre-checksum byte sequence. The checksum is computed as:

```
S       = Σ B[i]        (unsigned 32-bit sum)
CHK16   = (S XOR 0xFFFF) AND 0xFFFF
CHK_LO  = CHK16 AND 0xFF
CHK_HI  = (CHK16 >> 8) AND 0xFF
```

`CHK_LO` is transmitted first, `CHK_HI` second (little-endian).

### 6.2 Family V CRC32

Frames whose payload length `L` exceeds 38, or whose session has
observed at least one CRC-bearing frame, carry a 4-byte CRC trailer
immediately after the payload. The CRC is computed over the sequence
`[DC, 5A, 5C, 20, L, PAYLOAD[0..L−1]]`:

- Polynomial: **0xEDB88320** (reflected representation of CCITT
  CRC-32 / IEEE 802.3)
- Initial value: `0xFFFFFFFF`
- Final XOR: `0xFFFFFFFF`
- Input and output reflected: yes

I.e., the standard "CRC-32/ISO-HDLC" variant. The trailer is
transmitted **big-endian**.

### 6.3 Families G and K

No checksum. Integrity depends on header/footer matching (G) or on
the 20-byte fixed length plus `0x5A 0x5A` tail (K). Receivers are
expected to tolerate out-of-order or occasionally dropped frames.

---

## 7. Voltage Scaling (Family G)

Family G reports voltage assuming a 67.2 V reference pack. The true
pack voltage is obtained by multiplying the reported voltage by a
battery-class scaler selected out of band:

| Class id | Nominal pack voltage | Scaler                  |
|:--------:|---------------------:|-------------------------|
| 0        | 67.2                 | 1.000                   |
| 1        | 84                   | 1.250                   |
| 2        | 100.8                | 1.500                   |
| 3        | 117                  | 1.738 095 238 …         |
| 4        | 134                  | 2.000                   |
| 5        | 168                  | 2.500                   |
| 6        | 151                  | 2.250                   |

Battery-percentage curves (two variants — "linear" and "refined")
apply to the **unscaled** 67.2 V-referenced voltage:

*Linear (legacy)*

| Condition                                   | Percent             |
|---------------------------------------------|---------------------|
| V ≤ 52.90 V                                 | 0                   |
| V ≥ 65.80 V                                 | 100                 |
| otherwise                                   | `(V·100 − 5290)/13` |

*Refined*

| Voltage range (×100 V)                      | Percent             |
|---------------------------------------------|---------------------|
| > 6680                                      | 100                 |
| 5441 … 6680                                 | `(V − 5320) / 13.6` |
| 5121 … 5440                                 | `(V − 5120) / 36`   |
| ≤ 5120                                      | 0                   |

Equivalent refined curves exist per-voltage-class for Family K and
Family V; see §3.2.3 and §3.3.

---

## 8. Ambiguities & Observed Constraints

### 8.1 Family G temperature

Two scalings are observed depending on the firmware variant:

- `T[°C] = (raw / 340) + 36.53` (MPU6050 native scaling).
- `T[°C] = (raw / 333.87) + 21.00` (MPU6500 native scaling).

The selection is firmware-dependent. There is **no** in-band indicator;
client code typically infers it from the firmware identification strings
exchanged out-of-band (§9). This is an *open ambiguity*.

### 8.2 Family K temperature

Read raw as U16LE; temperature is `raw / 100 °C`. Page B (`0xB9`) carries
a second temperature sensor at offset 14 with identical scaling.

### 8.3 Family V 32-bit distance word order

The 4-byte distance words at offsets 8 and 12 are **word-swapped**: the
upper 16 bits are transmitted *after* the lower 16. Concretely, if the
four bytes are `b0 b1 b2 b3`, the integer is:

```
value = (b2 << 24) | (b3 << 16) | (b0 << 8) | b1
```

Stated test vector: bytes `00 00 07 1F` ⇒ `1823` metres.

### 8.4 Family V firmware-version encoding

The 16-bit value `V` decodes as three decimal groups:

```
major = V / 1000
minor = (V % 1000) / 100
patch =  V % 100            (display as 2 digits)
```

Printed as `"%03d.%d.%02d"`. Example: `V = 1058` → `"001.0.58"`.

### 8.5 Family K current field

Unlike all other multi-byte integers in Family K, the current at
live-page-A offset 10 is stored **with little-endian low byte at +0 and
signed high byte at +1**, i.e., the hi byte is sign-extended via arith-
metic shift. Treat as S16LE.

---

## 9. Bootstrap / Identification

### 9.1 Family G

Before live telemetry begins, the host issues short ASCII commands on
the data characteristic:

| Command ASCII | Meaning                                |
|:-------------:|----------------------------------------|
| `"N"`         | Request device name (reply: `"NAME..."`)|
| `"V"`         | Request firmware identification strings |

The device replies (within one or more notifications) with ASCII
tokens. Tokens observed:

| Prefix  | Meaning                                    |
|:-------:|--------------------------------------------|
| `NAME`  | Marketing model name (after a space)       |
| `GW`    | Stock Begode firmware, version follows     |
| `JN`    | ExtremeBull firmware, version follows      |
| `CF`    | Freestyl3r custom firmware, version follows|
| `BF`    | SmirnoV custom firmware, version follows   |
| `MPU`   | IMU identification (MPU6050 / MPU6500)     |

If neither a name nor version is received within ~2 seconds, the host
declares the device a generic "Begode" and proceeds.

### 9.2 Family K

Host sends a 20-byte request with byte 16 = `0x9B` to receive the name
frame (`0xBB`), a request with byte 16 = `0x63` to receive the serial
(`0xB3`), and a request with byte 16 = `0x98` to receive alarm and
max-speed settings (`0xA4`/`0xB5`).

### 9.3 Family N1 / N2 keep-alive schedule

The host drives the conversation with a 25 ms timer issuing one
message per tick. The tick cycle is modulo 5: ticks 1–4 are idle
(device-initiated only), tick 0 emits one of the following in priority
order:

1. Serial-number request (until reply)
2. Firmware-version request (until reply)
3. Any pending setting-write command
4. Live-data request (PARAM `0xB0`)

### 9.4 Family V

No identification handshake is required; the firmware-version word at
payload offset 28 (§3.3) is used to select the model and battery curve.

---

## 10. Command Reference (non-exhaustive)

### 10.1 Family G single-character commands

All commands are single ASCII bytes written as write-without-response.
Some commands expect a short follow-up byte after ~100 ms.

| Command | Effect                                  |
|:-------:|-----------------------------------------|
| `b`     | Beep                                    |
| `c` + `y` | Wheel calibration (two-byte, 300 ms gap) |
| `m` / `g` | Miles / kilometres                    |
| `E` / `Q` / `T` | Light off / on / strobe           |
| `<` / `=` / `>` | Roll angle (soft / medium / hard) |
| `o` / `u` / `i` / `I` | Alarm mode 2 / 1 / off / custom-FW |
| `h` / `f` / `s` / `i` | Pedals mode hard / medium / soft / custom |
| `W`,`Y`,N1,N2,`b`,`b` | Set max speed (sequenced; digits encoded as ASCII `0x30+n`) |
| `W`,`M`, digit, `b`, `b` | Set LED mode                 |
| `W`,`B`, digit, `b`, `b` | Set beeper volume            |

Custom-firmware 3-byte tuning commands use ASCII opcodes such as
`EM`, `BA`, `RC`, `rs`, `as`, `hp`, `hi`, `hd`, `hc`, `hf`, `ac`,
`cp`, `ci`, `dp`, `di`, `tt`, each followed by a single parameter
byte.

### 10.2 Family V text commands

| ASCII command      | Effect                      |
|--------------------|-----------------------------|
| `"CLEARMETER"`     | Reset trip counters         |
| `"SETh"`           | Pedals: hard                |
| `"SETm"`           | Pedals: medium              |
| `"SETs"`           | Pedals: soft                |
| `"SetLightON"`     | Headlight on                |
| `"SetLightOFF"`    | Headlight off               |
| `b` (v < 3)        | Beep (legacy)               |
| binary beep v ≥ 3  | `4C 6B 41 70 0E 00 80 80 80 01 CA 87 E6 6F` (14 B) |

### 10.3 Family K settings

See §3.2. All command frames follow the 20-byte layout with relevant
bytes of the payload set and the rest zero.

---

## 11. Units & Conventions

- All speeds reported in km/h internally. Conversions to mph are
  applied by the host, not by the device.
- All distances reported in metres, except where otherwise noted.
- All voltages and currents expressed as signed integers scaled by
  100 (hundredths) unless otherwise stated.
- Signed integers use two's-complement.
- Reserved/unknown bytes **must** be transmitted as zero and ignored on
  receive.

---

## 12. Compliance

An implementation is said to be **conformant** to this specification if:

1. It honours the GATT topologies in §1.
2. It correctly frames and defragments data as specified in §2.
3. It interprets the numeric fields in §3 using the scalings declared.
4. It validates checksums/CRCs where present (§6) and drops invalid
   frames.
5. For Family N2, it performs the §5 handshake before reading telemetry
   and applies `γ` for both directions.
6. It tolerates unknown message types (forward compatibility).