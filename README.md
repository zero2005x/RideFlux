# RideFlux

An open-source Android companion app for **Electric Unicycles (EUC)**, with a
forthcoming real-time AR HUD experience for **Rokid** AR glasses.

RideFlux connects to your EUC over Bluetooth Low Energy, decodes live telemetry
(speed, battery, temperature, alerts), and presents it on a glanceable Jetpack
Compose dashboard — so you never have to take your eyes off the road.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/liangtinglin)

---

## ⚠️ Project Status — Clean-Room Rewrite in Progress

This repository is undergoing a **clean-room rewrite** of its BLE / protocol
decoding layer under the `com.rideflux.*` namespace. The legacy code in the
previous `com.wheellog.next.*` namespace has been removed; what remains is the
new, independently authored implementation.

- Governance: see [CLEAN_ROOM_PROCESS.md](CLEAN_ROOM_PROCESS.md).
- Provenance & scope: see [PROVENANCE.md](PROVENANCE.md).
- Specification bundle authored by Team A:
  [clean-room/spec/](clean-room/spec/).

Feature coverage of the current build:

| Feature | Status |
| --- | --- |
| BLE scan + connect | ✅ Working |
| Live telemetry dashboard (Compose) | ✅ Working |
| Protocol decoders (Begode / KingSong / Inmotion / Ninebot / Veteran) | ✅ Working, unit-tested |
| Headlight / pedals-mode / beep control commands | 🛠 In progress |
| Rokid AR glasses HUD overlay | 🛠 In progress |
| Trip recording & history | ⏸ Pending re-implementation |
| User-preferences screen | ⏸ Pending re-implementation |
| HUD-gateway GATT server (phone → glasses) | ⏸ Pending re-implementation |

---

## Architecture

RideFlux is organised as a **4-module** Gradle project following Clean
Architecture principles:

```
app/              Phone app — MainActivity, NavHost, Hilt DI, Compose UI
domain/           Pure Kotlin contracts — WheelRepository / WheelConnection /
                  WheelCodec / telemetry / command types.
data/
  ├── protocol/   Clean-room codec implementations for every supported family.
  └── ble/        Android BluetoothGatt transport + repository implementation.
```

All modules share a Kotlin 2.0.0 / Compose BOM 2024.06 / Hilt 2.51.1 stack
via the `gradle/libs.versions.toml` version catalog.

## Supported Wheel Families

| Family | Codec |
| --- | --- |
| Begode (Gotway) | `BegodeWheelCodec` |
| KingSong | `KingSongWheelCodec` |
| Veteran | `VeteranWheelCodec` |
| Ninebot N1 / N2 | `NinebotN1WheelCodec` / `NinebotN2WheelCodec` |
| Inmotion I1 / I2 | `InmotionI1WheelCodec` / `InmotionI2WheelCodec` |

Every codec is verified by unit tests that replay the test vectors in
[clean-room/spec/TEST_VECTORS.md](clean-room/spec/TEST_VECTORS.md).

## Tech Stack

| Category | Technology |
| --- | --- |
| Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (BOM 2024.06.00), Material 3 |
| Architecture | Clean Architecture + MVVM |
| BLE | Native Android `BluetoothGatt` (no third-party BLE library) |
| DI | Hilt 2.51.1 + KSP |
| Navigation | `androidx.navigation:navigation-compose` 2.7.7 |
| Async | Kotlin Coroutines 1.8.1 |
| Build | AGP 8.5.2, Gradle 9.x, **JDK 17 or 21** |
| Test | JUnit 4, Turbine 1.1.0, kotlinx-coroutines-test |
| SAST | SonarCloud |

## Getting Started

### Prerequisites

- **Android Studio** Jellyfish (2024.1) or newer.
- **JDK 17 or JDK 21**. Kotlin 2.0.0 cannot parse version strings from JDK 25
  or newer. If your system Java is too new, point Gradle at a compatible JDK
  via a **user-level** `gradle.properties`:

  ```properties
  # Windows: %USERPROFILE%/.gradle/gradle.properties
  # Unix:    ~/.gradle/gradle.properties
  org.gradle.java.home=/path/to/jdk-21
  ```

- **Android SDK** — compileSdk 34, minSdk 26.

### Build

```bash
# Clone the repository
git clone git@github.com:zero2005x/RideFlux.git
cd RideFlux

# Build the debug APK
./gradlew :app:assembleDebug

# Run all unit tests
./gradlew test

# Install on a connected device
./gradlew :app:installDebug
```

## How It Works

1. `ScannerViewModel` drives a BLE scan via `WheelRepository.scan()`, which
   filters advertisements against the service UUIDs recognised in
   [`GattUuids`](data/ble/src/main/kotlin/com/rideflux/data/ble/).
2. Tapping a discovered wheel routes to the dashboard with the MAC address
   and inferred family passed as nav arguments.
3. `DashboardViewModel` calls `WheelRepository.connect(address, family)`;
   the repository ref-counts `WheelConnection`s so multiple observers share
   a single GATT session.
4. `WheelConnectionImpl` drives the handshake + keep-alive loop, feeds raw
   bytes into the per-family `WheelCodec`, and publishes:
   - `StateFlow<WheelTelemetry>` — latest snapshot.
   - `SharedFlow<WheelAlert>` — discrete events (tilt-back, over-temperature …).
5. The dashboard renders speed, battery %, voltage, current, MOS temperature,
   and a banner for any active alert.

## CI/CD

Two GitHub Actions workflows run on every push / PR to `main` or `master`:

- **CI** — Builds the debug APK, runs unit tests, runs Android Lint.
- **SonarCloud** — Static analysis and code-quality gate.

## Contributing

Before opening a PR, please read [CLEAN_ROOM_PROCESS.md](CLEAN_ROOM_PROCESS.md)
— it defines the Chinese-wall separation between the team that reads the
external reference project ("Team A") and the team that writes new code
("Team B"). Contributions to the BLE / protocol layer must follow the
process or they cannot be merged.

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/my-feature`).
3. Make your changes and add tests. Run `./gradlew test`.
4. Submit a pull request.

## License

This project is released under **GPL-3.0-or-later**. See the [LICENSE](LICENSE)
file for details.

## Acknowledgments

- Clean-room protocol specifications derived from public vendor documentation
  and on-hardware observation only. See [PROVENANCE.md](PROVENANCE.md) for the
  full provenance policy.
- AR HUD integration targets [Rokid](https://www.rokid.com/) glasses.
# RideFlux

An open-source Android companion app for **Electric Unicycles (EUC)** with real-time AR HUD support for **Rokid AR glasses**.

RideFlux connects to your EUC via Bluetooth Low Energy, decodes telemetry data (speed, battery, temperature, alerts), and streams it live to a heads-up display on Rokid RV101 glasses — so you never have to look down while riding.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/liangtinglin)

## Features

- **Multi-brand EUC support** — Begode (Gotway), KingSong, Inmotion (v1 & v2), Ninebot, Ninebot Z-series, and Veteran wheels
- **Real-time dashboard** — Speed, battery, temperature, distance, and alert indicators on your phone
- **AR HUD** — Dedicated glasses APK streams telemetry to Rokid RV101 via BLE GATT Server (14-byte binary protocol)
- **Trip recording** — Automatic trip logging with start/stop detection, distance, and duration tracking
- **Metric & Imperial** — Full unit system support propagated from phone to HUD
- **Alert system** — Speed alarm, temperature warning, voltage/current alerts, displayed on both phone and glasses
- **Modern architecture** — Clean Architecture + MVI, Jetpack Compose, Kotlin Coroutines, Hilt DI

## Architecture

RideFlux is organized as a **14-module** Gradle project following Clean Architecture principles:

```
app/                    Main phone app — MainActivity, NavHost, Hilt setup
hud-app/                Standalone APK for Rokid AR glasses (BLE Central client)
core/
  ├── common/           MVI base classes, shared utilities
  ├── ui/               Compose theme, reusable UI components
  └── testing/          Fake repositories and test helpers
domain/                 Use cases and repository interfaces (pure Kotlin)
data/
  ├── ble/              Kable-based BLE connection management
  ├── protocol/         EUC protocol decoders (8 brands)
  ├── database/         Room database for trip persistence
  └── preferences/      DataStore-based user preferences
feature/
  ├── dashboard/        Real-time telemetry display
  ├── device-scan/      BLE device discovery UI
  ├── hud-gateway/      GATT Server that pushes data to glasses
  ├── settings/         User preferences screen
  └── trips/            Trip history and export
```

### Data Flow

```
EUC wheel  ──BLE──►  Phone (RideFlux app)  ──GATT Server──►  Rokid glasses (hud-app)
                     ├─ Protocol decoder
                     ├─ Domain use cases
                     └─ Dashboard UI
```

## Supported Devices

### EUC Brands

| Brand | Protocol | Models |
|-------|----------|--------|
| Begode (Gotway) | Frame A/B | MCM5, Tesla, Nikola, Monster, EX.N, Master, A2 |
| KingSong | 3-byte header | 14D/S, 16X, 18XL, S18, S22 |
| Inmotion v1 | 18-byte frames | V5/V5F, V8/V8F, V10/V10F |
| Inmotion v2 | Extended | V11, V12, V13 |
| Ninebot | Encrypted | One S2, One E+ |
| Ninebot Z | Z-series | Z6/Z8/Z10 |
| Veteran | Sherman protocol | Sherman, Sherman Max, Abrams, Patton |

### AR Glasses

| Device | Connection | Status |
|--------|-----------|--------|
| Rokid RV101 | BLE GATT (Central client) | Supported |

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 2.0.0 |
| UI | Jetpack Compose (BOM 2024.06.00) |
| Architecture | MVI + Clean Architecture |
| BLE | Kable 0.31.1 |
| DI | Hilt 2.51.1 |
| Database | Room 2.6.1 |
| Preferences | DataStore 1.1.1 |
| Async | Kotlin Coroutines 1.8.1 |
| Build | AGP 8.5.2, Gradle 9.0, JDK 17 |
| Test | JUnit 4, Turbine 1.1.0, Kotlin Coroutines Test |
| SAST | SonarCloud |

## Getting Started

### Prerequisites

- **Android Studio** Jellyfish (2024.1) or newer
- **JDK 17**
- **Android SDK** — compileSdk 34, minSdk 26 (phone app) / minSdk 31 (hud-app)

### Build

```bash
# Clone the repository
git clone https://github.com/<your-org>/RideFlux.git
cd RideFlux

# Build debug APK (phone app)
./gradlew :app:assembleDebug

# Build HUD APK (glasses app)
./gradlew :hud-app:assembleDebug

# Run all unit tests
./gradlew test
```

### Install

```bash
# Install phone app
adb install app/build/outputs/apk/debug/app-debug.apk

# Install HUD app on Rokid glasses (connect glasses via USB)
adb install hud-app/build/outputs/apk/debug/hud-app-debug.apk
```

## How It Works

1. **Phone app** scans for and connects to your EUC via BLE
2. **Protocol decoder** parses raw BLE notifications into structured telemetry (speed, battery, temp, alerts)
3. **Dashboard** displays real-time data using Jetpack Compose
4. **GATT Server** (on phone) advertises a custom BLE service and encodes telemetry into a compact 14-byte payload
5. **HUD app** (on glasses) acts as a BLE Central, discovers the phone's GATT service, subscribes to notifications, and renders an always-on overlay

### HUD Payload Format (14 bytes)

| Offset | Size | Type | Field |
|--------|------|------|-------|
| 0 | 4 | Float | Speed |
| 4 | 1 | UByte | Battery % (0–100) |
| 5 | 4 | Float | Temperature (°C or °F) |
| 9 | 4 | Int | Alert bitmask |
| 13 | 1 | UByte | Unit system (0 = imperial, 1 = metric) |

## CI/CD

Two GitHub Actions workflows run on every push and PR to `main`:

- **CI** — Builds debug APK, runs all unit tests, runs Android Lint
- **SonarCloud** — Static analysis and code quality gate

## Project Structure Details

The project uses a **Gradle Version Catalog** (`gradle/libs.versions.toml`) as the single source of truth for all dependency versions. No version numbers are hardcoded in submodule build files.

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes and add tests
4. Ensure `./gradlew test` passes
5. Submit a pull request

## License

This project is open source. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Protocol decoding is based on reverse-engineering work from the [Wheellog.Android](https://github.com/Wheellog/Wheellog.Android) community
- BLE communication powered by [Kable](https://github.com/JuulLabs/kable)
- AR HUD designed for [Rokid](https://www.rokid.com/) RV101 glasses
