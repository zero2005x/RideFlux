# RideFlux

**Real-time electric-unicycle telemetry for Android, with a heads-up display for Rokid AR glasses.**

*Android 版電動獨輪車即時遙測應用程式，並支援 Rokid AR 眼鏡抬頭顯示。*

[English](#english) · [繁體中文](#繁體中文)

| | |
|---|---|
| Version | `0.1.5` (versionCode 6) |
| License | GPL-3.0-or-later |
| Language | Kotlin 2.0.21 · Jetpack Compose |
| Min / Target / Compile SDK | 28 / 36 / 36 |
| Modules | 9 Gradle modules (2 apps, 1 domain, 5 data, 1 core) |

---

## English

### Overview

RideFlux connects to an electric unicycle (EUC) over Bluetooth Low Energy, decodes its
proprietary mainboard protocol, and presents live telemetry — speed, pack voltage,
current, battery state of charge, PWM load and temperatures — on the phone. It records
rides to a local database with GPS traces, raises threshold alerts while you ride, and
relays a compact telemetry frame to **Rokid AR glasses** so the rider can keep their eyes
up instead of on a handlebar mount.

The project ships **two installable apps** that coexist on separate devices:

- **`:app`** (`com.rideflux.app`) — the phone app. Owns the BLE link to the wheel, the
  Compose dashboard, trip recording and the bridge server.
- **`:hud-app`** (`com.rideflux.hud`) — a minimal-surface app for the glasses. Renders a
  HUD from either a direct wheel link or the phone bridge, and never needs to fight the
  phone for the wheel's single BLE connection.

### Features

**Connect & monitor**

- Unfiltered BLE scan with in-process family classification (advertised name first, GATT
  service UUIDs as fallback), so wheels that advertise no service UUID are still found.
- Unified, family-agnostic `WheelTelemetry` snapshot in canonical SI-style units — codecs
  apply all family-specific scaling before the domain layer ever sees a value.
- Reactive dashboard: main gauge, BMS detail, live graph, map, parameters and events pages.
- Typed, family-agnostic commands (headlight, LED strip, beep/horn, volume, max speed,
  tilt-back, pedal sensitivity/tilt, ride mode, calibrate, power off, PIN unlock), each
  returning an explicit `CommandOutcome` — including `Unsupported` for families that lack
  the feature.

**Ride recording**

- Foreground `RecordingService` samples telemetry alongside fused-location fixes.
- Trips and per-sample series persist to Room; trip history and detail screens are built in.
- Export a trip as **CSV** or **GPX** (with RideFlux telemetry extensions embedded in each
  track point).

**Safety alerts**

- `ThresholdMonitor` evaluates overspeed, over-temperature, low battery and PWM load with
  debounce, cooldown and hysteresis, so a value hovering on a limit does not machine-gun
  alerts.
- Defaults: 45 km/h · 80 °C · 25 % battery · 90 % PWM — all adjustable in Settings.

**AR glasses HUD**

- Two selectable transports: a custom **Android BLE GATT bridge** (phone advertises as
  peripheral, glasses subscribe as central) or the official **Rokid CXR** message channel.
- The glasses stay fully passive: the frame carries phone battery, a coarse signal bucket
  and a staleness flag so the HUD never has to talk to the wheel itself.
- Optional autostart on boot via `BridgeBootReceiver`.

### Supported wheel families

`WheelFamily` is the single routing key used across the domain layer. Enum names are a
**stability contract** — they are persisted and used in nav deep links, so renaming one is
a breaking change.

| Family | Vendors / models | Wire format |
|---|---|---|
| `G` | Begode / Gotway / ExtremeBull | Serial byte stream |
| `GX` | Begode Extended (dual-BMS) | Serial stream + smart-BMS pages |
| `K` | KingSong | Fixed 20-byte frames |
| `V` | Veteran — Sherman, Abrams, Patton, Lynx, Oryx, Nosfet | Veteran framing |
| `N1` | Ninebot One / E+ / S2 / Mini | Short CAN-like, zero keystream |
| `N2` | Ninebot Z / ZT / KickScooter Z | Long CAN-like, session key |
| `I1` | Inmotion legacy — V5 / V8 / V10 | Escape-byte framing |
| `I2` | Inmotion current — V9 / V11 / V12 / V13 / V14 | XOR-check framing |

`G` and `GX` share `BegodeWheelCodec`; the remaining families each have their own codec
under `:data:protocol`.

### GATT topologies

| Topology | Service / characteristics | Families |
|---|---|---|
| `SINGLE_CHAR` | `FFE0` service, `FFE1` notify + write | `G`, `GX`, `K`, `N1` |
| `SPLIT_CHAR` | notify `FFE0`/`FFE4`, write `FFE5`/`FFE9` | `I1` |
| `NORDIC_UART` | `6E400001…`, RX `…0002`, TX `…0003` | `N2`, `I2` |

A UUID-only guess is never authoritative — the true family is confirmed by the family's
bootstrap handshake after connect. Callers that already know the family should pass
`expectedFamily` to `WheelRepository.connect()`.

> **Note on `§` references.** KDoc throughout the codebase cites section numbers
> (`§1.1`, `§2.6`, `§9.*`) from a clean-room protocol specification
> (`clean-room/spec/PROTOCOL_SPEC.md`). That document is **not** part of this source tree;
> the citations are kept so the two can be cross-read when the spec is available.

### Architecture

Clean-architecture layering with a strict dependency direction — nothing below points
upward, and `:domain` never sees an `android.*` type.

```
┌──────────────────────────┐   ┌──────────────────────────┐
│          :app            │   │        :hud-app          │
│    phone application     │   │  AR-glasses application  │
└────────────┬─────────────┘   └────────────┬─────────────┘
             │      shared by both apps     │
             ├───────── :data:ble ──────────┤
             ├───────── :data:bridge ───────┤
             ├───────── :data:preferences ──┘
             │
             ├───────── :data:database      (:app only)
             └───────── :core:location      (:app only)

  :data:ble ──▶ :data:protocol ──▶ :domain    per-family byte codecs (pure Kotlin)
  :data:bridge · :data:database · :data:preferences ──▶ :domain
  :core:location ──▶ Play Services location only (no project dependencies)

  :domain  ── models, interfaces, alert logic (pure Kotlin, no android.*)
```

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android app | Launcher, NavHost, Compose screens, `BridgeService`, `RecordingService`, Hilt graph |
| `:hud-app` | Android app | Standalone glasses app — own Hilt graph, HUD-only Compose tree |
| `:domain` | Pure Kotlin | `WheelTelemetry`, `WheelCommand`, `WheelCodec`, `WheelConnection`, `WheelRepository`, `Trip`, `AppSettings`, `ThresholdMonitor` |
| `:data:protocol` | Pure Kotlin | `familyg` · `familyk` · `familyv` · `familyn` · `familyi1` · `familyi2` decoders and command builders |
| `:data:ble` | Android lib | `AndroidBleTransport` (platform `android.bluetooth.*`), scanning, codec factory, connection impl |
| `:data:bridge` | Android lib | Phone↔glasses GATT service: protocol constants, binary frame, server + client |
| `:data:database` | Android lib | Room database, trip DAO / entities, exported schema |
| `:data:preferences` | Android lib | DataStore-backed `SettingsRepository` |
| `:core:location` | Android lib | Fused-location trip source |

**Design rules worth knowing before you contribute**

- Codecs are **stateless objects**. Per-connection state (reassembly buffers, XOR
  keystreams, last snapshot) lives in an opaque `WheelCodec.State` allocated by
  `newState()` and threaded through every call — so one codec instance serves many
  connections. That state is *not* thread-safe; serialize calls per connection.
- `decode` must never throw. Callers use `decodeSafely`, which converts any escaping
  exception into a `DecodeEvent.Malformed` so one bad frame cannot tear down a GATT session.
- Domain invariants are enforced in `init` blocks (e.g. `batteryPercent` must be finite and
  within `0..100`), which means `copy()` is checked too.
- `null` in telemetry means **unknown**, never "zero" or "no fault" — safety logic depends
  on that distinction.
- Versions live **only** in `gradle/libs.versions.toml`. Submodule build files must not
  hardcode a version.
- All repositories are declared centrally in `settings.gradle.kts` under
  `FAIL_ON_PROJECT_REPOS`.

### Phone ↔ glasses bridge

The phone owns the wheel's single BLE link and re-broadcasts a compact frame, so two
centrals never fight over the wheel.

| | |
|---|---|
| Service UUID | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` |
| Telemetry characteristic | `e7810a72-73ae-499d-8c15-faa9aef0c3f2` (notify-only) |
| Magic byte | `0x52` (`'R'`) |
| Protocol version | `2` (20-byte frame) |
| Frame size (v2) | 20 bytes, little-endian — fits the default 23-byte ATT MTU |
| Preferred ATT MTU | 64 (client-initiated; v2 never depends on it) |
| Pairing token | 8 bytes of service data under the service UUID, in the scan response |
| Rokid CXR channel | `rideflux.telemetry.v1` |

**Pairing identity.** The glasses must recognise *their* phone and reject everyone
else, because a BLE service UUID is public and anyone can advertise fabricated
telemetry under it. That identity is a pairing token, not a MAC address: Android
advertises from a resolvable private address that the controller rotates roughly
every 15 minutes and regenerates on every Bluetooth restart, so a MAC captured
during pairing silently stops matching minutes later. The phone mints an 8-byte
token on first run, persists it and publishes it as service data; the glasses
store it at pairing time and match on it, so address rotation is irrelevant. The
token is displayed as a grouped code (`A1B2-C3D4-E5F6-0718`) in the phone's
settings and as its first four characters in the glasses' pairing picker, so the
rider can confirm they paired with their own phone. A MAC stored by a build from
before tokens existed is still honoured as a fallback until the rider re-pairs.

The token rides in the scan response rather than the advertisement: flags (3 B)
plus the 128-bit service UUID (18 B) already use 21 of the advertisement's 31
bytes, and 128-bit service data costs another 26. Android merges both PDUs into a
single `ScanRecord` for legacy advertising, so the receiver reads it through
`ScanRecord.getServiceData()` without caring which PDU carried it. Because the
token is public and replayable it is a stable *name*, not a secret; defending
against a deliberately spoofed peer needs LE bonding
(`BridgePeerFilter.Bonded`).

**Startup ordering.** `BluetoothGattServer.addService()` completes asynchronously.
Advertising before `onServiceAdded` confirms registration lets a fast central
discover an empty GATT database, which Android then caches by address — and the
usual escape hatch, `BluetoothGatt.refresh()`, is a hidden API blocked since
Android 9. `BridgeServer.open()` therefore waits for the confirmation before it
starts advertising.

**Scanning budget.** Android silently stops delivering scan results once an app
exceeds five `startScan` calls in 30 seconds; there is no callback for it. Both
the reconnect loop and the pairing scanner book a slot with a shared
`BleScanThrottle` (four per 30 s, leaving one spare) and each connection attempt
issues exactly one unfiltered scan, matching the service UUID in code.

Frame payload: timestamp (seconds, decoded unsigned), speed, wheel battery %,
phone battery %, pack voltage, trip distance, trip duration, coarse signal level,
stale flag, ready flag. Sentinels encode "absent" for each numeric slot. The v2
layout packs signal into the flags byte and narrows the timestamp to one word so
one notification always carries a complete frame; the previous 32-byte v1 layout
is retained decode-only so a glasses APK updated ahead of the phone still reads
frames during a mixed-install window. Any breaking change must bump
`PROTOCOL_VERSION`.

### Getting started

**Requirements**

- **JDK 17 or 21.** Kotlin 2.0 cannot parse version strings from JDK 25+. If your system
  JDK is newer, set `org.gradle.java.home` in your *user-level*
  `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`) — for
  example to the JBR bundled with Android Studio. It is deliberately **not** hardcoded in
  the project so CI and other contributors are unaffected.
- Android SDK with API 36 installed; `sdk.dir` in `local.properties`.
- Gradle wrapper 8.13 (checked in — do not run a system Gradle).

**Build**

```bash
./gradlew :app:assembleDebug        # phone APK
./gradlew :hud-app:assembleDebug    # glasses APK
./gradlew assembleDebug             # both
```

On Windows use `gradlew.bat`. The two APKs have different `applicationId`s and can be
installed side by side.

**Install**

```bash
adb -s <phone-serial>   install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <glasses-serial> install -r hud-app/build/outputs/apk/debug/hud-app-debug.apk
```

**Runtime permissions.** The phone app requests `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` /
`BLUETOOTH_ADVERTISE`, `ACCESS_FINE_LOCATION` (needed for trip GPS, and as the scan gate on
API ≤ 30), `POST_NOTIFICATIONS`, and foreground-service types `connectedDevice` + `location`.
`BLUETOOTH_SCAN` is flagged `neverForLocation`.

### Signing & secrets

Nothing sensitive is committed. Both apps resolve release credentials in this order:
**environment variable (CI) → gitignored `local.properties` (local dev)**.

| Key | Purpose |
|---|---|
| `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Release signing |
| `ROKID_CLIENT_SECRET` | Optional Rokid CXR authentication |
| `ROKID_SN_AUTH_BASE64` *or* `ROKID_SN_AUTH_FILE` | CXR SN auth blob — the file path is resolved against the repo root, so `secrets/<id>.lc` works |

Credentials are optional at configuration time. A task-graph guard fails the build only
when a release artifact is actually about to be signed without them, so debug builds and
`lintRelease` are unaffected. Consumer RV101 builds can connect without the CXR values;
provisioned devices may require both.

The entire `secrets/` tree is gitignored, along with `*.lc`, `*_key.txt` and every common
keystore extension — the `.lc` filename is itself the Client ID, so even the name is
sensitive.

### Testing & code quality

```bash
./gradlew test                 # all JVM unit tests (24 test classes)
./gradlew :data:protocol:test  # codec round-trip tests only
./gradlew jacocoTestReport     # aggregate coverage XML + HTML across every module
./gradlew sonar                # SonarCloud analysis (project zero2005x_RideFlux)
```

`jacocoTestReport` is a root-level aggregate: it runs every module's unit tests and merges
the `.exec` files into a single XML at
`build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`, which is what SonarCloud's
coverage gate consumes. Generated code (Hilt, Room `_Impl`, `R`, `BuildConfig`, KSP output)
is excluded from both coverage and analysis.

The GitHub Actions workflow runs `lintDebug`, builds both debug apps, executes the JVM unit
tests, generates the aggregate coverage report, and then runs SonarCloud on trusted events.
Import and bind the repository as SonarCloud project `zero2005x_RideFlux`, then create a
repository Actions secret named `SONAR_TOKEN` with **Execute Analysis** permission before the
first `main` push. Fork and Dependabot pull requests still run lint/build/tests but skip
Sonar, because GitHub does not expose repository secrets to those workflows. In SonarCloud,
use CI-based analysis and disable Automatic Analysis so the JaCoCo report is accepted without
duplicate analysis.

Instrumented tests live in `data/database/src/androidTest` (`TripDaoTest`) and run with
`./gradlew :data:database:connectedAndroidTest` against a connected device or emulator.

**Dependency verification** is enabled via `gradle/verification-metadata.xml`
(`verify-metadata=true`, `verify-signatures=false`). Adding or bumping a dependency
requires regenerating it:

```bash
./gradlew --write-verification-metadata sha256 help
```

The **configuration cache** is on by default (`org.gradle.configuration-cache=true`).

### Project layout

```
RideFlux/
├── app/                     # :app — phone application
│   └── src/main/kotlin/com/rideflux/app/
│       ├── bridge/          # BridgeService, publishers, boot receiver, link mode
│       ├── recording/       # RecordingService, TripStatistics
│       ├── navigation/      # RideFluxNavHost + Routes
│       └── ui/              # dashboard · scanner · settings · trips · hud · theme
├── hud-app/                 # :hud-app — Rokid AR glasses application
│   └── src/main/kotlin/com/rideflux/hud/
│       └── source/          # bridge / direct / CXR telemetry sources
├── domain/                  # :domain — pure Kotlin core
├── data/
│   ├── protocol/            # per-family codecs (familyg/k/v/n/i1/i2)
│   ├── ble/                 # BLE transport, scanning, codec factory
│   ├── bridge/              # phone↔glasses GATT protocol
│   ├── database/            # Room + exported schemas/
│   └── preferences/         # DataStore settings
├── core/location/           # fused-location trip source
├── gradle/
│   ├── libs.versions.toml   # single source of truth for all versions
│   └── verification-metadata.xml
├── tools/                   # verify-v5f.ps1 and captured scan artifacts
├── secrets/                 # gitignored — never committed
└── build.gradle.kts         # root: JaCoCo aggregate, Sonar, BouncyCastle pin
```

### Tooling

`tools/verify-v5f.ps1` is an end-to-end Windows verification script: it runs the scan-path
unit tests, builds and installs the debug APK, launches the app, drives the Scan button via
`uiautomator`, records logcat, and dumps a screenshot plus the view hierarchy into `tools/`.
Target the **phone** — the glasses run `:hud-app` and have no wheel to find.

### Known build-level workarounds

Two deliberate constraints are worth knowing before you touch the build files:

1. **BouncyCastle pin.** The Sonar plugin transitively pulls an older `bcprov-jdk15on` that
   shadows the modern BouncyCastle AGP's `validateSigningDebug` needs, producing
   `NoClassDefFoundError: …EdECObjectIdentifiers`. The root build rewrites every
   `org.bouncycastle:*-jdk15on` coordinate on the buildscript classpath to `-jdk18on:1.78.1`.
   `-jdk15to18` artifacts are *not* rewritten.
2. **Plugin repository content filter.** `pluginManagement` admits only `com.android.*`,
   `com.google.*` and `androidx.*` from Google's Maven. A future plugin hosted only there
   under a different group will fail to resolve — widen the regex rather than debugging a
   repository error.

The Rokid Maven repository (`https://maven.rokid.com/repository/maven-public/`) is declared
**before** `google()` / `mavenCentral()` so CXR artifacts resolve from their publisher.

### License

Distributed under the **GNU General Public License v3.0 or later**. Source files carry
`SPDX-License-Identifier: GPL-3.0-or-later`. See [`LICENSE`](LICENSE) for the full text.

---

## 繁體中文

### 專案簡介

RideFlux 透過藍牙低功耗（BLE）連線至電動獨輪車（EUC），解碼各廠牌主機板的專有通訊協定，
並在手機上即時呈現遙測資料——時速、電池組電壓、電流、電量、PWM 負載與各處溫度。它會將行程
連同 GPS 軌跡記錄到本機資料庫、在騎乘過程中發出門檻警示，並將精簡的遙測封包轉送到
**Rokid AR 眼鏡**，讓騎士的視線可以保持在前方，而不必盯著車把上的手機。

專案包含**兩個可安裝的應用程式**，分別安裝在不同裝置上：

- **`:app`**（`com.rideflux.app`）——手機端。負責與車輛的 BLE 連線、Compose 儀表板、
  行程記錄，以及橋接伺服器。
- **`:hud-app`**（`com.rideflux.hud`）——眼鏡端的精簡應用程式。可從「直連車輛」或
  「手機橋接」兩種來源繪製 HUD，因此不必與手機爭搶車輛唯一的 BLE 連線。

### 功能特色

**連線與監控**

- 不使用控制器端過濾的 BLE 掃描，改在程式內分類（先比對廣播名稱，再退回 GATT 服務 UUID），
  因此連完全不廣播服務 UUID 的車款也找得到。
- 統一且與廠牌無關的 `WheelTelemetry` 快照，採用標準 SI 風格單位——所有廠牌專屬的換算
  都由 codec 在進入 domain 層之前完成。
- 反應式儀表板：主儀表、BMS 詳情、即時圖表、地圖、參數與事件頁面。
- 型別化、與廠牌無關的指令（大燈、燈條、嗶聲／喇叭、音量、最高速、回正角度、踏板靈敏度／
  水平、騎乘模式、校正、關機、PIN 解鎖），每一項都回傳明確的 `CommandOutcome`——包含在該
  廠牌不支援時回傳 `Unsupported`。

**行程記錄**

- 前景服務 `RecordingService` 同時取樣遙測資料與融合定位座標。
- 行程與逐筆取樣資料儲存於 Room；內建行程歷史與詳情畫面。
- 可將行程匯出為 **CSV** 或 **GPX**（在每個軌跡點中嵌入 RideFlux 遙測擴充欄位）。

**安全警示**

- `ThresholdMonitor` 針對超速、過熱、低電量與 PWM 負載進行判斷，並套用去彈跳（debounce）、
  冷卻時間（cooldown）與遲滯（hysteresis），避免數值在門檻附近徘徊時警示連發。
- 預設值：45 km/h、80 °C、電量 25 %、PWM 90 %——皆可於設定中調整。

**AR 眼鏡 HUD**

- 兩種可切換的傳輸方式：自訂的 **Android BLE GATT 橋接**（手機作為周邊端廣播，眼鏡作為
  中央端訂閱），或官方的 **Rokid CXR** 訊息通道。
- 眼鏡端完全被動：封包內已帶有手機電量、粗略訊號等級與資料過期旗標，HUD 完全不需要直接
  與車輛通訊。
- 可透過 `BridgeBootReceiver` 選擇開機自動啟動。

### 支援的車輛協定家族

`WheelFamily` 是 domain 層唯一的路由鍵。列舉名稱屬於**穩定性契約**——它會被持久化並用於
導覽深層連結，因此更名即為破壞性變更。

| 家族 | 廠牌／型號 | 傳輸格式 |
|---|---|---|
| `G` | Begode / Gotway / ExtremeBull | 序列位元組串流 |
| `GX` | Begode Extended（雙 BMS） | 序列串流 + 智慧 BMS 分頁 |
| `K` | KingSong | 固定 20 位元組封包 |
| `V` | Veteran——Sherman、Abrams、Patton、Lynx、Oryx、Nosfet | Veteran 封包格式 |
| `N1` | Ninebot One / E+ / S2 / Mini | 短 CAN-like，無金鑰串流 |
| `N2` | Ninebot Z / ZT / KickScooter Z | 長 CAN-like，含工作階段金鑰 |
| `I1` | Inmotion 舊款——V5 / V8 / V10 | 跳脫位元組封包 |
| `I2` | Inmotion 新款——V9 / V11 / V12 / V13 / V14 | XOR 校驗封包 |

`G` 與 `GX` 共用 `BegodeWheelCodec`；其餘家族在 `:data:protocol` 中各自擁有獨立 codec。

### GATT 拓撲

| 拓撲 | 服務／特徵值 | 適用家族 |
|---|---|---|
| `SINGLE_CHAR` | `FFE0` 服務，`FFE1` 通知 + 寫入 | `G`、`GX`、`K`、`N1` |
| `SPLIT_CHAR` | 通知 `FFE0`/`FFE4`，寫入 `FFE5`/`FFE9` | `I1` |
| `NORDIC_UART` | `6E400001…`，RX `…0002`，TX `…0003` | `N2`、`I2` |

僅憑 UUID 的推測永遠不是定論——真正的家族要等連線後的啟動握手才會確認。若呼叫端已經知道
家族，應將 `expectedFamily` 傳入 `WheelRepository.connect()`。

> **關於 `§` 章節編號。** 程式碼中的 KDoc 大量引用某份淨室（clean-room）協定規格
> （`clean-room/spec/PROTOCOL_SPEC.md`）的章節編號（`§1.1`、`§2.6`、`§9.*`）。該文件
> **並不在**本原始碼樹中；保留這些引用是為了在取得規格時能相互對照閱讀。

### 架構

採用 Clean Architecture 分層，相依方向嚴格單向——下層絕不反向指向上層，且 `:domain`
永遠看不到任何 `android.*` 型別。

```
┌──────────────────────────┐   ┌──────────────────────────┐
│          :app            │   │        :hud-app          │
│       手機應用程式        │   │     AR 眼鏡應用程式       │
└────────────┬─────────────┘   └────────────┬─────────────┘
             │      兩個 app 共用           │
             ├───────── :data:ble ──────────┤
             ├───────── :data:bridge ───────┤
             ├───────── :data:preferences ──┘
             │
             ├───────── :data:database      （僅 :app）
             └───────── :core:location      （僅 :app）

  :data:ble ──▶ :data:protocol ──▶ :domain    各家族位元組 codec（純 Kotlin）
  :data:bridge · :data:database · :data:preferences ──▶ :domain
  :core:location ──▶ 僅相依 Play Services location（無專案內相依）

  :domain  ── 模型、介面、警示邏輯（純 Kotlin，無 android.*）
```

| 模組 | 類型 | 職責 |
|---|---|---|
| `:app` | Android app | 啟動器、NavHost、Compose 畫面、`BridgeService`、`RecordingService`、Hilt 圖 |
| `:hud-app` | Android app | 獨立的眼鏡應用程式——自有 Hilt 圖與 HUD 專用 Compose 樹 |
| `:domain` | 純 Kotlin | `WheelTelemetry`、`WheelCommand`、`WheelCodec`、`WheelConnection`、`WheelRepository`、`Trip`、`AppSettings`、`ThresholdMonitor` |
| `:data:protocol` | 純 Kotlin | `familyg`、`familyk`、`familyv`、`familyn`、`familyi1`、`familyi2` 的解碼器與指令建構器 |
| `:data:ble` | Android lib | `AndroidBleTransport`（直接使用平台 `android.bluetooth.*`）、掃描、codec 工廠、連線實作 |
| `:data:bridge` | Android lib | 手機↔眼鏡 GATT 服務：協定常數、二進位封包、伺服器與用戶端 |
| `:data:database` | Android lib | Room 資料庫、行程 DAO／實體、匯出的 schema |
| `:data:preferences` | Android lib | 以 DataStore 實作的 `SettingsRepository` |
| `:core:location` | Android lib | 融合定位的行程座標來源 |

**貢獻前值得先了解的設計規則**

- Codec 是**無狀態物件**。每條連線的狀態（重組緩衝區、XOR 金鑰串流、上一份快照）存放在由
  `newState()` 配置、並在每次呼叫中傳遞的不透明 `WheelCodec.State` 中——因此單一 codec
  實例可服務多條連線。該狀態**非執行緒安全**，同一條連線的呼叫必須序列化。
- `decode` 絕不可拋出例外。呼叫端應使用 `decodeSafely`，它會把任何逸出的例外轉換成
  `DecodeEvent.Malformed`，使單一壞封包不至於拆掉整個 GATT 連線。
- Domain 不變式在 `init` 區塊中強制檢查（例如 `batteryPercent` 必須為有限值且落在
  `0..100`），這代表連 `copy()` 也會被檢查。
- 遙測中的 `null` 代表**未知**，絕非「零」或「無故障」——安全邏輯仰賴這個區別。
- 版本號**只能**寫在 `gradle/libs.versions.toml`。子模組的 build 檔不得寫死版本。
- 所有 repository 皆集中宣告於 `settings.gradle.kts`，並套用 `FAIL_ON_PROJECT_REPOS`。

### 手機 ↔ 眼鏡橋接

手機獨佔車輛唯一的 BLE 連線，再以精簡封包轉播出去，避免兩個中央端搶奪同一台車。

| | |
|---|---|
| 服務 UUID | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` |
| 遙測特徵值 | `e7810a72-73ae-499d-8c15-faa9aef0c3f2`（僅通知） |
| 魔術位元組 | `0x52`（`'R'`） |
| 協定版本 | `2`（20 位元組封包） |
| 封包大小（v2） | 20 位元組，小端序 — 可容於預設的 23 位元組 ATT MTU |
| 偏好 ATT MTU | 64（由用戶端發起協商；v2 不依賴協商結果） |
| 配對權杖 | 8 位元組，以服務 UUID 的 service data 放在掃描回應中 |
| Rokid CXR 通道 | `rideflux.telemetry.v1` |

**配對身分。** 眼鏡必須認得「自己的」手機並拒絕其他裝置 —— BLE 服務 UUID 是公開的，
任何人都能用它廣播偽造的遙測資料。這個身分是配對權杖，而不是 MAC 位址：Android 廣播時
使用可解析的隨機私有位址，控制器大約每 15 分鐘輪替一次，藍牙重啟時也會重新產生，因此配對
當下記下的 MAC 幾分鐘後就會安靜地失效。手機在首次啟動時產生 8 位元組的權杖並永久保存，
再以 service data 廣播出去；眼鏡在配對時存下它並據此比對，位址怎麼輪替都不影響。權杖會在
手機設定頁以分組形式顯示（`A1B2-C3D4-E5F6-0718`），在眼鏡的配對清單中則顯示前四碼，
讓騎士能確認配對到的是自己的手機。若是在權杖機制之前配對的舊版本，仍會沿用已儲存的 MAC
作為後備，直到重新配對為止。

權杖放在掃描回應而非主廣播中：flags（3 B）加上 128 位元服務 UUID（18 B）已經用掉主廣播
31 位元組中的 21 個，而 128 位元的 service data 還要再花 26 個位元組。Android 會把兩個
PDU 合併成單一 `ScanRecord`，因此接收端透過 `ScanRecord.getServiceData()` 讀取即可，
不必在意是哪個 PDU 帶來的。由於權杖是公開且可被重放的，它是穩定的「名字」而非機密；要防範
刻意偽造的對端，需要 LE 綁定（`BridgePeerFilter.Bonded`）。

**啟動順序。** `BluetoothGattServer.addService()` 是非同步完成的。在 `onServiceAdded`
確認註冊之前就開始廣播，會讓搶先連上的中央端讀到空的 GATT 資料庫，而 Android 會依位址把它
快取起來 —— 慣用的補救手段 `BluetoothGatt.refresh()` 自 Android 9 起已被隱藏 API 限制
擋掉。因此 `BridgeServer.open()` 會先等待註冊確認，之後才開始廣播。

**掃描預算。** 一旦應用程式在 30 秒內呼叫 `startScan` 超過五次，Android 就會靜默停止回報
掃描結果，而且沒有任何回呼通知。重連迴圈與配對掃描都會先向共用的 `BleScanThrottle` 取得
名額（每 30 秒四次，保留一個名額），且每次連線嘗試只發出一次無過濾掃描，服務 UUID 改在
程式內比對。

封包內容：時間戳（秒，以無號解碼）、時速、車輛電量 %、手機電量 %、電池組電壓、行程距離、
行程時間、粗略訊號等級、資料過期旗標、就緒旗標。每個數值欄位皆以哨兵值表示「無資料」。v2
將訊號等級併入 flags 位元組並把時間戳縮為一個字，確保單一通知一定能承載完整封包；先前的
32 位元組 v1 版面保留為「僅解碼」，讓比手機先更新的眼鏡 APK 在混合安裝期間仍讀得懂。任何
破壞性變更都必須遞增 `PROTOCOL_VERSION`。

### 開始使用

**環境需求**

- **JDK 17 或 21。** Kotlin 2.0 無法解析 JDK 25 以上的版本字串。若系統 JDK 較新，請在
  **使用者層級**的 `~/.gradle/gradle.properties`（Windows：
  `%USERPROFILE%\.gradle\gradle.properties`）設定 `org.gradle.java.home`——例如指向
  Android Studio 內建的 JBR。專案刻意**不**寫死此路徑，以免影響 CI 與其他貢獻者。
- 已安裝 API 36 的 Android SDK；並在 `local.properties` 中設定 `sdk.dir`。
- Gradle wrapper 8.13（已納入版控——請勿使用系統安裝的 Gradle）。

**建置**

```bash
./gradlew :app:assembleDebug        # 手機 APK
./gradlew :hud-app:assembleDebug    # 眼鏡 APK
./gradlew assembleDebug             # 兩者
```

Windows 請使用 `gradlew.bat`。兩個 APK 的 `applicationId` 不同，可並存安裝。

**安裝**

```bash
adb -s <手機序號>   install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <眼鏡序號>   install -r hud-app/build/outputs/apk/debug/hud-app-debug.apk
```

**執行期權限。** 手機端會請求 `BLUETOOTH_SCAN`／`BLUETOOTH_CONNECT`／`BLUETOOTH_ADVERTISE`、
`ACCESS_FINE_LOCATION`（行程 GPS 需要，且在 API ≤ 30 上是掃描的前置條件）、
`POST_NOTIFICATIONS`，以及 `connectedDevice` + `location` 兩種前景服務型別。
`BLUETOOTH_SCAN` 已標記 `neverForLocation`。

### 簽章與機密資訊

專案不會提交任何機密資料。兩個應用程式依下列順序解析發行版憑證：
**環境變數（CI）→ 已被 gitignore 的 `local.properties`（本機開發）**。

| 鍵值 | 用途 |
|---|---|
| `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` | 發行版簽章 |
| `ROKID_CLIENT_SECRET` | 選用的 Rokid CXR 驗證 |
| `ROKID_SN_AUTH_BASE64` **或** `ROKID_SN_AUTH_FILE` | CXR SN 驗證資料——檔案路徑以專案根目錄為基準，因此可直接寫 `secrets/<id>.lc` |

這些憑證在設定期（configuration time）皆為選用。建置流程會檢查任務圖，只有在**真的**要簽署
發行版產物卻缺少憑證時才失敗，因此 debug 建置與 `lintRelease` 不受影響。消費版 RV101 不填
CXR 憑證也能連線；已佈建（provisioned）的裝置則可能兩項都需要。

整個 `secrets/` 目錄都已被 gitignore，另外還包含 `*.lc`、`*_key.txt` 與各種常見的
keystore 副檔名——因為 `.lc` 的檔名本身就是 Client ID，連檔名都屬敏感資訊。

### 測試與程式碼品質

```bash
./gradlew test                 # 所有 JVM 單元測試（24 個測試類別）
./gradlew :data:protocol:test  # 僅執行 codec 來回編解碼測試
./gradlew jacocoTestReport     # 跨所有模組的彙整覆蓋率 XML + HTML
./gradlew sonar                # SonarCloud 分析（專案 zero2005x_RideFlux）
```

`jacocoTestReport` 是根層級的彙整任務：它會執行每個模組的單元測試，並把產生的 `.exec`
合併成單一 XML，輸出到
`build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`，正是 SonarCloud 覆蓋率
關卡所讀取的檔案。產生式程式碼（Hilt、Room `_Impl`、`R`、`BuildConfig`、KSP 產出）已從
覆蓋率與靜態分析中排除。

GitHub Actions 會執行 `lintDebug`、建置兩個 debug app、跑完 JVM 單元測試、產生彙整
覆蓋率，再於可信任的事件上執行 SonarCloud。請先將 repository 匯入並綁定為 SonarCloud
專案 `zero2005x_RideFlux`；第一次推送 `main` 前，再建立名稱為 `SONAR_TOKEN`、具有
**Execute Analysis** 權限的 repository Actions secret。fork 與 Dependabot PR 仍會跑
lint／build／tests，但 GitHub 不會把 repository secret 暴露給這些 workflow，因此會跳過
Sonar。SonarCloud 端請採 CI-based analysis 並停用 Automatic Analysis，才能接收 JaCoCo
報告且不會重複分析。

儀器化測試位於 `data/database/src/androidTest`（`TripDaoTest`），需連接實機或模擬器後以
`./gradlew :data:database:connectedAndroidTest` 執行。

**相依驗證**已透過 `gradle/verification-metadata.xml` 啟用
（`verify-metadata=true`、`verify-signatures=false`）。新增或升級相依套件後必須重新產生：

```bash
./gradlew --write-verification-metadata sha256 help
```

**設定快取（configuration cache）** 預設為開啟（`org.gradle.configuration-cache=true`）。

### 專案結構

```
RideFlux/
├── app/                     # :app — 手機應用程式
│   └── src/main/kotlin/com/rideflux/app/
│       ├── bridge/          # BridgeService、發佈器、開機接收器、連線模式
│       ├── recording/       # RecordingService、TripStatistics
│       ├── navigation/      # RideFluxNavHost 與 Routes
│       └── ui/              # dashboard · scanner · settings · trips · hud · theme
├── hud-app/                 # :hud-app — Rokid AR 眼鏡應用程式
│   └── src/main/kotlin/com/rideflux/hud/
│       └── source/          # 橋接／直連／CXR 三種遙測來源
├── domain/                  # :domain — 純 Kotlin 核心
├── data/
│   ├── protocol/            # 各家族 codec（familyg/k/v/n/i1/i2）
│   ├── ble/                 # BLE 傳輸、掃描、codec 工廠
│   ├── bridge/              # 手機↔眼鏡 GATT 協定
│   ├── database/            # Room 與匯出的 schemas/
│   └── preferences/         # DataStore 設定
├── core/location/           # 融合定位的行程座標來源
├── gradle/
│   ├── libs.versions.toml   # 所有版本號的唯一真實來源
│   └── verification-metadata.xml
├── tools/                   # verify-v5f.ps1 與擷取到的掃描產物
├── secrets/                 # 已 gitignore——絕不提交
└── build.gradle.kts         # 根建置檔：JaCoCo 彙整、Sonar、BouncyCastle 版本鎖定
```

### 開發工具

`tools/verify-v5f.ps1` 是端對端的 Windows 驗證腳本：它會執行掃描路徑相關的單元測試、
建置並安裝 debug APK、啟動應用程式、透過 `uiautomator` 找到並點擊「掃描」按鈕、錄製
logcat，最後把螢幕截圖與畫面階層匯出到 `tools/`。請以**手機**為目標——眼鏡執行的是
`:hud-app`，上面沒有車可以掃。

### 已知的建置層變通做法

在動到建置檔之前，有兩個刻意保留的限制值得先知道：

1. **BouncyCastle 版本鎖定。** Sonar 外掛會遞移引入較舊的 `bcprov-jdk15on`，遮蔽掉 AGP
   `validateSigningDebug` 所需的新版 BouncyCastle，導致
   `NoClassDefFoundError: …EdECObjectIdentifiers`。根建置檔會把 buildscript classpath 上
   所有 `org.bouncycastle:*-jdk15on` 座標改寫為 `-jdk18on:1.78.1`。`-jdk15to18` 系列
   **不會**被改寫。
2. **外掛 repository 內容過濾。** `pluginManagement` 只允許 Google Maven 上的
   `com.android.*`、`com.google.*` 與 `androidx.*`。未來若有僅託管於該處、但群組不符的
   外掛就會解析失敗——請直接放寬正規表示式，而不是花時間排查 repository 錯誤。

Rokid 的 Maven repository（`https://maven.rokid.com/repository/maven-public/`）刻意宣告在
`google()`／`mavenCentral()` **之前**，以確保 CXR 相關套件從原發佈者解析。

### 授權

本專案依 **GNU General Public License v3.0 或後續版本**散布。原始碼檔案皆標註
`SPDX-License-Identifier: GPL-3.0-or-later`。完整條款請見 [`LICENSE`](LICENSE)。
