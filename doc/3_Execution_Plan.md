# Development Execution Plan and MVP Roadmap

## 1. GitHub Epic Breakdown

* **Epic 1: Foundation** - Establish the 14-module skeleton, Version Catalog, MVI base classes, and DI (Dependency Injection) setup.
* **Epic 2: BLE Core (Based on Kable)** - Implement the BLE scanner, connection state machine (Idle/Scanning/Connected/Disconnected), and Foreground Service.
* **Epic 3: Protocol Parsing** - Port the basic parsing logic for Inmotion and Begode, and verify the conversion from Byte Array to `TelemetryState`.
* **Epic 4: Modern Dashboard** - Implement a strict MVI Compose UI to render high-frequency real-time data.
* **Epic 5: HUD Bluetooth Relay and Rokid Display** - Set up a GATT Server on the phone to push streamlined data; develop a dedicated Rokid app to receive and render the HUD with green text on a black background.

## 2. MVP Development Schedule (6-Week Sprint)

* **Week 1-2**: Project initialization, Gradle Version Catalog setup, and establishing Kable BLE scanning/connection.
* **Week 3**: Complete Kotlin protocol decoding and data streaming for 1-2 wheel models (e.g., Inmotion/Begode).
* **Week 4**: Implement the MVI-architecture Compose dashboard on the phone and bind it to the Foreground Service.
* **Week 5**: Set up the phone-side BLE GATT Server, define the HUD Payload, and successfully broadcast it.
* **Week 6**: Develop the Rokid glasses app, establish end-to-end (EUC -> Phone -> Rokid) connection, and conduct outdoor latency testing.

---

