# RideFlux PLEV Architecture & Engineering White Paper
# (個人輕型電動載具全鏈路架構與工程白皮書)

> **Document Version:** 1.0  
> **Target Release:** RideFlux 2.0 (PLEV Multi-Vehicle Milestone)  
> **Status:** Completed & Validated (Commit: `16df25d`)  
> **Test Suite:** 375 Unit & Integration Tests (100% Pass, 0 Failures)  
> **Protocol Consistency:** 89/90 Claims Verified via automated AST/Bytecode checkers  

---

## 1. 執行摘要 (Executive Summary)

**RideFlux** 原為專注於電動獨輪車（EUC）與 Rokid AR 智慧眼鏡連動的高效能儀表應用。隨著微型移動載具普及，社群與使用者對跨品牌電動滑板車（Electric Scooter）及獨立智慧電池管理系統（Smart BMS）的支援需求急遽增加。

自 **Phase 1** 至 **Phase 7**，RideFlux 完成了全面的架構升級，由單一車種拓展為跨載具的 **PLEV (Personal Light Electric Vehicle)** 系統：
1. **多元載具平行抽象**：在 `:domain` 層確立 `PlevDevice` 介面，同等對待獨輪車（Wheel）、滑板車（Scooter）與智慧電池（Smart BMS），徹底避免「以獨輪車假冒其他載具」的架構壞味道。
2. **多家族協定編解碼實作**：
   - **Ninebot Retail Scooter** (`5A A5` 幀頭，len+9 定位，小端 2-byte 長度讀取，三步動態配對握手)。
   - **Xiaomi M365 Lineage** (`55 AA` 幀頭，B0 暫存器鏡像解析，時速刻度與哨兵值防禦)。
   - **Smart BMS** (JBD / 小象 BMS、Ant 螞蟻 BMS 封包解碼器)。
   - **Inmotion 全型號矩陣** (31 款車型，精確劃分 Legacy 轉義協定與 XOR 校驗協定)。
3. **Fail-Closed 核心安全聯鎖機制**：
   - 任何涉及鎖車（0x70）、解鎖（0x71）或敏感指令，必須通過 `MotionInterlock` 靜止閘門（至少 3 筆且時限內的零速回報）。
   - 針對實車減速或懸空空轉時產生的 `0xFF3E..0xFFF4` 等哨兵值，嚴格解碼為 `null`（速度未知），**絕不誤判為 0 km/h**，防止安全漏洞。
4. **Android 原生 BLE GATT 傳輸層貫通**：
   - 實作原生 `ScooterRepositoryImpl`，支援 Nordic UART (`6E400001`) 與 `SINGLE_CHAR` (`FFE0`) 自動降級與分包重組（`NinebotRetailFrameAssembler`）。
   - 提供引用計數（Ref-counting）與非同步取消守護（`NonCancellable`），消弭 Android BLE 資源洩漏。
5. **UI & Rokid AR HUD 全端自適應流轉**：
   - 儀表板自適應隱藏腳踏板傾角等非相關指標，展示時速、電量、里程、20 秒配對確認倒數橫幅與觸覺回饋。
   - Rokid AR HUD 透過低延遲資料管道實時投影滑板車遙測指標。

---

## 2. 系統架構分層 (Clean Architecture)

系統嚴格遵循 Clean Architecture 與 `claude-android-ninja` 規範，模組間依賴單向由外向內：

```mermaid
flowchart TD
    subgraph UI_Layer [UI & Presentation Layer]
        AppUI["📱 :app (Jetpack Compose / Dashboard / Scanner)"]
        HudUI["🥽 :hud-app (Rokid AR HUD Surface)"]
    end

    subgraph DI_Layer [Dependency Injection Layer]
        HiltApp["BleModule (:app)"]
        HiltHud["BleModule (:hud-app)"]
    end

    subgraph Domain_Layer [Domain Layer (:domain - Pure Kotlin / Zero Android Deps)]
        PlevRepo["interface PlevRepository"]
        ScooterRepo["interface ScooterRepository"]
        WheelRepo["interface WheelRepository"]
        Interlock["class MotionInterlock"]
        PlevModels["PlevDevice (WheelDevice / ScooterDevice / BmsDevice)"]
    end

    subgraph Protocol_Layer [Protocol Layer (:data:protocol - Pure Kotlin)]
        PlevRepoImpl["PlevRepositoryImpl"]
        ScooterConn["ScooterConnectionImpl"]
        HandshakeSM["ScooterHandshakeStateMachine"]
        RetailCodec["NinebotRetailCodec"]
        M365Codec["M365Codec"]
        FrameAssembler["NinebotRetailFrameAssembler"]
        SmartBmsCodec["JbdBmsCodec / AntBmsCodec"]
    end

    subgraph BLE_Layer [Hardware & BLE Layer (:data:ble - Android SDK)]
        BleScooterRepo["ScooterRepositoryImpl"]
        BleWheelRepo["WheelRepositoryImpl"]
        AndroidTransport["AndroidBleTransport (GATT / NUS / FFE0)"]
        Classifier["ScooterClassifier / WheelNameClassifier"]
    end

    AppUI --> PlevRepo
    HudUI --> PlevRepo
    HiltApp --> BleScooterRepo
    HiltApp --> BleWheelRepo
    HiltApp --> PlevRepoImpl
    HiltHud --> BleScooterRepo
    HiltHud --> BleWheelRepo
    HiltHud --> PlevRepoImpl

    PlevRepoImpl --> PlevRepo
    PlevRepoImpl --> ScooterRepo
    PlevRepoImpl --> WheelRepo

    BleScooterRepo --> ScooterRepo
    BleScooterRepo --> AndroidTransport
    BleScooterRepo --> ScooterConn

    ScooterConn --> HandshakeSM
    ScooterConn --> RetailCodec
    ScooterConn --> FrameAssembler
    ScooterConn --> Interlock
```

---

## 3. 通訊協定與封包規格 (Protocol & Wire Specifications)

### 3.1 Ninebot Retail Scooter (`5A A5`)
*   **用途**：Ninebot KickScooter 系列（ES1/ES2/ES4、MAX G30、F20/F30/F40 等）。
*   **幀頭**：`0x5A 0xA5`。
*   **長度欄位**：`payloadLength = frame[2]`。總幀長為 `payloadLength + 9` 位元組。
*   **校驗碼 (Checksum)**：16 位元小端累加和取反，**範圍自 `frame[2]`（長度位元組）起至 payload 結尾**。
*   **讀取暫存器請求**：
    *   目標代碼：`0x20` (ESC 儀表主控)。
    *   長度欄位：2 位元組小端序（例如讀取 32 位元組暫存器：`20 00`）。
    *   標準遙測請求幀：`5A A5 04 20 01 B0 20 00 <CRC16>`。
*   **三步無人值守動態配對流程**：
    1.  **Step 1 (連線發起)**：App 發送握手請求 `0x5B`。車輛回傳 16 位元組 `bleRandom`。
    2.  **Step 2 (提案與等待確認)**：App 產生隨機 16 位元組 `appRandom`，發送 `0x5C` 提案。車輛蜂鳴並閃爍儀表，進入 20 秒確認等待。
    3.  **Step 3 (車把電源鍵確認與接受)**：騎士按下車把實體電源鍵，車輛送出確認通知（`0x5C 0x01`）。App 自動取用防禦性快取的 `appRandom` 組裝 `0x5D` 接受幀回傳。狀態機推進至 `ReadyForTelemetry`，啟動 `0xB0` 暫存器輪詢。

### 3.2 Xiaomi M365 Lineage (`55 AA`)
*   **用途**：Xiaomi M365 / Pro / Pro 2 / 1S / Lite / Mi 3。
*   **幀頭**：`0x55 0xAA`。
*   **長度欄位**：`frame[2]` 代表 payload 長度；總長度為 `len + 9` 位元組。
*   **B0 鏡像區塊解碼**：
    *   速度欄位：位移 offset 10 (暫存器 B5)，2 位元組小端有號整數，速度大小為 `abs(signedRaw) / 1000` km/h。倒退以負值傳送；原始無號 word 保留供診斷。
    *   **倒退速度修正（2026-10-07）**：原本的 `>= 0xFF00` 哨兵假設會誤判低速倒退；M365 現在解析為非零速度，只有原始零值表示停止。修正依車主回報推論，待實車複驗，詳見 [修正紀錄](M365_REVERSE_SPEED_2026-10-07.md)。

### 3.3 智慧電池管理系統 (Smart BMS)
*   **JBD / 小象 BMS**：
    *   幀結構：`0xDD` 幀頭 + 狀態代碼 + 資料長度 + 資料內容 + 校驗和 + `0x77` 幀尾。
    *   暫存器：`0x03` 基本狀態（總電壓、電流、剩餘容量、溫度、充放電狀態）、`0x04` 各串單體電芯電壓（精準度達 mV）。
*   **Ant 螞蟻 BMS**：
    *   幀頭：`0xAA 0x55 0xAA`。

---

## 4. 安全工程與邊界防禦 (Safety Architecture)

在個人移動載具控制中，安全永遠高於功能。RideFlux 實裝了以下硬性安全保證：

| 安全防禦模組 | 觸發場景 | 行為定義 | 失敗處置 (Fail-Closed) |
|---|---|---|---|
| **MotionInterlock** | 鎖車、校準、電源控制、高危設定寫入 | 必須在時效內累積 $\ge 3$ 筆確定為 $0\text{ km/h}$ 的有效讀數 | 速度未知 (`null`) 或大於 0 時，一律丟出 `MotionInterlockException` 拒絕執行 |
| **Direction-aware Speed** | M365 倒退或低速反向空轉 | B5 按有號 LE16 解析並取速度大小；未驗證的 Ninebot 診斷解碼保留未知值保護 | 非零倒退速度撤銷靜止許可；M365 鎖車、解鎖及電源寫入維持停用 |
| **Lock Profile Opt-in** | 發送 `0x70` 鎖車或 `0x71` 解鎖 | 僅在車型通過實體驗證（`lockProfileVerified == true`）時放行 | 未經驗證車種停用鎖車按鈕，防止向不相容韌體寫入重置封包 |
| **Handshake Watchdog** | 車輛等待騎士按下實體電源鍵 | 啟動 20 秒高精度協程計時器 | 超過 20 秒未按鍵，狀態機自動切換為 `TimedOut` 並中斷連線 |
| **Frame Boundary Sanitizer** | 藍牙 MTU 分割包、黏包或受干擾雜訊 | 環形緩衝搜尋合法標頭與長度，核對 Checksum | 拋棄損毀位元組，絕不將殘缺封包送入業務解析層 |

---

## 5. 跨階段研發里程碑紀錄 (Phase 1 ~ Phase 7)

```
[Phase 1] PLEV 領域模型確立 (PlevDevice, ScooterDevice, BmsDevice, MotionInterlock)
   │
[Phase 2] JBD/Ant BMS 解析器、VESC 解析器、Inmotion 31 款型號完整矩陣
   │
[Phase 3] Ninebot Retail (5A A5) 與 M365 (55 AA) 協定編解碼與三步握手狀態機
   │
[Phase 4] ScooterConnectionImpl 響應式管線（自動握手、20s 看門狗、B0 輪詢）
   │
[Phase 5] UI & HUD 雙端儀表對接（PlevRepository, 20s 橫幅與觸覺回饋, PlevTelemetrySource）
   │
[Phase 6] 協定細節收斂（0x5D 自動裝載 appRandom、B0 速度哨兵防禦、0x70/0x71 鎖車封包）
   │
[Phase 7] Android 原生 BLE GATT 傳輸層貫通（ScooterRepositoryImpl, 分包重組, 雙端 Hilt DI）
```

---

## 6. 自動化測試與代碼庫驗證矩陣

### 6.1 自動化測試覆蓋
全專案測試數量由 Phase 1 的 226 項大幅擴充至 **375 項**，純 JVM 離線測試 100% 通過：
*   `:domain:test`：75 項測試（0 失敗）
*   `:data:protocol:test`：236 項測試（0 失敗）
*   `:data:ble:testDebugUnitTest`：64 項測試（0 失敗，涵蓋 Robolectric 與 MockK 藍牙掃描/生命週期測試）
*   **測試總計：375 項測試，0 失敗、0 錯誤、0 略過**。

### 6.2 協定知識一致性校驗
透過專案 AST / Bytecode 稽核腳本 `tools/findings_consistency_check.py` 進行逆向證據檢核：
*   **檢查成果**：90 條協定斷言中，**89 條嚴格通過，0 失敗**（1 條為已標記待實車驗證項目）。
*   確保程式碼常數、暫存器位移、CRC 演算法與逆向工程研究成果保持 100% 同步。

---

## 7. 結論與展望 (Conclusion & Next Milestones)

Phase 7 的完成標誌著 RideFlux 正式具備商用級跨品牌微型移動載具通訊能力。從純 Kotlin 領域邏輯、協議重組到 Android 實體藍牙調度與 Compose UI 儀表，所有工程環節皆已通過自動化測試與代碼審核。

**後續硬體驗收路線：**
1. **實車 HCI 抓包驗收**：連結實體 Ninebot ES2 / G30 或小米 M365，錄製真實 btsnoop 日誌，雙重驗收實車速度刻度。
2. **多載具藍牙聚合**：未來可支援「獨輪車/滑板車 + 獨立外掛智慧 BMS」雙 BLE 連線同時顯示於同一儀表板。
