# RideFlux Technical Specification (Tech Spec)

## 1. Architecture Guidelines

This project comprehensively adopts a **strict MVI (Model-View-Intent)** architecture, **Jetpack Compose**, and **Kable** as a lightweight Kotlin BLE solution.

* **Unidirectional Data Flow (MVI)**: `Intent` (User actions / System events) -> `Processor` (Business logic / API / BLE) -> `Reducer` (State updates) -> `State` (Compose rendering UI) -> `SideEffect` (One-time events like Toasts or Navigation).
* **BLE Handling**: Deprecating native Android BLE APIs and fully migrating to Touchlab's [Kable](https://github.com/JuulLabs/kable) to manage GATT connections and characteristic subscriptions via Coroutines.
* **Dependency Injection**: Using Hilt.

---

## 2. System Module Breakdown (14 Modules)

The project adopts a Multi-module Architecture to strictly separate concerns:

| Module Path | Type | Responsibility Description |
| :--- | :--- | :--- |
| `:app` | Android App | Application / MainActivity / NavHost / Hilt setup |
| `:feature:dashboard` | Android Lib (UI) | Dashboard Screen, MVI (Intent/State/Reducer) |
| `:feature:trips` | Android Lib (UI) | Trip list and details Screen |
| `:feature:settings` | Android Lib (UI) | Settings Screen, brand-specific options |
| `:feature:device-scan` | Android Lib (UI) | BLE device scanning and pairing Screen |
| `:feature:hud-gateway` | Android Lib (UI+Svc) | HUD gateway configuration Screen, BLE GATT Server background service |
| `:domain` | Kotlin Lib | UseCases, interfaces, `TelemetryState` Domain Model |
| `:data:ble` | Android Lib | BLE Central scanning and connection implementation based on **Kable** |
| `:data:protocol` | Kotlin Lib | Byte Array parsing logic for various brands (KS, Begode, Inmotion, etc.) |
| `:data:database` | Android Lib | Room DB, DAO (Trip persistence) |
| `:data:preferences` | Android Lib | DataStore (User settings) |
| `:core:ui` | Android Lib | Shared Compose components (Theme, Dashboard Arc, etc.) |
| `:core:common` | Kotlin Lib | Extension functions, unit conversions, MVI base classes (BaseViewModel) |
| `:core:testing` | Kotlin Lib | BLE Mock data, Hex dump test suites, Coroutine Rules |

*(Note: There is an independent `:hud-app` serving as the dedicated compilation target APK for Rokid RV101)*

---

## 3. Core Data Models and Communication Protocol

* **TelemetryState**: Includes speed (km/h), battery level (%), voltage (V), temperature (°C), mileage (km), and alarm flags.
* **HUD Payload Format (Phone to Rokid)**: Uses a custom Service UUID for high-frequency transmission via a streamlined 20-byte payload (or JSON) to ensure low latency.

---

