# Vehicle protocols / 車輛通訊協定

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← 說明文件首頁](README.md) · [PLEV White Paper](PLEV_ARCHITECTURE_WHITE_PAPER.md)

---

## English

Which vehicle families (Electric Unicycles, Electric Scooters, and Smart BMS) RideFlux decodes, how it reaches them over GATT, and where the protocol knowledge comes from. For per-vehicle verification status see the [README](../README.md#supported-vehicles).

### 1. Electric Unicycle (EUC) Families

`WheelFamily` is the routing key used across the EUC domain layer. Enum names are a **stability contract** — they are persisted and used in nav deep links, so renaming one is a breaking change.

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
| `VESC` | VESC-based controllers (Nordic UART) | `COMM_GET_VALUES_SETUP` `0x2F`, read-only |

`G` and `GX` share `BegodeWheelCodec`; the remaining families each have their own codec under `:data:protocol`.
`VESC` was appended last on purpose: the enum name is the persistence key, so new families go at the end.
Its read contract, sources and limits are in [Multidevice resolution](MULTIDEVICE_RESOLUTION.md).

---

### 2. Electric Scooter (PLEV Scooter) Protocols

Electric scooters are modeled as peers under `ScooterDevice` and connected through `ScooterRepository`.

| Dialect | Models | Framing & Wire Spec | Features |
|---|---|---|---|
| **Ninebot Retail** | Ninebot KickScooter (ES1/ES2/ES4, MAX G30, F20/F30/F40, etc.) | Header `5A A5`, length = `payload + 9`, 16-bit negative sum checksum over length + payload. Request target `0x20` with 2-byte LE length. | 3-step automatic pairing (`0x5B` → `0x5C` → user button confirmation → `0x5D` acceptance). `0xB0` telemetry polling. `0x70`/`0x71` stationary lock commands. |
| **Xiaomi Mi / M365** | M365-family development profile; model compatibility untested | Logical `55 AA` frames: total `size + 6` (`payload + 8`); authenticated UART `55 AB`: total `size + 16`, AES-CCM and inverted additive checksum. | FE95 token login and explicit-consent registration; B5 signed LE16 velocity, speed `abs(signedRaw) / 1000f`. Reverse fix inferred from owner feedback, pending physical recheck; lock/unlock/power disabled. See [Mi authentication](MI_AUTH.md) and [reverse-speed correction](M365_REVERSE_SPEED_2026-10-07.md). |

---

M365 is **Experimental with read-only vehicle registers**: L2 written protocol/crypto specifications
plus real hardware logs. T07 extends the existing codec/authentication rather than replacing pairing.
The passive register map requires explicit `experimental = true`: 37 documented read addresses,
12 writable addresses marked `NotYetEnabled` (including lock/unlock/power/KERS/cruise), no write encoder.
SOC, speed, odometer and frame temperature use the B0 hardware fixtures; separate voltage reuses
the existing 48 decoder. ESC 50 current stays raw with unknown sign/scale, so calculated V×I power
stays absent until current is established. B9 units and reverse-speed interpretation remain disputed;
the existing signed-magnitude speed and legacy trip conversion are preserved. The expanded inventory
is L1 community evidence, not promoted to L2 by the crypto specifications. See
[T07 inventory, provenance and gaps](T07_M365_READ_CRYPTO_REPORT.md).

### 3. Smart BMS & VESC Protocols

| System | Protocol / Wire Spec | Telemetry Decoded |
|---|---|---|
| **JBD / Xiaoxiang BMS** | Header `0xDD`, status code, length, payload, checksum, footer `0x77`. Registers `0x03` (basic info) and `0x04` (individual cell voltages). | Total pack voltage, current, residual capacity, temperatures, cycle count, and millivolt-level cell balance. |
| **Ant BMS** | Header `0xAA 0x55 0xAA`, structured telemetry report. | Multi-sensor battery telemetry. |
| **VESC** | Packet framing `[0x02][len][payload][CRC16 hi/lo][0x03]`; `COMM_GET_VALUES_SETUP` (`0x2F`) poll only. `COMM_GET_VALUES` (`0x04`) and `GetPkgInfo` (`0x65`) are **not** sent: no verified reply layout/side effects here. | MOSFET temperature, motor/input current, duty, RPM, speed, input voltage, battery level, odometer. The speed word is **metres per second** (firmware `mc_interface_get_speed()`, scale 1e3); it is converted to the domain's km/h exactly once, at the telemetry boundary. Read-only: no setting encoder. |

---

### 4. GATT Topologies & BLE Service Discovery

| Topology | Service / Characteristics | Target Vehicles |
|---|---|---|
| `SINGLE_CHAR` | `FFE0` service, `FFE1` notify + write | Begode (`G`/`GX`), KingSong (`K`), Ninebot One (`N1`), Veteran (`V`), and Ninebot Retail fallback |
| `SPLIT_CHAR` | Notify `FFE0`/`FFE4`, write `FFE5`/`FFE9` | Inmotion legacy (`I1`) |
| `NORDIC_UART` | `6E400001…`, RX `…0002` (write), TX `…0003` (notify) | Ninebot Z (`N2`), Inmotion (`I2`), VESC, and Ninebot Retail |
| `XIAOMI_MI` | FE95 UPNP `00000010…`, AVDTP `00000019…`, plus NUS write `…0002` / notify `…0003` | Xiaomi Mi development profile; all characteristics required, no topology fallback |
| `SERVICE_FE95` | `0000fe95-0000-1000-8000-00805f9b34fb` | Xiaomi / Ninebot scooter advertisement identifier |

A UUID-only guess is never authoritative — the true family/dialect is confirmed by the bootstrap handshake after connect.

### 5. GATT signature detection and family resolution

After connect, the discovered service/characteristic table is resolved by
`WheelSessionResolver`. The decision, the codec, the notify/write characteristics and the bootstrap
all come from **one** `WheelResolution`; the pre-connect guess is only a provisional codec.

Resolution precedence (highest first):

1. **Manual selection** — the rider picked a family in the "change brand" dialog. It is authoritative
   when the discovered table can carry it; otherwise the connection fails with
   `UNKNOWN_FAMILY` and the reason, and **no bootstrap is written** (a manual G on a Nordic-UART
   device is a diagnostic, not a silent switch to another family).
2. **Saved profile** — a manual choice remembered for the same MAC. Used when compatible; when it
   contradicts the table the table is re-resolved and an ambiguous table stays ambiguous.
3. **GATT structure** — split `FFE0[FFE4]+FFE5[FFE9]` → `I1`; Nordic UART (`…0002` + `…0003`) →
   `I2` / `N2` / `VESC`; single `FFE0[FFE1]` → `G` / `K` / `V` / `N1`. If several structures are
   present, all of their families are candidates.
4. **Name hint** — only narrows a tie *inside* a present structure. A model name that contradicts the
   table (for example `KS-18XL` on Nordic UART) is a conflict, not a routing decision.

`AMBIGUOUS` and `CONFLICT` are terminal for that connect: the UI gets `UNKNOWN_FAMILY` with the
candidate list, and neither a codec nor a bootstrap is chosen. An unnamed `FFE0`/`FFE1` peer is
ambiguous across `G`/`K`/`V`/`N1`; an unnamed Nordic-UART peer is ambiguous across `I2`/`N2`/`VESC`.
This deliberately replaces the old silent fallbacks (G for single-char, N2 for NUS), which could put
another family's initialisation on the wire.

The seeded `FamilyDetector` table stays a *diagnostic* signal (signature ids are recorded in the
resolution) and does not override the structural precedence above. Families that share Nordic UART
(`I2`, `N2`, `VESC`) cannot be separated passively — that is what `ProbeStrategy` is for (an active,
read-only probe), and **no probe is sent today**; VESC therefore requires a manual selection until a
verified probe (or a recovered name predicate) is implemented.

---

### 6. T03: passive Begode BMS evidence (Experimental)

Packets 1/2/3/5/6 are preserved as complete `BmsFrame.Raw` frames, including byte 19,
through `DecodeEvent.RawBmsFrame` into nullable `WheelTelemetry.bmsFrame`. Their physical
fields remain null: type 1 has conflicting vendor layouts; cell pages have no established
scaling/count in the available evidence. No BMS request bytes are sent. Raw evidence does
not refresh telemetry timestamps, count as stationary speed samples, or identify a wheel.
CAP-A contains type 1 unsolicited in the 0/1/4/7 cycle, but no cell pages. See the
[T03 evidence table and capture gaps](agent-tasks/reports/T03-begode-bms.md).

封包 1/2/3/5/6 以完整 `BmsFrame.Raw` 保留至 nullable 遙測欄位，標示 Experimental。
欄位語意有衝突或尺度未知時，實體量維持 null；不送出 BMS 請求，不新增 UI。
Raw 幀不更新遙測時間、不提供靜止速度樣本，也不作為辨識依據。

## 繁體中文

RideFlux 解碼哪些車輛家族（電動獨輪車、電動滑板車與智慧 BMS）、如何透過 GATT 連上它們，以及協定知識的來源。各車款的驗證狀態請見 [README](../README.zh-TW.md#支援的車款)。

### 1. 電動獨輪車（EUC）協定家族

`WheelFamily` 是 EUC 領域層唯一的路由鍵。列舉名稱屬於**穩定性契約**——它會被持久化並用於導覽深層連結，因此更名即為破壞性變更。

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
| `VESC` | VESC 控制器（Nordic UART） | `COMM_GET_VALUES_SETUP` `0x2F`，唯讀 |

`G` 與 `GX` 共用 `BegodeWheelCodec`；其餘家族在 `:data:protocol` 中各自擁有獨立 codec。
`VESC` 刻意附加在最後：列舉名稱是持久化鍵，新家族只能往後加。讀取契約、來源與限制見
[多家族解析](MULTIDEVICE_RESOLUTION.md)。

---

### 2. 電動滑板車（PLEV Scooter）協定

電動滑板車在領域層以 `ScooterDevice` 平行建模，由 `ScooterRepository` 統一管理連線與生命週期。

| 協定方言 | 適用車型 | 封包結構與通訊特徵 | 關鍵功能 |
|---|---|---|---|
| **Ninebot Retail** | Ninebot KickScooter 系列（ES1/ES2/ES4、MAX G30、F20/F30/F40 等） | 幀頭 `5A A5`，長度為 `payload + 9`，校驗和涵蓋長度位元組的小端 16 位元累加和反碼。讀取暫存器目標為 `0x20`，帶 2-byte 小端長度。 | 三步無人值守動態配對（`0x5B` → `0x5C` → 儀表按鍵確認 → `0x5D` 接受）。`0xB0` 暫存器週期性遙測輪詢。`0x70`/`0x71` 靜止鎖車指令。 |
| **Xiaomi Mi / M365** | M365 家族開發設定；車型相容性尚未完整驗證 | 邏輯 `55 AA` 封包總長為 `size + 6`（`payload + 8`）；認證後 UART `55 AB` 總長為 `size + 16`，使用 AES-CCM 與加總取反檢查碼。 | FE95 token 登入與明確同意後註冊；B5 按小端有號 16 位元解析，速度為 `abs(signedRaw) / 1000f`。倒退修正依車主回報推論，待實車複驗；鎖車、解鎖與電源寫入關閉。見 [Mi 認證](MI_AUTH.md#繁體中文) 與 [倒退速度修正](M365_REVERSE_SPEED_2026-10-07.md)。 |

---

M365 為 **Experimental，車輛暫存器唯讀**，證據為 L2 協定／密碼學規格加真實硬體日誌。
T07 延伸既有解碼與認證，不改版配對流程。被動暫存器地圖需明確設定 `experimental = true`：
37 個文件記載的讀取地址；12 個可寫地址（含鎖車／解鎖／關機／KERS／定速）皆為
`NotYetEnabled`，沒有寫入 encoder。SOC、速度、總里程、車架溫度以 B0 硬體夾具回歸；
電壓沿用既有 48 解碼。ESC 50 電流符號／比例未定，保留原始值，V×I 功率亦不輸出。
B9 單位與倒退速度解讀衝突保留在報告，現行有號速度取絕對值與舊行程換算維持不變。
擴充清單仍是 L1 社群證據，密碼學規格不會將其提升至 L2。見 [T07 報告](T07_M365_READ_CRYPTO_REPORT.md)。

### 3. 智慧電池管理系統（Smart BMS）與 VESC 協定

| 系統 | 協定與封包特徵 | 解碼遙測指標 |
|---|---|---|
| **JBD / 小象 BMS** | 幀頭 `0xDD`，狀態碼、長度、負載、校驗和、幀尾 `0x77`。暫存器 `0x03`（基本資訊）與 `0x04`（單體電芯電壓）。 | 總電壓、電流、剩餘容量、溫度、循環次數、單體電芯毫伏級電壓平衡。 |
| **Ant 螞蟻 BMS** | 幀頭 `0xAA 0x55 0xAA`，結構化資料幀。 | 多感測器電池健康度與電壓監控。 |
| **VESC** | 封包格式 `[0x02][len][payload][CRC16 hi/lo][0x03]`；只送 `COMM_GET_VALUES_SETUP`（`0x2F`）輪詢。`COMM_GET_VALUES`（`0x04`）與 `GetPkgInfo`（`0x65`）**不送**：本專案沒有可驗證的回覆格式／副作用。 | MOSFET 溫度、馬達／輸入電流、占空比、RPM、速度、輸入電壓、電量、里程。唯讀，沒有設定 encoder。 |

---

### 4. GATT 拓撲與藍牙服務解析

| 拓撲 | 服務／特徵值 | 適用載具 |
|---|---|---|
| `SINGLE_CHAR` | `FFE0` 服務，`FFE1` 通知 + 寫入 | Begode（`G`/`GX`）、KingSong（`K`）、Ninebot One（`N1`）、Veteran（`V`）及 Ninebot Retail 自動降級 |
| `SPLIT_CHAR` | 通知 `FFE0`/`FFE4`，寫入 `FFE5`/`FFE9` | Inmotion 舊款（`I1`） |
| `NORDIC_UART` | `6E400001…`，RX `…0002`（寫入），TX `…0003`（通知） | Ninebot Z（`N2`）、Inmotion 新款（`I2`）、VESC 及 Ninebot Retail |
| `XIAOMI_MI` | FE95 UPNP `00000010…`、AVDTP `00000019…`，加 NUS 寫入 `…0002`／通知 `…0003` | 小米 Mi 開發設定；必須具有所有特徵值，不降級至其他拓撲 |
| `SERVICE_FE95` | `0000fe95-0000-1000-8000-00805f9b34fb` | 小米／九號滑板車專屬廣播識別服務 UUID |

僅憑 UUID 的推測永遠不是定論——真正的家族要等連線後的啟動握手才會確認。

### 5. GATT 指紋與家族解析

連線後，實際 discovery 到的服務／特徵值表由 `WheelSessionResolver` 解析。家族、codec、
notify/write 特徵值與 bootstrap **全部來自同一個** `WheelResolution`；連線前的猜測只是暫用 codec。

解析優先序（高到低）：

1. **手選**——使用者在「變更品牌」對話框選的家族。只要 discovery 表能承載就採用；不能承載時
   連線以 `UNKNOWN_FAMILY` 失敗並附原因，**不送任何 bootstrap**（手選 G 卻只有 Nordic UART 是
   診斷訊息，不是悄悄換成別家）。
2. **已存 profile**——同一 MAC 先前的手選記憶。相容時採用；與 discovery 表衝突時重新由表解析，
   表本身有歧義就維持歧義。
3. **GATT 結構**——split `FFE0[FFE4]+FFE5[FFE9]` → `I1`；Nordic UART（`…0002` + `…0003`）→
   `I2`／`N2`／`VESC`；單一 `FFE0[FFE1]` → `G`／`K`／`V`／`N1`。若同時存在多種結構，全部列為候選。
4. **名稱提示**——只在已存在的結構內縮小歧義。名稱與表衝突（例如 Nordic UART 上出現 `KS-18XL`）
   視為 conflict，不是路由決定。

`AMBIGUOUS` 與 `CONFLICT` 對該次連線是終態：UI 收到 `UNKNOWN_FAMILY` 與候選清單，既不選 codec
也不送 bootstrap。未命名的 `FFE0`/`FFE1` 在 `G`/`K`/`V`/`N1` 之間有歧義；未命名的 Nordic UART 在
`I2`/`N2`/`VESC` 之間有歧義。這刻意取代舊的靜默 fallback（單字元 → G、NUS → N2），因為那會把
別家的初始化送上線。

`FamilyDetector` 指紋表保留為**診斷**訊號（簽章 id 會記進 resolution），不覆寫上述結構優先序。
共用 Nordic UART 的家族（`I2`、`N2`、`VESC`）無法被動區分——這正是 `ProbeStrategy` 的用途
（主動但唯讀的探測），**目前不送任何探測**；因此在探測實作前，VESC 只能靠手選。
