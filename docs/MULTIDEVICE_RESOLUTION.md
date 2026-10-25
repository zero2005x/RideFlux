# Multidevice family resolution / 多家族解析

[English](#english) · [繁體中文](#繁體中文) · [← PROTOCOLS](PROTOCOLS.md)

---

## English

How a discovered device becomes one family, one codec and one set of GATT channels — and what is
still not proven.

### 1. Call chain (production)

```text
WheelRepositoryImpl.connect(address, expectedFamily)
  └─ SessionHints(manual = expectedFamily, saved = manualFamilies[address] ?: savedVehicleFamily(address),
                  name = lastSeenNames[address], advertised = lastSeenServiceUuids[address])
  └─ WheelSessionResolver.provisional(hints)            // provisional codec only
  └─ AndroidBleTransport(topology = provisional.topology ?: SINGLE_CHAR,
                         resolveSession = { resolver.resolve(hints, it) })
       └─ connect() → onServicesDiscovered
            ├─ GattSnapshot(device.name, discovered services/chars)
            ├─ resolveSession(snapshot) → WheelResolution
            ├─ !usable  → failConnect(FamilyResolutionException(resolution))
            └─ usable   → resolveExactly(topology)      // no cross-family fallback
  └─ WheelConnectionImpl(transport, codec = provisional codec,
                         codecForResolution = { factory.forFamilyWithAddress(it.family, address) })
       └─ start(): transport.connect() → read transport.resolution
            ├─ unresolved/conflict → Failed(UNKNOWN_FAMILY, reason); no handshake
            ├─ rebuild codec + newState from the resolved family
            └─ handshake → ingest → Ready → telemetry/HUD
```

The key property: **discovery finishes before any bootstrap frame is written**, and the codec,
notify/write characteristics, handshake and capabilities all come from the same `WheelResolution`.
The pre-connect family is only a provisional codec; if discovery cannot confirm it, the connection
fails instead of decoding another family's bytes.

The final codec, its state, the family and the resolution are published together as one immutable
binding inside `WheelConnectionImpl`'s lifecycle lock. Until that binding exists, `dispatch` returns
an unavailable outcome and writes **nothing** — the provisional codec is never a command encoder.
Every physical write re-checks the session epoch under the same lock that `close()` uses, so no frame
is written after close begins retiring the session, and a codec factory that finishes after `close()`
cannot republish the binding.

`WheelConnectionImpl.codec` / `resolvedFamily` expose the bound codec for diagnostics, and are
unavailable/null until the final binding exists. Each connection owns its codec state; closing it
cancels ingest and keep-alive, clears the binding and starts a new connection with a fresh state, so
a late callback from a retired transport cannot splice bytes into a new session.

### Link stages

The connection reports only the stages it can prove; "Ready" is never "the first notification":

| Stage | Evidence |
|---|---|
| GATT connected + services discovered | `transport.connect()` has read the table; `gattSnapshot`/`resolution` are set |
| Notifications enabled | `transport.connect()` returns only after the CCC write succeeds (Android transport contract) |
| Bootstrap sent | `ConnectionState.Handshaking(family)`, set after the resolved codec's handshake frames are written |
| Family authenticated | only the scooter route (M365); wheel families do not use an auth stage |
| Telemetry ready | the **codec's** `Identified` event for that family (K: live page; V: first valid frame; VESC: first valid `0x2F`; G: boot frame; I2: a non-blank CAR_TYPE). A random notification cannot flip `Ready` |

### 1.1 `Ready` is not full support

The wheel-family `Identified` event now carries an optional `WheelSupport`
statement alongside `capabilities`. The connection exposes it as
`WheelConnection.support`. For Inmotion I2 the contract is:

| Identity evidence | Capabilities | Telemetry | UI |
|---|---|---|---|
| non-blank CAR_TYPE, unknown model | all false | core voltage/current | limited-support banner; controls disabled |
| exact model, firmware unknown | all false | core voltage/current | limited-support banner; controls disabled |
| exact model + decoded firmware, Lorin generation and a closed control model | family command set | model layout if implemented, else core | controls enabled; partial-telemetry notice |
| exact EZCAN/I1 catalog name or an unclosed Lorin model | all false | core only | read-only; no I2 write contract from catalog membership alone |

V11Y realtime received before its firmware reply is withheld entirely: its
voltage is /50, so reusing the core /100 scale would publish a known-wrong
value. A malformed firmware reply is rejected as `Malformed`; the last
consistent model+firmware+control identity is retained rather than mixing a
new firmware string with old parsed numbers.

Blank CAR_TYPE is `Malformed`, not an identity. The raw codec stays fail-soft and
`decodeSafely` remains the production entry point. `WheelConnectionImpl.dispatch`
re-checks the published capability matrix before encoding, and the Inmotion I2
encoder repeats the model+firmware gate on its own state, so a caller cannot
bypass the UI. VESC and Veteran publish all-false matrices and their codecs
return no control frame.

### 1.2 Saved vehicles

`VehicleProfileStore` persists one record per BLE address in its own DataStore
(`rideflux_vehicles`): category (wheel/scooter), explicit family, display name,
provenance, per-vehicle alert thresholds and calibration offsets. The global
display settings remain in `AppSettings`. The BLE repository reads a saved
family as a pre-connect hint only; the discovered GATT table still decides.
A fresh process routes a saved scooter through the typed
`PlevRepository.connect(address, category, family, modelHint)` from the persisted
category instead of falling back to `WheelRepository.connect`. Scanner discovery
and the typed router share `PlevDiscoveryRegistry` evidence: the same wheel stream
that fills the UI is what makes a saved SCOOTER fail closed. A recognized scooter
at the same address as a generic fallback wheel is recorded as a scooter, not as
wheel proof. A persisted `transportHint` (classifier name such as ES2) is separate
from verified model identity and only selects the scooter transport profile.

The scanner's Save is one atomic `VehicleProfileStore.update`: name, family,
per-vehicle alerts and display-calibration offsets are one record. The legacy
single-field rename/family methods merge through a pending edit instead of each
re-reading the published snapshot. The editor exposes a follow-global alert
switch and vehicle-scoped alert/calibration fields; reset restores global
alerts and zero display offsets without touching the alias.

A successfully decoded session identity writes model, firmware and (only when
the profile has no explicit family) family back to the same address; the
rider's alias, alerts, calibration and notes are preserved. Advertisement
display names are never stored as verified models.
The older per-address Begode layout and battery-pack stores are kept and remain
the authority for those values.

### 1.3 Telemetry layouts actually implemented

* V11 with verified firmware < 1.4: the established early layout.
* V11 with verified firmware >= 1.4: core fields + fault bitmap at offset 48.
* V11Y: vendor-static 74-byte Lorin layout for voltage (/50), current (/100),
speed (/100) and battery ratio (/10000). Trip and temperatures remain `null`
because their scale is not established.
* Every other recognised Inmotion model: core voltage/current only.

Other families' registry vocabularies (Begode 22, KingSong 21) are not used by
production identity or capabilities; a family-only session keeps a generic
identity and a model-unknown limitation.

### 2. Precedence

| Evidence | Behaviour when compatible | Behaviour when contradictory |
|---|---|---|
| Manual selection (`expectedFamily`) | `EXPLICIT`; used for codec + topology | `CONFLICT`; `Failed(UNKNOWN_FAMILY)`; **zero writes** |
| Saved profile (persisted garage, same MAC) | `SAVED`; used as a hint | table re-resolved; ambiguous stays `AMBIGUOUS` |
| Auto / profile deletion | persisted hint is `null`; any earlier explicit choice is gone | next scan/connect starts from discovery |
| GATT structure | one family → `GATT_EXACT` | several → `AMBIGUOUS` with all candidates |
| Name hint | narrows a tie inside a present structure → `GATT_PROBABLE` | name outside every present structure → `CONFLICT` |

Shared UUID sets are never enough:

* `FFE0` + `FFE1` → candidates `G`, `GX`, `K`, `V`, `N1`;
* Nordic UART (`…0002` + `…0003`) → candidates `I2`, `N2`, `VESC`;
* split `FFE0[FFE4]` + `FFE5[FFE9]` → `I1`.

There is no process-local `manualFamilies` cache. An explicit family is scoped
to the current `connect` call; after it, only the persisted profile (or
discovery) supplies a hint, so switching to Auto or deleting the profile
actually retires the old choice without a restart.

Previously an unnamed single-char device silently became `G` and an unnamed NUS device became `N2`.
Both fallbacks are gone; the scanner still shows such a device (unclassified) and the rider can pick a
brand explicitly, which is a real manual selection.

### 3. VESC (new vehicle family)

Read-only telemetry over Nordic UART.

| Item | Value | Source |
|---|---|---|
| Service / RX (write) / TX (notify) | `6e400001…` / `…0002` / `…0003` | upstream `vesc_tool/bleuart.cpp` (vendor-static), recorded in `euc-programme/findings/EDGE_BRAND_PROTOCOL_RECON.md` §10.7 |
| Framing | `[0x02][len][payload][CRC16 hi][CRC16 lo][0x03]`; `0x03`/`0x04` length widths also parsed | upstream `comm/packet.c` §10.2 |
| Telemetry command | `COMM_GET_VALUES_SETUP` `0x2F`, full-mask 70-byte payload | upstream handler §10.6; independent parsers agree on offsets/divisors |
| Poll frame | `02 01 2F D5 8D 03`, every 250 ms | CRC16/XMODEM over `2F` |
| Fields published | speed, input voltage, input current, battery %, MOSFET temperature, odometer | `VescSetupCodec` + §10.6 |
| Speed unit | wire is **m/s** (`mc_interface_get_speed()` × 1e3); domain km/h via a single ×3.6 at the telemetry boundary | upstream `comm/commands.c`, `motor/mc_interface.c` @ `abc9217` |
| Identification | protocol-level `"VESC"`; `modelName` only | `GetPkgInfo` `0x65` is **not** sent (no verified reply layout) |
| Settings | none; every capability `false`; `encode` returns empty | no readback/apply/persist evidence |

`VescWheelCodec` is wired through `WheelCodecFactoryImpl` (`forFamilyWithAddress`, `topologyFor`),
`WheelFamily.VESC`, the resolver (NUS candidates) and the scanner's manual brand picker. The brand
choice is a distinct [`ScannerSelection.Brand`](../app/src/main/kotlin/com/rideflux/app/ui/scanner/ScannerSelection.kt)
event, carried through the dashboard `family` navigation argument into
`WheelRepositoryImpl.connect(expectedFamily)`, so it reaches `SessionHints.manualFamily` instead of
being flattened to `null` by the route.

### 4. What the tests prove — and what they do not

`data/ble/src/test/kotlin/com/rideflux/data/ble/WheelFamilyEndToEndTest.kt` drives discovery →
resolver → connection → real codec → telemetry with a fake negotiating transport.
`AndroidBleTransportResolutionTest` drives the real `onServicesDiscovered` callback with stand-in
GATT objects. `WheelSessionResolverTest` pins every precedence case.
`SessionBindingLifecycleTest` pins the session-binding contract (dispatch unavailable and zero
writes before the final binding; close during connect/factory/bootstrap leaves nothing behind).
`ScannerBrandDialogComposeTest` clicks the real scanner UI, and
`ScannerRouteFamilySelectionTest` carries the route's navigation arguments through the real resolver.
`VescSpeedUnitsTest` fixes the official 10 m/s frame to 36 km/h with literal expectations.

Covered: KingSong and Veteran selection from the same `FFE0`/`FFE1` table; unnamed single-char and NUS
ambiguity (no writes); manual/GATT conflict (no writes); VESC poll + telemetry + split notifications +
bad CRC + noise/truncated/unknown messages; I1 split; I2/N2 preserved; session-close isolation;
brand choice → navigation argument → VESC codec; provisional-codec suppression and close-during-binding.

**Not covered:** a real GATT stack, a real wheel, rider acceptance, command readback, or any VESC
setting. VESC wheel diameter / gear ratio / pole-pair calibration and any moving-vehicle sample are
still open; the wire speed unit itself is closed by the upstream firmware definition.
These are software tests (`software-tested`), not `vehicle-verified` evidence.

---

## 繁體中文

### 1. 正式呼叫鏈

`WheelRepositoryImpl.connect` 先以手選／已存 profile／名稱建立 `SessionHints` 與暫用 codec，
`AndroidBleTransport` 在 `onServicesDiscovered` 取得完整 GATT 表後呼叫
`WheelSessionResolver.resolve`。解析結果不可用時直接 `failConnect`；可用時只綁定該結果的拓撲
（不再跨家族 fallback）。`WheelConnectionImpl.start()` 在送出任何 handshake 前讀取
`transport.resolution` 重建 codec 與 state，解析失敗則 `Failed(UNKNOWN_FAMILY)` 且不寫任何 byte。
codec、notify/write 特徵值、handshake 與 capabilities 都來自同一個 `WheelResolution`。最終 binding
以單一不可變物件在 `lifecycleMutex` 內發布；binding 完成前 `dispatch` 一律 unavailable 且零寫入，
`close` 之後不得再發布 codec／state／family。品牌手選是獨立的 `ScannerSelection.Brand` 事件，經
dashboard 的 `family` 導航參數傳到 `connect(expectedFamily)`，再成為 `SessionHints.manualFamily`；
普通點選仍是 `family = null`，由 GATT 表決定。

### 2. 優先序

手選（相容 → `EXPLICIT`；不相容 → `CONFLICT`、零寫入）> 已存 profile（持久車庫、同一 MAC；相容 → `SAVED` 提示；衝突則
重新由表解析）> GATT 結構（單一家族 `GATT_EXACT`；多家族 `AMBIGUOUS`）> 名稱（僅在同一結構內
縮小歧義；與表衝突即 `CONFLICT`）。`FFE0/FFE1` 不再靜默變 `G`，NUS 不再靜默變 `N2`。

### 2.1 Ready 不等於完整支援

`Identified` 另帶 optional `WheelSupport`，連線以 `WheelConnection.support` 暴露。I2 的契約：
非空 CAR_TYPE 但型號未知 → 全部 capability false、只有 core 電壓/電流、UI 顯示受限且控制停用；
型號已知但 FW 未知 → 同樣停用；型號+FW 都已解出 → 才發布家族命令集，且只有已實作 layout 的型號
才有 extended telemetry。空白 CAR_TYPE 是 `Malformed`，不是 identity；raw codec 維持 fail-soft。
`WheelConnectionImpl.dispatch` 在 encode 前重驗 capability，I2 encoder 再以自身 state 重驗，
VESC／Veteran 則永遠 all-false 且回空 frame。

### 2.2 已存車輛

`VehicleProfileStore` 以位址為 key，獨立 DataStore（`rideflux_vehicles`）保存 category、明確 family、
名稱、每車告警門檻與校正偏移；全域顯示偏好仍在 `AppSettings`。BLE repository 只把已存 family 當
pre-connect hint，GATT discovery 仍是最終決定。新 process 會用持久 category 將已存 scooter 路由到
`PlevRepository.connect`，不再因尚未掃描而誤走 wheel。既有 Begode layout 與電池串數 store 保留。

### 2.3 已實作的 telemetry layout

V11 且 verified FW < 1.4 → early layout；V11 FW >= 1.4 → core + offset 48 fault bitmap；
V11Y → vendor-static 74-byte Lorin layout（voltage /50、current /100、speed /100、battery /10000），
trip 與溫度因尺度未建立而保持 `null`；其餘已辨識 Inmotion 型號 → 只有 core。Begode 22／KingSong 21
registry 仍未被 production identity/capability 呼叫，只能維持 generic identity 與 model-unknown 限制。

### 3. VESC

Nordic UART 唯讀遙測，`COMM_GET_VALUES_SETUP 0x2F`（70-byte payload、CRC16/XMODEM），250 ms 輪詢；
`GetPkgInfo 0x65` 不送（沒有可驗證回覆格式），因此識別只到 protocol 層（`"VESC"`）。所有 setting
capability 為 false，`encode` 永遠回空。速度欄位在 wire 上是 **m/s**（`mc_interface_get_speed()`
×1e3），只於 telemetry 邊界乘 3.6 一次轉成 domain 的 km/h；輪徑／齒比／極數校準與移動樣本仍需真車
驗證，與已由官方原始碼閉合的單位問題分開。來源與逐欄位對照見英文段與
`euc-programme/findings/EDGE_BRAND_PROTOCOL_RECON.md` §10。

### 4. 驗證邊界

上述測試走正式 resolver／connection／codec／telemetry 路徑，但用的是假 transport 與 Robolectric
替身；**沒有真車、沒有 GATT stack、沒有設定讀回**。因此只能稱 `software-tested`，不是
`vehicle-verified`。
