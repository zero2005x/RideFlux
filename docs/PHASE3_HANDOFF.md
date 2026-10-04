# RideFlux Phase 3 handoff

Updated 2026-10-04 (Asia/Taipei). Phase 1 and Phase 2 changes remain uncommitted in this worktree.

## Implemented

- `NinebotRetailCodec` now follows SHU 4.2.1 Dart AOT's `5A A5` geometry: payload-only length, total `len + 9`, and little-endian `0xFFFF xor sum` over bytes starting at the length byte. The older WIKI and FRAME_CODEC alternatives were removed from the active codec.
- Read requests carry a two-byte little-endian size. The pinned `0x22`/2-byte vector is `5A A5 02 3E 20 01 22 02 00 7A FF`.
- Pairing step 1 emits `5A A5 00 3E 04 5B 00 62 FF`. Steps 2 and 3 require a `MotionInterlock` with three fresh zero-speed samples. Lock/unlock registers are classified critical; reboot, power-down and firmware operations are forbidden. No speculative register write encoder was added.
- `ScooterHandshakeStateMachine` enforces the 0x5B → 0x5C proposal → 0x5C/0x01 button confirmation → 0x5D acceptance sequence, with a 20-second confirmation timeout. It validates plaintext reply framing and checksum at each received stage.
- Pure JVM tests cover exact vectors, corrupted lengths/checksums, unknown/moving speed rejection, stale speed, incorrect ACK, successful transition and timeout.

## Evidence limits and next work

- The framing conclusions are **L1 third-party static evidence**, not a local Ninebot retail wire capture. The quoted read and step-1 vectors are derived from the disassembly.
- SHU's 0x5D payload remains `NOT_ESTABLISHED`. The step-3 builder requires caller-supplied bytes from a separately verified profile and never invents them.
- Pairing destination 0x04 is a derived pre-encryption vector; the device-specific `tx_addr` configuration was not observed. Builders expose the destination for an explicit profile.
- Scooter speed scaling remains unresolved, so production telemetry does not feed a verified stationary speed to the pairing interlock. Critical pairing is consequently fail-closed in ordinary operation. No scooter BLE transport/factory integration exists yet.
- Lock/unlock wire writes are not implemented because their register-write payload and acknowledgement semantics are not verified. `requireControlAllowed` provides policy for a future verified builder.

## Verification

WSL with Java 21 and `GRADLE_USER_HOME=/home/kali/ElectricUnicycleHacking/.gradle-home`:

`./gradlew :domain:test :data:protocol:test --no-configuration-cache`

Build successful: **75 domain + 226 protocol tests, zero failures**. No test imports Android framework classes.
