# Wheel protocols / 車輛通訊協定

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← 說明文件首頁](README.md)

---

## English

Which wheel families RideFlux decodes, how it reaches them over GATT, and where the protocol knowledge comes from. For per-wheel verification status see the [README](../README.md#supported-wheels).

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
| `SINGLE_CHAR` | `FFE0` service, `FFE1` notify + write | `G`, `GX`, `K`, `N1`, `V` |
| `SPLIT_CHAR` | notify `FFE0`/`FFE4`, write `FFE5`/`FFE9` | `I1` |
| `NORDIC_UART` | `6E400001…`, RX `…0002`, TX `…0003` | `N2`, `I2` |

A UUID-only guess is never authoritative — the true family is confirmed by the family's
bootstrap handshake after connect. Callers that already know the family should pass
`expectedFamily` to `WheelRepository.connect()`.

> **Note on `§` references.** KDoc throughout the codebase cites section numbers
> (`§1.1`, `§2.6`, `§9.*`) from the project's own protocol notes (`PROTOCOL_SPEC.md`, with
> test vectors in `TEST_VECTORS.md`). Those notes are **not** part of this source tree and
> are not published, so the citations cannot be followed from this repository; they only
> label which part of the protocol each piece of code implements.
>
> **Where the protocol knowledge comes from.** The decoders are RideFlux's own Kotlin code,
> written with reference to open-source projects such as
> [WheelLog](https://github.com/Wheellog/Wheellog.Android) (GPL-3.0). Some test vectors are
> taken from WheelLog; see [`NOTICE`](../NOTICE).

---

## 繁體中文

RideFlux 解碼哪些車輛家族、如何透過 GATT 連上它們，以及協定知識的來源。各車款的驗證狀態請見 [README](../README.zh-TW.md#支援的車款)。

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
| `SINGLE_CHAR` | `FFE0` 服務，`FFE1` 通知 + 寫入 | `G`、`GX`、`K`、`N1`、`V` |
| `SPLIT_CHAR` | 通知 `FFE0`/`FFE4`，寫入 `FFE5`/`FFE9` | `I1` |
| `NORDIC_UART` | `6E400001…`，RX `…0002`，TX `…0003` | `N2`、`I2` |

僅憑 UUID 的推測永遠不是定論——真正的家族要等連線後的啟動握手才會確認。若呼叫端已經知道
家族，應將 `expectedFamily` 傳入 `WheelRepository.connect()`。

> **關於 `§` 章節編號。** 程式碼中的 KDoc 大量引用本專案自己的協定筆記（`PROTOCOL_SPEC.md`，
> 測試向量在 `TEST_VECTORS.md`）的章節編號（`§1.1`、`§2.6`、`§9.*`）。這些筆記**並不在**本
> 原始碼樹中，也未公開，因此無法從本儲存庫查閱這些引用；它們只用來標示各段程式碼實作的是協定
> 的哪一部分。
>
> **協定知識的來源。** 各解碼器是 RideFlux 自行以 Kotlin 撰寫的程式碼，撰寫時參考了
> [WheelLog](https://github.com/Wheellog/Wheellog.Android)（GPL-3.0）等開源專案。部分測試向量
> 取自 WheelLog，詳見 [`NOTICE`](../NOTICE)。
