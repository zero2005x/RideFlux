# RideFlux glossary — fixed terminology for the 18 locales / 術語表

[← Localization](LOCALIZATION.md) · [← 說明文件首頁](README.md)

---

## English

### Why this file exists

RideFlux ships **18 locales** and several of its terms name things a rider acts on: PWM, tilt-back,
motor cutoff, regenerative braking, calibration, the zero-speed interlock. A term that drifts between
locales — or a translation that reads as *"the wheel limits speed"* where the English says *"the wheel
cuts motor power"* — is a safety defect, not a style defect.

`LocalizationCoverageTest` (`app` and `hud-app`) enforces **key completeness**: every locale defines
exactly the translatable key set of `values/strings.xml`, no blanks, and identical format specifiers.
It cannot enforce **word choice**. This file is the only guard for that, so it is the reference to
open before translating a protocol or safety string.

### How to use it

1. Find the term in §2 (term index) — it lists the shipped key(s) each term is anchored to.
2. Open your language in §3 and use the translation given there, verbatim.
3. If the string you are writing is **not** one of these terms, translate normally and keep the
   glossary terms that appear inside it consistent with §3.
4. If a term has no good equivalent in your language, leave the English acronym and say so in your PR
   (see the `BMS`/`PWM` rows — those are deliberately untranslated).

### Provenance and the `SAFETY` flag

| label | meaning |
|---|---|
| `hand` | English and Traditional Chinese: written by hand for this file, not bulk machine output. Traditional Chinese is the project's working language, so it is the cheapest one for a human to check. **Neither has been owner-reviewed yet** |
| `machine` | the other 16 locales: machine-translated. Correct but not native-reviewed |
| `shipped` | the value is already in the app's `values-*/strings.xml`, so it is what users see today |
| `new` | translated for this glossary in T12; **no shipped string uses it yet** |
| `acronym` | deliberately not translated in any locale (BMS, PWM) |

**`SAFETY`** marks a term where a mistranslation could change what a rider does. Those rows need a
**native-speaker check before release**; they are listed in one place so that check can be scheduled:

> `cell` · `PWM` · `tilt-back` · `cutoff` · `regen` · `pedal sensitivity` · `lock / unlock` ·
> `calibrate` · `power off` · `zero speed`

Non-safety terms in this glossary: `BMS` (an acronym, and the surrounding sentence carries the
meaning) and `odometer` (a distance readout).

---

### §2 Term index

| # | term (English) | SAFETY | anchor key(s) in the apps | note |
|---|---|---|---|---|
| 1 | BMS | – | inside `bms_per_cell_body` ("Smart-BMS") | acronym; **never translated** |
| 2 | cell (battery cell) | yes | `section_cells`, `bms_per_cell_title` | not "battery" and not "element" |
| 3 | PWM | yes | `metric_pwm`, `settings_pwm_load`, `hud_limit_pwm` | acronym; **never translated** |
| 4 | tilt-back | yes | `event_tilt_back`, `alert_tilt_back_title` | the pedal push-back, not a "reminder" in the generic sense |
| 5 | cutoff (motor cutoff) | yes | `event_speed_cutoff`, `alert_speed_cutoff_title`, `alert_speed_cutoff_body` | the motor *stops driving*; never "speed limit" |
| 6 | regen (regenerative braking) | yes | **no shipped key yet** | new term; see §3 |
| 7 | odometer (total distance) | – | `metric_total_distance` | the app says "Total distance"; "odometer" is the term of art |
| 8 | pedal sensitivity | yes | `hud_pedals_mode`, `pedals_soft`/`pedals_medium`/`pedals_hard` | the three levels must stay a matched triple |
| 9 | lock / unlock (the vehicle) | yes | **no shipped key yet** | see the trap below |
| 10 | calibrate | yes | `controls_calibrate_button`, `dialog_calibrate_title`/`_warning`/`_confirm` | level: on a flat surface, not mounted |
| 11 | power off | yes | `controls_power_off_button`, `dialog_power_off_title`/`_warning`/`_confirm` | the *vehicle*, not the phone |
| 12 | zero speed (stationary) | yes | `controls_safety_locked_moving` | the interlock condition for dangerous commands |

#### Two traps this index exists to prevent

- **`Unlock` in `bond_reauth_subtitle`/`bond_reauth_unavailable` is the *phone* screen lock**, not the
  vehicle. Do not reuse those translations for the vehicle lock/unlock of term 9, and do not reuse
  term 9 for the phone. In several locales the natural word is the same, so the *string* must make the
  object explicit ("unlock the vehicle", not "unlock").
- **`cutoff` is not a speed limit.** `alert_speed_limit_*` and `alert_speed_cutoff_*` are two different
  events: exceeding a configured limit versus the motor actually cutting out. Keep the two words
  distinct in every locale, exactly as the shipped translations already do.

---

### §3 Fixed translations, by locale

Every table below lists the same 12 terms. `src` is the provenance label from the table above.
`hud-app` uses a smaller string set (52 keys); where a term also appears there, the same word is used
in both apps.

Every cell marked `shipped` was **extracted from the current `values-*/strings.xml` files by key**
(not retyped from memory), so it matches what the app ships today. Cells marked `new` have no shipped
string and were translated for this glossary; they are the rows the native-speaker check should look
at first. Composed rows (term 8, pedal sensitivity) keep each locale's own punctuation style —
full-width `（）` and `／` for Chinese and Japanese — and join the three shipped level words
`pedals_soft` / `pedals_medium` / `pedals_hard`; the English row names the shipped label in
parentheses where it differs from the term (plural, capitalisation).

#### English (default) — `values` — `hand`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Cell (shipped label: "Cells") | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Speed cutoff | shipped |
| 6 | regen | Regen (regenerative braking) | new |
| 7 | odometer | Total distance (shipped label: "Total Distance") | shipped |
| 8 | pedal sensitivity | Pedals mode (Soft / Medium / Hard) | shipped |
| 9 | lock / unlock | Lock / Unlock | new |
| 10 | calibrate | Calibrate | shipped |
| 11 | power off | Power off | shipped |
| 12 | zero speed | Zero speed (stationary) | new |

#### Traditional Chinese — `values-zh-rTW` — `hand`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | 電芯 | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | 回正提醒 | shipped |
| 5 | cutoff | 斷電保護 | shipped |
| 6 | regen | 回充（動能回收煞車） | new |
| 7 | odometer | 總里程 | shipped |
| 8 | pedal sensitivity | 踏板模式（柔和／中等／強硬） | shipped |
| 9 | lock / unlock | 上鎖／解鎖 | new |
| 10 | calibrate | 水平校準 | shipped |
| 11 | power off | 關閉電源 | shipped |
| 12 | zero speed | 零速（靜止） | new |

#### Simplified Chinese — `values-zh-rCN` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | 电芯 | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | 回正提醒 | shipped |
| 5 | cutoff | 断电保护 | shipped |
| 6 | regen | 回充（动能回收刹车） | new |
| 7 | odometer | 总里程 | shipped |
| 8 | pedal sensitivity | 踏板模式（柔和／中等／强硬） | shipped |
| 9 | lock / unlock | 上锁／解锁 | new |
| 10 | calibrate | 水平校准 | shipped |
| 11 | power off | 关闭电源 | shipped |
| 12 | zero speed | 零速（静止） | new |

#### Hindi — `values-hi` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | सेल | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | टिल्ट-बैक | shipped |
| 5 | cutoff | स्पीड कटऑफ़ | shipped |
| 6 | regen | रीजन (पुनरुत्पादक ब्रेकिंग) | new |
| 7 | odometer | कुल दूरी | shipped |
| 8 | pedal sensitivity | पैडल मोड (सॉफ़्ट / मीडियम / हार्ड) | shipped |
| 9 | lock / unlock | लॉक / अनलॉक | new |
| 10 | calibrate | कैलिब्रेट करें | shipped |
| 11 | power off | पावर बंद करें | shipped |
| 12 | zero speed | शून्य गति (स्थिर) | new |

#### Spanish — `values-es` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Celdas | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Corte de velocidad | shipped |
| 6 | regen | Frenada regenerativa | new |
| 7 | odometer | Distancia total | shipped |
| 8 | pedal sensitivity | Modo de pedales (Suave / Medio / Duro) | shipped |
| 9 | lock / unlock | Bloquear / Desbloquear | new |
| 10 | calibrate | Calibrar | shipped |
| 11 | power off | Apagar | shipped |
| 12 | zero speed | Velocidad cero (detenido) | new |

#### Modern Standard Arabic — `values-ar` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | الخلايا | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | إمالة للخلف | shipped |
| 5 | cutoff | قطع السرعة | shipped |
| 6 | regen | الكبح التجديدي (ريجن) | new |
| 7 | odometer | المسافة الإجمالية | shipped |
| 8 | pedal sensitivity | وضع الدواسات (لين / متوسط / قوي) | shipped |
| 9 | lock / unlock | قفل / إلغاء القفل | new |
| 10 | calibrate | معايرة | shipped |
| 11 | power off | إيقاف التشغيل | shipped |
| 12 | zero speed | سرعة صفر (متوقف) | new |

#### French — `values-fr` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Cellules | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Coupure moteur | shipped |
| 6 | regen | Freinage régénératif | new |
| 7 | odometer | Distance totale | shipped |
| 8 | pedal sensitivity | Mode pédales (Souple / Moyen / Ferme) | shipped |
| 9 | lock / unlock | Verrouiller / Déverrouiller | new |
| 10 | calibrate | Étalonner | shipped |
| 11 | power off | Éteindre | shipped |
| 12 | zero speed | Vitesse nulle (à l'arrêt) | new |

#### Portuguese — `values-pt` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Células | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Corte de velocidade | shipped |
| 6 | regen | Travagem regenerativa | new |
| 7 | odometer | Distância total | shipped |
| 8 | pedal sensitivity | Modo de pedais (Suave / Médio / Firme) | shipped |
| 9 | lock / unlock | Bloquear / Desbloquear | new |
| 10 | calibrate | Calibrar | shipped |
| 11 | power off | Desligar | shipped |
| 12 | zero speed | Velocidade zero (parado) | new |

#### Russian — `values-ru` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Ячейки | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Отклон назад | shipped |
| 5 | cutoff | Отключение мотора | shipped |
| 6 | regen | Рекуперация | new |
| 7 | odometer | Общий пробег | shipped |
| 8 | pedal sensitivity | Режим педалей (Мягкий / Средний / Жёсткий) | shipped |
| 9 | lock / unlock | Блокировка / Разблокировка | new |
| 10 | calibrate | Калибровка | shipped |
| 11 | power off | Выключить | shipped |
| 12 | zero speed | Нулевая скорость (стоит) | new |

#### Ukrainian — `values-uk` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Комірки | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Відхил назад | shipped |
| 5 | cutoff | Відключення мотора | shipped |
| 6 | regen | Рекуперація | new |
| 7 | odometer | Загальний пробіг | shipped |
| 8 | pedal sensitivity | Режим педалей (М'який / Середній / Жорсткий) | shipped |
| 9 | lock / unlock | Блокування / Розблокування | new |
| 10 | calibrate | Калібрування | shipped |
| 11 | power off | Вимкнути | shipped |
| 12 | zero speed | Нульова швидкість (стоїть) | new |

#### German — `values-de` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Zellen | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-Back | shipped |
| 5 | cutoff | Motorabschaltung | shipped |
| 6 | regen | Rekuperation | new |
| 7 | odometer | Gesamtstrecke | shipped |
| 8 | pedal sensitivity | Pedalmodus (Weich / Mittel / Hart) | shipped |
| 9 | lock / unlock | Sperren / Entsperren | new |
| 10 | calibrate | Kalibrieren | shipped |
| 11 | power off | Ausschalten | shipped |
| 12 | zero speed | Stillstand (Geschwindigkeit null) | new |

#### Japanese — `values-ja` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | セル | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | チルトバック | shipped |
| 5 | cutoff | モーター遮断 | shipped |
| 6 | regen | 回生ブレーキ（レゲン） | new |
| 7 | odometer | 総走行距離 | shipped |
| 8 | pedal sensitivity | ペダルモード（ソフト／ミディアム／ハード） | shipped |
| 9 | lock / unlock | ロック／ロック解除 | new |
| 10 | calibrate | キャリブレーション | shipped |
| 11 | power off | 電源オフ | shipped |
| 12 | zero speed | 速度ゼロ（停止） | new |

#### Vietnamese — `values-vi` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Cell | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Ngắt động cơ | shipped |
| 6 | regen | Phanh tái sinh (regen) | new |
| 7 | odometer | Tổng quãng đường | shipped |
| 8 | pedal sensitivity | Chế độ bàn đạp (Mềm / Trung bình / Cứng) | shipped |
| 9 | lock / unlock | Khóa / Mở khóa | new |
| 10 | calibrate | Hiệu chuẩn | shipped |
| 11 | power off | Tắt nguồn | shipped |
| 12 | zero speed | Tốc độ bằng không (đang dừng) | new |

#### Korean — `values-ko` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | 셀 | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | 틸트백 | shipped |
| 5 | cutoff | 모터 차단 | shipped |
| 6 | regen | 회생 제동(리젠) | new |
| 7 | odometer | 총 주행 거리 | shipped |
| 8 | pedal sensitivity | 페달 모드(소프트/미디엄/하드) | shipped |
| 9 | lock / unlock | 잠금 / 잠금 해제 | new |
| 10 | calibrate | 수평 보정 | shipped |
| 11 | power off | 전원 끄기 | shipped |
| 12 | zero speed | 속도 0(정지) | new |

#### Italian — `values-it` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Celle | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Taglio motore | shipped |
| 6 | regen | Frenata rigenerativa | new |
| 7 | odometer | Distanza totale | shipped |
| 8 | pedal sensitivity | Modalità pedane (Morbido / Medio / Rigido) | shipped |
| 9 | lock / unlock | Blocca / Sblocca | new |
| 10 | calibrate | Calibra | shipped |
| 11 | power off | Spegni | shipped |
| 12 | zero speed | Velocità zero (fermo) | new |

#### Dutch — `values-nl` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Cellen | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Motor uitgeschakeld | shipped |
| 6 | regen | Recuperatie | new |
| 7 | odometer | Totale afstand | shipped |
| 8 | pedal sensitivity | Pedaalmodus (Zacht / Gemiddeld / Hard) | shipped |
| 9 | lock / unlock | Vergrendelen / Ontgrendelen | new |
| 10 | calibrate | Kalibreren | shipped |
| 11 | power off | Uitschakelen | shipped |
| 12 | zero speed | Stilstaand (snelheid nul) | new |

#### Indonesian — `values-in` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | Sel | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | Tilt-back | shipped |
| 5 | cutoff | Pemutusan motor | shipped |
| 6 | regen | Pengereman regeneratif (regen) | new |
| 7 | odometer | Jarak total | shipped |
| 8 | pedal sensitivity | Mode pedal (Lembut / Sedang / Keras) | shipped |
| 9 | lock / unlock | Kunci / Buka kunci | new |
| 10 | calibrate | Kalibrasi | shipped |
| 11 | power off | Matikan daya | shipped |
| 12 | zero speed | Kecepatan nol (diam) | new |

#### Urdu — `values-ur` — `machine`

| # | term | translation | src |
|---|---|---|---|
| 1 | BMS | BMS | acronym |
| 2 | cell | سیل | shipped |
| 3 | PWM | PWM | acronym |
| 4 | tilt-back | ٹلٹ بیک | shipped |
| 5 | cutoff | موٹر بند | shipped |
| 6 | regen | ریجن (ری جنریٹو بریک) | new |
| 7 | odometer | کل فاصلہ | shipped |
| 8 | pedal sensitivity | پیڈل موڈ (نرم / درمیانہ / سخت) | shipped |
| 9 | lock / unlock | لاک / اَن لاک | new |
| 10 | calibrate | کیلیبریٹ کریں | shipped |
| 11 | power off | پاور آف | shipped |
| 12 | zero speed | صفر رفتار (کھڑا) | new |

---

### §4 HUD-specific notes (`:hud-app`)

The glasses render at 40 sp on a 320 dp viewport, so the placard strings must stay short, and
`LocalizationCoverageTest` in `:hud-app` additionally bounds their line length. Three HUD limit labels
reuse terms from this glossary and must stay consistent with §3: `hud_limit_pwm` (PWM),
`hud_limit_speed` (Speed, not "speed limit"), `hud_limit_low_battery` (Low battery). `hud_stale`
("STALE") is a freshness badge, not a fault — do not translate it as "error".

---

### §5 Checklist for a new string

1. Does it name a term in §2? Use the §3 translation for your locale, verbatim.
2. Is it marked `SAFETY` in §2? Then it needs the native-speaker check before release — say so in the PR.
3. Add the key to `values/strings.xml` **and** all 17 `values-*/strings.xml` siblings; the coverage
   test fails the build otherwise.
4. Keep placeholders (`%1$s`, `%2$d`) identical to the default in count, type and position.
5. Run `./gradlew testDebugUnitTest` — `LocalizationCoverageTest` is part of it.
6. If a term here is wrong in your language, change it **in this file first**, then in the strings,
   so the next translator inherits the fix.

---

## 繁體中文

### 這份文件的用途

RideFlux 出貨 **18 種語系**，其中多個術語是騎士會據以行事的：PWM、回正提醒（tilt-back）、
馬達斷電（cutoff）、動能回收（regen）、校準、零速互鎖。術語在各語系間漂移，或把「馬達切斷動力」
譯成「限制速度」，是**安全缺陷**，不是風格問題。

`LocalizationCoverageTest`（`app` 與 `hud-app` 各一份）只保證**鍵的完整性**：每個語系的可翻譯鍵
集合與 `values/strings.xml` 完全一致、沒有空白值、格式化參數相符。它**無法**檢查用詞。因此用詞
一致性只靠本檔把關——翻譯協定或安全字串前，請先開這份文件。

### 規則

1. 先查 §2 術語索引，找到該術語在 App 中對應的鍵。
2. 到 §3 找到你的語言，**逐字**使用表內的翻譯。
3. 你寫的字串若不屬於這些術語，照常翻譯，但其中出現的術語仍須與 §3 一致。
4. 標記 `SAFETY` 的術語，其誤譯可能改變騎士的判斷，**發行前需要母語者複核**。
5. `shipped` 表示該值已存在於 App 的 `values-*/strings.xml`，也就是使用者現在看到的字；
   `new` 表示本檔案（T12）新譯、尚未有任何字串使用；`acronym` 表示刻意不翻譯（BMS、PWM）。
6. 若你認為某個術語在你的語言譯錯了：**先改本檔**，再改字串，讓下一位翻譯者承接修正。

英文與繁體中文為手寫（`hand`）；其餘 16 種語系為機器翻譯（`machine`），正確但未經母語者複核。

### 兩個必須避免的陷阱

- `bond_reauth_subtitle`／`bond_reauth_unavailable` 裡的 "Unlock" 指的是**手機螢幕鎖**，不是車輛。
  車輛的上鎖／解鎖不得沿用那兩條翻譯，反之亦然；字串本身要把對象寫清楚。
- `cutoff` 不是速度上限。`alert_speed_limit_*`（超過設定上限）與 `alert_speed_cutoff_*`
  （馬達真的斷電）是兩個事件，各語系都必須維持現有字串所做的區分。
