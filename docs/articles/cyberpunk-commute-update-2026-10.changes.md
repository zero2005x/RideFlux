# 修改清單：〈Cyberpunk Commute〉更新稿

對應稿件：[cyberpunk-commute-update-2026-10.md](cyberpunk-commute-update-2026-10.md)（英文，可直接貼進 Medium；同資料夾另有一份貼上用的 `.medium.html`）
原文：<https://medium.com/@20x05zero/cyberpunk-commute-building-an-ar-heads-up-display-for-the-inmotion-v5f-2d5264bb451e>（2025-12-23）

原文寫的是**更早的版本**：本 repo 的 git 歷史從 2026-08-14 才開始，目前程式碼裡已經沒有 `Presentation`、`DisplayManager`、Mapbox，也沒有文章裡的 `onCharacteristicChanged` 解析方式。更新稿保留原本的結構與口吻，只改過時的技術內容，並把進展放在最後。

## 1. 逐段對照

| 原文段落 | 處理 | 依據 |
|---|---|---|
| 標題 | **不動**（保留網址與 SEO）。若想換，建議把「for the Inmotion V5F」改成「for Electric Unicycles」。 | — |
| 副標 | 加上「現在是 Google Play 上的開源 App」；**沒有寫 free**（我無法確認售價）。 | Play 頁面 |
| （新增）頂端 Update 引言 | 說明發表於 2025-12、之後重建、上架 Google Play。 | — |
| The "Why" | 幾乎逐字保留；修正 `phone(` 空格、`25km/h`；「Rokid Air」改成「我的第一副眼鏡」。 | 原文 |
| Turning Point | 保留你對 WheelLog 技術堆疊的看法與「決定重寫」；**新增「A word on WheelLog」段**，說明解碼器參考了開源專案、部分測試資料取自 WheelLog、NOTICE 有致謝、專案為 GPL-3.0-or-later。 | `README.md`「Where the protocol knowledge comes from」、[NOTICE](../../NOTICE)、`InmotionI1RealFrames.kt` |
| Gear List | 改成「V5F（第一版的測試車）＋ Begode A2（現在的主力、唯一實機驗證過新版的車）」；眼鏡改成「Rokid Air（第一版）→ 現在跑獨立 Android App 的 Rokid 眼鏡」。 | 你的回答；`hud-app/` |
| Technical Challenge | 兩個難題擴成三個，新增第三個「手機到眼鏡的連線」。 | `docs/BRIDGE_PROTOCOL.md` |
| Part 1（破解 Inmotion 協定） | **整段改寫**。舊的 `rawData[4]/[5] / 100` 片段刪除，因為與現在的 I1 解碼器不符（現在用校正常數 3812、處理跳脫位元組與有號數）。改講三個真實教訓：只廣播名稱、跳脫位元組與被切斷的通知、有號 32 位元。附兩段**真實**程式碼（測試與 `WheelCodec` 介面，標明 abridged）。 | `InmotionI1SignednessTest.kt`、`InmotionI1SplitEscapeTest.kt`、`WheelCodecFactoryImplTest.kt`、`WheelCodec.kt` |
| Part 2（Compose 設計 AR 介面） | 「黑色即透明」「對比」兩條保留並補上實際色票與鏡像開關；**「重要資訊略偏離中心」這條拿掉**（現在是三區版面，速度在正中央）；新增「警示不可忽略、不可誤關」一條與真實程式碼。**Presentation API 那句改成過去式**並說明已移除。 | `HudScreen.kt`（背景、鏡像、警示紅框、`suppressed`） |
| （新增）Part 3 手機到眼鏡 | 20 位元組封包、配對權杖取代 MAC、`addService()` 非同步、掃描預算、靜默連線監看。 | `docs/BRIDGE_PROTOCOL.md` |
| The Result: The First Ride | **保留**，並加一段說明「那是第一版、V5F」，現在騎的是 A2。 | 你的回答 |
| Future Steps | 改成「我說過要做的 vs 實際發生的」：Mapbox 導航 → 擱置（App 無網路權限）；警示變色 → 已做、做法不同（閾值監看＋整圈閃爍紅框）。 | `PRIVACY.md`、`HudScreen.kt`、`ThresholdMonitor` |
| 結尾連結 | 原文重複貼了兩次 GitHub 連結，**合併**，並加上 Google Play 與網站。 | — |
| （新增）Where RideFlux Is Today | Google Play、18 語言、行程記錄與匯出、指環、隱私、開源；**誠實的支援清單**（只有 A2 實機驗證，其餘實驗性，包含 V5F；用條列而非表格）；徵求實測回報。 | 與網站、README 一致 |
| 免責聲明 | 新增「獨立開發、與車廠及 Rokid 無關」與「騎乘時請勿操作手機」。 | Play 商店頁 |

## 2. 沿用你的原文、我**無法驗證**的說法

這些我保留了，但它們是你個人的經驗，repo 裡沒有證據能佐證：

1. 「Through some trial and error, I identified the right service and read characteristic UUIDs for the V5F」：我加了「In the first version」限定。
2. 第一次試騎、爬坡時觀察電壓下陷，以及「改變了騎乘的安全性」。
3. 第一版用 Rokid Air 經 USB-C 接手機、用 Presentation API 投影到副螢幕。

## 3. 發佈前請你確認

- [x] **眼鏡型號**：已依你的更正寫成 Rokid Glasses RV101（獨立運行、無線、非 USB-C）。
- [ ] **第一版的 Rokid Air／USB-C 描述**：沿用你原文的說法並標明是「first version, as originally described」，請確認你接受這種過去式寫法。
- [x] 與 PR #33（語言選單、每副眼鏡的 HUD 自訂）無關：本稿**沒有**把任何 #33 的功能寫成已上架。
- [ ] **「現在的主力是 Begode A2」**：依你的回答寫成，請確認措辭你能接受。
- [ ] **Mapbox「擱置」**：事實是 App 沒有網路權限；「擱置」是我替你下的結論。
- [ ] **對 WheelLog 的看法**：我保留了「無法忍受舊技術堆疊」，同時加了致謝。語氣若想更緩和可改。
- [ ] **「三個教訓」是你學到的嗎**：它們是真實修過的 bug（見 `fix/inmotion-i1-real-trace-bugs`），但我是從程式碼與測試推回來的。
- [ ] **V5F 狀態**：新稿明說「新版尚未在 V5F 上實機驗證」，與網站一致。若你其實有在新版上測過，請告訴我，網站與 README 也要一起改。
- [ ] **日期說法**：我沒有寫「2026 年 8 月重寫」這類具體日期（repo 歷史從 2026-08-14 開始，但那不一定等於重寫日）。

## 4. 我刻意不寫的內容

- 不寫「支援 V5F」「支援所有品牌」（網站與 README 都是分級標示）。
- 不寫價格、下載數、延遲或效能數字（沒有依據）。
- 不寫「應用內語言選單」與「每副眼鏡的 HUD 自訂」：那是 PR #33，尚未合併，也不在 Play 上架的 v0.1.10 裡。文章只寫「18 種語言」（跟隨裝置語言）這個已上架的行為。
- 不放任何金鑰、序號或你裝置的 MAC 位址。

## 5. 貼進 Medium

1. 用瀏覽器開 `cyberpunk-commute-update-2026-10.medium.html`，全選複製，貼進 Medium 編輯器（標題、粗體、斜體、連結、條列與程式碼區塊會保留）。稿子裡**刻意沒有表格**，因為 Medium 不支援表格。
2. 文中的圖要自己上傳。建議兩張：`docs/play-store/phone/en-US/01-dashboard.png`（儀表板）與 `.../06-hud.png`（HUD 畫面，註明為範例資料）。
3. 若直接**編輯原文章**，Medium 會保留網址與既有的推薦數；若想當成新文章發，請在頂端放原文連結。
