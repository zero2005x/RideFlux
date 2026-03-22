# RideFlux & Rokid AR HUD Integration Project PRD

## 1\. Project Overview

  * **Product Positioning**: A next-generation, open-source dashboard tailored for advanced Electric Unicycle (EUC) riders, featuring a modern Android interface and AR glasses HUD expandability for zero-gaze-shift monitoring.
  * **Core Value**: Safe monitoring with zero gaze shifting, a modern and fluid mobile operation experience, and a highly extensible underlying architecture.
  * **Success Metrics**:
      * Brand Compatibility Rate: Retain support for existing brands (KS/GW/IM/NB/Veteran).
      * HUD Data Latency: \< 200 ms (EUC → Phone → Glasses).
      * Cold Start Time: \< 2s (including BLE scanning initialization).

## 2\. System Architecture Design (Topology)

The system is divided into three main nodes, with the mobile phone acting as the edge computing and data distribution hub:

1.  **Data Source (EUC)**: Sends raw vehicle status data via BLE.
2.  **Processing Hub (Android Phone)**: Responsible for maintaining the connection with the EUC, parsing complex byte arrays, rendering the main dashboard, and packaging the filtered critical data.
3.  **Display Terminal (Rokid RV101)**: Acts as a Bluetooth Client to receive the streamlined data from the phone, rendering the AR HUD interface with ultra-low latency.

## 3\. Core User Stories and Acceptance Criteria

| Epic | User Story | Priority | Acceptance Criteria |
| :--- | :--- | :--- | :--- |
| **EUC Connection & Parsing** | As an EUC rider, I want the App to stably connect to and parse BLE data from my EUC (such as the Inmotion V5F or Begode A2). | P0 | 1. Scan and filter the target EUC.<br>2. Successfully establish a connection and receive ByteArrays.<br>3. Correctly parse speed, battery level, temperature, and mileage. |
| **Mobile Dashboard** | I want a high-contrast, highly responsive modern dashboard on my phone. | P1 | 1. Implement dark mode using Jetpack Compose.<br>2. Data refresh rate \> 5Hz without stuttering.<br>3. Support Foreground Service. |
| **HUD Data Relay** | I need the mobile phone to rebroadcast the streamlined data to the Rokid glasses. | P0 | 1. Establish a BLE GATT Server on the phone.<br>2. Push the Payload 5 times per second.<br>3. Support stable Client connections. |
| **AR Glasses Display** | Upon wearing the Rokid RV101, I can instantly see the HUD speed and battery level on a transparent background. | P1 | 1. The glasses App automatically connects to the phone's GATT server.<br>2. Pure black background (optically transparent) with bright green text.<br>3. Data synchronization latency \< 500ms. |
| **Proactive Safety Alerts** | In the event of overspeeding or low voltage, the AR glasses will provide a strong visual flashing warning. | P2 | 1. Set thresholds on the phone.<br>2. Send a Flag when triggered.<br>3. Render red flashing warnings on the edges of the glasses display. |

-----
