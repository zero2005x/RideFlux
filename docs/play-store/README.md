# Play Store assets / Play 商店素材

[English](#english) · [繁體中文](#繁體中文) · [← README](../../README.md) · [← 說明文件首頁](../README.md)

---

## English

Store listing graphics and the scripts that regenerate them.

The store icon, feature graphics and phone screenshots live under `docs/play-store/`
(the icon at `app/src/main/ic_launcher-playstore.png`), together with the scripts that
regenerate them:

| Script | Output |
|---|---|
| `docs/play-store/tools/render_icon.py` | 512×512 icon, drawn from the adaptive launcher vectors |
| `docs/play-store/tools/feature_graphic.py` | 1024×500 feature graphic per locale |
| `docs/play-store/tools/capture.ps1` | 1080×1920 phone screenshots per locale, from a running emulator |

The screenshots come from `StoreScreenshotActivity` in `app/src/debug/`, which renders the
real stateless screens with sample data and seeds the emulator's trip database, so no wheel
is needed. It lives only in the debug source set and never ships in release builds. Install
the debug APK on an Android 13+ emulator, then run `.\docs\play-store\tools\capture.ps1
-Locale zh-TW` (or `en-US`) from the repository root. The Python scripts need Pillow.

---

## 繁體中文

商店資訊用的圖片，以及重新產生它們的腳本。

商店圖示、主要宣傳圖片與手機截圖都放在 `docs/play-store/`（圖示位於
`app/src/main/ic_launcher-playstore.png`），並附有重新產生素材的腳本：

| 腳本 | 產出 |
|---|---|
| `docs/play-store/tools/render_icon.py` | 512×512 圖示，依啟動器向量圖繪製 |
| `docs/play-store/tools/feature_graphic.py` | 各語系的 1024×500 主要宣傳圖片 |
| `docs/play-store/tools/capture.ps1` | 從執行中的模擬器擷取各語系的 1080×1920 手機截圖 |

截圖來自 `app/src/debug/` 中的 `StoreScreenshotActivity`：它以範例資料渲染 App 實際的無狀態
畫面，並將範例行程寫入模擬器的資料庫，因此不需要連接車輛。它只存在於 debug 原始碼集，絕不會
進入發行版。在 Android 13 以上的模擬器安裝 debug APK 後，於專案根目錄執行
`.\docs\play-store\tools\capture.ps1 -Locale zh-TW`（或 `en-US`）即可。Python 腳本需要
Pillow。
