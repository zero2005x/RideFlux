# RideFlux Privacy Policy / 隱私權政策

Effective date / 生效日期: 2026-10-08

This policy covers the RideFlux phone app (`com.rideflux.app`) distributed on Google Play.
本政策適用於在 Google Play 上架的 RideFlux 手機應用程式（`com.rideflux.app`）。

- [English](#english)
- [繁體中文](#繁體中文)

---

## English

### Summary

RideFlux does not collect, transmit, sell, or share any personal data. The app has no
internet permission, contains no advertising, analytics, or crash-reporting SDKs, and has
no user accounts. Everything it records stays on your phone unless you choose to export it.

### Data the app handles on your device

| Data | Why it is used | Where it is kept |
|---|---|---|
| **Precise and approximate location** | To draw the route of a trip while a trip is being recorded. | Stored on your phone with the trip. |
| **Wheel and scooter telemetry** (speed, battery, voltage, current, temperature, PWM load, odometer) | To show the dashboard, raise safety alerts, and record trips. | Recent values in memory; trip samples stored on your phone. |
| **Bluetooth device identifiers** (addresses of your wheel, scooter, AR glasses, and control ring) and pairing tokens | To reconnect to the devices you paired. | Stored on your phone. |
| **Scooter pairing keys** (the authentication value a Xiaomi or Ninebot scooter gives the app when you pair, one per scooter) | To reconnect to a scooter without pairing it again. | Stored on your phone in app-private storage, encrypted with a non-exportable key in the Android Keystore. |
| **App settings** (alert thresholds, units, display options) | To remember your preferences. | Stored on your phone. |
| **Diagnostic log** (when the HUD link changed state and why, when the glasses connected or left, with Bluetooth addresses shortened to their last two bytes; never keys, tokens or location) | To find out why the HUD stopped working after the fact. | A small capped file (about 256 KB at most) on your phone, and on your AR glasses for the HUD app. Never sent anywhere. |

### Location

Location is read only while a trip is being recorded. Recording starts when a connected
wheel begins moving and runs as a foreground service with a persistent notification, so you
can always see when it is active and stop it. RideFlux does not request background location
access. If you deny the location permission, trips are still recorded without a route.

### Bluetooth

RideFlux uses Bluetooth to connect to your electric unicycle, to send ride information to
optional AR glasses, and to receive button presses from an optional control ring. It scans
for nearby Bluetooth devices only to find these devices, and does not use Bluetooth to
determine your location.

### Export and backup

You can export a trip as a CSV or GPX file, or back up all trips and settings to a ZIP file.
These files are written only to a location you choose through the Android file picker, and
RideFlux does not send them anywhere. The trip and settings backup excludes Bluetooth pairing
tokens and scooter pairing keys. Once you save or share an exported file, its handling is
governed by wherever you put it.

**Pairing-key backup.** Separately, the "Pairing keys" screen can export the pairing keys of
the scooters you select to a `.rfbond` file, so you can restore them on a new phone or after
a reinstall. For each selected scooter the file holds its Bluetooth address, brand, the
name you gave it, and its pairing key. The file is encrypted with a passphrase that you
choose (at least 10 characters; AES-256-GCM, with the key derived by PBKDF2-HMAC-SHA256), and
before exporting RideFlux asks Android to confirm it is you with your fingerprint, face or
screen lock. RideFlux does not read or store any biometric data; Android performs that
check. The file never contains the pairing token used between the phone and AR glasses.
Anyone who has both the file and its passphrase can connect to those scooters, so keep it
somewhere safe. Importing works the same way: you open a `.rfbond` file and enter its
passphrase. You can also type a pairing key in by hand.

**Diagnostic log.** Settings has a "Diagnostic log" row that saves this log as a text file to a
location you choose, so you can attach it when you report a problem. RideFlux never uploads it.
The file starts with the app version, Android version and phone model, followed by the events.

Android cloud backup is disabled for this app, so its data is not copied to your Google
account.

### Deleting your data

You can delete individual trips or clear all trips inside the app, and delete the pairing
key of a single scooter from the "Pairing keys" screen. Uninstalling RideFlux, or clearing
its storage in Android Settings, permanently removes all of its data from your phone.

### Children

RideFlux is not directed at children under 13 and does not knowingly process children's
data.

### Changes to this policy

Changes will be published in this file, with the effective date updated. The full history
is available in this repository's commit log.

### Contact

Questions about this policy can be raised at
<https://github.com/zero2005x/RideFlux/issues>.

---

## 繁體中文

### 摘要

RideFlux 不會收集、傳輸、出售或分享任何個人資料。本 App 沒有網路存取權限，不含任何廣告、
數據分析或當機回報 SDK，也沒有使用者帳號。除非您主動匯出，所有記錄的資料都只存在您的手機中。

### App 在您裝置上處理的資料

| 資料 | 用途 | 存放位置 |
|---|---|---|
| **精確與大概位置** | 記錄行程時繪製騎乘路線。 | 與行程一起儲存在您的手機中。 |
| **車輛與滑板車遙測資料**（速度、電量、電壓、電流、溫度、PWM 負載、總里程） | 顯示儀表板、發出安全警示、記錄行程。 | 最近的數值保存在記憶體中；行程取樣資料儲存在您的手機中。 |
| **藍牙裝置識別碼**（您的車輛、滑板車、AR 眼鏡與控制指環的位址）及配對權杖 | 重新連接您已配對的裝置。 | 儲存在您的手機中。 |
| **滑板車配對金鑰**（小米或 Ninebot 滑板車在配對時交給 App 的驗證值，每台一把） | 不必重新配對即可再次連接滑板車。 | 存放在手機的 App 專用儲存空間，並以 Android Keystore 中不可匯出的金鑰加密。 |
| **App 設定**（警示門檻、單位、顯示選項） | 記住您的偏好設定。 | 儲存在您的手機中。 |
| **診斷日誌**（HUD 連線何時改變狀態與原因、眼鏡何時連上或離開；藍牙位址只保留最後兩個位元組；絕不含金鑰、權杖或位置） | 在事後找出 HUD 為什麼停止運作。 | 手機上一個有大小上限的小檔案（最多約 256 KB），HUD 應用程式則存在 AR 眼鏡上。絕不傳送到任何地方。 |

### 位置資訊

只有在記錄行程時才會讀取位置。已連線的車輛開始移動時會自動開始記錄，並以帶有常駐通知的
前景服務執行，因此您隨時都能看到記錄是否進行中，也可以停止。RideFlux 不會要求背景位置
存取權。如果您拒絕位置權限，行程仍會記錄，只是沒有路線。

### 藍牙

RideFlux 使用藍牙連接您的電動獨輪車、將騎乘資訊傳送到選用的 AR 眼鏡，以及接收選用控制指環
的按鍵。掃描附近的藍牙裝置只是為了找到上述裝置，不會用藍牙推測您的位置。

### 匯出與備份

您可以將行程匯出為 CSV 或 GPX 檔，或將所有行程與設定備份成 ZIP 檔。這些檔案只會寫入您透過
Android 檔案選擇器指定的位置，RideFlux 不會將它們傳送到任何地方。行程與設定備份不包含藍牙
配對權杖與滑板車配對金鑰。匯出的檔案在您儲存或分享之後，其處理方式由您存放或分享的服務決定。

**配對金鑰備份。** 另外，「配對金鑰」頁面可以把您選擇的滑板車的配對金鑰匯出成 `.rfbond`
檔，方便在新手機或重新安裝後還原。每台被選取的滑板車，檔案中會有它的藍牙位址、品牌、您為它
取的名稱與配對金鑰。檔案以您自訂的密語加密（至少 10 個字元；AES-256-GCM，金鑰由
PBKDF2-HMAC-SHA256 推導），匯出前 RideFlux 會請 Android 以指紋、臉部或螢幕鎖確認是您本人。
RideFlux 不會讀取或儲存任何生物辨識資料，驗證由 Android 執行。檔案絕不包含手機與 AR 眼鏡之間
使用的配對權杖。任何同時取得檔案與密語的人都能連上這些滑板車，請妥善保管。匯入的方式相同：
開啟 `.rfbond` 檔並輸入它的密語。您也可以手動輸入配對金鑰。

**診斷日誌。** 設定頁有一列「診斷日誌」，可把這份日誌存成文字檔放到您指定的位置，方便您回報問題時附上。
RideFlux 不會上傳它。檔案開頭是 App 版本、Android 版本與手機型號，後面才是事件記錄。

本 App 已停用 Android 雲端備份，因此資料不會複製到您的 Google 帳戶。

### 刪除資料

您可以在 App 內刪除單筆行程或清除所有行程，也可以在「配對金鑰」頁面刪除單一滑板車的配對金鑰。
解除安裝 RideFlux，或在 Android 設定中清除其儲存空間，就會從手機永久移除它的所有資料。

### 兒童

RideFlux 並非以 13 歲以下兒童為對象，也不會在知情的情況下處理兒童的資料。

### 政策變更

政策如有變更，會更新本檔案並修改生效日期。完整的修改歷程可在本 repository 的 commit
記錄中查閱。

### 聯絡方式

對本政策有任何疑問，請至 <https://github.com/zero2005x/RideFlux/issues> 提出。
