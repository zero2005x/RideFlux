# Phase 6 protocol convergence handoff

## Implemented

- `M365Codec.B0Block` decodes register B5 as an unsigned little-endian metres-per-hour value and exposes `raw / 1000f` km/h in M365 telemetry. Values `>= 0xFF00` produce `null` (unknown).
- The SHU 4.2.1 pairing path retains a defensive copy of the proposed 16-byte app random and uses it as the default `0x5D` payload after a valid `0x5C/0x01` button confirmation. The 20-second deadline and stationary interlock still apply.
- `NinebotRetailCodec` has a diagnostic B0 speed candidate decoder and restricted lock/unlock frame builders. The only supported write geometry is destination `0x20`, command `0x02`, register `0x70` or `0x71`, payload `01 00`. Both builders require a fresh stationary `MotionInterlock` permit.
- `ScooterConnectionImpl` exposes lock support and sends these writes only when `lockProfileVerified = true` and a model-specific `verifiedSpeed` decoder has established stationary status. Defaults remain closed. A valid B0 reply with an unknown speed revokes the stationary permit.

## Evidence and safety boundary

The captured B5 scale comes from one Xiaomi M365 in `/home/kali/ScooterHacking/artifacts/SPEED-FIELD-ANALYSIS.md`. It does not establish the scale for a Ninebot ES2. The capture also reports invalid B5 sentinel values while slowing and with a spinning wheel. Therefore a sentinel cannot mean a safe zero speed; it is unknown and fails the motion interlock. Values are not clamped to 60 km/h because that would hide an out-of-range observation.

The SHU AOT notes in `/home/kali/ScooterHacking/re/shu/notes-aot.md` show the `0x5D` payload copy. The lock/write layout is supported by the [Ninebot IAP write handler](https://github.com/scooterhacking/Ninebot_IAP_V1/blob/master/iap/IAP/IAP_Form.cs) and [command definitions](https://github.com/scooterhacking/Ninebot_IAP_V1/blob/master/iap/IAP/CmdDefine.cs). These sources do not prove successful lock operation on the user's ES2 or justify enabling lock writes for every model. The `lockProfileVerified` constructor flag must be enabled only after model-specific validation. A completed BLE transport write does not prove the scooter changed lock state.

## Files and verification

Changed: `M365Codec.kt`, `NinebotRetailCodec.kt`, `ScooterHandshakeStateMachine.kt`, `ScooterConnectionImpl.kt`, and their four corresponding protocol test files. Added: this handoff.

Pure JVM verification: `./gradlew :domain:test :data:protocol:test --no-configuration-cache` with Java 21 in WSL passed. JUnit XML reports 308 tests, 0 failures, 0 errors, and 0 skipped.
