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
