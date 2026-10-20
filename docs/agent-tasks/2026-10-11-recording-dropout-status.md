# Recording dropout takeover — 2026-10-11

## Provenance and scope

Reconstructed the first batch described in Claude's handoff in the actual Windows checkout, on `codex/recording-dropout-recovery`, based on `d15d26c35b0b1cfea107d21e0b0ac7214046a327`. The handoff's commit `d8e3435` and patch were not available locally; this is a separately implemented and verified change, not an application of that commit. No remote push or phone installation was performed.

The backup shows a trip ending at 16:46:14.102 and another starting at 16:46:25.322 (UTC+08:00). Their boundary is consistent with the previous recorder's stop/restart policy. It does **not** prove that stale telemetry, a specific GATT failure, screen lock, or process throttling caused this incident. Recorder timestamps and system notification events cannot establish BLE causality. The approximately 18.5 m gap calculation in the handoff is a synthetic statistics example, not a measured error in the user's trips: all 23 exported trips have no within-trip sample gap above two seconds.

## Implemented behavior

- Keep an existing trip open for 30 seconds after the first poll without fresh telemetry or a Ready connection. This includes asynchronous initial connection startup; it is bounded rather than waiting forever.
- Use a monotonic clock for that grace period. Recorder/sample timestamps retain their existing wall-clock convention.
- Preserve the existing inclusive three-second telemetry freshness window. Once stale, skip samples, reset the distance integration baseline, and reset the consecutive idle counter. Resume the same trip ID if this same connection becomes usable within the grace period.
- A terminal `Failed` handle still ends the recording immediately. This patch does not acquire a replacement handle, implement Bluetooth retries, or establish recovery from a real GATT failure.
- Null/nonfinite speed is unknown: do not synthesize zero, change speed maxima/averages, integrate an interval, or count it as stationary. Other available summary metrics can still update.
- A statistics interval longer than 2.5 seconds contributes no distance, weighted average, or moving duration. The next valid sample establishes the new baseline. Ordinary zero-speed samples still affect the average as before.
- Preserve cancellation, database finalization and release of location/connection resources.
- Add recorder start/pause/resume/ending events to the existing bounded, user-exportable diagnostic ring. Ending reasons distinguish silence timeout, idle timeout, terminal failure, cancellation and session error. They contain no address, coordinates, raw packets or exception messages. Cancellation does not distinguish a user stop from service destruction. These events are recorder diagnostics, not complete GATT tracing.

## Validation

The first Gradle run against the original production code reproduced failures for short-silence recovery, unknown-speed idle stopping, gap integration and unknown-speed statistics. A stationary fixture initially included one moving sample; that test setup was corrected to start at zero speed.

`gradlew.bat :app:testDebugUnitTest --tests "com.rideflux.app.recording.*" --no-daemon --console=plain` passed: 31 tests, zero failures/errors (RecordingService 13, RecordingSession 14, TripStatistics 4). These tests use the real Android modules and coroutines test scheduler, not a separate Kotlin/stub harness.

The full `:app:testDebugUnitTest` XML reports contain 41 classes and 250 tests, with zero failures/errors/skips. `:app:assembleDebug` completed and produced `app/build/outputs/apk/debug/app-debug.apk`.

The combined full-tests/build/lint invocation was deliberately interrupted after approximately 25 minutes because lint had not completed or produced a new report. Thread inspection showed Kotlin/UAST analysis rather than a Maven download failure. The client consequently reported `Gradle build daemon disappeared`; this was caused by stopping this invocation's daemon, not evidence of an app crash. **Lint is incomplete and cannot be claimed as passing.** The existing lint report predates this patch and is not its validation. Re-run `gradlew.bat :app:lintDebug --no-daemon --console=plain` separately and investigate the toolchain analysis if it again fails to finish.

Local build startup initially failed in JDK Unix-domain socket initialization (`Invalid argument`). A process-local `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<short local directory>` allowed Gradle to start with JDK 21. No user-specific JDK or temporary-directory path was added to project configuration. This local failure differs from the proxy limitation reported in Claude's sandbox; cached dependencies are sufficient for the checks attempted here.

## Remaining work / next agent prompt

Continue from the current checkout and inspect the diff before changing it. Do not reapply Claude's missing patch or revert the recorder regressions. Read `2026-10-11-rideflux-improvement-prompts.md` and the backup-analysis addendum for the full task specifications.

1. Complete Task A's stable connection ownership and bounded reconnect strategy. A terminal Failed handle currently still splits a trip; preserve a logical ride session across replacement handles without duplicate GATT clients, unbounded retries or delayed manual cancellation. Add privacy-safe GATT/state/freshness events and correlate them with the recorder ending reasons.
2. Validate with a Begode A2 and Redmi Note 11 Pro+ 5G while foregrounded, locked and switched to another app. Confirm same-trip resumption, no stale distance inflation, cancellation responsiveness and GPS availability. The current patch has not been installed or tested on a physical phone.
3. Complete Task D's profile-aware A2 metric corrections. Do not expose offset 14's constant setting word as PWM, apply an unverified temperature conversion across every Begode firmware, or silently rewrite exported historical data.
4. Continue the documented multi-device capability model and independently verify the UI findings before implementing the prioritized interface changes. Those larger tasks are not completed by this batch.

Deliver reviewable code, relevant tests and accurate evidence. Do not describe these trip exports as raw-frame/GATT logs or claim the observed disconnect root cause is established.
