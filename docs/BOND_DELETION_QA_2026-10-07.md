# 配對金鑰單筆刪除與布局驗證

## 變更

- 每筆列表項目有 48 dp 刪除按鈕；TalkBack 描述使用翻譯資源，包含名稱與遮罩 MAC。名稱、MAC、family 分別使用 titleMedium、bodyMedium、labelMedium，摘要占剩餘寬度。長名稱可省略，MAC 保持 LTR；RTL 操作鏡像。
- 點擊只開啟確認對話框。確認才刪所選 MAC；取消保留資料。說明提示可能需重新配對，並會關閉該車的現有連線。busy 阻擋重複刪除／匯入／新增，失敗保留對話框供重試。
- 刪除先停止該 MAC 的連線並等待其子工作結束，再移除加密儲存；連線互斥鎖阻擋同時重新連接。其他車輛、橋接配對、行程及設定不移除。成功同步列表與匯出選取；丟棄刪除前啟動的過期列表讀取。
- 對話框標題、內容及操作在同一捲動容器，保留 safe drawing／IME padding；操作垂直排列。頁面錯誤提示改為可換行、可捲動的卡片，避免 Toast 限制。沒有調降全 App 字級或停用系統字體倍率。
- 新增刪除及 hex 提示的 18 語言翻譯；修正義大利文口令用語。匯出筆數與略過筆數使用 plurals，保留索引參數與各語系數量規則。
- 加密格式及原子替換維持不變；修正覆寫時舊 credential copy 未清零的問題。頁面 FLAG_SECURE 保留，對話框明確 SecureOn。RFBOND wire format、60 秒身份確認時限及登入協定未修改。

## 儲存／連線來源核對

`EncryptedFileBondStore.remove(mac)` 已正規化 MAC，以 mutex 包住讀取、過濾及加密原子寫入，並在 finally 清零所有讀入的 credential。正式 ViewModel 使用 `ScooterRepository.removePairingKey`，不是只刪檔案而留下活動連線。

Xiaomi 的 `MiScooterConnection` 從 BondStore.list 讀取 token，註冊完成後可寫回 BondStore；因此必須 cancelAndJoin 該連線工作後才 remove。Ninebot 現行登入每次產生 app random，沒有另一份連線偏好設定或舊資料遷移來源。持久化 app random 的移除不改變 Ninebot 的登入協定。其他保留的 discovery/name 快取只有車輛識別資料，沒有金鑰。未修改 M365-Rokid-HUD 專案。

## 自動測試與資源

| 驗證 | 結果 |
| --- | --- |
| BondBackupViewModelTest | 25 tests，0 failures／errors |
| EncryptedFileBondStoreTest | 9 tests，0 failures／errors |
| app 全套單元測試 | 207 tests，0 failures／errors／skipped |
| data:preferences 全套單元測試 | 25 tests，0 failures／errors／skipped |
| data:ble 全套單元測試 | 103 tests，0 failures／errors |
| locale 掃描 | default 與 17 locale 目錄；每個 locale 338 個可翻譯資源；key／重複 key／resource type／參數索引、型別、數量／plurals 分支檢查通過；bond 字串無英文複製 |
| 資源編譯、debug lint、signed release build | 通過 |

測試涵蓋確認才刪、取消不改、所選 MAC／最後一筆／選取同步、失敗原檔不變與重試、busy 重複操作與同時匯入被阻擋、過期列表讀取、重開 store 不復活且其他 credential 不變、取消註冊工作後才刪、等待刪除期間禁止新連線，以及舊連線 handle 不影響新連線。

可重跑：`python tools/check_bond_locales.py`；Gradle tasks：`:app:testDebugUnitTest :data:preferences:testDebugUnitTest :data:ble:testDebugUnitTest :app:lintDebug :app:assembleRelease`。

完整三個 module 共 335 tests 通過，沒有 skipped。上方 ViewModel／store 數字是其各自 suite 的子集合。

## 裝置矩陣

語言完整集合：en、ar、de、es、fr、hi、id、it、ja、ko、nl、pt、ru、uk、ur、vi、zh-CN、zh-TW。id 對應資源目錄 values-in。

| 裝置／寬度 | font scale 1.0 | 1.3 | 2.0 |
| --- | --- | --- | --- |
| Redmi Note 11 Pro+ 5G，Android 13／API 33，320 dp（880×1760 px、440 dpi） | 全 18 語言通過 | 全 18 語言通過 | 全 18 語言通過 |
| 同裝置原始尺寸：Compose screenWidthDp=392（1080×2400 px、440 dpi） | zh-TW 通過 | 未重跑 | 未重跑 |

320 dp 共 54 組全部通過。原始尺寸繁中追加一組，共 55 組。2.0 初版測試曾在手動新增的鍵盤 resize 動畫期間失敗；測試改為等待真正 IME window 的邊界穩定，再檢查原本的操作可見／觸控斷言，完整 18 語言重跑通過。沒有放寬可見性斷言或透過關閉鍵盤避過此情境。

USB 曾暫時中斷；本機 emulator 只啟動後即關閉，沒有納入通過數字或操作其設定。所有矩陣結果皆來自 Redmi。

測試套件使用獨立 applicationId `com.rideflux.layoutqa`；完全使用 synthetic UI state，不讀取實際 BondStore。語言使用 Android Configuration 的 locale resource context，字體使用 Compose LocalDensity；每輪也實際設定 system font_scale 為對應倍率。IME、觸控、window bounds 都來自實際裝置。這驗證資源與 Compose 布局，不等同逐語言測試系統語言選單。

獨立 QA 建置可重跑：`./gradlew.bat --init-script tools/bond-layoutqa.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest`。安裝上述 debug／androidTest APK 後，runner 為 `com.rideflux.layoutqa.test/androidx.test.runner.AndroidJUnitRunner`，class 為 `com.rideflux.app.ui.bond.BondBackupLayoutTest`；`qaFontScale` 可指定 1.0／1.3／2.0，`qaLocale` 可選單一語言，不指定 locale 會跑全部 18 種。需先記錄及最後還原裝置 wm size／density／system font_scale；正式 release 建置不使用此 init script。

測試內容：混合中英與 emoji、日本／韓文、長法文、阿拉伯文、64 code-point emoji 名稱、空名稱；6 筆多筆匯入／衝突預覽；風險、刪除解釋與重試錯誤、口令弱／不一致／錯誤、取代確認、手動輸入與取消／送出、空列表／載入失敗／I/O 提示、0／1／2／3／11／21 的匯出 plural。使用 Compose semantics／TextLayoutResult 確認完整文字、無省略，48 dp 刪除觸控區、摘要不重疊、RTL 位置；使用真正 IME window 確認取消操作不在鍵盤下，並觸控取消／送出。

## 原 App 實機清理與設定

原 App 為 0.1.10（versionCode 11）；修正版維持版本號，release 簽章已核對與原 APK 相同，已透過 install -r 更新並保留資料。沒有匯出金鑰、清除 App 資料或卸載原 App。

已核對並透過新刪除 UI 移除：`HUDtestMi`／遮罩尾碼 `EE:FF`、`HUDtestNinebot`／遮罩尾碼 `60:06`。初始頁面只列這兩筆。

1. 開啟 Mi 刪除確認，再按取消；兩筆資料與兩個匯出選取皆不變。
2. 再核對 Mi 名稱／遮罩，確認刪除；Ninebot 名稱／遮罩與匯出選取保留。
3. 核對 Ninebot 名稱／遮罩，確認刪除最後一筆；顯示空列表，匯出按鈕停用。
4. force-stop 並重開 App，再進入金鑰頁；空列表保持，兩筆皆無復活。

正式頁面與刪除對話框的 dumpsys window flags 均包含 SECURE。UI hierarchy 只核對名稱、遮罩及操作，沒有顯示或取得 credential。

Redmi 原設定與最終核對結果皆為：1080×2400 px（無 override）、440 dpi、font scale 1.0、系統 zh-TW、RideFlux locale zh-TW。QA／test 套件已移除，生成的裝置 XML 已清除，手機留在 Home。已通知 HUD 工作 ADB 釋出；後續不再操作手機。眼鏡未操作。

## 驗證界限

沒有對金鑰頁截圖、錄影或關閉 FLAG_SECURE。布局結果來自語意、文字布局、邊界與觸控檢查，沒有聲稱完成截圖視覺審查、各語言母語者審校、TalkBack 實際語音播放、字形／色彩主觀檢查或各型車實際登入回歸。原始尺寸的大字體沒有另外重跑，通過矩陣明確列於上表。沒有修改或重新測試 RFBOND 交換協定。

64 code-point emoji 名稱是 UI-only 壓力資料；不代表儲存接受 64 個 surrogate-pair emoji。既有 BondEntry 的 64 字元限制使用 String.length（UTF-16 code units），本次沒有變更 wire format／驗證規則或聲稱通過該名稱的實際匯入／持久化。
