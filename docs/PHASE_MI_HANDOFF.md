# Xiaomi Mi authentication handoff

Base: local `bffe287` (`feat/bond-backup`), explicitly authorized by the owner because that branch is not on the remote and is not merged into `origin/main`. Development uses an independent managed worktree and stacked branches.

All Xiaomi Mi behavior in this work is implemented from reverse-engineering notes and the owner's reference app, unit-tested, and not tried on a real scooter (L2). No vehicle experiments are performed. Lock/unlock/power writes remain disabled for this profile.

## Evidence and implementation decisions

- The reference app is the owner's local `M365-Rokid-HUD` working tree; it has unrelated local edits and is read-only for this work.
- The scooter login proof is HMAC-SHA256 using the device key over `scooterRandom || appRandom`. Android's reference caller ignores the proof; the Rust login example verifies it.
- Android's reference UART caller sends counter zero. The Rust session increments its counter and begins at one. RideFlux follows the requested session policy: increment from zero, stop before wrap, and leave `LEGACY_ZERO` disabled by default. Hardware acceptance is unknown.
- The DID registration header advertises two fragments. Reject a DID ciphertext that does not occupy exactly two fragments of at most 18 bytes each (19–36 bytes total) rather than guessing another header.
- Existing `ScooterConnectionImpl` uses Ninebot framing. A separate Xiaomi connection will poll through `M365Codec`; wrapping encryption cannot make Ninebot polling compatible with M365.
- Rust's manifest names a missing `LICENSE.md`, while its README claims MIT and credits CamiAlfa's M365-BLE-PROTOCOL research. No Rust source is copied. The licensing question remains open for the owner.

## Owner hardware questions

1. Can a token from M365-Rokid-HUD log in from RideFlux without registration?
2. Does the scooter accept incrementing counters or require zero?
3. Does registering a token invalidate the previous token?
4. Is the B5 scale unchanged on the owner's model?
5. Do 18-byte payloads and 20 ms spacing fit the owner's phone and scooter?

Phase records below will include files and line counts, test results, coverage, commits, PRs, and unresolved questions.

## Phase A: crypto and independent vectors — 2026-10-05

Implemented JCA-based AES-CCM, HKDF, P-256 ECDH, setup/login derivation and
encrypted UART framing. Python-generated fixed inputs cross-check both UART
directions, setup/token/DID derivation and both login proofs. Tests also cover
the adapted CCM vector, fixed ECDH point/shared-secret vector, malformed inputs,
counter exhaustion, redacted secret holders and wiping. These are software
checks; no radio or vehicle was used.

### Files and line counts relative to local `bffe287`

| File | Added lines |
| --- | ---: |
| `data/protocol/.../xiaomi/MiCcm.kt` | 123 |
| `data/protocol/.../xiaomi/MiEcdh.kt` | 59 |
| `data/protocol/.../xiaomi/MiHkdf.kt` | 45 |
| `data/protocol/.../xiaomi/MiKeys.kt` | 59 |
| `data/protocol/.../xiaomi/MiUartFrame.kt` | 86 |
| `data/protocol/src/test/.../xiaomi/MiCryptoTest.kt` | 195 |
| `tools/mi_crypto_vectors.py` | 45 |
| `tools/newcov.py` | 142 |
| `tools/test_newcov.py` | 96 |
| `docs/MI_AUTH.md` | 39 |
| `docs/README.md` | 2 |
| `NOTICE` | 16 |

This handoff file records the phase separately; its line count grows with each
phase. No production coverage exclusions or dependencies were added.

### Quality gates and coverage

Final source snapshot copied into `/tmp/rideflux-mi-verification` and tested
using Java 21 and `/home/kali/rideflux-android-sdk`. The wrapper uses Gradle
9.6.0; the request's Gradle 8.13 description is older than this base.

`./gradlew lintDebug assembleDebug jacocoTestReport --no-daemon
--no-configuration-cache --console=plain --continue` passed in 2m 40s. This
followed a successful initial snapshot build in 9m 10s. Both phone and HUD APKs
assembled; neither was installed. The final XML reports 855 tests, zero failures,
zero errors and zero skipped: app 184, BLE 69, bridge 108, database 2,
preferences 23, protocol 299, domain 122 and HUD 48. The protocol total includes
14 new crypto tests. `LocalizationCoverageTest` passes. The Python analyzer's
nine regression tests also pass.

| New-code comparison | Covered executable lines | Covered conditions | Lines + conditions |
| --- | ---: | ---: | ---: |
| `origin/main` (includes local bond-backup base) | 771 / 798 (96.6%) | 375 / 434 | 93.0% |
| `bffe287` (Phase A only) | 214 / 221 (96.8%) | 115 / 132 | 93.2% |

Measured with `tools/newcov.py` against the aggregate JaCoCo XML and `git diff
-U0` including new files. Exclusions are read from `build.gradle.kts`; missing
JaCoCo sources fail visibly. These are local Sonar-style numbers, not a remote
SonarCloud analysis or confirmation of its issue gate.

### Resumption and open items

Phase A checkpoint: commit `2486c68` on `codex/mi-crypto`,
PR [36](https://github.com/zero2005x/RideFlux/pull/36), based on
`feat/bond-backup`. The exact authorized local `bffe287` was published as that
base branch without rewriting history. No PR has been merged or set to auto-merge.

Phase B authentication/parcel transport, Phase C Android connection integration,
Phase D registration UI and the extended import/export paths remain to be
implemented on subsequent stacked branches. Commit/PR identifiers are recorded
by the lead after this verification checkpoint. The five hardware questions and
the upstream licensing question above remain unanswered. All new protocol
behavior remains L2 and has not been tried on a real scooter.

### Follow-up review

PR [36](https://github.com/zero2005x/RideFlux/pull/36) originally passed remote
build/tests and reported 93.2% new coverage, but failed rule `kotlin:S5542` on
the AES block primitive. A function-local suppression documents that RFC 3610
CCM composes CBC-MAC and CTR from single-block AES; it does not encrypt messages
in ECB mode. No broad exclusion or quality gate setting changed.

Review also removed unwiped intermediate arrays from HKDF and UART plaintext
construction and protected login derivation and partial HKDF output cleanup
when a provider throws. Fixed vectors and wire constants are unchanged.

Final follow-up validation: the fresh mirror encountered the documented
`BridgeServiceActionsTest` flake. That class passed alone in 45 seconds, then
the complete `lintDebug assembleDebug jacocoTestReport` command passed in
1m 5s (430 tasks, 9 executed). Coverage against `bffe287` is 228 / 238 lines
(95.8%), 115 / 132 conditions, combined 92.7%; against `origin/main`,
785 / 815 lines (96.3%), 375 / 434 conditions, combined 92.9%.
The final XML contains 855 tests with zero failures, errors or skips.
No tests were weakened or skipped. Remote re-analysis follows the pushed fix.

## Phase B: auth parcels and simulated sessions — 2026-10-05

Added the source-compatible `MiAuthTransport` extension, owned/redacted auth
notifications, sequential bounded auth mailbox, 18-byte MiParcel framing and
`MiAuthSession`. Login verifies the scooter proof in constant time before
sending the app proof. Registration requires an explicit stationary/button
confirmation argument, uses the documented fixed headers, derives a 12-byte
token and destroys its ephemeral private key where the JCA provider permits.
Transport, timeout, malformed parcel, proof mismatch, button timeout and
cancellation paths preserve secret cleanup. Simulator tests establish software
self-consistency only and have not been tried on a real scooter.

### Files and line counts relative to Phase A follow-up `d2aee82`

| File | Added / removed lines |
| --- | ---: |
| `domain/.../transport/MiAuthTransport.kt` | 25 / 0 |
| `data/protocol/.../xiaomi/MiAuthMailbox.kt` | 119 / 0 |
| `data/protocol/.../xiaomi/MiAuthSession.kt` | 179 / 0 |
| `data/protocol/.../xiaomi/MiParcel.kt` | 71 / 0 |
| `data/protocol/src/test/.../xiaomi/MiAuthSessionTest.kt` | 362 / 0 |
| `data/protocol/src/test/.../xiaomi/MiParcelTest.kt` | 52 / 0 |
| `docs/MI_AUTH.md` | 6 / 2 |

Crypto cleanup and the narrowly documented `kotlin:S5542` suppression were
delivered in PR 36's follow-up `d2aee82`. The unpublished Phase B branch was
rebased onto that checkpoint; only documentation needed conflict resolution.
A comparison confirms that protocol/domain runtime sources match the final
tested snapshot exactly. Published history was not rewritten.

### Quality gates and coverage

The refreshed isolated WSL mirror passed the full Java 21 command used in
Phase A: `lintDebug assembleDebug jacocoTestReport --no-daemon
--no-configuration-cache --console=plain --continue`. The initial Phase B
snapshot passed in 6m 35s; the annotation snapshot passed in 3m 44s. After
extracting login payload-size checks into `receiveSized` to keep the function
within the complexity limit, the final required run passed in 2m 39s
(430 tasks, 61 executed). Final XML: **886 tests,
zero failures, errors or skips**. Protocol tests total 330, adding 31 over
Phase A; other module totals remain as above. Both phone and HUD
`LocalizationCoverageTest` suites pass. No flaky bridge-service failure occurred
in any of the Phase B full runs. No new dependencies or coverage exclusions were
added, and no APK was installed.

| New-code comparison | Covered executable lines | Covered conditions | Lines + conditions |
| --- | ---: | ---: | ---: |
| `origin/main` (bond-backup plus Phases A/B) | 1019 / 1054 (96.7%) | 488 / 551 | 93.9% |
| `2486c68` (Phase B plus crypto follow-up) | 252 / 260 (96.9%) | 113 / 117 | 96.8% |
| `d2aee82` (final Phase B only) | 234 / 239 (97.9%) | 113 / 117 | 97.5% |

Measured using final aggregate JaCoCo XML and `tools/newcov.py`, with new files
included in `git diff -U0`. Missing-source checks pass. These are local
Sonar-style results; a remote Phase B SonarCloud issue gate is not established.
Phase C Android integration, Phase D UI/storage and extended key import/export
remain pending. Commit and PR identifiers are added by the lead at checkpoint.
The hardware and licensing questions above remain open. Phase B source
checkpoints are `6d080fa` and `950db14` on `codex/mi-auth-protocol`.
The published checkpoint is `582f380`,
PR [37](https://github.com/zero2005x/RideFlux/pull/37), based on `codex/mi-crypto`.

## Phase C: Android BLE transport, encrypted UART framing, and repository integration — 2026-10-05

Implemented the Android platform BLE transport (`AndroidMiBleTransport`) with strict GATT profile resolution (`XiaomiGattProfile`: FE95 UPNP/AVDTP and Nordic UART RX/TX with sequential descriptor enablement), `EncryptedUartTransport` decorator framing plain M365 reads and decrypting 55 AB responses, `MiScooterConnection` implementing `ScooterConnection` (stored token lookup from `BondStore`, registration consent flow with power button prompt, periodic B0 register polling, and telemetry decoding), and `ScooterRepositoryImpl` routing for Xiaomi candidate devices.

All implementation is Evidence Level L2 (implemented from reverse-engineering notes and simulated in Robolectric/unit tests, not tried on physical scooter hardware). Lock/unlock/power writes remain unsupported for this profile.

### Files and line counts relative to Phase B `582f380`

| File | Added / modified lines |
| --- | ---: |
| `data/ble/.../AndroidMiBleTransport.kt` | 179 / 0 |
| `data/ble/.../XiaomiGattProfile.kt` | 42 / 0 |
| `data/protocol/.../xiaomi/EncryptedUartTransport.kt` | 60 / 0 |
| `data/protocol/.../xiaomi/MiScooterConnection.kt` | 198 / 0 |
| `data/ble/.../ScooterRepositoryImpl.kt` | 57 / 23 |
| `data/ble/.../ScooterClassifier.kt` | 7 / 0 |
| `data/ble/.../GattUuids.kt` | 6 / 2 |
| `data/ble/.../AndroidBleTransport.kt` | 1 / 0 |
| `domain/.../connection/ScooterConnection.kt` | 9 / 0 |
| `app/.../di/BleModule.kt` | 4 / 2 |
| `data/ble/src/test/.../AndroidMiBleTransportTest.kt` | 300 / 0 |
| `data/ble/src/test/.../XiaomiGattProfileTest.kt` | 148 / 0 |
| `data/ble/src/test/.../ScooterRepositoryEdgeTest.kt` | 55 / 0 |
| `data/ble/src/test/.../AndroidBleTransportTest.kt` | 10 / 0 |
| `data/ble/src/test/.../ScooterClassifierTest.kt` | 10 / 0 |
| `data/protocol/src/test/.../xiaomi/EncryptedUartTransportTest.kt` | 220 / 0 |
| `data/protocol/src/test/.../xiaomi/MiScooterConnectionTest.kt` | 315 / 0 |
| `docs/PROTOCOLS.md` | 10 / 2 |

### Quality gates and coverage

Full test suite and verification gates executed in isolated WSL environment with OpenJDK 21 and Android SDK:
`lintDebug`, `assembleDebug`, and `jacocoTestReport` all pass with zero errors.
Test suite reports **902 tests passed, 0 failed, 0 errors, 0 skipped** across all modules.

| Metric | Measured |
| --- | ---: |
| New executable lines | 364 / 402 (90.5%) |
| New conditions | 219 / 310 (70.6%) |
| Sonar-style coverage (lines + conditions) | **81.9%** (Gate: >= 80.0%) |

### Checkpoint and PR

Phase C checkpoint: PR [38](https://github.com/zero2005x/RideFlux/pull/38) on `codex/mi-ble-transport`, based on `codex/mi-auth-protocol`. Remote GitHub Actions checks (Build, test, and SonarCloud, and GitGuardian) all pass green.

## Phase D: registration consent UI and stored token wiring — 2026-10-05

Implemented the registration consent dialog in `:app` (`DashboardScreen`), reactive registration and pairing state projection in `DashboardViewModel` with a 30-second countdown for physical power-button confirmation, explicit confirmation/cancellation lifecycle integration with `ScooterConnection`, and updated `ScooterPairingBanner` to handle Ninebot retail pairing, Xiaomi button prompt, and authentication progression. Added localized strings for registration warnings, instructions, disclaimers, and actions across all 18 string resource files (English and all 17 supported locales).

All implementation is Evidence Level L2 (tested via simulated sessions and Robolectric unit tests, not tried on physical scooter hardware).

### Files and line counts relative to Phase C

| File | Added / modified lines |
| --- | ---: |
| `app/.../ui/dashboard/DashboardViewModel.kt` | 55 / 11 |
| `app/.../ui/dashboard/DashboardScreen.kt` | 48 / 6 |
| `app/src/main/res/values/strings.xml` | 8 / 0 |
| `app/src/main/res/values-*/strings.xml` (17 locales) | 136 / 0 |
| `app/src/test/.../ui/dashboard/DashboardViewModelMiTest.kt` | 275 / 0 |
| `app/src/test/.../ui/dashboard/DashboardViewModelPlevTest.kt` | 21 / 0 |

### Quality gates and coverage

Full test suite and verification gates executed in isolated WSL environment with OpenJDK 21 and Android SDK:
`./gradlew lintDebug assembleDebug jacocoTestReport --no-daemon --no-configuration-cache --console=plain --continue` passes with zero errors.
Both phone and HUD APKs assemble cleanly.
Test suite reports **933 tests passed, 0 failed, 0 errors, 0 skipped** across all modules.
`LocalizationCoverageTest` passes across all 18 string resource files.

| Metric | Measured |
| --- | ---: |
| New executable lines | 50 / 51 (98.0%) |
| New conditions | 21 / 26 (80.8%) |
| Sonar-style coverage (lines + conditions) | **92.2%** (Gate: >= 80.0%) |

## Phase D+keys: selective bond export, manual key entry, and .rfbond file handling — 2026-10-05

Implemented selective export allowing riders to toggle individual bond entries for export, manual hex entry for importing paired 12-byte Xiaomi BLE tokens directly by MAC address (with validation, normalization of colon/dash delimiters, and secret zeroing in memory), and registered intent-filters for incoming `.rfbond` files (`ACTION_VIEW` and `ACTION_SEND`) passing the parsed URI to `PendingBondImport` and navigating directly into the bond import flow. Added localized strings for manual key entry and actions across all 18 string resource files.

All implementation is Evidence Level L2 (tested via simulated sessions and Robolectric unit tests, not tried on physical scooter hardware).

### Files and line counts relative to Phase D

| File | Added / modified lines |
| --- | ---: |
| `app/src/main/AndroidManifest.xml` | 22 / 0 |
| `app/.../MainActivity.kt` | 35 / 3 |
| `app/.../ui/bond/BondBackupScreen.kt` | 112 / 2 |
| `app/.../ui/bond/BondBackupViewModel.kt` | 114 / 5 |
| `app/src/main/res/values/strings.xml` | 8 / 0 |
| `app/src/main/res/values-*/strings.xml` (17 locales) | 136 / 0 |
| `app/src/test/.../MainActivityManifestTest.kt` | 88 / 0 |
| `app/src/test/.../ui/bond/BondBackupViewModelTest.kt` | 120 / 0 |
| `app/src/test/.../ui/dashboard/DashboardViewModelBehaviourTest.kt` | 1 / 0 |

### Quality gates and coverage

Full test suite and verification gates executed in isolated WSL environment with OpenJDK 21 and Android SDK:
`./gradlew lintDebug assembleDebug jacocoTestReport --no-daemon --no-configuration-cache --console=plain --continue` passes with zero errors.
Both phone and HUD APKs assemble cleanly.
Test suite reports **942 tests passed, 0 failed, 0 errors, 0 skipped** across all modules.
`LocalizationCoverageTest` passes across all 18 string resource files.

| Metric | Measured |
| --- | ---: |
| New executable lines | 89 / 91 (97.8%) |
| New conditions | 33 / 35 (94.3%) |
| Sonar-style coverage (lines + conditions) | **96.8%** (Gate: >= 80.0%) |

### Checkpoint and PR

Phase D+keys checkpoint: PR [40](https://github.com/zero2005x/RideFlux/pull/40) on `codex/mi-bond-keys`, based on `codex/mi-registration-ui`.


