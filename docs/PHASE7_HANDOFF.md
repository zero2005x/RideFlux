# Phase 7 Android BLE scooter integration

## Implemented

- Added the domain `ScooterRepository` boundary and a `PlevRepositoryImpl` adapter. A discovered scooter address routes to the scooter repository; scooter connection failures propagate instead of falling back to a wheel codec.
- Added advertisement classification for the requested scooter name patterns and FE95 service UUID, with explicit wheel-name exclusion. The Android repository merges name and service UUID packets by address, scans with low latency, and publishes deduplicated scooter candidates.
- Added reference-counted scooter sessions. Separate consumers receive handles for one physical connection. The last close disconnects and removes the session; a failed session is closed before a new one is created. Teardown is cancellation-safe.
- Added a bounded `5A A5` frame assembler to the pure Kotlin connection path so split BLE notifications and concatenated frames are validated before handshake or telemetry handling.
- Wired the repository into the phone and HUD Hilt modules. The phone scanner now displays a separate scooter group and passes the scooter category into dashboard navigation.
- Added classifier, scan, repository lifecycle, frame assembly, and PLEV routing tests. Corrected a pre-existing wheel test that expected power-off to succeed despite its `T-FORBIDDEN` production tier and omitted the three stationary samples required for other controls.

## Protocol boundary

The physical repository opens the Ninebot Retail `5A A5` connection path on Nordic UART with the transport's single-characteristic fallback. It deliberately rejects `MIScooter` and generic FE95 candidates because the current connection state machine cannot speak their verified pairing profile. The M365 B5 scale has not been established for ES2; therefore the production Ninebot connection supplies no speed decoder and does not enable lock writes. Its critical pairing steps remain blocked by `MotionInterlock` until a model-specific stationary source is validated. Scan classification identifies a candidate, not proof of a compatible GATT or protocol profile. No real scooter connection or telemetry stream has been validated on hardware in this phase.

The app requests BLE permissions through its existing flow. The HUD binding uses the same repository lifecycle, but hardware behavior remains subject to device testing.

## Verification

`./gradlew :domain:test :data:protocol:test :data:ble:testDebugUnitTest :app:compileDebugKotlin :hud-app:compileDebugKotlin --no-configuration-cache` passed with Java 21 and Android SDK 36 in WSL. JUnit XML reports 75 domain + 236 protocol + 64 BLE = **375 tests**, 0 failures, 0 errors, 0 skipped. The Windows Gradle daemon fails before compilation with `Unable to establish loopback connection`; WSL compiled the same source tree.

## Files changed

Line counts below are current physical lines after Phase 7. Paths are repository relative.

| File | Lines |
| --- | ---: |
| `domain/src/main/kotlin/com/rideflux/domain/repository/ScooterRepository.kt` | 15 |
| `data/ble/src/main/kotlin/com/rideflux/data/ble/ScooterClassifier.kt` | 33 |
| `data/ble/src/main/kotlin/com/rideflux/data/ble/ScooterRepositoryImpl.kt` | 233 |
| `data/ble/src/test/kotlin/com/rideflux/data/ble/ScooterClassifierTest.kt` | 26 |
| `data/ble/src/test/kotlin/com/rideflux/data/ble/ScooterRepositoryLifecycleTest.kt` | 76 |
| `data/ble/src/test/kotlin/com/rideflux/data/ble/ScooterRepositoryScanTest.kt` | 87 |
| `data/ble/src/test/kotlin/com/rideflux/data/ble/WheelConnectionImplTest.kt` | 625 |
| `data/protocol/src/main/kotlin/com/rideflux/protocol/familyscooter/ninebot/NinebotRetailFrameAssembler.kt` | 38 |
| `data/protocol/src/main/kotlin/com/rideflux/protocol/familyscooter/connection/ScooterConnectionImpl.kt` | 253 |
| `data/protocol/src/main/kotlin/com/rideflux/protocol/repository/PlevRepositoryImpl.kt` | 54 |
| `data/protocol/src/test/kotlin/com/rideflux/protocol/familyscooter/NinebotRetailFrameAssemblerTest.kt` | 31 |
| `data/protocol/src/test/kotlin/com/rideflux/protocol/familyscooter/PlevRepositoryImplTest.kt` | 80 |
| `data/protocol/src/test/kotlin/com/rideflux/protocol/familyscooter/ScooterConnectionImplTest.kt` | 157 |
| `app/src/main/kotlin/com/rideflux/app/di/BleModule.kt` | 117 |
| `app/src/main/kotlin/com/rideflux/app/navigation/RideFluxNavHost.kt` | 241 |
| `app/src/main/kotlin/com/rideflux/app/ui/scanner/ScannerScreen.kt` | 707 |
| `app/src/main/kotlin/com/rideflux/app/ui/scanner/ScannerViewModel.kt` | 141 |
| `app/src/main/res/values/strings.xml` | 360 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 322 |
| `hud-app/src/main/kotlin/com/rideflux/hud/di/BleModule.kt` | 109 |
| `docs/PHASE7_HANDOFF.md` | 48 |
