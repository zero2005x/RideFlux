# T07 — M365 passive reads and cryptographic regression

TASK: T07

BRANCH / PR: `feat/T07-m365-read-and-crypto` (PR attached to the task after creation).

SUMMARY:
- Existing: `M365Codec` already decoded B0, signed-magnitude reverse speed, legacy B9 trip distance,
  voltage 48 and raw current 50; `MiScooterConnection` already polled authenticated B0 reads.
- Existing: `MiEcdh`, `MiHkdf`, `MiCcm`, `MiKeys`, `MiUartFrame`, `MiAuthSession` and BLE transport
  already supplied P-256, HKDF-SHA256, AES-CCM, pairing/consent and encrypted UART. These are extended.
- Added: explicit Experimental passive capability map (37 read addresses and 12 disabled write addresses),
  raw-only unknown units, B0 frame-temperature propagation and three real hardware regression records.
- Added: unmodified official NIST CCM KAT, RFC 5869 A.3 and negative crypto cases on the existing core;
  public Mi nonce/tag defaults stay unchanged. No dependency, permission, pairing or UI changes.

FILES CHANGED:
- `README.md`
- `README.zh-TW.md`
- `docs/PROTOCOLS.md`
- `docs/T07_M365_READ_CRYPTO_REPORT.md`
- `domain/src/main/kotlin/com/rideflux/domain/telemetry/VehicleTelemetry.kt`
- `data/protocol/src/main/kotlin/com/rideflux/protocol/familyscooter/m365/M365Codec.kt`
- `data/protocol/src/main/kotlin/com/rideflux/protocol/familyscooter/m365/M365RegisterMap.kt`
- `data/protocol/src/main/kotlin/com/rideflux/protocol/familyscooter/xiaomi/MiCcm.kt`
- `data/protocol/src/test/kotlin/com/rideflux/protocol/familyscooter/m365/M365RegisterMapTest.kt`
- `data/protocol/src/test/kotlin/com/rideflux/protocol/familyscooter/xiaomi/MiStandardsKatTest.kt`
- `data/protocol/src/test/resources/m365/hardware-b0.csv`
- `data/protocol/src/test/resources/m365/register-source-rows.md`

TESTS:
```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\gtmp'
.\gradlew.bat :data:protocol:test :domain:test --max-workers=2 --console=plain
```
Actual initial targeted tail (`C:\gtmp\T07-targeted.log`):
```text
BUILD SUCCESSFUL in 58s
9 actionable tasks: 9 executed
Configuration cache entry stored.
```
Full suite/lint/coverage command:
```powershell
.\gradlew.bat testDebugUnitTest lint :data:protocol:test :domain:test jacocoTestReport --max-workers=2 --console=plain
```
Actual tail (`C:\gtmp\T07-full.log`):
```text
> Task :data:protocol:jacocoTestReport
> Task :jacocoTestReport

BUILD SUCCESSFUL in 8m 14s
370 actionable tasks: 102 executed, 268 up-to-date
Configuration cache entry reused.
```
Final XML: `M365RegisterMapTest` 4 tests and `MiStandardsKatTest` 4 tests, zero failures/errors.
Aggregate JaCoCo XML joined to staged added main-source lines: **146/146 = 100%** combined
line/branch coverage (78/78 executable lines, 68/68 branches). Per file: register map 59 lines/28
branches, CCM additions 16/32, telemetry model 2/8, codec temperature propagation 1/0.
Diff checks confirm no dependency/manifest changes, no existing test modifications, no UTF-8 BOM.
Existing tests are untouched. New fixtures pin inventory source rows and all 12 blocked writes;
individual and B0 reads reject invalid sizes/SOC, retain raw unknowns and replay matching CSV fields.
Crypto tests cover ciphertext/tag/AAD corruption, short nonce, illegal tag lengths and short ciphertext.
Existing `MiCryptoTest` retains fixed P-256 scalar KAT, RFC 5869 A.1, Mi-specific CCM KATs and negatives.

EVIDENCE LEVEL OF NEW CONSTANTS:

| Constant / behavior | Level | Source and limit |
|---|---|---|
| `READ_SIZES_L1`: all 37 register addresses and exact sizes | L1 | `findings/NINEBOT_XIAOMI_UPSTREAM_REFERENCE.md:105-141`; original read rows pinned in `register-source-rows.md`, opt-in only |
| `WRITE_SIZES_L1`: `17,70,71,74,75,78,79,7A,7B,7C,7D,BE` and sizes | L1 | Same note `150-161`; complete machine-extracted source rows pinned; metadata only, every address NotYetEnabled |
| B0 block size 32; B4/B5/B7/BB offsets 8/10/14/22; BB signed LE16 ÷10 °C | L3 | `scooter-apps/hardware-evidence/SPEED-FIELD-ANALYSIS.md:13-29`, independently matched logcat and CSV fields below |
| B5 signed magnitude ÷1000; B9 offset 18 raw-only | Inherited policy / L3 bytes | `docs/M365_REVERSE_SPEED_2026-10-07.md:9-16`; hardware confirms offsets/scale, not reverse/sentinel semantics; B9 units unresolved |
| Register 48 ÷100 V and register 50 unsigned raw | Inherited, not newly established | Existing `M365Codec.decodeBatteryVoltageV/decodeBatteryCurrentRaw`; inventory source confirms addresses only; no new hardware validation of these reads |
| CCM even tag lengths 4..16, B0 M-field formula, authentication before release | L2 formal specification | [RFC 3610 §2](https://www.rfc-editor.org/rfc/rfc3610); fixed nonce12/L3 and public tag4 already existed, internal tag-length entry points share the same AES/ECB core |
| NIST CCM key/nonce/AAD/plaintext/expected ciphertext+tag | L2 published KAT | [NIST CAVP CCM vectors](https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Algorithm-Validation-Program/documents/mac/ccmtestvectors.zip), `VNT128.rsp Count=50`, Alen32/Plen24/Tlen16/Nlen12; no adapted bytes |
| HKDF expected output, SHA256, 22 bytes 0b, L42 | L2 formal KAT | [RFC 5869 Appendix A.3](https://www.rfc-editor.org/rfc/rfc5869#appendix-A.3); existing JCA HMAC implementation reused |
| Power = voltage × current | Derived only | No power address added. Unknown current A means absent power; never borrow BMS-board 33 units for ESC 50 |

Hardware fixture provenance (only three decrypted 32-byte payloads and five relevant CSV columns copied):

| Record | Logcat source row / time | Matching telemetry CSV source row / time |
|---|---|---|
| Stopped, 51%, 31°C, 400107m | `logcat-spin-raw.txt:34`, 14:30:39.578 | `phone-logs/m365_telemetry_20260920_142701.csv:315`, 14:30:39.580 |
| Signed logged speed -5.514, raw EA76 | `logcat-spin-raw.txt:19169`, 14:34:02.158 | `phone-logs/m365_telemetry_20260920_143318.csv:34`, 14:34:02.162 |
| Speed 0.15, raw 0096 | `logcat-spin-raw.txt:19316`, 14:34:02.970 | Same CSV `:35`, 14:34:02.973 |

All hardware paths are under read-only WSL `scooter-apps/hardware-evidence/`.
CSV speed is retained signed in the fixture. The test checks the existing magnitude policy, not that
the wheel physically traveled backwards. Fixtures contain no tokens, credentials or full auth logs.

UNSUPPORTED / GAPS:
- **Speed conflict preserved:** current RideFlux uses signed B5 magnitude and only zero is stationary.
  Hardware note `SPEED-FIELD-ANALYSIS.md:3,23-24` instead proposes unsigned speed plus invalid near-FFxx
  filtering. Captured signed CSV values do not decide physical direction. No sentinel threshold enabled.
- **B9 conflict preserved:** existing codec outputs `raw ×10` metres; written `m×10` can imply raw/10 m,
  while Scootbatt reports raw/100 **km** (equivalent to the existing raw×10 m). Audit
  `AUDIT-2026-10-07.md:872-883` says the written m×10 interpretation and that app conversion differ by 100×
  and cannot yet be resolved. Legacy trip output remains unchanged; new map exports raw B9 only.
- **ESC 50 current units/sign unknown:** raw word only, current A/power null. BMS board 33 signed /100
  semantics are a different address space and cannot establish ESC 50 behavior.
- All other documented read registers remain raw if units/semantics are unpinned; aliases such as
  22/26/29/3E are not assumed to use the mirror register conversion. Write-only inventory entries
  are conservatively not marked readable; all twelve are NotYetEnabled and decode Unsupported.
- Register inventory is explicitly L1 in upstream note `16,342-349`. The requested L2 designation
  applies to written crypto/profile evidence, not blanket promotion of the community address map.
- Older key-import feasibility findings describe a pre-Mi-auth repo; current repo MI_AUTH/PHASE_MI_HANDOFF
  supersede that implementation inventory. No pairing or key-import redesign attempted.

RISKS / THINGS I DID NOT VERIFY:
- No new physical scooter connection, reverse test, 48/50 capture or trip-distance measurement.
- No Android hardware-provider/minSdk execution. JVM standard KATs exercise the portable AES/ECB
  fallback; the app's fixed Mi API remains nonce12/tag4 and does not depend on AES/CCM availability.
- No change to nonce compatibility modes or auth/control flows. Crypto algorithm agreement and
  hardware replay do not prove compatibility with every M365 firmware/model.
- No AGPL code copied or ported; only written evidence rows, own existing code and published KAT bytes.
- No new dependency, INTERNET permission, network code, write encoder, UI tile or version change.

NEEDS FROM OWNER:
- Gatekeeper ACCEPT / REWORK; owner merges after review.
- Timestamped physical forward/reverse/stationary B5 captures and distance reference to decide speed policy.
- B9 capture with independently measured trip distance; authenticated ESC 48/50 captures with meter
  reference to establish voltage/current units and sign before emitting current A or calculated power.
