# Begode profile-gated telemetry — 2026-10-11

## Proven

The rider backup is not imported by these tests. Prior notes still bound the incident: ordinals 20/21 end after about 11–12 seconds with three identical final samples, and 22/23 match a 120-sample idle stop. Every stored PWM value in that backup is 13.7%. That does not establish why the ride stopped, and it is not a physical pass.

Offset 14 of the documented live vector remains a tenths-of-a-percent word for that decoder. Publication is now profile-gated. The constant 137 seen on the captured A2 is treated as a mode word, not 13.7% PWM.

## Fixed

- An explicit per-address profile (`UNKNOWN`, `A2_CAPTURED`, `LEGACY_DOCUMENTED`) is persisted and read on every frame. Nothing auto-selects A2 from address, voltage, packet presence, name, or model identification.
- `UNKNOWN` withdraws PWM, MOS, IMU, and motor temperature. `A2_CAPTURED` publishes MPU6050 IMU and packet `0x07` PWM/motor temperature only when in range; MOS stays withdrawn. `LEGACY_DOCUMENTED` keeps the documented PWM byte and MPU6500 temperatures, and withdraws packet-7 motor temperature.
- Out-of-range packet-7 PWM clears the previous in-range value instead of clamping. Phase current stays in `phaseCurrentA`. `currentA` is invalidated so voltage times current is not shown as pack power.
- A profile change clears the disputed fields on the next frame. Null telemetry still means retain; an invalidation wins even if the delta also carries a value.
- Family G/GX dashboard exposes the choice and shows IMU separately from MOS.

## Tested

- `:data:protocol:test --tests com.rideflux.protocol.familyg.BegodeProfileCodecTest`: 7 tests, 0 failures.
- `:domain:test --tests com.rideflux.domain.telemetry.WheelTelemetryMergeTest`: passed, 0 failures.
- `:data:ble:testDebugUnitTest --tests com.rideflux.data.ble.WheelCodecFactoryImplTest`: 22 tests, 0 failures.
- `:data:preferences:testDebugUnitTest` for `DataStoreBegodeProfileStoreTest` and `DataStoreBegodeProfileStoreRestartTest`: each 1 test, 0 failures.
- `:app:compileDebugKotlin` succeeded. `:app:testDebugUnitTest` for `LocalizationCoverageTest`, `DashboardViewModelTest`, and `DashboardViewModelBatteryPackTest` succeeded. Lint was not run.

## Hardware-pending

No wheel was connected. Packet `0x07` scaling under load, MPU identification, and the dashboard chooser are not physically confirmed. Do not claim 150+ models or a field pass.
