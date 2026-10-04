# RideFlux Phase 2 handoff

Updated 2026-10-04 (Asia/Taipei). This file records the working state for an agent resuming an interrupted turn. It does not claim that Phase 2 is complete.

**Phase 3 supersedes this file's Ninebot framing uncertainty.** See [PHASE3_HANDOFF.md](PHASE3_HANDOFF.md) for the SHU AOT result and current codec behaviour.

## Latest integration result

Four subagents completed the scooter, KingSong, Begode/Veteran and independent TDD assignments. The shared worktree remains uncommitted. The combined WSL JVM run `:domain:test :data:protocol:test --offline` passed: **75 domain + 223 protocol tests, zero failures**. `git diff --check` passed. No protocol or domain test imports `android.*`.

Phase 2 delivered read-only M365 and Ninebot retail structural codecs, KingSong offline frame/command classification, Begode and Veteran/NOSFET registries and explicit parsing profiles, and synthetic hex tests. This is a structural and read-path expansion; it is not 150-model hardware validation and does not enable new critical writes.

## Baseline already present in the shared worktree

- Phase 1 changes are uncommitted. Do not discard them. They add `PlevDevice`, peer wheel/BMS/scooter telemetry, `MotionInterlock`, `ClosedLoopVerification`, read-only JBD/Ant/VESC codecs, and the Inmotion 31-model registry.
- `WheelConnectionImpl.dispatch()` now checks three fresh zero-speed frames before critical commands and rejects `PowerOff` and `Raw` at the transport boundary. The old command types still exist for compatibility; do not describe them as unconstructible.
- Verified before Phase 2: `:domain:test` 75 tests, `:data:protocol:test` 197 tests, no failures. `:data:ble:compileDebugKotlin` passed in a clean Linux copy. The BLE unit-test task could not run offline because Robolectric 4.17 and MockK 1.14.11 were absent from the WSL Gradle cache.
- On Windows, Gradle currently fails to establish a loopback connection. WSL builds against `/home/kali/android-sdk` and `GRADLE_USER_HOME=/home/kali/ElectricUnicycleHacking/.gradle-home`. Building Android modules in the mounted Windows checkout can fail KSP with mixed Windows/Linux paths; use a clean Linux copy for Android compile checks.

## Phase 2 ownership (completed assignments)

- Scooter agent: `familyscooter` and its pure JVM tests; evidence-first Xiaomi M365 and Ninebot retail reads.
- KingSong agent: `familyk` model registry, frame and command taxonomy, matching tests.
- Begode/Veteran agent: `familyg` and `familyv` registries, parsing and matching tests.
- Integration/test agent: independent review, cross-family tests and final full `:domain:test :data:protocol:test` after the three implementation agents finish.

All agents share this worktree. Each owns its package and should leave unrelated files untouched. No agent should commit until integrated review.

The fourth agent added `Phase2WireInvariantTest.kt`, with independent literal vectors for M365, Ninebot, KingSong, Begode and a 44-byte Veteran CRC32 frame. The tests reject corrupted frames and make the synthetic L1 evidence label explicit.

## Evidence and safety boundaries

The authoritative research directory is `\\wsl.localhost\kali-linux\home\kali\ElectricUnicycleHacking`; consult `findings/` before implementing any disputed byte geometry or model parameter. `PLAN.md` says only Begode A2 has local wire-and-behaviour validation. Static or community-derived findings must not be presented as hardware-verified.

- Ninebot retail registers `0x70` and `0x71` are lock/unlock and are **T-CRITICAL** because they reset the scooter. `0x78` reboot and `0x79` power-down are **T-FORBIDDEN**. The Phase 2 prompt labels `0x70`/`0x71` incorrectly.
- Some retail length and checksum rules are in conflict in `NINEBOT_RETAIL_PROTOCOL_SPEC.md`. Unknown scaling or reply geometry should remain nullable or unsupported, never guessed into telemetry.
- The KingSong 193 figure is a classification inventory: 107 fixed-tail, 34 checksum-tail and 52 heterogeneous. Do not invent encoders for unknown commands.
- Begode `WY` pacing is a timed transport operation. Four identical frames returned as a list do not encode 500–600 ms spacing.
- The Veteran/NOSFET byte-3 length and profile selection questions are documented in `LEAPERKIM_BYTE3_POLICY.md` and `LEAPERKIM_PROFILE_SELECTION.md`. Do not silently select a profile by name or plausibility.

## Completion checks

1. Review `git status --short` and `git diff --check` without discarding the pre-existing Phase 1 changes.
2. Run the pure JVM domain and protocol suites from WSL with Java 21 and the cached Gradle home.
3. Report concrete files and test counts, distinguishing implemented read paths, structural registries and missing hardware evidence.
