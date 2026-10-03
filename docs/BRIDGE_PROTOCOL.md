# Phone ↔ glasses bridge / 手機 ↔ 眼鏡橋接

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← 說明文件首頁](README.md)

---

## English

The BLE GATT bridge that relays a compact telemetry frame from the phone to the glasses HUD: identity, startup ordering, scanning budget, silent-link handling and the frame layout.

### Phone ↔ glasses bridge

The phone owns the wheel's single BLE link and re-broadcasts a compact frame, so two
centrals never fight over the wheel.

| | |
|---|---|
| Service UUID | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` |
| Telemetry characteristic | `e7810a72-73ae-499d-8c15-faa9aef0c3f2` (notify-only) |
| HUD profile characteristic | `e7810a74-73ae-499d-8c15-faa9aef0c3f2` (read-only, per approved glasses token) |
| Magic byte | `0x52` (`'R'`) |
| Protocol version | `2` (20-byte frame) |
| Frame size (v2) | 20 bytes, little-endian — fits the default 23-byte ATT MTU |
| Preferred ATT MTU | 64 (client-initiated; v2 never depends on it) |
| Pairing token | 8 bytes of service data under the service UUID, in the scan response |
| Rokid CXR channel | `rideflux.telemetry.v1` |
| Rokid CXR HUD profile channel | `rideflux.hud-profile.v1` |

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

**Silent links.** A subscribed link can stop delivering without Android saying so:
the phone's app was killed and its new GATT server knows nothing about the glasses,
the rider took the glasses off the approved list, or the approval window ran out.
The connection stays up and the notifications just stop. The phone sends a frame
every second while it runs (a standby heartbeat when there is no wheel), so the HUD
wraps the client's frame flow in `endWhenSilent`, which fails the flow after 30 s
without a frame once the first one has arrived, and the HUD's reconnect loop starts
over. The limit is far above the few seconds after which the HUD marks its data stale
because a phone with its screen off holds no wake lock and its heartbeat is only as
regular as the phone is awake. Before the first frame the phone may be waiting for
the rider to approve the glasses (it holds the request for 60 s,
`BridgeProtocol.PENDING_AUTHORIZATION_TIMEOUT_MILLIS`), so that wait is that window
plus 40 s for scanning, connecting and subscribing, and it doubles each time it went
unanswered, up to 10 minutes, which keeps glasses that were turned down from asking
again and again.

Frame payload: timestamp (seconds, decoded unsigned), speed, wheel battery %,
phone battery %, pack voltage, trip distance, trip duration, coarse signal level,
stale flag, ready flag. Sentinels encode "absent" for each numeric slot. The v2
layout packs signal into the flags byte and narrows the timestamp to one word so
one notification always carries a complete frame; the previous 32-byte v1 layout
is retained decode-only so a glasses APK updated ahead of the phone still reads
frames during a mixed-install window. Any breaking change must bump
`PROTOCOL_VERSION`.

The glasses read a separate 10-byte, versioned HUD profile every second after
subscribing to telemetry. This keeps the telemetry frame at 20 bytes and lets
older clients ignore the new characteristic. The phone looks up the profile by
the glasses handshake token (or legacy MAC) and stores one profile per device.
The CXR publisher sends the same payload on its profile channel when it changes
and periodically while connected. The HUD applies the profile locally; the
phone can edit profiles offline and sends the latest value on reconnect.

---

## 繁體中文

將精簡遙測封包從手機轉播到眼鏡 HUD 的 BLE GATT 橋接：身分、啟動順序、掃描預算、靜默連線處理與封包版面。

### 手機 ↔ 眼鏡橋接

手機獨佔車輛唯一的 BLE 連線，再以精簡封包轉播出去，避免兩個中央端搶奪同一台車。

| | |
|---|---|
| 服務 UUID | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` |
| 遙測特徵值 | `e7810a72-73ae-499d-8c15-faa9aef0c3f2`（僅通知） |
| HUD 偏好特徵值 | `e7810a74-73ae-499d-8c15-faa9aef0c3f2`（唯讀，依已核准眼鏡權杖選取） |
| 魔術位元組 | `0x52`（`'R'`） |
| 協定版本 | `2`（20 位元組封包） |
| 封包大小（v2） | 20 位元組，小端序 — 可容於預設的 23 位元組 ATT MTU |
| 偏好 ATT MTU | 64（由用戶端發起協商；v2 不依賴協商結果） |
| 配對權杖 | 8 位元組，以服務 UUID 的 service data 放在掃描回應中 |
| Rokid CXR 通道 | `rideflux.telemetry.v1` |
| Rokid CXR HUD 偏好通道 | `rideflux.hud-profile.v1` |

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

眼鏡訂閱遙測後每秒讀取獨立的 10 位元組 HUD 偏好資料。它有自己的版本號，不占用原本
20 位元組的遙測封包；舊版眼鏡會忽略新增的特徵值。手機以眼鏡握手權杖（舊版則以 MAC）
尋找各自的版面設定。CXR 連線透過另一個通道在設定變更時及連線期間定期傳送相同資料。
手機離線時仍可編輯，重連後眼鏡會套用最新設定。
