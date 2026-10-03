# Building, testing and releasing / 建置、測試與發行

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← 說明文件首頁](README.md)

---

## English

Everything needed to build the two apps, sign a release, run the test suite and keep dependency verification and CI healthy.

### Getting started

**Requirements**

- **JDK 17 or 21.** AGP 9 needs JDK 17 or newer; the build is only verified on 17 and 21
  (CI uses 21). If your system JDK is newer, set `org.gradle.java.home` in your
  *user-level* `~/.gradle/gradle.properties` (Windows:
  `%USERPROFILE%\.gradle\gradle.properties`) to a JDK 17 or 21 installation. Recent Android
  Studio releases bundle a JBR built on JDK 25, which is **not** verified — run
  `<studio>/jbr/bin/java -version` before pointing at it. It is deliberately **not**
  hardcoded in the project so CI and other contributors are unaffected.
- Android SDK with API 36 and Build-Tools 36.0.0 installed; `sdk.dir` in `local.properties`.
- Gradle wrapper 9.6.0 (checked in — do not run a system Gradle).

**Build**

```bash
./gradlew :app:assembleDebug        # phone APK
./gradlew :hud-app:assembleDebug    # glasses APK
./gradlew assembleDebug             # both
```

On Windows use `gradlew.bat`. The two APKs have different `applicationId`s and can be
installed side by side.

**Install**

```bash
adb -s <phone-serial>   install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <glasses-serial> install -r hud-app/build/outputs/apk/debug/hud-app-debug.apk
```

**Release bundle (Google Play)**

```bash
./gradlew :app:bundleRelease   # → app/build/outputs/bundle/release/app-release.aab
```

This needs the release signing credentials described in [Signing & secrets](#signing--secrets),
and it refuses to run if CXR credentials would be embedded. Every Play upload must carry a
higher `versionCode` than the last one; bump `:app` and `:hud-app` together, because
`ModuleVersionAlignmentTest` fails when their `versionCode` or `versionName` differ.

**Runtime permissions.** The phone app requests `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` /
`BLUETOOTH_ADVERTISE`, `ACCESS_FINE_LOCATION` (needed for trip GPS, and as the scan gate on
API ≤ 30), `POST_NOTIFICATIONS`, and foreground-service types `connectedDevice` + `location`.
`BLUETOOTH_SCAN` is flagged `neverForLocation`.

### Signing & secrets

Nothing sensitive is committed. Both apps resolve release credentials in this order:
**environment variable (CI) → gitignored `local.properties` (local dev)**.

| Key | Purpose |
|---|---|
| `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Release signing |
| `ROKID_CLIENT_SECRET` | Optional Rokid CXR authentication |
| `ROKID_SN_AUTH_BASE64` *or* `ROKID_SN_AUTH_FILE` | CXR SN auth blob — the file path is resolved against the repo root, so `secrets/<id>.lc` works |

Credentials are optional at configuration time. A task-graph guard fails the build only
when a release artifact is actually about to be signed without them, so debug builds and
`lintRelease` are unaffected. Consumer RV101 builds can connect without the CXR values;
provisioned devices may require both.

The entire `secrets/` tree is gitignored, along with `*.lc`, `*_key.txt` and every common
keystore extension — the `.lc` filename is itself the Client ID, so even the name is
sensitive.

### Testing & code quality

```bash
./gradlew test                 # all JVM unit tests (54 test classes)
./gradlew :data:protocol:test  # codec round-trip tests only
./gradlew jacocoTestReport     # aggregate coverage XML + HTML across every module
./gradlew sonar                # SonarCloud analysis (project zero2005x_RideFlux)
```

`jacocoTestReport` is a root-level aggregate: it runs every module's unit tests and merges
the `.exec` files into a single XML at
`build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`, which is what SonarCloud's
coverage gate consumes. Generated code (Hilt, Room `_Impl`, `R`, `BuildConfig`, KSP output)
is excluded from both coverage and analysis.

The GitHub Actions workflow runs `lintDebug`, builds both debug apps, executes the JVM unit
tests, generates the aggregate coverage report, and then runs SonarCloud on trusted events.
Import and bind the repository as SonarCloud project `zero2005x_RideFlux`, then create a
repository Actions secret named `SONAR_TOKEN` with **Execute Analysis** permission before the
first `main` push. Fork and Dependabot pull requests still run lint/build/tests but skip
Sonar, because GitHub does not expose repository secrets to those workflows. In SonarCloud,
use CI-based analysis and disable Automatic Analysis so the JaCoCo report is accepted without
duplicate analysis.

Instrumented tests live in `data/database/src/androidTest` (`TripDaoTest`) and run with
`./gradlew :data:database:connectedAndroidTest` against a connected device or emulator.

**Dependency verification** is enabled via `gradle/verification-metadata.xml`
(`verify-metadata=true`, `verify-signatures=false`). Adding or bumping a dependency
requires regenerating it:

```bash
./gradlew --write-verification-metadata sha256 help
```

The **configuration cache** is on by default (`org.gradle.configuration-cache=true`).

### Known build-level workarounds

Two deliberate constraints are worth knowing before you touch the build files:

1. **BouncyCastle pin.** The Sonar plugin transitively pulls an older `bcprov-jdk15on` that
   shadows the modern BouncyCastle AGP's `validateSigningDebug` needs, producing
   `NoClassDefFoundError: …EdECObjectIdentifiers`. The root build rewrites every
   `org.bouncycastle:*-jdk15on` coordinate on the buildscript classpath to `-jdk18on:1.78.1`.
   `-jdk15to18` artifacts are *not* rewritten.
2. **Plugin repository content filter.** `pluginManagement` admits only `com.android.*`,
   `com.google.*` and `androidx.*` from Google's Maven. A future plugin hosted only there
   under a different group will fail to resolve — widen the regex rather than debugging a
   repository error.

The Rokid Maven repository (`https://maven.rokid.com/repository/maven-public/`) is declared
**before** `google()` / `mavenCentral()` so CXR artifacts resolve from their publisher.

---

## 繁體中文

建置兩個 App、簽署發行版、執行測試，並維持相依驗證與 CI 健康所需的一切。

### 開始使用

**環境需求**

- **JDK 17 或 21。** AGP 9 需要 JDK 17 以上；本專案僅驗證過 17 與 21（CI 使用 21）。若
  系統 JDK 較新，請在**使用者層級**的 `~/.gradle/gradle.properties`（Windows：
  `%USERPROFILE%\.gradle\gradle.properties`）設定 `org.gradle.java.home`，指向 JDK 17
  或 21 的安裝路徑。請注意近期 Android Studio 內建的 JBR 已改用 JDK 25，**尚未驗證**——
  指向它之前請先以 `<studio>/jbr/bin/java -version` 確認。專案刻意**不**寫死此路徑，
  以免影響 CI 與其他貢獻者。
- 已安裝 API 36 與 Build-Tools 36.0.0 的 Android SDK；並在 `local.properties` 中設定 `sdk.dir`。
- Gradle wrapper 9.6.0（已納入版控——請勿使用系統安裝的 Gradle）。

**建置**

```bash
./gradlew :app:assembleDebug        # 手機 APK
./gradlew :hud-app:assembleDebug    # 眼鏡 APK
./gradlew assembleDebug             # 兩者
```

Windows 請使用 `gradlew.bat`。兩個 APK 的 `applicationId` 不同，可並存安裝。

**安裝**

```bash
adb -s <手機序號>   install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <眼鏡序號>   install -r hud-app/build/outputs/apk/debug/hud-app-debug.apk
```

**發行版套件（Google Play）**

```bash
./gradlew :app:bundleRelease   # → app/build/outputs/bundle/release/app-release.aab
```

需要[簽章與機密資訊](#簽章與機密資訊)所述的發行版簽章憑證；若會嵌入 CXR 憑證則拒絕建置。每次上傳
Play 的 `versionCode` 都必須比上一次大；`:app` 與 `:hud-app` 要一起調整，因為
`ModuleVersionAlignmentTest` 會在兩者的 `versionCode` 或 `versionName` 不一致時失敗。

**執行期權限。** 手機端會請求 `BLUETOOTH_SCAN`／`BLUETOOTH_CONNECT`／`BLUETOOTH_ADVERTISE`、
`ACCESS_FINE_LOCATION`（行程 GPS 需要，且在 API ≤ 30 上是掃描的前置條件）、
`POST_NOTIFICATIONS`，以及 `connectedDevice` + `location` 兩種前景服務型別。
`BLUETOOTH_SCAN` 已標記 `neverForLocation`。

### 簽章與機密資訊

專案不會提交任何機密資料。兩個應用程式依下列順序解析發行版憑證：
**環境變數（CI）→ 已被 gitignore 的 `local.properties`（本機開發）**。

| 鍵值 | 用途 |
|---|---|
| `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` | 發行版簽章 |
| `ROKID_CLIENT_SECRET` | 選用的 Rokid CXR 驗證 |
| `ROKID_SN_AUTH_BASE64` **或** `ROKID_SN_AUTH_FILE` | CXR SN 驗證資料——檔案路徑以專案根目錄為基準，因此可直接寫 `secrets/<id>.lc` |

這些憑證在設定期（configuration time）皆為選用。建置流程會檢查任務圖，只有在**真的**要簽署
發行版產物卻缺少憑證時才失敗，因此 debug 建置與 `lintRelease` 不受影響。消費版 RV101 不填
CXR 憑證也能連線；已佈建（provisioned）的裝置則可能兩項都需要。

整個 `secrets/` 目錄都已被 gitignore，另外還包含 `*.lc`、`*_key.txt` 與各種常見的
keystore 副檔名——因為 `.lc` 的檔名本身就是 Client ID，連檔名都屬敏感資訊。

### 測試與程式碼品質

```bash
./gradlew test                 # 所有 JVM 單元測試（54 個測試類別）
./gradlew :data:protocol:test  # 僅執行 codec 來回編解碼測試
./gradlew jacocoTestReport     # 跨所有模組的彙整覆蓋率 XML + HTML
./gradlew sonar                # SonarCloud 分析（專案 zero2005x_RideFlux）
```

`jacocoTestReport` 是根層級的彙整任務：它會執行每個模組的單元測試，並把產生的 `.exec`
合併成單一 XML，輸出到
`build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`，正是 SonarCloud 覆蓋率
關卡所讀取的檔案。產生式程式碼（Hilt、Room `_Impl`、`R`、`BuildConfig`、KSP 產出）已從
覆蓋率與靜態分析中排除。

GitHub Actions 會執行 `lintDebug`、建置兩個 debug app、跑完 JVM 單元測試、產生彙整
覆蓋率，再於可信任的事件上執行 SonarCloud。請先將 repository 匯入並綁定為 SonarCloud
專案 `zero2005x_RideFlux`；第一次推送 `main` 前，再建立名稱為 `SONAR_TOKEN`、具有
**Execute Analysis** 權限的 repository Actions secret。fork 與 Dependabot PR 仍會跑
lint／build／tests，但 GitHub 不會把 repository secret 暴露給這些 workflow，因此會跳過
Sonar。SonarCloud 端請採 CI-based analysis 並停用 Automatic Analysis，才能接收 JaCoCo
報告且不會重複分析。

儀器化測試位於 `data/database/src/androidTest`（`TripDaoTest`），需連接實機或模擬器後以
`./gradlew :data:database:connectedAndroidTest` 執行。

**相依驗證**已透過 `gradle/verification-metadata.xml` 啟用
（`verify-metadata=true`、`verify-signatures=false`）。新增或升級相依套件後必須重新產生：

```bash
./gradlew --write-verification-metadata sha256 help
```

**設定快取（configuration cache）** 預設為開啟（`org.gradle.configuration-cache=true`）。

### 已知的建置層變通做法

在動到建置檔之前，有兩個刻意保留的限制值得先知道：

1. **BouncyCastle 版本鎖定。** Sonar 外掛會遞移引入較舊的 `bcprov-jdk15on`，遮蔽掉 AGP
   `validateSigningDebug` 所需的新版 BouncyCastle，導致
   `NoClassDefFoundError: …EdECObjectIdentifiers`。根建置檔會把 buildscript classpath 上
   所有 `org.bouncycastle:*-jdk15on` 座標改寫為 `-jdk18on:1.78.1`。`-jdk15to18` 系列
   **不會**被改寫。
2. **外掛 repository 內容過濾。** `pluginManagement` 只允許 Google Maven 上的
   `com.android.*`、`com.google.*` 與 `androidx.*`。未來若有僅託管於該處、但群組不符的
   外掛就會解析失敗——請直接放寬正規表示式，而不是花時間排查 repository 錯誤。

Rokid 的 Maven repository（`https://maven.rokid.com/repository/maven-public/`）刻意宣告在
`google()`／`mavenCentral()` **之前**，以確保 CXR 相關套件從原發佈者解析。
