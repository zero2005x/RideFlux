# Pairing-key backup (`.rfbond`) / 配對金鑰備份

[English](#english) · [繁體中文](#繁體中文) · [← docs](README.md)

---

## English

A scooter's pairing key is a secret the phone remembers between sessions. It controls the scooter, so losing it means pairing again (a power-button press), and leaking it means someone else can take over. This page defines the encrypted backup file RideFlux uses to move keys between phones and apps. The format is shared with other apps by the same developer, so it is specified byte for byte.

### What it covers, and what it never does

| Family id | Key | Bytes |
|---|---|---|
| `xiaomi_mi` | Xiaomi Mi authentication token (M365 family) | 12 |
| `ninebot_crypto` | Ninebot legacy-crypto app random | 16 |

- **The Rokid bridge token is never part of a backup.** It pairs the phone with the glasses, is 8 bytes, and has its own store. A backup can only hold `BondEntry` values, so there is no field to put anything else in.
- RideFlux supports Xiaomi Mi authentication and stored pairing keys. Riders can import keys from backup files or enter them manually, as well as selectively export stored keys.

### Container

```
offset  size  field
0       6     magic "RFBOND" (ASCII)
6       1     version = 1
7       4     PBKDF2 iterations, unsigned big-endian (100 000 .. 10 000 000; default 600 000)
11      16    salt (random)
27      12    AES-GCM nonce (random)
39      n+16  ciphertext || 16-byte GCM tag
```

- Key: PBKDF2-HMAC-SHA256 over the passphrase encoded as UTF-8 (what `PBKDF2WithHmacSHA256` does on the JDK; an implementation elsewhere must encode it the same way), 256-bit output. The test vector below uses an ASCII passphrase.
- Cipher: AES-256-GCM, 128-bit tag. The whole 39-byte header is the additional authenticated data, so editing the iteration count, salt, nonce or version invalidates the file.
- A new salt and nonce are drawn for every file. Files larger than 256 KiB are refused before any key derivation.
- A wrong passphrase and a damaged file are indistinguishable by design.
- Passphrases must have at least 10 characters and at least 4 distinct ones.

### Payload (before encryption)

UTF-8 JSON, `schema` = `rideflux-bond/v1`:

```json
{"schema":"rideflux-bond/v1","createdAt":"2026-10-05T00:00:00Z","entries":[
  {"mac":"AA:BB:CC:DD:EE:FF","family":"xiaomi_mi","credentialHex":"000102030405060708090a0b","label":"My M365","model":"M365"}]}
```

- `mac`: upper-case colon-separated Bluetooth address. `credentialHex`: lower- or upper-case hex, exactly the family's byte length. `label` and `model` (optional): at most 64 characters, no control characters.
- At most 64 entries, no duplicate addresses. Unknown top-level and entry keys are ignored. An entry with an unknown `family` is skipped and counted, never stored. Any other malformed entry rejects the whole file.

### Known-answer vector

Passphrase `correct horse battery`, 100 000 iterations, salt `00 01 … 0f`, nonce `a0 a1 … ab`, `createdAt` `2026-10-05T00:00:00Z`, the single entry shown above produce this 256-byte file (hex):

```
5246424f4e4401000186a0000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a7a8a9aaab3d8433dda0721f193807e6681bf94d567
45abf6a49da176ea9317691966a4255a2430e53a42f7d85010417d3c9081d0ba5f520db5f221d84f4560558f6506e1e802b10266494730
680ce6d62a12d12139fd3e9174860f9ccae5c24a66de121223eac3cf6e4d1f8327f0d4cc80651c7ca28330957297df288abb74c82d374c
42a14d18d9b0b024e13d8229d8fbe7307b2fec4d82999ac5896fe2a9462f8a9488e15ae3f5ef1045d957d5beb8a882206f1c90923dc083
71c398bdd56f11e4ae72a27014188e3d5b484b8511c80de5defaaf31a7299bac0f8dccb
```

It was computed with Python `cryptography` (OpenSSL), independently of the Kotlin code; `BondEnvelopeVectorTest` checks that RideFlux reproduces it byte for byte and opens it.

### In the app

Settings → Backup → **Pairing keys backup**.

- **Export** first asks you to confirm with a biometric or the screen lock (a phone without a screen lock cannot export). The confirmation covers one export for 60 seconds. You then choose a passphrase (typed twice) and a file location. Nothing is written until you pick one.
- **Import** asks for the file, then the passphrase, then shows what is inside. Keys that already exist on the phone are kept unless you tick "replace".
- The screen blocks screenshots, screen recording and the recents thumbnail (`FLAG_SECURE`).
- Passphrases are handled as character arrays and zeroed after use; decrypted keys and the sealed file are zeroed when a flow ends. Keys are never written to the log.
- On the phone the keys live in one app-private file encrypted with an AES-256-GCM key in the Android Keystore. Android backup is off for the app, and a Keystore key cannot be restored on another phone anyway, which is why moving keys goes through this file.

### Limits to know about

- A backup is as strong as its passphrase. Anyone with the file can try passphrases offline; PBKDF2 slows that down, it does not stop a weak passphrase.
- A key controls the scooter. Share a backup only with yourself, and only for your own scooter.
- Not yet covered: sharing keys between apps without a file (same-signature provider) and showing a key as a QR code. Both would reuse this container.

---

## 繁體中文

滑板車的配對金鑰是手機在不同連線之間記住的秘密。它能控制滑板車：遺失就得重新配對（按一次電源鍵），外洩則別人可以接管。本頁定義 RideFlux 用來在手機與 App 之間搬移金鑰的加密備份檔。這個格式會與同一位開發者的其他 App 共用，所以逐位元組寫明。

### 涵蓋範圍，以及絕不包含的東西

| 家族代號 | 金鑰 | 位元組 |
|---|---|---|
| `xiaomi_mi` | Xiaomi Mi 驗證 token（M365 系列） | 12 |
| `ninebot_crypto` | Ninebot 舊式加密的 app random | 16 |

- **Rokid 橋接 token 絕不會放進備份。** 它用於手機與眼鏡配對、長度 8 位元組、有自己的儲存。備份只能容納 `BondEntry`，根本沒有欄位可以放其他東西。
- RideFlux 已支援 Xiaomi Mi 認證與已儲存的配對金鑰。騎士可從備份檔匯入或手動輸入金鑰，亦可選擇性匯出已儲存的金鑰。

### 容器格式

```
位移    大小  欄位
0       6     魔術字 "RFBOND"（ASCII）
6       1     版本 = 1
7       4     PBKDF2 迭代次數，無號大端序（100 000 .. 10 000 000；預設 600 000）
11      16    salt（隨機）
27      12    AES-GCM nonce（隨機）
39      n+16  密文 || 16 位元組 GCM 標籤
```

- 金鑰：PBKDF2-HMAC-SHA256，輸入為以 UTF-8 編碼的口令（JDK 的 `PBKDF2WithHmacSHA256` 就是這樣做；其他平台的實作必須用同樣的編碼），輸出 256 位元。下方測試向量使用 ASCII 口令。
- 加密：AES-256-GCM，標籤 128 位元。完整的 39 位元組標頭是附加驗證資料，所以改動迭代次數、salt、nonce 或版本都會使檔案失效。
- 每個檔案都重新產生 salt 與 nonce。大於 256 KiB 的檔案在任何金鑰推導之前就拒絕。
- 口令錯誤與檔案損毀在設計上無法區分。
- 口令至少 10 個字元，且至少包含 4 種不同字元。

### 內容（加密前）

UTF-8 JSON，`schema` 為 `rideflux-bond/v1`（範例見上方英文段落）。

- `mac`：大寫、以冒號分隔的藍牙位址。`credentialHex`：大小寫皆可的十六進位，長度必須恰為該家族的位元組數。`label` 與 `model`（選填）：最多 64 個字元、不得含控制字元。
- 最多 64 筆，位址不得重複。未知的頂層與條目欄位會被忽略。`family` 未知的條目會被略過並計數，絕不儲存。其他格式錯誤的條目會使整個檔案被拒絕。

### 已知答案向量

口令 `correct horse battery`、100 000 次迭代、salt `00 01 … 0f`、nonce `a0 a1 … ab`、`createdAt` 為 `2026-10-05T00:00:00Z`、僅含上方那一筆條目，會產生上方英文段落列出的 256 位元組檔案。

它是用 Python `cryptography`（OpenSSL）獨立於 Kotlin 程式算出來的；`BondEnvelopeVectorTest` 會檢查 RideFlux 逐位元組重現並能開啟它。

### 在 App 裡

設定 → 備份 → **配對金鑰備份**。

- **匯出**會先要求您用生物辨識或螢幕鎖定確認（沒有螢幕鎖定的手機無法匯出）。一次確認在 60 秒內只涵蓋一次匯出。接著設定口令（輸入兩次）並選擇檔案位置，選好之前不會寫出任何東西。
- **匯入**先選檔案，再輸入口令，然後顯示內容。手機上已有的金鑰預設保留，勾選「取代」才會覆蓋。
- 此畫面禁止截圖、螢幕錄影與最近工作縮圖（`FLAG_SECURE`）。
- 口令以字元陣列處理，用完立即清零；解密後的金鑰與封裝好的檔案在流程結束時清零。金鑰絕不寫入日誌。
- 在手機上，金鑰存在一個 App 私有檔案，以 Android Keystore 裡的 AES-256-GCM 金鑰加密。此 App 已關閉 Android 備份，而 Keystore 金鑰本來就無法還原到另一支手機，所以搬移金鑰要透過這個備份檔。

### 需要知道的限制

- 備份的強度取決於口令。任何拿到檔案的人都能離線試口令；PBKDF2 只是拖慢，擋不住弱口令。
- 金鑰能控制滑板車。只分享給自己、只用於自己的滑板車。
- 尚未涵蓋：不透過檔案、在 App 之間共用金鑰（同簽章 provider），以及用 QR 碼顯示金鑰。兩者都會沿用這個容器。
