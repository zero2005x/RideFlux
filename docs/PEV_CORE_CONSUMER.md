# Shared MIT core consumer

This isolated RideFlux change consumes `io.github.zero2005x.pev:pev-protocol-core:0.1.0-SNAPSHOT`
from the authoritative `M365-Rokid-HUD` source tree through Gradle dependency substitution.
No core source or captured fixture is copied into RideFlux. The dependency flows from the GPL
consumer to the MIT library; RideFlux code remains GPL and is not a source for the MIT library.

## Configure and validate

The default source is the sibling `../M365-Rokid-HUD` directory. A thin included build under
`build-logic/pev-core-consumer` points its module project directory at the external core and reuses
the authoritative module build script. It configures only Kotlin/JVM and Kover, avoiding the HUD's
Android/Rust applications. Build outputs stay inside the consumer build directory. Override the source with
`-PpevCoreDir=<absolute HUD repository root>` or `PEV_CORE_DIR`. The path must contain
`pev-protocol-core/build.gradle.kts`. Missing source fails early with an actionable error.
The unreleased snapshot is not fetched from an untrusted Maven mirror.

```sh
./gradlew -PpevCoreDir=/path/to/M365-Rokid-HUD :domain:test :data:protocol:test
./gradlew -PpevCoreDir=/path/to/M365-Rokid-HUD :data:protocol:dependencyInsight --dependency pev-protocol-core --configuration testRuntimeClasspath
```

CI must supply the matching core source checkout before configuring this consumer. The current
core is an unreleased local snapshot; this branch does not claim a remotely published artifact or
an already passing remote pipeline. Record both consumer and core revisions when reproducing a run.

## Actual consumption and limits

The M365 polling/authenticated UART path already uses `M365Codec.decodeB0`. That function now
delegates SOC validation, unsigned total distance and frame-temperature decoding to
`XiaomiMotorInfoDecoder`. Read requests obtain the logical PDU from `XiaomiPdu`; RideFlux retains
its existing retail envelope/checksum and authentication. Exact 32-byte reply requirements,
invalid-SOC rejection and existing DTO/UI behavior are retained.

RideFlux's legacy speed display interprets the raw word as signed LE16 and takes its magnitude,
so very slow reverse motion still revokes the stationary permit. The shared core uses a forward/wrap
threshold at `0xC000`, which conflicts with the RideFlux display contract at `0x8001..0xBFFF`.
This consumer retains the current display policy and adds a test documenting the disagreement;
it does not call the disagreement physically verified. The legacy B9 trip projection is also retained
only for DTO compatibility; the core does not resolve that field's physical meaning. Those two
policies need separate evidence/review before a complete physical-telemetry migration.

The ES2 diagnostic B0 implementation is independent in `NinebotRetailCodec`, because its previous
reuse of the M365 function must not inherit M365-specific provenance. Its existing word positions,
unknown-speed guard and write permissions remain unchanged. No Ninebot hardware support is newly
asserted. Begode physical telemetry is not changed or promoted by this consumer change.

## Source and worktree baseline

The selected committed baseline is `9bd9eaa82e55efaa936afa5d332e7ef2c752b7b8` on the isolated
`codex/pev-core-consumer` worktree. Its source repository is
`https://github.com/zero2005x/RideFlux`; the license is GPL-3.0-or-later. The external MIT core source is
`https://github.com/zero2005x/M365-Rokid-HUD`, whose `pev-protocol-core/SOURCE_PROVENANCE.md`
records original sources and captures. Consumer contract fixtures here are synthetic, not captures.

At initial inspection the original checkout had advanced to `b7a51ad` (`chore/bump-0.1.11`) with modified
`app/build.gradle.kts`, `hud-app/build.gradle.kts` and an untracked
`GEMINI_NOSFET_F18_V11_REMEDIATION_PROMPT.md`. The owner subsequently committed the two version files
as `ee23b875f0c72fee1d0bcb6ff30e6c582b3653db` (0.1.11/versionCode 12); the untracked file remains.
All owner changes are preserved. Four main commits newer than the selected baseline add pairing-key
privacy text and remove glasses pairing-token logging (`c963fc2`, `992f9ff`, `ee45588`, `b7a51ad`);
their files do not overlap this protocol/build integration. This selected baseline predates those
privacy fixes. Before release, integrate this patch with current main and retain those newer fixes
and the owner's version changes. No push, merge, release or vehicle write is authorized by this work.

## Dependency verification

Strict Gradle SHA256 verification remains enabled. The 16 new public Kover/instrumentation
artifact checksums were compared with freshly fetched bytes from their exact Maven Central
coordinates; every existing trusted checksum was preserved. The coordinate/hash/source log is
[`pev-core-artifact-verification.json`](pev-core-artifact-verification.json). No verification mode
was weakened. The snapshot itself resolves to a local project, not a downloaded binary.

Kotlin is Apache-2.0, JUnit4 is test-only EPL-1.0, and Kover 0.9.9 is build/test-only Apache-2.0;
the external core's `SOURCE_PROVENANCE.md` records upstream license links and instrumentation
dependencies. Those tools retain their own licenses. Their inclusion does not change the MIT
core or GPL consumer licenses.

The new artifact identities were also audited by reading their exact versioned primary-repository
POM license declarations and inspecting the verified JAR contents. All nine coordinate identities
declare Apache-2.0: Kover plugin marker/plugin/features/agent 0.9.9; IntelliJ coverage agent/reporter
1.0.765 and report 1.0.25; FreeMarker 2.3.32; and Apache parent POM 17 (metadata only).
FreeMarker's JAR additionally includes the Apache license at `META-INF/LICENSE`.
The coordinate-specific POM URLs/hashes and inspected JAR hashes are recorded in
[`pev-core-license-audit.json`](pev-core-license-audit.json).

Both the IntelliJ agent and repackaged Kover agent contain 64 shaded ASM classes under
`org/jetbrains/coverage/org/objectweb/asm`. ASM retains its separate
[BSD-3-Clause license](https://asm.ow2.io/license.html); the outer Apache declaration does not
relicense those classes. These are build/test instrumentation, with upstream
[Kover 0.9.9 license](https://github.com/Kotlin/kotlinx-kover/blob/v0.9.9/LICENSE.TXT) and
[IntelliJ coverage license](https://github.com/JetBrains/intellij-coverage/blob/master/LICENSE).
No instrumentation tool was added as an application runtime dependency, and the two generated
debug APKs contain the shared MIT M365 core class without the coverage-agent class namespace.
No new runtime SDK was introduced. Existing proprietary Rokid/native SDK licensing was not
re-audited or relabeled by this change.

## Isolated validation (2026-10-08)

The external module snapshot uses these SHA256 hashes (file bytes, including existing line endings):

| File under `pev-protocol-core` | SHA256 |
|---|---|
| `src/main/kotlin/io/github/zero2005x/pev/core/codec/xiaomi/XiaomiMotorInfoDecoder.kt` | `bb2150aebd8fe5a7559b1d18b464d4b39251255fde186b5c4f13a3c4a9aad4c0` |
| `src/main/kotlin/io/github/zero2005x/pev/core/codec/xiaomi/XiaomiPdu.kt` | `603a7d9cf6515710f7bca71451091907215fb6e8a5a1eca6e23b703e16dd73a8` |
| `src/main/kotlin/io/github/zero2005x/pev/core/codec/xiaomi/XiaomiBmsDecoder.kt` | `a3fb244bdbe4fdb1bcec531e0e97a4d4ca510d56be2c66afee2f1cb28a6b582a` |
| `build.gradle.kts` | `c44c0252188e9e8f54ad9136017aaf6f5c0b15acfff69d04defd5b17f59678f0` |

The source snapshot was staged independently under `/home/kali/build/rideflux-pev-core-source`;
consumer output stayed under `/home/kali/build/rideflux-pev-consumer-stage`. The live owner
checkout and HUD source were not modified. A later HUD decoder comment correction does not
alter these validated wire operations; use the hashes above to reproduce the exact snapshot.

The final locked run used Gradle 9.6.0, Java 21, Kotlin 2.3.21, JVM target 17 and
`--offline`, without writing verification metadata:

```sh
./gradlew --no-daemon --offline -PpevCoreDir=/home/kali/build/rideflux-pev-core-source \
  :domain:test :data:protocol:test :data:protocol:jacocoTestReport \
  :pev-core-source:pev-protocol-core:test \
  :data:protocol:dependencyInsight --dependency pev-protocol-core --configuration testRuntimeClasspath
```

Result: **BUILD SUCCESSFUL**. Domain 123 tests, protocol 395 tests (including six new synthetic
consumer tests), and core 166 tests passed with zero failures, errors or skips. Gradle reports
`io.github.zero2005x.pev:pev-protocol-core:0.1.0-SNAPSHOT -> project ':pev-core-source:pev-protocol-core'`
with a JVM 17 runtime variant. The generated core JAR SHA256 was
`7e5144a48a717c0e5824e74dd4bfed5ebbd3a9f8a4d65183c78310d2e4d3776c`.

JaCoCo for the `M365Codec` class reports 24/24 lines and 58/62 branches covered. The module
report includes existing unrelated codecs (3122/3789 lines, 1754/2713 branches); no repository-wide
coverage gate, hardware run or remote CI result is claimed. The remaining M365
branches include core-null guards that cannot be reached by a complete currently supported B0
layout. Exact test and coverage counters are in [`pev-core-validation.json`](pev-core-validation.json).
An independent read-only review found no new correctness blocker in the consumer wiring,
framing, SOC/odometer/temperature delegation or retained legacy behavior.

## Android validation (2026-10-08)

The applicable build documentation requires Java 17/21, API 36 and Build-Tools 36.0.0;
those packages were present in the isolated SDK. No lint baseline was configured and no new
exclusion, suppression, credential embedding or verification exception was added. The actual
related-module tasks were run with strict checksum verification:

```sh
./gradlew --no-daemon --offline --continue -PpevCoreDir=/home/kali/build/rideflux-pev-core-source \
  :app:lintDebug :hud-app:lintDebug :app:assembleDebug :hud-app:assembleDebug
```

Result: **BUILD SUCCESSFUL**, 314 actionable tasks, in 6m 46s. Both lint tasks passed with zero
errors/fatal issues. Phone lint reports 37 warnings; HUD lint reports 13 warnings, including three
`Aligned16KB` warnings for existing SDK native binaries. The build also reported existing native
libraries packaged without symbol stripping and a D8 `Companion` warning in Play Services Location
21.3.0. These were not suppressed; native hardware behavior and SDK alignment remain unverified.

Both debug APKs assembled from consumer commit `eb941870c583bed86072f304cd5e0e2cbddffc1a`:

| Artifact | Bytes | SHA256 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 68257266 | `562b7b4d1b32167cbaf71b87d180e52b5e46636b33762ff0a75e3f8e68277517` |
| `hud-app/build/outputs/apk/debug/hud-app-debug.apk` | 62729188 | `1c146642561e300e849226a56c59a8c977930e64cea784b448b0c0006b43f0d3` |

Each APK contains the shared `XiaomiMotorInfoDecoder` class in DEX. They retain the selected
baseline version 0.1.10/versionCode 11; the owner's newer 0.1.11/versionCode 12 commit is preserved
in the original checkout and has not been merged into this isolated branch. Exact lint issue
counts, report hashes, APK paths/hashes and class-presence checks are in
[`pev-core-android-validation.json`](pev-core-android-validation.json). No missing package,
credential, compilation or lint error blocked this run. No remote CI, installation or vehicle
hardware verification was performed.


## Latest authoritative core validation (2026-10-08)

The earlier results above are historical snapshots. The external core was refreshed from HUD
code revision `bd3913d9c917f2eeccc60db0428b81fb6cdae4cc`; every main Kotlin source file was
byte-compared with the authoritative source before recording results. Consumer code stayed at
`c984f52fb7415e8d376b3a4ba45b89dbd86358e3`, on the explicit older base `9bd9eaa`.

```sh
./gradlew --no-daemon --offline --max-workers=2 \
  -PpevCoreDir=/home/kali/build/rideflux-pev-core-source \
  :domain:test :data:protocol:test :data:protocol:jacocoTestReport \
  :pev-core-source:pev-protocol-core:test :pev-core-source:pev-protocol-core:koverXmlReport \
  :app:lintDebug :hud-app:lintDebug :app:assembleDebug :hud-app:assembleDebug
```

Result: BUILD SUCCESSFUL in 4m 27s, 326 tasks (36 executed / 290 up-to-date), with strict dependency
verification and no new dependencies. Domain 123 + protocol 395 + core 182 = **700 tests**, zero
failures/errors/skips. Core Kover: LINE 930/932 (99.79%), BRANCH 959/1013 (94.67%), both above 90%.
M365Codec remains 24/24 lines and 58/62 branches. Both lint tasks report zero errors/fatal issues,
with phone 37 / HUD 13 warnings. Both debug APKs assembled:

| Artifact | Bytes | SHA256 |
|---|---:|---|
| Phone | 68460100 | `eb51b2505bda5300f191bd2850bed49c618ca678bc7989f8018a70d2015c131f` |
| HUD | 62932019 | `351e3bed6b28cfc6b111ca562cd93974de74f7fa4bfa37bcb2b7362c5c56d6a7` |

Actual DEX class definitions contain both XiaomiMotorInfoDecoder and InmotionI1Codec in both APKs;
neither APK defines Kover/JetBrains coverage-agent classes. Packaging the I1 class does not wire
Inmotion selection, physical telemetry or settings into RideFlux. This new decoder provides raw
receive framing only. Consumer version remains 0.1.10/code 11; preserve newer owner version,
privacy, token-log and diagnostic-log commits when integrating. No owner checkout modification,
merge, push, install, vehicle write, remote CI, external Sonar or hardware acceptance occurred.

Exact source/JAR/APK/log hashes, counters and task names are in
[`pev-core-latest-validation.json`](pev-core-latest-validation.json). Copied local artifacts/reports
remain ignored under `build/pev-core-validation/`. The core JAR uses the consumer's Kotlin 2.3.21
toolchain with JVM target 17; HUD independently validates the same source using Kotlin 2.2.10.
The previous checksum/license audits remain applicable; no dependency trust exception was added.
