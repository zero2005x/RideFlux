# RideFlux documentation / 說明文件

[English](#english) · [繁體中文](#繁體中文) · [← README](../README.md) · [← README（繁體中文）](../README.zh-TW.md)

---

## English

Developer documentation for RideFlux. Each page carries both languages, English first.

| Document | What is in it |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module layering, design rules, repository layout |
| [PROTOCOLS.md](PROTOCOLS.md) | Supported wheel families, GATT topologies, where the protocol knowledge comes from |
| [BRIDGE_PROTOCOL.md](BRIDGE_PROTOCOL.md) | Phone ↔ glasses bridge: pairing token, startup ordering, scan budget, silent links, frame layout |
| [BOND_BACKUP.md](BOND_BACKUP.md) | Encrypted `.rfbond` pairing-key backup: byte layout, payload, test vector, threat model |
| [MI_AUTH.md](MI_AUTH.md) | Xiaomi Mi authentication in development: crypto, evidence, safety and open questions |
| [LOCALIZATION.md](LOCALIZATION.md) | The 18 translations, the coverage test, adding a string or a language |
| [BUILDING.md](BUILDING.md) | JDK and SDK, build and install, signing and secrets, tests, Sonar, dependency verification |
| [play-store/](play-store/README.md) | Play Store graphics and the scripts that regenerate them |
| [../site/](../site/README.md) | The GitHub Pages website: sources, translations, build script |
| [articles/](articles/) | Long-form writing. [The story behind RideFlux](articles/cyberpunk-commute-update-2026-10.md) is rendered as the website's `/story/` page |

Other files in the repository: [`PRIVACY.md`](../PRIVACY.md) (privacy policy), [`NOTICE`](../NOTICE) (third-party credits), [`LICENSE`](../LICENSE).

---

## 繁體中文

RideFlux 的開發者文件。每份文件都同時包含兩種語言，英文在前。

| 文件 | 內容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md#繁體中文) | 模組分層、設計規則、儲存庫目錄結構 |
| [PROTOCOLS.md](PROTOCOLS.md#繁體中文) | 支援的車輛家族、GATT 拓撲、協定知識的來源 |
| [BRIDGE_PROTOCOL.md](BRIDGE_PROTOCOL.md#繁體中文) | 手機 ↔ 眼鏡橋接：配對權杖、啟動順序、掃描預算、靜默連線、封包版面 |
| [BOND_BACKUP.md](BOND_BACKUP.md#繁體中文) | 加密的 `.rfbond` 配對金鑰備份：位元組版面、內容、測試向量、威脅模型 |
| [MI_AUTH.md](MI_AUTH.md#繁體中文) | 開發中的小米 Mi 認證：加密、證據、安全與未確認事項 |
| [LOCALIZATION.md](LOCALIZATION.md#繁體中文) | 18 種翻譯、覆蓋率測試、如何新增字串或語言 |
| [BUILDING.md](BUILDING.md#繁體中文) | JDK 與 SDK、建置與安裝、簽章與機密、測試、Sonar、相依驗證 |
| [play-store/](play-store/README.md#繁體中文) | Play 商店圖片，以及重新產生它們的腳本 |
| [../site/](../site/README.md) | GitHub Pages 網站：原始檔、翻譯、建置腳本 |
| [articles/](articles/) | 長文。〈[RideFlux 背後的故事](articles/cyberpunk-commute-update-2026-10.md)〉（英文）會被轉成網站的 `/story/` 頁 |

儲存庫中的其他檔案：[`PRIVACY.md`](../PRIVACY.md)（隱私權政策）、[`NOTICE`](../NOTICE)（第三方致謝）、[`LICENSE`](../LICENSE)。
