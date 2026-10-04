# Phase 4 handoff: scooter BLE connection

## Delivered

- `domain/.../connection/ScooterConnection.kt` (21 lines): reactive scooter API and domain-owned handshake status. Domain does not depend on the protocol module.
- `domain/.../connection/ConnectionState.kt` (71 lines): scooter handshake lifecycle state alongside the existing wheel states.
- `data/protocol/.../familyscooter/connection/ScooterConnectionImpl.kt` (226 lines): BLE connect, receive loop, Ninebot Retail pairing, 20-second confirmation watchdog, B0 polling, read requests, telemetry, and command gates.
- `data/protocol/.../familyscooter/ScooterConnectionImplTest.kt` (113 lines): fake BLE integration tests for pairing, timeout, polling, speed gate, and forbidden controls.
- `data/ble/.../GattUuids.kt` (62 lines): Xiaomi FE95 service UUID and topology guidance.

## Safety and protocol limits

The source material does not establish the Ninebot 0x5D payload, the retail lock/unlock write frame, or a trustworthy speed scale for the B0 block. `ScooterConnectionImpl` therefore requires an explicitly supplied, verified speed decoder and 0x5D payload provider for the critical handshake path. Without them, pairing fails closed. Lock and unlock reject unknown or moving speed; even after stationary confirmation they return `Unsupported` without sending invented bytes. Reboot, power-down, and firmware opcodes remain forbidden. B0 battery percentage is decoded, while speed remains unknown unless a verified decoder is supplied. The FE95 characteristic layout is not inferred from its service UUID.

The current pipeline handles plaintext `5A A5` Ninebot Retail frames. Encrypted `55 AB` traffic and a production model-specific GATT binding still require verified protocol evidence before enabling a real scooter connection.

## Verification

Java 21 under WSL Kali: `./gradlew :domain:test :data:protocol:test --no-configuration-cache` — **304 tests, 0 failures, 0 errors** (75 domain, 229 protocol). The three new integration tests use only JVM coroutines and a fake `BleTransport`.
