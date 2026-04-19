# Terms and Glossary

This glossary standardizes the vocabulary used in [PROTOCOL_SPEC.md](PROTOCOL_SPEC.md)
and [TEST_VECTORS.md](TEST_VECTORS.md).

## Units

| Symbol | Meaning | Notes |
| --- | --- | --- |
| V | volt | Battery and pack voltages. |
| A | ampere | Signed when the protocol distinguishes charge/discharge direction. |
| W | watt | Typically derived, not transmitted. |
| km/h | kilometers per hour | Convert as specified per field. |
| °C | degrees Celsius | |
| mAh | milliampere-hour | |
| % | percent | State-of-charge and similar. |
| ms | milliseconds | |

## Data Types

| Name | Width | Signed | Endianness | Notes |
| --- | --- | --- | --- | --- |
| `u8` | 1 byte | no | — | |
| `i8` | 1 byte | yes (two's complement) | — | |
| `u16` | 2 bytes | no | little-endian unless stated | |
| `i16` | 2 bytes | yes (two's complement) | little-endian unless stated | |
| `u32` | 4 bytes | no | little-endian unless stated | |
| `i32` | 4 bytes | yes (two's complement) | little-endian unless stated | |
| `bcd` | variable | n/a | — | Binary-coded decimal, MSB nibble first. |

## Domain Terms

- **Frame** — a complete, framed message on the BLE link.
- **Telemetry frame** — a device-to-app frame reporting measured state.
- **Command frame** — an app-to-device frame requesting an action or change.
- **Scale** — the multiplier that converts a raw integer to its physical
  value. Example: raw `1234` with scale `0.01 V` is `12.34 V`.
- **Checksum scope** — the contiguous byte range a checksum covers. Defined
  per frame in [PROTOCOL_SPEC.md](PROTOCOL_SPEC.md).
