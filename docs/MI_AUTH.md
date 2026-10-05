# Xiaomi Mi authentication / 小米 Mi 認證

[English](#english) · [繁體中文](#繁體中文) · [Documentation](README.md)

## English

This profile is in development. It is implemented from reverse-engineering notes and the owner's M365-Rokid-HUD reference app, unit-tested, and not tried on a real scooter (L2). Cryptographic test vectors demonstrate algorithm agreement; a simulated scooter demonstrates self-consistency. Neither establishes compatibility with a vehicle.

Mi authentication uses FE95, with UPNP characteristic `00000010-0000-1000-8000-00805f9b34fb` and AVDTP characteristic `00000019-0000-1000-8000-00805f9b34fb`. Authenticated telemetry uses Nordic UART. A stored 12-byte `xiaomi_mi` token logs in; registration uses ephemeral P-256 ECDH and obtains a token after a power-button confirmation.

Setup derives 64 bytes with HKDF-SHA256 and `mible-setup-info`. The token is bytes 0–11 and the DID key is bytes 28–43. Login derives directional AES keys and four-byte IV prefixes from the token and `appRandom || scooterRandom`, using `mible-login-info`. Authentication compares the scooter proof in constant time before sending the app proof or accepting session keys. A failed login never starts registration automatically.

`MiParcel` uses sequential fragments with two-byte index prefixes and at most 18 payload bytes. Authentication retains notifications in one ordered mailbox for the operation, checks their source characteristic, and rejects malformed or out-of-order responses. Each receive parcel is bounded by ten seconds and 64 bytes. Login steps default to ten seconds; registration's public-key readiness wait defaults to thirty seconds. Timing is configurable for tests. Registration requires explicit consent and rejects DID ciphertext lengths outside 19–36 bytes because the reference announces exactly two fragments.

UART frames use `55 AB`, a clear size byte, a little-endian 16-bit counter, AES-128-CCM ciphertext with a four-byte tag, and an inverted additive checksum. CCM has a 12-byte nonce (L=3). The app and device use separate keys and IVs. RideFlux increments the outgoing counter starting at zero and requires a new session before wrap. `LEGACY_ZERO` is an explicit compatibility option that reuses nonces; it is disabled by default. The reference Android app uses zero, while the Rust session increments from one. Which mode the owner's scooter accepts is unknown.

Tokens and derived keys are owned byte arrays, with explicit wiping and redacted `toString()` output. Credentials must never be logged or placed in Intents or saved UI state. Persistent tokens use the existing encrypted `BondStore`, and portable backups use the encrypted `.rfbond` format described in [BOND_BACKUP.md](BOND_BACKUP.md).

Wiping clears application-owned arrays. JCA providers retain their own internal cipher and private-key state; the JVM does not guarantee that this internal state can be erased. Registration must attempt to destroy its ephemeral private key after use.

Reading telemetry after login is benign. Registration is critical and requires explicit user confirmation that the scooter is stationary plus a physical power-button press. Speed cannot be checked before authentication; the UI must state that limitation. Lock, unlock, and power writes remain disabled for this development profile.

Open questions include token portability, whether registration invalidates an older token, counter acceptance, the owner's B5 speed scale, and phone-specific parcel timing. The owner answers these later; this work performs no vehicle experiments. The inspected Rust manifest refers to a missing `LICENSE.md`; implementation uses the documented behavior without copying Rust source, and credits CamiAlfa's research in `NOTICE`.

## 繁體中文

此連線設定仍在開發中。實作依據是逆向研究筆記與車主的 M365-Rokid-HUD 參考 App，經單元測試，但尚未在真實滑板車上試用（L2）。加密向量證明演算法結果一致；模擬車端測試證明流程自洽。兩者都不代表已確認實車相容性。

Mi 認證使用 FE95 服務中的 UPNP 特徵值 `00000010-0000-1000-8000-00805f9b34fb` 與 AVDTP 特徵值 `00000019-0000-1000-8000-00805f9b34fb`。認證後的遙測走 Nordic UART。既有的 12 位元組 `xiaomi_mi` token 用於登入；註冊則使用暫時的 P-256 ECDH 金鑰，在按下車端電源鍵確認後取得 token。

註冊以 HKDF-SHA256 與 `mible-setup-info` 衍生 64 位元組，前 12 位元組是 token，第 28–43 位元組是 DID 金鑰。登入以 token、`appRandom || scooterRandom` 與 `mible-login-info` 衍生雙向 AES 金鑰及 IV 前綴。認證流程先以固定時間比較驗證車端證明，再送出 App 證明並接受工作階段金鑰。登入失敗不會自動啟動註冊。

`MiParcel` 使用依序編號的分片，前綴為兩個位元組，每片最多 18 位元組資料。每次認證操作使用同一個依序接收佇列，檢查通知來源特徵值，並拒絕格式錯誤或順序錯亂的回覆。每個接收分包限制為十秒及 64 位元組；登入步驟預設十秒，註冊公鑰交換的按鍵等待預設三十秒。測試可調整時序。註冊必須明確同意；參考流程固定宣告兩片，因此 DID 密文超出 19–36 位元組時會拒絕。

UART 封包包含 `55 AB`、明文長度、16 位元小端計數器、AES-128-CCM 密文與 4 位元組標籤，以及加總取反檢查碼。CCM nonce 長度為 12 位元組（L=3），兩個方向使用不同金鑰與 IV。RideFlux 預設從 0 遞增，計數器耗盡前必須建立新工作階段。`LEGACY_ZERO` 會重複使用 nonce，是預設關閉的相容選項。參考 Android App 固定使用 0，Rust 工作階段從 1 遞增；車主的車能接受哪個模式仍未知。

Token 與衍生金鑰以位元組陣列持有，提供清零方法，且 `toString()` 遮蔽內容。憑證不得寫入日誌、Intent 或可還原的 UI 狀態。持久化使用既有加密 `BondStore`；可攜備份使用 [BOND_BACKUP.md](BOND_BACKUP.md#繁體中文) 所述的加密 `.rfbond` 格式。

清零會清除 App 自己持有的陣列。JCA 提供者另外持有加密器與私密金鑰的內部狀態，JVM 無法保證這些內部資料可以清除。註冊流程使用完暫時私密金鑰後，必須嘗試銷毀它。

登入後讀取遙測屬於低風險操作。註冊屬於高風險操作，必須由使用者明確確認車輛靜止，並按下車端電源鍵。認證前無法檢查速度，介面必須清楚說明。此開發設定仍關閉鎖車、解鎖與關機寫入。

仍待車主回答：token 能否跨 App 使用、註冊是否讓舊 token 失效、計數器模式、該車型的 B5 速度刻度，以及手機的分片時序。本工作不進行任何實車實驗。參考 Rust 的 manifest 指向缺少的 `LICENSE.md`；本實作沒有複製 Rust 原始碼，而是依已描述的行為自行實作，並在 `NOTICE` 致謝 CamiAlfa 的研究。
