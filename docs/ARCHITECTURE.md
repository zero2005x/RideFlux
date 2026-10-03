# Architecture / 架構

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← 說明文件首頁](README.md)

---

## English

Module layering, the rules every module follows, and the repository layout.

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

### Project layout

```
RideFlux/
├── app/                     # :app — phone application
│   ├── src/main/kotlin/com/rideflux/app/
│   │   ├── bridge/          # BridgeService, publishers, boot receiver, link mode
│   │   ├── recording/       # RecordingService, TripStatistics
│   │   ├── navigation/      # RideFluxNavHost + Routes
│   │   └── ui/              # dashboard · scanner · settings · trips · hud · permission · theme
│   └── src/debug/           # StoreScreenshotActivity (debug builds only)
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
├── docs/                    # developer documentation (this folder)
│   └── play-store/          # store listing graphics + tools/ that regenerate them
├── site/                    # GitHub Pages website: sources, i18n, build.py (see site/README.md)
├── gradle/
│   ├── libs.versions.toml   # single source of truth for all versions
│   └── verification-metadata.xml
├── secrets/                 # gitignored — never committed
├── README.md · README.zh-TW.md   # project front page (English / 繁體中文)
├── PRIVACY.md               # privacy policy linked from the Play listing
├── NOTICE                   # third-party credits and notices
└── build.gradle.kts         # root: JaCoCo aggregate, Sonar, BouncyCastle pin
```

Each app module also carries `src/main/res/values/` plus 17 `values-<locale>/` siblings —
see [Localization](LOCALIZATION.md#localization).

---

## 繁體中文

模組分層、各模組遵守的規則，以及儲存庫目錄結構。

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

### 專案結構

```
RideFlux/
├── app/                     # :app — 手機應用程式
│   ├── src/main/kotlin/com/rideflux/app/
│   │   ├── bridge/          # BridgeService、發佈器、開機接收器、連線模式
│   │   ├── recording/       # RecordingService、TripStatistics
│   │   ├── navigation/      # RideFluxNavHost 與 Routes
│   │   └── ui/              # dashboard · scanner · settings · trips · hud · permission · theme
│   └── src/debug/           # StoreScreenshotActivity（僅 debug 建置）
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
├── docs/                    # 開發者文件（本資料夾）
│   └── play-store/          # 商店圖片素材，以及重新產生素材的 tools/
├── site/                    # GitHub Pages 網站：原始檔、多語系文案、build.py（見 site/README.md）
├── gradle/
│   ├── libs.versions.toml   # 所有版本號的唯一真實來源
│   └── verification-metadata.xml
├── secrets/                 # 已 gitignore——絕不提交
├── README.md · README.zh-TW.md   # 專案首頁（English／繁體中文）
├── PRIVACY.md               # Play 商店資訊連結的隱私權政策
├── NOTICE                   # 第三方致謝與聲明
└── build.gradle.kts         # 根建置檔：JaCoCo 彙整、Sonar、BouncyCastle 版本鎖定
```

兩個 app 模組另外各自帶有 `src/main/res/values/` 以及 17 個 `values-<語系>/` 目錄——
詳見[多國語系](LOCALIZATION.md#多國語系)。
