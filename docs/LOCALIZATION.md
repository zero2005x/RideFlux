# Localization / 多國語系

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← 說明文件首頁](README.md)

---

## English

How the 18 translations are organised, tested and extended.

### Localization

Both apps carry a full translation set. `values/strings.xml` is the source of truth; every
`values-<qualifier>/strings.xml` mirrors its translatable key set exactly.

| Language | Qualifier | Language | Qualifier |
|---|---|---|---|
| English (default) | `values` | Urdu | `values-ur` |
| Mandarin, Simplified | `values-zh-rCN` | German | `values-de` |
| Mandarin, Traditional | `values-zh-rTW` | Japanese | `values-ja` |
| Hindi | `values-hi` | Vietnamese | `values-vi` |
| Spanish | `values-es` | Korean | `values-ko` |
| Modern Standard Arabic | `values-ar` | Italian | `values-it` |
| French | `values-fr` | Ukrainian | `values-uk` |
| Portuguese | `values-pt` | Dutch | `values-nl` |
| Russian | `values-ru` | Indonesian | `values-in` |

Two qualifier traps worth knowing before you add a locale:

- **Indonesian is `in`**, the legacy ISO 639-1 code. Android does not resolve `values-id`.
- **Mandarin needs both `zh-rCN` and `zh-rTW`.** Neither is a fallback for the other.

`LocalizationCoverageTest` (one per app) runs as part of `./gradlew test` and fails the
build when a locale is missing, has an unknown or absent key, carries a blank value, or
whose format specifiers (`%1$s`, `%2$d`, …) do not match the default — a mismatch there is
an `IllegalFormatException` waiting to happen at runtime. The `:hud-app` copy additionally
bounds placard line length, because the glasses viewport is 320 dp wide and the placards
render at 40 sp.

**Adding a string**

1. Add it to `app/src/main/res/values/strings.xml` (or the `hud-app` equivalent).
2. Add the same key to all 17 `values-*/strings.xml` siblings.
3. Mark it `translatable="false"` instead if it is a brand name, an SI unit or pure
   punctuation — those are deliberately not duplicated per locale, and the test skips them.
4. Run `./gradlew test`.

**Adding a language** additionally means appending the qualifier to
`SUPPORTED_LOCALE_QUALIFIERS` in both apps' `i18n/StringResources.kt`, which is what the
coverage test iterates.

Both APKs offer a language picker and default to the device language. On Android 13+
their resources generate the system per-app language list; on Android 9–12 each
APK stores its own choice and applies it before its Activity starts.

---

## 繁體中文

18 種語言的翻譯如何組織、測試與擴充。

### 多國語系

兩個應用程式都備有完整翻譯。`values/strings.xml` 是唯一真實來源；每個
`values-<語系>/strings.xml` 都與它的可翻譯鍵集合完全一致。

| 語言 | 目錄限定符 | 語言 | 目錄限定符 |
|---|---|---|---|
| 英語（預設） | `values` | 烏爾都語 | `values-ur` |
| 華語（簡體） | `values-zh-rCN` | 德語 | `values-de` |
| 華語（繁體） | `values-zh-rTW` | 日語 | `values-ja` |
| 印地語 | `values-hi` | 越南語 | `values-vi` |
| 西班牙語 | `values-es` | 韓語 | `values-ko` |
| 現代標準阿拉伯語 | `values-ar` | 義大利語 | `values-it` |
| 法語 | `values-fr` | 烏克蘭語 | `values-uk` |
| 葡萄牙語 | `values-pt` | 荷蘭語 | `values-nl` |
| 俄語 | `values-ru` | 印尼語 | `values-in` |

新增語系前，有兩個容易踩到的限定符陷阱：

- **印尼語是 `in`**，即舊版 ISO 639-1 代碼。Android 不會解析 `values-id`。
- **華語需要 `zh-rCN` 與 `zh-rTW` 兩者。** 兩者互不作為對方的後備。

`LocalizationCoverageTest`（每個 app 各一份）會隨 `./gradlew test` 執行；當某個語系檔缺漏、
出現未知或缺少的鍵、含有空白值，或格式化參數（`%1$s`、`%2$d` …）與預設值不一致時即讓建置
失敗——最後這一項若放過，執行期就會拋出 `IllegalFormatException`。`:hud-app` 的版本另外
限制字卡的行長，因為眼鏡可視區僅 320 dp 寬，而字卡以 40 sp 繪製。

**新增字串**

1. 加入 `app/src/main/res/values/strings.xml`（或 `hud-app` 的對應檔案）。
2. 在全部 17 個 `values-*/strings.xml` 中加入相同的鍵。
3. 若屬品牌名稱、SI 單位或純標點，請改標記 `translatable="false"`——這類字串刻意不逐語系
   複製，測試也會略過它們。
4. 執行 `./gradlew test`。

**新增語言**還需要把該限定符加進兩個 app 的 `i18n/StringResources.kt` 中的
`SUPPORTED_LOCALE_QUALIFIERS`，覆蓋率測試正是依此列表逐一檢查。

兩個 APK 各有語言選單，預設跟隨各自裝置。Android 13 以上會從翻譯資源產生系統的
App 語言清單；Android 9–12 則由各自 APK 保存選擇，並在 Activity 啟動前套用。
