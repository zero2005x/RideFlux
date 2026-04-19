# Terminology & Notation

| Term            | Meaning                                                       |
|-----------------|---------------------------------------------------------------|
| Mainboard       | On-wheel microcontroller running the motor-control firmware. |
| Host            | The nearby mobile app / controller parsing telemetry.        |
| Family          | A group of products sharing one wire-format protocol (see §1 of the spec). |
| Frame           | One self-delimited application-layer message.                |
| Notification    | One GATT notification (or indication) from device to host.   |
| BLE             | Bluetooth Low Energy.                                         |
| CCC             | Client Characteristic Configuration Descriptor (UUID `2902`).|
| LE (endianness) | Little-endian byte order (least-significant byte first).      |
| BE (endianness) | Big-endian byte order (most-significant byte first).          |
| U16             | Unsigned 16-bit integer.                                      |
| S16             | Signed 16-bit integer, two's complement.                      |
| U32             | Unsigned 32-bit integer.                                      |
| Keystream       | The 16-byte vector `γ` used for XOR obfuscation (§5).         |
| BMS             | Battery Management System sub-device.                         |
| Pack voltage    | Sum of all series cell voltages of the battery.               |
| Phase current   | Three-phase motor-winding current (as opposed to DC battery current). |
| PWM             | The motor-controller duty cycle, 0 % … 100 %.                 |
| Pedals mode     | Balancing stiffness preset.                                   |
| Tiltback        | Firmware-triggered pedal tilt used to indicate over-speed.    |
| Activation date | Date the mainboard was first powered, used by Family N.       |
| Refined curve   | Non-linear voltage-to-percent battery curve.                  |

### Symbol conventions

- Bytes are written as two-digit uppercase hex, e.g. `5A`.
- Ranges are expressed as `a..b` (inclusive).
- Bit ranges are expressed `[hi..lo]`.
- `N(signed)` / `N(unsigned)` denotes two's-complement / unsigned
  interpretation of an N-bit field.
- `U16LE(buf, off)` means the unsigned 16-bit integer read at byte
  offset `off` in `buf`, little-endian.
- All offsets are zero-based.

### Informative vs. normative

Sections 1–7 are **normative**. Sections 8–10 mix normative
requirements (unit scalings, handshake) with **informative**
observations (ambiguity notes, known extensions). Sections 11–12 are
normative.