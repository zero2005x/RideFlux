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

`G` and `GX` share `BegodeWheelCodec`; the remaining families each have their own codec under `:data:protocol`.

---

### 2. Electric Scooter (PLEV Scooter) Protocols

Electric scooters are modeled as peers under `ScooterDevice` and connected through `ScooterRepository`.

| Dialect | Models | Framing & Wire Spec | Features |
|---|---|---|---|
| **Ninebot Retail** | Ninebot KickScooter (ES1/ES2/ES4, MAX G30, F20/F30/F40, etc.) | Header `5A A5`, length = `payload + 9`, 16-bit negative sum checksum over length + payload. Request target `0x20` with 2-byte LE length. | 3-step automatic pairing (`0x5B` → `0x5C` → user button confirmation → `0x5D` acceptance). `0xB0` telemetry polling. `0x70`/`0x71` stationary lock commands. |
| **Xiaomi Mi / M365** | M365-family development profile; model compatibility untested | Logical `55 AA` frames: total `size + 6` (`payload + 8`); authenticated UART `55 AB`: total `size + 16`, AES-CCM and inverted additive checksum. | FE95 token login and explicit-consent registration; B0 register decoding with B5 `raw / 1000f` and unknown-speed sentinel `0xFF00`. L2, not tried on a real scooter; lock/unlock/power disabled. See [Mi authentication](MI_AUTH.md). |

---

### 3. Smart BMS & VESC Protocols

| System | Protocol / Wire Spec | Telemetry Decoded |
|---|---|---|
| **JBD / Xiaoxiang BMS** | Header `0xDD`, status code, length, payload, checksum, footer `0x77`. Registers `0x03` (basic info) and `0x04` (individual cell voltages). | Total pack voltage, current, residual capacity, temperatures, cycle count, and millivolt-level cell balance. |
| **Ant BMS** | Header `0xAA 0x55 0xAA`, structured telemetry report. | Multi-sensor battery telemetry. |
| **VESC** | Comm frame format with `COMM_GET_VALUES` (`0x2F`) polling and 16-bit XMODEM CRC (`0xD58D`). | Duty cycle, motor/MOSFET temperatures, input voltage, current. |

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

---

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

`G` 與 `GX` 共用 `BegodeWheelCodec`；其餘家族在 `:data:protocol` 中各自擁有獨立 codec。

---

### 2. 電動滑板車（PLEV Scooter）協定

電動滑板車在領域層以 `ScooterDevice` 平行建模，由 `ScooterRepository` 統一管理連線與生命週期。

| 協定方言 | 適用車型 | 封包結構與通訊特徵 | 關鍵功能 |
|---|---|---|---|
| **Ninebot Retail** | Ninebot KickScooter 系列（ES1/ES2/ES4、MAX G30、F20/F30/F40 等） | 幀頭 `5A A5`，長度為 `payload + 9`，校驗和涵蓋長度位元組的小端 16 位元累加和反碼。讀取暫存器目標為 `0x20`，帶 2-byte 小端長度。 | 三步無人值守動態配對（`0x5B` → `0x5C` → 儀表按鍵確認 → `0x5D` 接受）。`0xB0` 暫存器週期性遙測輪詢。`0x70`/`0x71` 靜止鎖車指令。 |
| **Xiaomi Mi / M365** | M365 家族開發設定；車型相容性尚未試驗 | 邏輯 `55 AA` 封包總長為 `size + 6`（`payload + 8`）；認證後 UART `55 AB` 總長為 `size + 16`，使用 AES-CCM 與加總取反檢查碼。 | FE95 token 登入與明確同意後註冊；B0 暫存器解碼、B5 `raw / 1000f` 及未知速度哨兵 `0xFF00`。L2，尚未實車試用；鎖車、解鎖與電源寫入關閉。見 [Mi 認證](MI_AUTH.md#繁體中文)。 |

---

### 3. 智慧電池管理系統（Smart BMS）與 VESC 協定

| 系統 | 協定與封包特徵 | 解碼遙測指標 |
|---|---|---|
| **JBD / 小象 BMS** | 幀頭 `0xDD`，狀態碼、長度、負載、校驗和、幀尾 `0x77`。暫存器 `0x03`（基本資訊）與 `0x04`（單體電芯電壓）。 | 總電壓、電流、剩餘容量、溫度、循環次數、單體電芯毫伏級電壓平衡。 |
| **Ant 螞蟻 BMS** | 幀頭 `0xAA 0x55 0xAA`，結構化資料幀。 | 多感測器電池健康度與電壓監控。 |
| **VESC** | 通用通訊幀格式，支援 `COMM_GET_VALUES`（`0x2F`）輪詢與 16 位元 XMODEM CRC（`0xD58D`）。 | 占空比、馬達/MOSFET 溫度、輸入電壓、相電流。 |

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
