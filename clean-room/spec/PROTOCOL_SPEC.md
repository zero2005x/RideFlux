# Electric Unicycle BLE Telemetry & Control Protocol Specification

**Version:** 1.2.0  
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
| 0      | 1    | Header high   | `0xAA`                                    |
| 1      | 1    | Header low    | `0x55`                                    |
| 2–15   | 14   | Payload       | Per-command                               |
| 16     | 1    | Command code  | See §3.2                                  |
| 17     | 1    | Sub-index / tail-1 | Meaning is direction- and command-specific; see below |
| 18–19  | 2    | Tail          | `0x5A 0x5A`                               |

> **Note on byte order.** The header bytes appear in memory order as
> `AA 55`. Integer fields inside the payload are **little-endian**
> unless otherwise stated.

**Device-to-host frames.** Byte 17 carries a **sub-index** (page number
within multi-packet records, e.g. for smart-BMS pages `0xF1` / `0xF2`).

**Host-to-device frames.** Byte 17 is a **command-specific tail
constant**. Unless a particular command in §3.2 / §10.3 states
otherwise, the default value for offset 17 is `0x14`. A small number
of commands override this default (notably pedals-mode `0x87` → `0x15`).

Commands that carry data populate the 14-byte payload region at offsets
2..15 using little-endian integers where multi-byte fields apply.
**Unused payload bytes MUST be transmitted as `0x00`.**

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
| LEN     | 1    | `DATA.size + 2` (see below)                         |
| SRC     | 1    | Source endpoint address                             |
| DST     | 1    | Destination endpoint address                        |
| PARAM   | 1    | Command / parameter identifier (see §3.4)           |
| DATA    | var. | Parameter-dependent payload, **little-endian**      |
| CHK_LO  | 1    | Checksum low byte (see §6.1)                        |
| CHK_HI  | 1    | Checksum high byte                                  |

The `+2` constant in `LEN = DATA.size + 2` is a fixed framing
convention that is identical between N1 and N2; it is **not** a
per-byte accounting of PARAM / CMD. The §5 test vector pins this: a
frame with `LEN = 0x03` carries exactly 1 byte of `DATA`.

Endpoint addresses are named `Controller`, `KeyGenerator`, `Host-App`.
Numeric values vary by hardware generation (see §3.4 table).

### 2.5 Family N2 — Long CAN-like frames with XOR obfuscation and session key

Identical framing to N1 except that an additional 1-byte **COMMAND**
field precedes PARAM:

```
LEN  SRC  DST  CMD  PARAM  DATA[LEN-3]  CHK_LO  CHK_HI
```

N2 adds one byte (CMD) between DST and PARAM. The LEN byte counts
that extra CMD byte, so the N2 formula is `LEN = DATA.size + 3`
(vs. `+2` for N1). Equivalently, for both families,
`LEN = PARAM(1) + CMD(0 or 1) + DATA.size + 1` where the final
`+1` is a fixed framing constant.

Also, the 16-byte keystream *γ* is **not** zero: it is negotiated during
a one-shot handshake executed immediately after link establishment.
See §5 for the key exchange and keystream algorithm.

### 2.6 Family I1 — Escape-byte framing with CAN-ID envelope

Family I1 is used by the legacy Inmotion V-series wheels (V5 / V5+ / V5F /
V5D, V8 / V8F / V8S / Glide3, V10 / V10F / V10S / V10SF / V10T / V10FT,
plus the R1/R2/L6/Lively/R0 family). It wraps a fixed-width CAN-bus
envelope inside a byte-stuffed transport frame.

#### 2.6.1 Transport frame

A single logical frame on the wire is:

```
AA AA │ ESCAPED( BODY ) │ CHECK │ 55 55
```

| Field     | Size  | Notes                                                                |
|-----------|:-----:|----------------------------------------------------------------------|
| Preamble  | 2     | literal `AA AA`                                                      |
| Body      | var.  | byte-stuffed (see §2.6.2); unstuffed length is 16 B, or 16 B + *N*   |
| Check     | 1     | sum-mod-256 of the **unstuffed** body (see §6.4.1); transmitted raw, **not** escape-encoded |
| Trailer   | 2     | literal `55 55`                                                      |

The preamble (`AA AA`) and trailer (`55 55`) are always transmitted as
literal bytes; they are **not** subject to the escape rule of §2.6.2.
The CHECK byte is likewise transmitted in the clear; implementations
MUST be prepared to accept a raw `0xAA`, `0x55` or `0xA5` byte in that
position.

#### 2.6.2 Escape (byte-stuffing) rule

Let `x` be any byte of the unstuffed body. The escape function is:

```
x ∈ { 0xAA, 0x55, 0xA5 }   →   transmit( 0xA5, x )
otherwise                  →   transmit( x )
```

On receive, a byte `0xA5` acts as an escape marker and is **consumed**;
the byte that follows it is appended literally to the unstuffed buffer.
A second consecutive `0xA5` is treated as a literal `0xA5` (one
escape, one literal). This yields a fully transparent channel for
arbitrary body content.

The CHECK byte (see §6.4.1) is appended **after** escaping, so a
received `A5` occurring as the byte just before the `55 55` trailer
MUST be interpreted as CHECK (literal) and not as an escape marker.
Parsers detect that position by counting backward from the trailer.

#### 2.6.3 Unstuffed body (CAN envelope)

After removing escape bytes, the body is always at least 16 bytes long,
laid out as a CAN-bus-style record. All multi-byte integers inside
the envelope are **little-endian**.

| Offset | Size | Name    | Meaning                                                            |
|-------:|-----:|---------|--------------------------------------------------------------------|
| 0      | 4    | CAN-ID  | Message selector; see §3.5.1                                       |
| 4      | 8    | DATA-8  | Standard-frame 8-byte payload (unused bytes are `0x00`)            |
| 12     | 1    | LEN     | `0x08` for a standard 8-byte frame; `0xFE` means “extended frame”  |
| 13     | 1    | CHAN    | Logical channel; observed value `0x05` for all host↔device traffic |
| 14     | 1    | FMT     | `0x00` = standard CAN ID (11-bit), `0x01` = extended (29-bit)      |
| 15     | 1    | TYPE    | `0x00` = data frame, `0x01` = remote frame (query)                 |
| 16…    | var. | EX-DATA | Present only when `LEN == 0xFE`; length given by EX-LEN (see below)|

#### 2.6.4 Extended frames (`LEN == 0xFE`)

When LEN is `0xFE`, the body is lengthened by a variable-size
EX-DATA block appended after offset 16. In this case the four bytes
at offset 4..7 (the first four bytes of the DATA-8 field) carry a
**little-endian U32** that is the byte-length of EX-DATA, hereafter
called `EX-LEN`.

| Offset      | Size  | Meaning                                                |
|------------:|:-----:|--------------------------------------------------------|
| 4..7        | 4     | EX-LEN (U32LE)                                         |
| 8..15       | 8     | Standard 8-byte payload (typically all-ones in queries) |
| 16..16+L−1  | L     | EX-DATA payload, with `L == EX-LEN`                    |

Remote-frame queries (TYPE = `0x01`) are used to solicit specific
extended records; the device responds with an extended (LEN = `0xFE`)
data frame carrying the requested block in EX-DATA.

#### 2.6.5 GATT binding

Family I1 uses the split single-characteristic profile of §1.1: host
writes commands to the write characteristic (`0000ffe9-…`) on the
write service (`0000ffe5-…`), and receives notifications on the
notify characteristic (`0000ffe4-…`) of the read service
(`0000ffe0-…`). Transport fragmentation follows §1.3.

---

### 2.7 Family I2 — XOR-checksum length-prefixed framing

Family I2 is used by the current-generation Inmotion wheels (V9,
V11 / V11Y, V12-HS / HT / PRO / S, V13 / V13 PRO, V14g / V14s). It is
**not** compatible with I1: the envelope is different, the checksum
algorithm is different, and there is no fixed trailer.

#### 2.7.1 Transport frame

```
AA AA │ ESCAPED( FLAGS │ LEN │ CMD │ DATA[LEN−1] ) │ CHECK
```

| Field     | Size       | Notes                                                                                 |
|-----------|:----------:|---------------------------------------------------------------------------------------|
| Preamble  | 2          | literal `AA AA`                                                                       |
| FLAGS     | 1          | Message class (see §2.7.2)                                                            |
| LEN       | 1          | `DATA.length + 1` (the CMD byte itself is counted)                                    |
| CMD       | 1          | Application command identifier (see §3.5.2 / §10.4). On received frames, the high bit (`0x80`) is reserved and MUST be masked before dispatch (`cmd &= 0x7F`). |
| DATA      | LEN − 1    | Command-specific payload; little-endian multi-byte integers                            |
| CHECK     | 1          | XOR of the **unstuffed** bytes `FLAGS, LEN, CMD, DATA[0..LEN−2]` (see §6.4.2); transmitted raw, not escape-encoded |

There is **no** trailer byte pair in I2. A receiver finalises a frame
when it has collected exactly `LEN + 3` escape-decoded bytes after the
preamble (that is: FLAGS + LEN + CMD + DATA + CHECK =
`1 + 1 + 1 + (LEN − 1) + 1 = LEN + 3`).

#### 2.7.2 FLAGS enumeration

| Value | Name      | Use                                                                    |
|:-----:|-----------|------------------------------------------------------------------------|
| `0x11`| Init      | Bootstrap / identification exchanges (car-type, serial, version, power-off sequencing) |
| `0x14`| Default   | Run-time traffic (settings, real-time telemetry, battery info, stats, control) |

Other FLAGS values are reserved; conformant receivers MUST ignore
frames whose FLAGS is neither `0x11` nor `0x14`.

#### 2.7.3 Escape (byte-stuffing) rule — differs from I1

I2 escapes **two** byte values, not three:

```
x ∈ { 0xAA, 0xA5 }   →   transmit( 0xA5, x )
otherwise            →   transmit( x )
```

Unlike I1, the byte `0x55` is **not** escaped in I2 (there is no `55 55`
trailer to disambiguate). The CHECK byte is transmitted in the clear
without escaping, exactly as in I1.

#### 2.7.4 GATT binding

Family I2 uses the Nordic-UART-style profile of §1.2. Host writes to
the TX characteristic (`6e400002-…`) and subscribes to the RX
characteristic (`6e400003-…`). Multiple I2 frames MAY be packed into
one GATT notification and a single frame MAY span several
notifications; the receiver MUST buffer bytes and frame on the
`AA AA` preamble + LEN semantics of §2.7.1.

#### 2.7.5 Protocol-version detection (V11 sub-variants)

On V11 only, the application-layer telemetry layout (see §3.5.4)
depends on the main-board firmware version reported during the §9.5
identification handshake:

| Main-board version `M.m.p` | I2 telemetry variant |
|----------------------------|----------------------|
| `M < 2` and `m < 4`        | §3.5.4.A (“V11 early”) |
| otherwise                  | §3.5.4.B (“V11 ≥ 1.4”) |

Other I2 models select their telemetry variant directly from the
car-type identification record (§3.5.2).
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

The command code at offset 16 is the primary dispatch key for Family K.
Host-to-device payload details for every writable command are given in
**§10.3**; receive-only formats (`0xA9`, `0xB9`, etc.) are given in
§3.2.1 – §3.2.3.

| Code   | Direction | Purpose                                     |
|:------:|:---------:|---------------------------------------------|
| `0xA9` | dev→host  | Live telemetry page A                       |
| `0xB9` | dev→host  | Live telemetry page B (distance/fan/temp)   |
| `0xBB` | dev→host  | Device name & firmware version              |
| `0xB3` | dev→host  | Serial number                               |
| `0xF5` | dev→host  | CPU load / output PWM                       |
| `0xF6` | dev→host  | Active speed limit                          |
| `0xA4`, `0xB5` | dev→host | Alarm speeds and max speed (report) |
| `0xF1`, `0xF2` | dev→host | Smart BMS #1 / #2 (paged, sub @ off 17) |
| `0xE1`, `0xE2` | dev→host | BMS serial number                   |
| `0xE5`, `0xE6` | dev→host | BMS firmware                        |
| `0x63` | host→dev  | Request serial number                       |
| `0x9B` | host→dev  | Request device name                         |
| `0x98` | host→dev  | Request alarm settings & max speed          |
| `0x85` | host→dev  | Set alarm speeds & max speed (§10.3.2)      |
| `0x87` | host→dev  | Set pedals mode (§10.3.1; byte 17 = `0x15`) |
| `0x88` | host→dev  | Beep                                        |
| `0x89` | host→dev  | Wheel calibration                           |
| `0x73` | host→dev  | Light mode (§10.3.3)                        |
| `0x6C` | host→dev  | LED mode (§10.3.4)                          |
| `0x53` | host→dev  | Strobe mode (§10.3.5)                       |
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


### 3.5 Family I1 / I2 — Inmotion payload maps

#### 3.5.1 Family I1 CAN-ID registry

CAN-ID is a 32-bit little-endian value at offset 0..3 of the unstuffed
body (§2.6.3). Observed values:

| CAN-ID         | Purpose                              | Direction    | Notes             |
|----------------|--------------------------------------|--------------|-------------------|
| `0x0F550113`   | Live telemetry (extended)            | device→host  | LEN = `0xFE`; solicited by remote-frame query with the same ID |
| `0x0F550114`   | Static info / settings (extended)    | device→host  | LEN = `0xFE`     |
| `0x0F550115`   | Ride-mode / max-speed / pedal params | both         | §10.4.1           |
| `0x0F550116`   | Remote-control cluster (LED, beep, power-off, misc) | host→dev | §10.4.2 |
| `0x0F550119`   | Wheel calibration                    | host→dev     | §10.4.3           |
| `0x0F55010D`   | Headlight on/off                     | host→dev     | §10.4.4           |
| `0x0F55012E`   | Handle-button enable                 | host→dev     | §10.4.5           |
| `0x0F550307`   | PIN-code exchange                    | host→dev     | §9.5.1            |
| `0x0F55060A`   | Speaker-volume set                   | host→dev     | §10.4.6           |
| `0x0F550609`   | Play sound index                     | host→dev     | §10.4.7           |
| `0x0F780101`   | Alert / event notification           | device→host  | §4.3              |

A standard-format query (FMT = `0x00`, TYPE = `0x01`, DATA-8 =
`FF FF FF FF FF FF FF FF`) on CAN-ID `0x0F550113` or `0x0F550114`
elicits the respective extended record. During the keep-alive loop the
host issues a query on `0x0F550113` every 25 ms (see §9.5.1).

#### 3.5.2 Family I1 extended-telemetry EX-DATA layout (CAN-ID `0x0F550113`)

All integers **little-endian**, signed where noted.

| EX-DATA offset | Size | Signed | Name                     | Unit / scaling                            |
|---------------:|-----:|:------:|--------------------------|-------------------------------------------|
| 0              | 4    | U      | Pitch angle (raw)        | raw / 65536 → degrees                     |
| 12             | 4    | U      | Speed-A                  | see below                                 |
| 16             | 4    | U      | Speed-B                  | see below                                 |
| 20             | 4    | S      | Phase current            | 1/100 A                                   |
| 24             | 4    | U      | Voltage                  | 1/100 V                                   |
| 32             | 1    | S      | Temperature 1            | 1 °C (sensor-native)                      |
| 34             | 1    | S      | Temperature 2 (IMU)      | 1 °C                                      |
| 44             | 4/8  | U      | Total distance           | model-dependent, see §8.7                 |
| 48             | 4    | U      | Trip distance            | metres                                    |
| 60             | 4    | U      | Work-mode / state word   | §4.3                                      |
| 72             | 4    | U      | Roll (raw)               | raw / 90 → degrees (some models: force 0) |

**Speed computation.** Let `S = SpeedA + SpeedB`. The reported
ground speed is `|S| / (2 · F)` m/s, where `F` is a per-model
calibration constant:

| Model group                         | `F`        |
|-------------------------------------|-----------:|
| R1S / R1Sample / R0                 | 1 000      |
| R1T                                 | 3 810      |
| all others                          | 3 812      |

**Total-distance decoding.** Depending on the model family, the four
or eight bytes beginning at offset 44 carry different encodings; see
§8.7.

**State word.** The 32-bit little-endian state word at offset 60 is
masked to its low nibble for the legacy work-mode enumeration
(V5 / R1 / L6 / Lively families) or shifted right 4 bits for the
modern enumeration (V8F / V8S / V10 / V10F / V10S / V10SF / V10T /
V10FT). See §4.3 for both enumerations.

#### 3.5.3 Family I1 static-info / settings EX-DATA layout (CAN-ID `0x0F550114`)

| EX-DATA offset | Size | Signed | Name                   | Unit / encoding                                |
|---------------:|-----:|:------:|------------------------|-------------------------------------------------|
| 0..7           | 8    | —      | Serial-number bytes    | 8 bytes; textual form printed in reverse order, each as two hex digits |
| 24             | 2    | U      | Version patch          | 1                                               |
| 26             | 1    | U      | Version minor          | 1                                               |
| 27             | 1    | U      | Version major          | 1; printed as `"major.minor.patch"`             |
| 56             | 4    | U      | Pedal-horizon zero     | raw / 6 553.6 → display units                   |
| 60             | 2    | U      | Maximum speed          | raw / 1000 → km/h                               |
| 80             | 1    | —      | Headlight state        | 1 = on                                          |
| 104            | 1    | —      | Model-ID low digit     | see §3.5.3.1                                    |
| 107            | 1    | —      | Model-ID high digit    | see §3.5.3.1                                    |
| 124            | 1    | U      | Pedal hardness         | reported value `− 28`; `0x20..0x80` → 4..100 %  |
| 125            | 2    | U      | Speaker volume         | raw / 100                                       |
| 129            | 1    | —      | Handle-button enable   | reported value `!= 1` = enabled                 |
| 130            | 1    | —      | LED strip enable       | 1 = on                                          |
| 132            | 1    | —      | Ride-mode              | 1 = classic, 0 = comfort                        |

##### 3.5.3.1 Model ID decoding

The textual model-id is formed by:

```
if  byte[107] > 0   →   concatenate( decimal(byte[107]), decimal(byte[104]) )
else                →   decimal(byte[104])
```

The resulting 1- or 2-digit string maps to a marketing name:

| ID   | Model                | ID   | Model           |
|:----:|----------------------|:----:|-----------------|
| `0`  | R1N                  | `50` | V5              |
| `1`  | R1S                  | `51` | V5+             |
| `2`  | R1CF                 | `52` | V5F             |
| `3`  | R1AP                 | `53` | V5D             |
| `4`  | R1EX                 | `60` | L6              |
| `5`  | R1Sample             | `61` | Lively          |
| `6`  | R1T                  | `80` | V8              |
| `7`  | R10                  | `85` | Glide 3         |
| `10` | V3                   | `86` | V8F             |
| `11` | V3C                  | `87` | V8S             |
| `12` | V3Pro                | `100`| V10S            |
| `13` | V3S                  | `101`| V10SF           |
| `20`…`24`| R2 family        | `140`| V10             |
| `30` | R0                   | `141`| V10F            |
|      |                      | `142`| V10T            |
|      |                      | `143`| V10FT           |

Unknown IDs are treated as generic **V8** for telemetry decoding.

#### 3.5.4 Family I2 application-layer CMD registry (FLAGS = `0x14` unless noted)

| CMD    | Name                  | Direction   | Notes                                  |
|:------:|-----------------------|:-----------:|----------------------------------------|
| `0x02` | MainInfo              | both        | FLAGS = `0x11`; identification bootstrap (§9.5.2) |
| `0x03` | Diagnostic / power-off| both        | FLAGS = `0x11`; two-stage power-off (§10.4.8) |
| `0x04` | RealTimeInfo          | both        | Live telemetry (§3.5.4)                |
| `0x05` | BatteryRealTimeInfo   | both        | Per-BMS snapshot (§3.5.5)              |
| `0x10` | Reserved / ping       | both        | Host DATA `00 01`; reply content undocumented |
| `0x11` | TotalStats            | both        | Lifetime counters (§3.5.6)             |
| `0x20` | Settings              | both        | Per-model settings blob (§3.5.7)       |
| `0x60` | Control               | host→device | All write-setters and commands (§10.4) |

Host requests for informational CMDs carry an empty DATA region
(LEN = 1, payload is just the CMD byte). The device replies using the
same CMD value with a populated DATA region.

#### 3.5.4.A Family I2 real-time telemetry, V11 early variant (CMD `0x04`)

DATA layout when the device is a V11 whose main-board version is
< 1.4 (see §2.7.5). All multi-byte integers are **little-endian**.

| DATA off | Size | Signed | Name                     | Unit / scaling              |
|---------:|-----:|:------:|--------------------------|-----------------------------|
| 0        | 2    | U      | Voltage                  | 1/100 V                     |
| 2        | 2    | S      | Phase current            | 1/100 A                     |
| 4        | 2    | S      | Speed                    | 1/100 km/h                  |
| 6        | 2    | S      | Torque                   | 1/100 N·m                   |
| 8        | 2    | S      | Battery power            | 1 W                         |
| 10       | 2    | S      | Motor power              | 1 W                         |
| 12       | 2    | U      | Trip distance            | ×10 m                       |
| 14       | 2    | U      | Remaining range          | ×10 m                       |
| 16       | 1    | —      | Battery byte             | bits 0..6 = level %, bit 7 = mode |
| 17       | 1    | U      | MOS-FET temperature      | `raw + 80 − 256` → °C       |
| 18       | 1    | U      | Motor temperature        | same transform              |
| 19       | 1    | U      | Battery temperature      | same transform              |
| 20       | 1    | U      | Board temperature        | same transform              |
| 21       | 1    | U      | Lamp temperature         | same transform              |
| 22       | 2    | S      | Pitch                    | 1/100 °                     |
| 24       | 2    | S      | Pitch setpoint           | 1/100 °                     |
| 26       | 2    | S      | Roll                     | 1/100 °                     |
| 28       | 2    | U      | Dynamic speed limit      | 1/100 km/h                  |
| 30       | 2    | U      | Dynamic current limit    | 1/100 A                     |
| 32       | 1    | U      | Ambient brightness       | 0..255                      |
| 33       | 1    | U      | Headlight brightness     | 0..255                      |
| 34       | 1    | U      | CPU temperature          | `raw + 80 − 256` → °C       |
| 35       | 1    | U      | IMU temperature          | same transform              |
| 36       | 2    | U      | Output PWM               | 1/100 %                     |
| 36 or 38 | 1    | —      | State byte A             | see below                   |
| +1       | 1    | —      | State byte B             | see below                   |
| +5       | ≥7   | —      | Error bitmap             | §4.3.2                      |

The “State” region starts at byte 36 when the frame is short
(DATA length < 49) and at byte 38 otherwise. State byte A:

| Bits | Meaning                                       |
|-----:|-----------------------------------------------|
| 0..2 | PC mode (0 = lock, 1 = drive, 2 = shutdown, 3 = idle) |
| 3..5 | MC mode                                       |
| 6    | Motor-active flag                             |
| 7    | Charging flag                                 |

State byte B:

| Bits | Meaning                                       |
|-----:|-----------------------------------------------|
| 0    | Headlight on                                  |
| 1    | Decorative-light on                           |
| 2    | Lifted flag                                   |
| 3..4 | Tail-light mode                               |
| 5    | Cooling-fan active                            |

#### 3.5.4.B Family I2 real-time telemetry, V11 ≥ 1.4 / V12-HS/HT/PRO / V13 / V14 / V11Y / V12S / V9

These newer variants expand the frame to ≥ 78 bytes. The core
electrical channels sit at the same offsets; temperatures, trip and
state move to later offsets:

| DATA off | Size | Signed | Name                     | Unit / scaling                   |
|---------:|-----:|:------:|--------------------------|----------------------------------|
| 0        | 2    | U      | Voltage                  | 1/100 V                          |
| 2        | 2    | S      | Current                  | 1/100 A                          |
| 4        | 2    | S      | Reserved-A               | — (in some models: speed1)       |
| 8        | 2    | S      | Speed                    | 1/100 km/h (all 1.4+ variants)   |
| 10       | 2    | S      | Reserved-B               | — (in most models: `18000`)      |
| 12       | 2    | S      | Torque                   | 1/100 N·m                        |
| 14       | 2    | S      | Output PWM               | 1/100 %                          |
| 16       | 2    | S      | Battery power            | 1 W                              |
| 18       | 2    | S      | Motor power              | 1 W                              |
| 20       | 2    | S      | Pitch                    | 1/100 °                          |
| 22       | 2    | S      | Roll                     | 1/100 ° (V11 1.4 uses off 20/26) |
| 24       | 2    | S      | Pitch setpoint           | 1/100 °                          |
| 28       | 2    | U      | Trip distance            | ×10 m                            |
| 34       | 2    | U      | Battery level A          | raw / 100 → %                    |
| 36       | 2    | U      | Battery level B          | raw / 100 → % (average of A+B)   |
| 40       | 2    | U      | Dynamic speed limit      | 1/100 km/h                       |
| 50       | 2    | U      | Dynamic current limit    | 1/100 A                          |
| 58       | 1    | U      | MOS-FET temp             | `raw + 80 − 256` → °C            |
| 59       | 1    | U      | Motor temp               | same                             |
| 60       | 1    | U      | Battery temp             | same                             |
| 61       | 1    | U      | Board temp               | same                             |
| 62       | 1    | U      | CPU temp                 | same                             |
| 63       | 1    | U      | IMU temp                 | same                             |
| 64       | 1    | U      | Lamp temp                | same                             |
| 74       | 1    | —      | State byte A             | §3.5.4.A layout                  |
| 75 or 76 | 1    | —      | State byte B             | per-model; see variants          |
| 77       | ≥7   | —      | Error bitmap             | §4.3.2                           |

**Open ambiguity.** On V11 1.4+ the trip-distance field is
documented at DATA offset 26 (not 28); on V12-HS/HT/PRO the trip
field is at offset 22; on V13 the mileage word lies at offset 10 and
is reconstructed by reversing the byte order inside a 32-bit
little-endian load. These per-model offset tables are summarised in
§8.8 and marked as *observed constraints, pending authoritative
confirmation*.

**Battery-level rule.** For the 1.4+ variants the reported battery
percentage is the arithmetic mean of the two 16-bit U values at
offsets 34 and 36, divided by 100 and rounded to nearest integer.

#### 3.5.5 Family I2 battery real-time record (CMD `0x05`)

| DATA off | Size | Name                   | Notes                                      |
|---------:|-----:|------------------------|--------------------------------------------|
| 0        | 2    | BMS-1 pack voltage     | 1/100 V, U16LE                             |
| 4        | 1    | BMS-1 temperature      | 1 °C, signed                               |
| 5        | 1    | BMS-1 status byte      | bit 0 = valid, bit 1 = enabled             |
| 6        | 1    | BMS-1 work status      | bit 0 / bit 1                              |
| 8        | 2    | BMS-2 pack voltage     | 1/100 V, U16LE                             |
| 12       | 1    | BMS-2 temperature      | 1 °C, signed                               |
| 13       | 1    | BMS-2 status byte      | bit 0 = valid, bit 1 = enabled             |
| 14       | 1    | BMS-2 work status      | bit 0 / bit 1                              |
| 16       | 2    | Charger voltage        | 1/100 V                                    |
| 18       | 2    | Charger current        | 1/100 A                                    |

#### 3.5.6 Family I2 total-stats record (CMD `0x11`)

All four 32-bit fields are **little-endian**.

| DATA off | Size | Name                  | Unit                   |
|---------:|-----:|-----------------------|------------------------|
| 0        | 4    | Lifetime distance     | ×10 m                  |
| 4        | 4    | Energy dissipated     | 1 Wh                   |
| 8        | 4    | Energy recovered      | 1 Wh                   |
| 12       | 4    | Cumulative ride time  | 1 s                    |
| 16       | 4    | Cumulative power-on time | 1 s                 |

#### 3.5.7 Family I2 settings record (CMD `0x20`)

Settings DATA blobs are model-specific; the first DATA byte is an
echo of the sub-selector (`0x20`). The most important shared fields,
valid for V11 / V11Y / V12-HS/HT/PRO / V13 / V14 / V9 / V12S, are:

| DATA off | Size | Signed | Name                   | Unit / scaling                     |
|---------:|-----:|:------:|------------------------|-------------------------------------|
| 1        | 2    | U      | Speed limit            | 1/100 km/h                          |
| 3        | 2    | S      | Alarm-1 speed          | 1/100 km/h (V11Y/V13/V14/V9/V12S)   |
| 3        | 2    | S      | Pitch zero-point       | 1/10 ° (V11 variant only; differs per model) |
| 9        | 2    | U      | (V12) Speed limit      | 1/100 km/h                          |
| 11       | 2    | S      | (V12) Alarm-1 speed    | 1/100 km/h                          |
| 13       | 2    | S      | (V12) Alarm-2 speed    | 1/100 km/h                          |
| 15       | 2    | S      | (V12) Pitch zero-point | 1/10 °                              |
| 17       | 2    | U      | (V12) Stand-by delay   | 1 s (display as minutes = raw/60)   |
| 19       | 1    | —      | (V12) Mode bits        | bit 0 = Classic, bit 4 = Fancier    |
| 20       | 1    | U      | (V12) Pedal sens. Comfort | 0..255 (scale set by host)        |
| 21       | 1    | U      | (V12) Pedal sens. Classic | 0..255                            |
| 22       | 1    | U      | (V12) Volume           | 0..100                              |
| 26       | 1    | U      | (V12) Low-beam brightness  | 0..255                         |
| 27       | 1    | U      | (V12) High-beam brightness | 0..255                         |
| 31       | 1    | U      | (V12) Split-mode accel.| 0..255                              |
| 32       | 1    | U      | (V12) Split-mode brake | 0..255                              |
| 39       | 1    | —      | (V12) Flag byte 1      | b0 mute, b2 handle-button, b3 auto-light, b6 transport |
| 40       | 1    | —      | (V12) Flag byte 2      | b2 sound-wave                       |
| 41       | 1    | —      | (V12) Flag byte 3      | b0 split-mode                       |

For the V13 / V14 / V11Y / V9 / V12S families the settings blob uses
a second-generation layout starting at DATA offset 1, differing only
in which flag bits are present and their positions; all speed/angle
scalings are unchanged. The full per-model flag-bit table is given
in §10.4 cross-referenced from each setter command.

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

### 4.3 Families I1 / I2 — status, work-mode and error maps

This section defines the semantic content of the state word, the legacy
and modern work-mode enumerations, the I1 alert record, and the I2
error bitmap referenced from §3.5.2, §3.5.4.A and §3.5.4.B.

#### 4.3.1 Family I1 state word (telemetry EX-DATA offset 60)

The 32-bit little-endian state word at EX-DATA offset 60 of the
CAN-ID `0x0F550113` record (§3.5.2) is interpreted in one of two
ways, selected by model family:

- **Legacy enumeration** — applied to R1 / R2 / R0 / V3 / V5 / V5F /
  V5+ / V5D / L6 / Lively / V8 / Glide3 / V10 / V10S / V10SF. The
  decoder takes the low nibble of the state word (`state & 0x0F`)
  and maps it as:

  | Code | Mode string         |
  |:----:|---------------------|
  | `0`  | `Idle`              |
  | `1`  | `Drive`             |
  | `2`  | `Zero`              |
  | `3`  | `LargeAngle`        |
  | `4`  | `Check`             |
  | `5`  | `Lock`              |
  | `6`  | `Error`             |
  | `7`  | `Carry`             |
  | `8`  | `RemoteControl`     |
  | `9`  | `Shutdown`          |
  | `10` | `pomStop`           |
  | `12` | `Unlock`            |
  | other| `Unknown`           |

- **Modern enumeration** — applied to V8F / V8S / V10 / V10F / V10FT /
  V10S / V10SF / V10T. Here the high nibble (`state >> 4`) gives the
  primary state and the low bit of the low nibble qualifies it:

  | High nibble | Primary string   |
  |:-----------:|------------------|
  | `1`         | `Shutdown`       |
  | `2`         | `Drive`          |
  | `3`         | `Charging`       |
  | other       | `Unknown code N` |

  If `(state & 0x0F) == 1` the suffix ` - Engine off` is appended to
  the primary string.

#### 4.3.2 Family I1 alert record (CAN-ID `0x0F780101`)

When the device sends a standard (non-extended) frame whose CAN-ID is
`0x0F780101`, the 8-byte DATA-8 field is an asynchronous alert record:

| Offset | Size | Signedness | Name          | Derived values                                |
|-------:|-----:|:----------:|---------------|-----------------------------------------------|
| 0      | 1    | U          | `alertId`     | Alert code; table below                       |
| 1      | 1    | —          | reserved      | Transmit `0x00`; receivers must ignore        |
| 2..3   | 2    | S          | `aValue1`     | 16-bit **big-endian** quantity 1              |
| 4..7   | 4    | S          | `aValue2`     | 32-bit **big-endian** quantity 2              |

Derived human-readable fields:

```
a_speed_kmh = | aValue2 / 3812 | × 3.6
```

where the constant `3812` is the default I1 speed-calibration constant
`F` from §3.5.2 (unrelated to the per-model `F` used in live
telemetry — the alert path does not apply a model-specific scaler).

Alert-code table:

| `alertId` | Human-readable event                | Fields consumed                    |
|:---------:|-------------------------------------|------------------------------------|
| `0x05`    | Start from tilt angle               | tilt = `aValue1 / 100` °; speed = `a_speed_kmh` km/h |
| `0x06`    | Tilt-back                            | speed = `a_speed_kmh` km/h; limit = `aValue1 / 1000` |
| `0x19`    | Fall-down detected                   | —                                  |
| `0x1D`    | Please repair: bad battery cell      | voltage = `aValue2 / 100` V        |
| `0x20`    | Low battery                          | voltage = `aValue2 / 100` V        |
| `0x21`    | Speed cut-off                        | speed = `a_speed_kmh` km/h; aux = `aValue1 / 10` |
| `0x26`    | High load                            | speed = `a_speed_kmh` km/h; current = `aValue1 / 1000` A |
| other     | Unknown / reserved                   | raw hex of the 8-byte DATA-8 field |

Alert records are advisory; they do not replace the live-telemetry
state word of §4.3.1.

#### 4.3.3 Family I2 state bytes (telemetry CMD `0x04`)

The two state bytes of §3.5.4.A / §3.5.4.B decode as follows.

**State byte A** — bitfield:

| Bits  | Width | Meaning                                      |
|:-----:|:-----:|----------------------------------------------|
| 0..2  | 3     | PC-mode: `0` Lock, `1` Drive, `2` Shutdown, `3` Idle, others reserved |
| 3..5  | 3     | MC-mode (internal motor-controller sub-state; values reserved) |
| 6     | 1     | `1` = motor active                           |
| 7     | 1     | `1` = charging                               |

**State byte B** — bitfield (differs slightly between the V11 early
variant and newer telemetry; both are accepted):

| Bits  | Width | Meaning (all variants)                       |
|:-----:|:-----:|----------------------------------------------|
| 0     | 1     | Headlight on (V11 ≥ 1.4 / V12-HS/HT/PRO: low-beam on)            |
| 1     | 1     | Decorative light on (V11 ≥ 1.4 / V12-HS/HT/PRO: high-beam on)    |
| 2     | 1     | Lifted (foot-off pedal) detection            |
| 3..4  | 2     | Tail-light mode                              |
| 5     | 1     | Cooling fan active *(V11 early)* / firmware-update in progress *(V11 ≥ 1.4 and newer)* |

A convenience status string is produced by concatenating the tokens
`"Active"` (bit 6 of state A), `" Charging"` (bit 7 of state A) and
`" Lifted"` (bit 2 of state B) in that order.

#### 4.3.4 Family I2 error bitmap

Immediately following the state bytes, I2 telemetry frames carry at
least seven consecutive 8-bit error fields (labelled `E0..E6`). The
exact starting offset depends on the telemetry variant (see §8.8).
All 56 defined bits are independent fault flags; multiple may be
set simultaneously.

**`E0`**

| Bit | Symbolic name              | Meaning                                   |
|:---:|----------------------------|-------------------------------------------|
| 0   | `phase_current_sensor`     | Phase-current sensor fault                |
| 1   | `bus_current_sensor`       | Bus-current sensor fault                  |
| 2   | `motor_hall`               | Motor Hall-sensor fault                   |
| 3   | `battery`                  | Battery fault                             |
| 4   | `imu_sensor`               | IMU sensor fault                          |
| 5   | `controller_com_1`         | Controller ↔ peer link 1 fault            |
| 6   | `controller_com_2`         | Controller ↔ peer link 2 fault            |
| 7   | `ble_com_1`                | Bluetooth link 1 fault                    |

**`E1`**

| Bit | Symbolic name              | Meaning                                   |
|:---:|----------------------------|-------------------------------------------|
| 0   | `ble_com_2`                | Bluetooth link 2 fault                    |
| 1   | `mos_temp_sensor`          | MOS-FET temperature-sensor fault          |
| 2   | `motor_temp_sensor`        | Motor temperature-sensor fault            |
| 3   | `battery_temp_sensor`      | Battery temperature-sensor fault          |
| 4   | `board_temp_sensor`        | PCB temperature-sensor fault              |
| 5   | `fan`                      | Cooling-fan fault                         |
| 6   | `rtc`                      | Real-time-clock fault                     |
| 7   | `external_rom`             | External ROM fault                        |

**`E2`**

| Bit | Symbolic name              | Meaning                                   |
|:---:|----------------------------|-------------------------------------------|
| 0   | `vbus_sensor`              | Bus-voltage sensor fault                  |
| 1   | `vbattery_sensor`          | Battery-voltage sensor fault              |
| 2   | `cannot_power_off`         | Power-off controller refused              |
| 3   | `reserved_e2_3`            | Reserved; observed in the wild; semantics unspecified |

**`E3`**

| Bits  | Width | Symbolic name          | Meaning                                 |
|:-----:|:-----:|------------------------|-----------------------------------------|
| 0     | 1     | `under_voltage`        | Pack under-voltage                      |
| 1     | 1     | `over_voltage`         | Pack over-voltage                       |
| 2..3  | 2     | `over_bus_current`     | Bus over-current severity (`0..3`; `0` = no fault) |
| 4..5  | 2     | `low_battery`          | Low-battery severity (`0..3`)           |
| 6     | 1     | `mos_temp`             | MOS-FET over-temperature                |
| 7     | 1     | `motor_temp`           | Motor over-temperature                  |

**`E4`**

| Bit | Symbolic name              | Meaning                                   |
|:---:|----------------------------|-------------------------------------------|
| 0   | `battery_temp`             | Battery over-temperature                  |
| 1   | `over_board_temp`          | PCB over-temperature                      |
| 2   | `over_speed`               | Speed-limit exceeded                      |
| 3   | `output_saturation`        | Output saturated (PWM ceiling)            |
| 4   | `motor_spin`               | Motor abnormal spin                       |
| 5   | `motor_block`              | Motor stalled / blocked                   |
| 6   | `posture`                  | Chassis posture fault                     |
| 7   | `risk_behaviour`           | Rider risk behaviour detected             |

**`E5`**

| Bit | Symbolic name              | Meaning                                   |
|:---:|----------------------------|-------------------------------------------|
| 0   | `motor_no_load`            | Motor running with no load                |
| 1   | `no_self_test`             | Self-test not completed                   |
| 2   | `compatibility`            | Firmware / hardware incompatibility       |
| 3   | `power_key_long_press`     | Power key held (forced reset attempted)   |
| 4   | `force_dfu`                | Forced DFU (firmware-update) mode         |
| 5   | `device_lock`              | Device locked                             |
| 6   | `cpu_over_temp`            | CPU over-temperature                      |
| 7   | `imu_over_temp`            | IMU over-temperature                      |

**`E6`**

| Bit | Symbolic name              | Meaning                                   |
|:---:|----------------------------|-------------------------------------------|
| 0   | `reserved_e6_0`            | Reserved; transmit `0`                    |
| 1   | `hw_compatibility`         | Hardware-variant incompatibility          |
| 2   | `fan_low_speed`            | Fan running below expected speed          |
| 3   | `reserved_e6_3`            | Reserved; observed in the wild            |
| 4..7| reserved                   | Reserved; transmit `0`; receivers must ignore |

Implementations MUST treat any reserved bit that reads as `1` as a
warning-level "unknown error code `N`" event. Severity encodings in
`E3.2..3` and `E3.4..5` are ordered: `0` no fault, `1` informational,
`2` warning, `3` critical.

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

### 6.4 Family I1 / I2 checksums

These checksums are **different algorithms** despite both families
coming from the same vendor. Implementations MUST NOT share code
paths.

#### 6.4.1 Family I1 — 8-bit additive checksum

Let `B` be the **unstuffed** body sequence (preamble `AA AA` and
trailer `55 55` are not included). Then:

```
CHECK = ( Σ B[i] ) mod 256
```

The CHECK byte is transmitted unescaped between the last body byte
and the `55 55` trailer (§2.6.1). Frames whose recomputed CHECK does
not match the transmitted byte MUST be discarded.

#### 6.4.2 Family I2 — 8-bit XOR checksum

Let `B = FLAGS, LEN, CMD, DATA[0..LEN−2]` be the **unstuffed**
pre-check sequence. Then:

```
CHECK = B[0] XOR B[1] XOR … XOR B[n−1]    (all 8-bit)
```

The CHECK byte is transmitted unescaped immediately after DATA
(there is no trailer in I2; see §2.7.1). Frames whose recomputed
CHECK does not match MUST be discarded.

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
four wire bytes are `b0 b1 b2 b3`, the integer is:

```
value = (b2 << 24) | (b3 << 16) | (b0 << 8) | b1
```

Stated test vector: wire bytes `07 1F 00 00` ⇒ `1823` metres (the lower
word `0x071F` appears first, the upper word `0x0000` second).

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

### 8.6 Family K alarm/max-speed: read vs. write offset mismatch

The host-to-device write frame for command `0x85` and the device-to-host
report frames `0xA4` / `0xB5` use **different** offset maps for the same
four values (three alarm speeds + max speed). This is asymmetric and
must not be conflated:

| Field         | Write (`0x85`) offset | Report (`0xA4`/`0xB5`) offset |
|---------------|:---------------------:|:----------------------------:|
| Alarm 1 speed | 2                     | 4                            |
| Alarm 2 speed | 4                     | 6                            |
| Alarm 3 speed | 6                     | 8                            |
| Max speed     | 8                     | 10                           |

On write, the three bytes interleaved between the values (offsets 3, 5,
7) are **reserved `0x00`** (they act as padding that aligns each value
to an even address).

---

### 8.7 Family I1 total-distance encoding

The 4- or 8-byte total-distance field at EX-DATA offset 44 (§3.5.2) is
decoded differently per model family:

| Model family                                               | Load | Scaling                     |
|------------------------------------------------------------|:----:|-----------------------------|
| R1 / R2 family, V5 / V5F / V5+ / V5D, V8 / V8F / V8S / Glide3, V10 / V10F / V10S / V10SF / V10T / V10FT | U32LE | 1 m (raw value = metres) |
| R0                                                         | U64LE| 1 m                         |
| L6                                                         | U64LE| ×100 (raw × 100 = metres)   |
| legacy R1-era and all others                               | U64LE| `raw / 5.711 016 379 455 429 × 10⁷` → kilometres, then ×1000 → metres (rounded) |

> The last scaling constant reproduces a first-generation Inmotion
> odometer that used fixed-point rotational-tick units rather than
> metres.

### 8.8 Family I2 per-model telemetry offsets

Three I2 telemetry variants share offsets 0..18 (voltage, current,
pwm, powers) but diverge afterwards:

| Field                    | V11 early (§3.5.4.A) | V11 ≥1.4 (§3.5.4.B) | V12-HS/HT/PRO | V13 / V13 PRO | V14 / V11Y / V9 / V12S |
|--------------------------|:--------------------:|:-------------------:|:-------------:|:-------------:|:----------------------:|
| Trip distance offset     | 12                   | 26                  | 22            | 10 (rev-U32)  | 28                     |
| Battery level offset(s)  | 16 (byte)            | 28 (word)           | 24 (word)     | 34 & 36 (avg) | 34 & 36 (avg)          |
| Dynamic speed limit      | 28                   | 34                  | 30            | 40            | 40                     |
| Dynamic current limit    | 30                   | 36                  | 32            | 50            | 50                     |
| Temp block start (MOS)   | 17                   | 42                  | 40            | 58            | 58                     |
| State byte A             | 36 or 38             | 56                  | 54            | 74            | 74                     |
| Error bitmap start       | State A + 5          | 61                  | 59            | 76            | 77                     |

These offsets are *observed constraints* derived from field telemetry
captures. Fields marked in §3.5.4.B as `Reserved-A` / `Reserved-B`
consistently read `0`, `18000`, or nearby constants; their semantics
are presently undocumented and marked as open ambiguities.

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

### 9.5 Families I1 / I2 identification and keep-alive

#### 9.5.1 Family I1

A 6-character ASCII **PIN** (default: `"000000"`) is sent up to 6 times
in CAN-ID `0x0F550307` data frames. DATA-8 layout:

| Offset | Size | Meaning       |
|-------:|:----:|---------------|
| 0..5   | 6    | ASCII digits  |
| 6..7   | 2    | `0x00 0x00`   |

The wheel silently accepts any of the attempts whose PIN matches.
After the PIN is accepted, the host begins issuing remote-frame
queries on CAN-ID `0x0F550114` (static/slow record) until a valid
extended reply is received, then polls `0x0F550113` (fast telemetry)
at the keep-alive cadence (25 ms ticks; same scheduling shape as §9.3).

#### 9.5.2 Family I2

I2 does not use a PIN. The host issues three FLAGS = `0x11` requests
on CMD `0x02` (MainInfo) to retrieve:

1. Car-type record (DATA sub-selector `0x01`). Reply DATA is:

   | off | size | meaning                               |
   |----:|:----:|---------------------------------------|
   | 0   | 1    | sub-selector echo `0x01`              |
   | 1   | 1    | group (`0x02` for all supported I2 wheels) |
   | 2   | 1    | series (61 = V11, 62 = V11Y, 71 = V12 HS, 72 = V12 HT, 73 = V12 PRO, 81 = V13, 82 = V13 PRO, 91 = V14 50GB, 92 = V14 50S, 111 = V12S, 121 = V9) |
   | 3   | 1    | sub-type                              |
   | 4   | 1    | batch                                 |
   | 5   | 1    | feature                               |
   | 6   | 1    | reserved                              |

   The model key is `(series × 10 + sub-type)`.

2. Serial-number record (DATA sub-selector `0x02`). Reply DATA is 16
   ASCII characters starting at DATA offset 1.

3. Version record (DATA sub-selector `0x06`). Reply DATA carries five
   three-field version triplets encoded as
   `[ U16LE patch, U8 minor, U8 major ]`:

   | off | triplet name          |
   |----:|-----------------------|
   | 2   | Driver-board firmware |
   | 7   | Reserved A            |
   | 12  | Main-board firmware   |
   | 17  | Reserved B            |
   | 21  | BLE firmware          |

After identification the keep-alive loop drives the link at 25 ms
intervals, cycling in priority order:

1. Get current settings (FLAGS `0x14`, CMD `0x20`, DATA `0x20`)
2. Any pending host-initiated setting write (FLAGS `0x14`, CMD `0x60`)
3. Reserved ping (FLAGS `0x14`, CMD `0x10`, DATA `0x00 0x01`)
4. Total-stats (FLAGS `0x14`, CMD `0x11`)
5. Real-time telemetry (FLAGS `0x14`, CMD `0x04`)

Steady-state polling is real-time telemetry only; settings/stats are
retrieved once and re-requested whenever a setting-write has been
acknowledged.

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

### 10.3 Family K host-to-device command payloads

Every Family-K host-to-device command is carried in the 20-byte frame
defined in §2.2. Bytes 0–1 are always `AA 55`; bytes 18–19 are always
`5A 5A`; byte 16 is the command code; byte 17 is a command-specific
tail constant with default `0x14` (overridden where noted). **Any
payload byte not named below MUST be transmitted as `0x00`.**

The following sub-sections give the payload filling for each writable
command code.

#### 10.3.1 `0x87` — Set pedals mode

| Byte | Value                | Notes                                  |
|-----:|----------------------|----------------------------------------|
| 0    | `0xAA`               | Header                                 |
| 1    | `0x55`               | Header                                 |
| 2    | `pedalsMode`         | 0 = hard, 1 = medium, 2 = soft (semantics set by host) |
| 3    | `0xE0`               | **Required magic constant**            |
| 4–15 | `0x00`               | Reserved                               |
| 16   | `0x87`               | Command code                           |
| 17   | **`0x15`**           | **Overrides the default tail `0x14`**  |
| 18   | `0x5A`               | Tail                                   |
| 19   | `0x5A`               | Tail                                   |

Two departures from the general template are mandatory for this
command: **byte 3 must be `0xE0`** and **byte 17 must be `0x15`**.
Conformant devices reject the command otherwise.

#### 10.3.2 `0x85` — Set alarm speeds and maximum speed

Writes the three alarm thresholds and the tiltback maximum speed in a
single frame. All values are unsigned integers expressed in km/h (0..255).

| Byte | Value          | Notes                              |
|-----:|----------------|------------------------------------|
| 0    | `0xAA`         | Header                             |
| 1    | `0x55`         | Header                             |
| 2    | `alarm1Speed`  | Alarm level 1 threshold, km/h      |
| 3    | `0x00`         | Padding                            |
| 4    | `alarm2Speed`  | Alarm level 2 threshold, km/h      |
| 5    | `0x00`         | Padding                            |
| 6    | `alarm3Speed`  | Alarm level 3 threshold, km/h      |
| 7    | `0x00`         | Padding                            |
| 8    | `maxSpeed`     | Tiltback / maximum speed, km/h     |
| 9–15 | `0x00`         | Reserved                           |
| 16   | `0x85`         | Command code                       |
| 17   | `0x14`         | Tail constant                      |
| 18   | `0x5A`         | Tail                               |
| 19   | `0x5A`         | Tail                               |

**Degenerate case — read-back.** If all four values
(`alarm1Speed | alarm2Speed | alarm3Speed | maxSpeed`) are zero, the
host MUST instead transmit the same frame with byte 16 changed to
`0x98` — an explicit request to the device to report its current
alarm and max-speed settings (the device answers with `0xA4` or
`0xB5`; see §3.2 and §8.6 for the differing read/write offset maps).

#### 10.3.3 `0x73` — Set headlight mode

| Byte | Value              | Notes                                       |
|-----:|--------------------|---------------------------------------------|
| 0    | `0xAA`             | Header                                      |
| 1    | `0x55`             | Header                                      |
| 2    | `0x12 + lightMode` | `lightMode ∈ {0,1,2}` ⇒ byte 2 ∈ {`0x12`,`0x13`,`0x14`} |
| 3    | `0x01`             | **Required magic constant**                 |
| 4–15 | `0x00`             | Reserved                                    |
| 16   | `0x73`             | Command code                                |
| 17   | `0x14`             | Tail constant                               |
| 18   | `0x5A`             | Tail                                        |
| 19   | `0x5A`             | Tail                                        |

`lightMode` semantics (firmware-defined): `0` = off, `1` = on, `2` =
auxiliary/special. The value stored at byte 2 is always the mode value
**plus the bias `0x12`**, never the raw mode number.

#### 10.3.4 `0x6C` — Set LED mode

| Byte | Value     | Notes                                      |
|-----:|-----------|--------------------------------------------|
| 0    | `0xAA`    | Header                                     |
| 1    | `0x55`    | Header                                     |
| 2    | `ledMode` | LED pattern index; valid range is wheel-dependent |
| 3–15 | `0x00`    | Reserved                                   |
| 16   | `0x6C`    | Command code                               |
| 17   | `0x14`    | Tail constant                              |
| 18   | `0x5A`    | Tail                                       |
| 19   | `0x5A`    | Tail                                       |

#### 10.3.5 `0x53` — Set strobe mode

| Byte | Value        | Notes                                   |
|-----:|--------------|-----------------------------------------|
| 0    | `0xAA`       | Header                                  |
| 1    | `0x55`       | Header                                  |
| 2    | `strobeMode` | Strobe pattern index; wheel-dependent range |
| 3–15 | `0x00`       | Reserved                                |
| 16   | `0x53`       | Command code                            |
| 17   | `0x14`       | Tail constant                           |
| 18   | `0x5A`       | Tail                                    |
| 19   | `0x5A`       | Tail                                    |

#### 10.3.6 Integrity summary for §10.3

Family-K host-to-device command integrity is defined entirely by:

1. Fixed 20-byte length.
2. Fixed header `AA 55` (bytes 0..1).
3. Fixed tail `5A 5A` (bytes 18..19).
4. The single command-specific tail byte 17 (default `0x14`; `0x15`
   for command `0x87`).
5. Any command-specific magic constants declared above
   (`0xE0` at byte 3 for `0x87`, `0x01` at byte 3 for `0x73`,
   the `+0x12` bias on byte 2 of `0x73`).

There is no checksum or CRC; devices validate only the positional
constants. For this reason, hosts MUST zero-fill any unused bytes to
avoid triggering accidental matches of unrelated command payloads.

---

### 10.4 Family I1 / I2 control commands

#### 10.4.1 Family I1 — CAN-ID `0x0F550115` (ride-mode cluster)

All commands use standard-format data frames (LEN = `0x08`, CHAN = `0x05`,
FMT = `0x00`, TYPE = `0x00`). The DATA-8 byte layout is selected by
DATA-8[0]:

| DATA-8[0] | Purpose                          | DATA-8[1..7]                                                      |
|:---------:|----------------------------------|-------------------------------------------------------------------|
| `0x01`    | Set maximum speed                | `00 00 00 hi lo 00 00`, where `hi:lo` = (max_km/h × 1000) big-endian |
| `0x06`    | Set pedal sensitivity            | `00 00 00 hi lo 00 00`, where `hi:lo` = `(sens + 28) << 5` big-endian |
| `0x0A`    | Set ride-mode (Classic/Comfort)  | `00 00 00 flag 00 00 00` (flag = 0 Comfort, 1 Classic)            |
| `0x00`    | Set horizontal tilt              | `00 00 00 b3 b2 b1 b0`, where `b3..b0` = BE bytes of `(angle × 6553.6)` |

#### 10.4.2 Family I1 — CAN-ID `0x0F550116` (remote-control cluster)

All frames start with DATA-8[0] = `0xB2`; DATA-8[1..3] = `0x00`; the
action byte is DATA-8[4]:

| DATA-8[4] | Action             |
|:---------:|--------------------|
| `0x05`    | Power off          |
| `0x0F`    | LED strip on       |
| `0x10`    | LED strip off      |
| `0x11`    | Beep               |

Any other value at DATA-8[4] is reserved.

#### 10.4.3 Family I1 — CAN-ID `0x0F550119` (calibration)

DATA-8 = `32 54 76 98 00 00 00 00`. The first four bytes are a magic
constant; the wheel enters calibration mode only on an exact match.

#### 10.4.4 Family I1 — CAN-ID `0x0F55010D` (headlight)

DATA-8[0] = `0x01` turns the headlight on, `0x00` off; DATA-8[1..7] = 0.

#### 10.4.5 Family I1 — CAN-ID `0x0F55012E` (handle-button)

DATA-8[0] = `0x00` enables handle-button detection, `0x01` disables;
DATA-8[1..7] = 0. Note the **inverted** convention.

#### 10.4.6 Family I1 — CAN-ID `0x0F55060A` (speaker volume)

DATA-8[0..1] = U16LE of `(volume × 100)` (i.e., lo byte at +0, hi byte
at +1); DATA-8[2..7] = 0. Valid input range 0..100.

#### 10.4.7 Family I1 — CAN-ID `0x0F550609` (play sound)

DATA-8[0] = sound index (0..255); DATA-8[1..7] = 0.

#### 10.4.8 Family I2 — power-off (CMD `0x03`, FLAGS `0x11`)

Power-off is a two-frame sequence:

1. Host sends FLAGS `0x11`, CMD `0x03`, DATA `0x81 0x00`.
2. Wheel replies with FLAGS `0x11`, CMD `0x03` (any DATA).
3. Host responds within the same flow with FLAGS `0x11`, CMD `0x03`,
   DATA `0x82`. Wheel then powers off.

Issuing only stage 1 is a NO-OP; stage 2 must not be sent before
the reply is observed.

#### 10.4.9 Family I2 — settings writes (CMD `0x60`, FLAGS `0x14`)

Every write is a Control frame whose DATA begins with a sub-command
byte. Multi-byte numeric payloads are **big-endian** as sent on the
wire (high byte at +1, low byte at +2, etc.):

| Sub-cmd | Purpose                                   | Following DATA bytes                          |
|:-------:|-------------------------------------------|-----------------------------------------------|
| `0x21`  | Max speed (all)                           | U16BE of `maxKmh × 100`                       |
| `0x21`  | Max + alarm-1 (V13 / V14 / V11Y / V9 / V12S) | 2 × U16BE: `maxKmh × 100` then `alarmKmh × 100` |
| `0x22`  | Pedal horizon tilt                        | U16BE of `angle × 10`                         |
| `0x23`  | Classic / Comfort mode                    | 1 byte (1 = Classic, 0 = Comfort)             |
| `0x24`  | Fancier mode                              | 1 byte (0/1)                                  |
| `0x25`  | Pedal sensitivity                         | **two identical bytes**: `sens, sens`         |
| `0x26`  | Speaker volume                            | 1 byte (0..100)                               |
| `0x28`  | Stand-by delay                            | U16BE of `delayMin × 60` (seconds)            |
| `0x2B`  | Light brightness (V11 / V13 / V14 / V11Y / V9 / V12S) | 1 byte (0..255)                    |
| `0x2B`  | Low+high-beam brightness (V12-HS/HT/PRO)  | 2 bytes: `lowBeam, highBeam`                  |
| `0x2C`  | Mute (inverted sense)                     | 1 byte (0 = muted, 1 = sound)                 |
| `0x2D`  | DRL                                       | 1 byte (0/1)                                  |
| `0x2E`  | Handle-button (inverted sense)            | 1 byte (1 = disabled, 0 = enabled)            |
| `0x2F`  | Auto-light                                | 1 byte (0/1)                                  |
| `0x31`  | Lock mode                                 | 1 byte (0/1)                                  |
| `0x32`  | Transport mode                            | 1 byte (0/1)                                  |
| `0x37`  | Go-home mode                              | 1 byte (0/1)                                  |
| `0x38`  | Fan quiet mode                            | 1 byte (0/1)                                  |
| `0x39`  | Sound-wave effect                         | 1 byte (0/1)                                  |
| `0x3E`  | Alarm-speeds 1 & 2 (V11/V12)              | 2 × U16BE: `alarm1 × 100`, `alarm2 × 100`     |
| `0x3E`  | Split-mode toggle (non-V12-H)             | 1 byte (0/1) — **same sub-cmd; disambiguated by model**  |
| `0x3F`  | Split-mode accel+brake (V11/V13/V14/etc.) | 2 bytes: `accel, brake`                        |
| `0x40`  | Headlight on/off (V11 < 1.4 only)         | 1 byte (0/1)                                  |
| `0x40`  | Split-mode accel+brake (V12-HS/HT/PRO)    | 2 bytes: `accel, brake`                       |
| `0x41`  | Play sound (V11 < 1.4 only)               | 2 bytes: `soundIdx, 0x01`                     |
| `0x42`  | Wheel calibration                         | 3 bytes: `0x01 0x00 0x01`                     |
| `0x42`  | Split-mode toggle (V12-HS/HT/PRO)         | 1 byte (0/1)                                  |
| `0x43`  | Cooling-fan override                      | 1 byte (0/1)                                  |
| `0x45`  | Berm-angle mode                           | 1 byte (0/1)                                  |
| `0x50`  | Headlight on/off (V11 ≥ 1.4 and newer)    | 1 byte (0/1)                                  |
| `0x50`  | Low/high-beam on/off (V12-HS/HT/PRO)      | 2 bytes: `lowBeamOn, highBeamOn`              |
| `0x51`  | Play sound (V11 ≥ 1.4 and newer)          | 2 bytes: `soundIdx, 0x01`                     |
| `0x51`  | Play beep (V13 / V13 PRO / V14 / V11Y)    | 2 bytes: `beepIdx, 0x64`                      |
| `0x52`  | Wheel calibration (turn variant)          | 3 bytes: `0x01 0x00 0x01`                     |
| `0x52`  | Wheel calibration (balance variant)       | 3 bytes: `0x01 0x01 0x00`                     |

**Note on sub-commands with two meanings.** Sub-commands `0x3E`,
`0x40`, `0x42`, `0x50`, `0x51` and `0x52` are **model-disambiguated**:
the same first DATA byte means different things on V11 < 1.4,
V11 ≥ 1.4, V12-HS/HT/PRO, V13/V13 PRO, V14, V11Y, V9 and V12S.
Implementations MUST dispatch on the car-type identifier from §9.5.2
before selecting the sub-command.

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
2. It correctly frames and defragments data as spec
