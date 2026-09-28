# RideFlux Privacy Policy / 隱私權政策

Effective date / 生效日期: 2026-09-28

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
| **Wheel telemetry** (speed, battery, voltage, current, temperature, PWM load, odometer) | To show the dashboard, raise safety alerts, and record trips. | Recent values in memory; trip samples stored on your phone. |
| **Bluetooth device identifiers** (addresses of your wheel, AR glasses, and control ring) and pairing tokens | To reconnect to the devices you paired. | Stored on your phone. |
| **App settings** (alert thresholds, units, display options) | To remember your preferences. | Stored on your phone. |

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
RideFlux does not send them anywhere. Backups exclude Bluetooth pairing tokens. Once you
save or share an exported file, its handling is governed by wherever you put it.

Android cloud backup is disabled for this app, so its data is not copied to your Google
account.

### Deleting your data

You can delete individual trips or clear all trips inside the app. Uninstalling RideFlux,
or clearing its storage in Android Settings, permanently removes all of its data from your
phone.

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
| **車輛遙測資料**（速度、電量、電壓、電流、溫度、PWM 負載、總里程） | 顯示儀表板、發出安全警示、記錄行程。 | 最近的數值保存在記憶體中；行程取樣資料儲存在您的手機中。 |
| **藍牙裝置識別碼**（您的車輛、AR 眼鏡與控制指環的位址）及配對權杖 | 重新連接您已配對的裝置。 | 儲存在您的手機中。 |
| **App 設定**（警示門檻、單位、顯示選項） | 記住您的偏好設定。 | 儲存在您的手機中。 |

### 位置資訊

只有在記錄行程時才會讀取位置。已連線的車輛開始移動時會自動開始記錄，並以帶有常駐通知的
前景服務執行，因此您隨時都能看到記錄是否進行中，也可以停止。RideFlux 不會要求背景位置
存取權。如果您拒絕位置權限，行程仍會記錄，只是沒有路線。

### 藍牙

RideFlux 使用藍牙連接您的電動獨輪車、將騎乘資訊傳送到選用的 AR 眼鏡，以及接收選用控制指環
的按鍵。掃描附近的藍牙裝置只是為了找到上述裝置，不會用藍牙推測您的位置。

### 匯出與備份

您可以將行程匯出為 CSV 或 GPX 檔，或將所有行程與設定備份成 ZIP 檔。這些檔案只會寫入您透過
Android 檔案選擇器指定的位置，RideFlux 不會將它們傳送到任何地方。備份檔不包含藍牙配對權杖。
匯出的檔案在您儲存或分享之後，其處理方式由您存放或分享的服務決定。

本 App 已停用 Android 雲端備份，因此資料不會複製到您的 Google 帳戶。

### 刪除資料

您可以在 App 內刪除單筆行程或清除所有行程。解除安裝 RideFlux，或在 Android 設定中清除其
儲存空間，就會從手機永久移除它的所有資料。

### 兒童

RideFlux 並非以 13 歲以下兒童為對象，也不會在知情的情況下處理兒童的資料。

### 政策變更

政策如有變更，會更新本檔案並修改生效日期。完整的修改歷程可在本 repository 的 commit
記錄中查閱。

### 聯絡方式

對本政策有任何疑問，請至 <https://github.com/zero2005x/RideFlux/issues> 提出。
