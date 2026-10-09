# T00 - The 12:50:13 RELAYING exit (2026-10-08): hypothesis list

**Root cause: NOT PROVEN.** No log or bugreport was available. This note is built from code reading
only (main = 7bf2283, v0.1.11). Nothing below may be quoted as the cause until the matching log line
from the right-hand column exists in a saved diagnostic log.

## What "left RELAYING" means in the code

`BridgeState` (`app/.../bridge/BridgeService.kt`): `STOPPED, STANDBY, ATTACHING, RELAYING, DEGRADED`.
`RELAYING` is set in exactly one place: the wheel loop in `reconnectingWheelFrames` when a snapshot
has `connectionState == Ready` **and** `frame.ready`. `frame.ready` is `Ready && !stale`, and `stale`
is "no new wheel telemetry timestamp for more than 3 s" (`STALE_THRESHOLD_MILLIS`, evaluated on a
1 s tick), or no frame yet. So `RELAYING` already means "wheel link up AND data fresh".

Important scoping facts:

- The watchdog (PRs #24-#26, `LinkSilenceWatchdog` / `endWhenSilent`) and the hung-link 4 s timeout
  (#55) live on the **glasses/HUD side** and in the GATT client. They never call `setBridgeState`;
  they cannot move the phone's `BridgeState` out of RELAYING. They can only make the HUD reconnect.
  If the rider saw the HUD stall, that is a separate fact from the phone state leaving RELAYING.
- `BridgeState` is process-global and driven only by the phone-to-wheel side. The phone-to-glasses
  link has its own `GlassesLinkState`.
- The pipeline-death path was already fixed before 0.1.11 (`aa2fe10`, `restartingOnFailure`): the
  code comment there records that on 2026-10-08 12:50 an unexpected pipeline exception had left the
  HUD on standby frames for ten minutes. That is one candidate, now self-healing, see H3.

## Every path that leaves RELAYING

| # | Exit path (code) | New state |
|---|---|---|
| a | Snapshot Ready but `frame.ready == false` (telemetry stale) | DEGRADED |
| b | Snapshot `Disconnected` or `Failed` -> `WheelLinkEnded` -> loop end | DEGRADED, then standby frames, reconnect (ATTACHING) |
| c | `snapshots` flow completes (repository closed it) -> `WheelLinkEnded` | DEGRADED |
| d | Any exception inside the wheel loop's `collect` (catch-all `Throwable`) | DEGRADED, reconnect |
| e | Exception outside the loop's try/catch -> `onPipelineFailed` | DEGRADED, pipeline rebuilt after backoff |
| f | `ACTION_SET_TARGET` (re-target, also by the UI/dashboard) | ATTACHING |
| g | `ACTION_CLEAR_TARGET` -> `flatMapLatest` -> idle | STANDBY |
| h | Bluetooth adapter `TURNING_OFF/OFF` -> `releasePublisher` | DEGRADED |
| i | `onDestroy` (service stopped / killed / `stopService`) | STOPPED (sets `_state` directly, bypasses `setBridgeState`) |
| j | Wheel connect/handshake phase after a reconnect: `Connecting/Handshaking` snapshot | ATTACHING |

## Hypotheses, ranked

Ranking is by prior plausibility given the reported facts ("A2 link stayed healthy", state left
RELAYING at an exact second, cause unproven). It is a ranking of suspicion, not of evidence.

### H1 - Telemetry went stale while the A2 link stayed Ready (path a). Most likely.
- Trigger: A2 stops producing a new `telemetry.timestampMillis` for more than 3 s (a stall in the
  decoder or notification path, a burst of malformed/unrecognised frames, a periodic frame the A2
  skips, the repository not re-publishing an unchanged sample). The connection stays `Ready`, so the
  wheel UI looks healthy and no disconnect is logged.
- For: matches "A2 link healthy" exactly; the threshold is only 3 s; it is the one exit that needs no
  error at all; it flips back to RELAYING silently when data resumes (flap).
- Against: none from code. Would need the A2 telemetry timestamp history to see the gap.
- Confirming line (NEW): `[bridge] leaving RELAYING: conn=Ready ready=false stale=true frameAgeMs=N`
  immediately before `state RELAYING -> DEGRADED`, N a little over 3000.
- Distinguishable before this PR: **no.** Only the bare `state RELAYING -> DEGRADED` was logged.

### H2 - Wheel link actually dropped (paths b, c, d).
- Trigger: `ConnectionState.Failed` (BLE_LINK_LOST/GATT_ERROR) or `Disconnected` after having been
  active; or the repository flow completing.
- For: the most common real-world cause on a moving rider (body shadowing, range).
- Against: the owner observed the A2 link healthy (but "healthy" may have been judged from the
  wheel's own screen, or after the fact, so not conclusive).
- Confirming lines: `[bridge] leaving RELAYING: conn=Failed(<reason>...)` or `conn=Disconnected`,
  then `wheel link **:EE:FF ended: WheelLinkEnded wheel link ended (last state Failed(...))`.
- Distinguishable before: **partly.** `wheel link ... ended: WheelLinkEnded wheel link ended` was
  logged but the Failed reason and the last state were lost (the exception message was constant).
  Now they are in the message.

### H3 - Frame pipeline threw outside the wheel loop (path e).
- Trigger: any unexpected exception in `combine`, `readPhoneBatteryPercent`, settings access, etc.
- For: this is what the `restartingOnFailure` comment says happened at 12:50 on 2026-10-08; it ended
  the stream for ten minutes in the older build.
- Against: since `aa2fe10` the pipeline restarts itself, so on 0.1.11 it would show as a short
  DEGRADED gap with a `pipeline failed:` line, not a long outage. If the incident build was 0.1.11,
  this is only plausible as a short blip. Which build was running at 12:50:13 is not recorded here.
- Confirming line (already present): `[bridge] pipeline failed: <Class>: <message>`.
- Distinguishable before: **yes.**

### H4 - Rider/UI re-targeted or cleared the wheel (paths f, g).
- Trigger: dashboard/scanner calls `setTarget` / `clearTarget` (e.g. navigating to the scanner,
  reconnect button, `ScannerRoute` waiting for STANDBY).
- Confirming lines (already present): `[bridge] target set ...` or `[bridge] target cleared`.
- Distinguishable before: **yes.**

### H5 - Phone Bluetooth toggled, or the Bluetooth stack restarted (path h).
- Trigger: adapter `TURNING_OFF/OFF`, including Android moving to BLE-only scanning mode, which is
  reported to apps as OFF (see `bluetoothStateAction` KDoc).
- Confirming lines: `[bridge] bluetooth down: publisher released` (already present) and, NEW, the raw
  `[bridge] bluetooth adapter state N -> ACTION`, which also shows adapter states that were ignored
  (transitions that did not release the publisher) and the later `REOPEN_PUBLISHER`.
- Distinguishable before: yes for the release, no for ignored/other states and for the return.

### H6 - Service destroyed or killed (path i).
- Trigger: OS reclaiming the process, `stopService`, foreground-service timeout, user swipe.
- Confirming lines: `[bridge] service destroyed`, and a fresh `[app] process start ...` /
  `[bridge] service created` afterwards (a kill with no destroy shows only the latter).
- Distinguishable before: yes (note `onDestroy` writes `_state` directly, so there is **no**
  `state RELAYING -> STOPPED` line; the `service destroyed` line stands in for it).

### Not an exit, but easily mistaken for one: the glasses link (not a BridgeState change)
If the rider saw the HUD stop but the phone stayed RELAYING, the cause is on the phone-to-glasses
link (`GlassesLinkState`, the HUD watchdog from #24-#26, hung-link timeout #55). Before this PR the
phone log recorded **no** glasses-link transitions at all, although the `DiagnosticLog` KDoc promises
"glasses connecting and leaving". NEW: `[bridge] glasses link A -> B` on every change.

## What the pre-existing log could and could not say

| Question | Before | After this PR |
|---|---|---|
| Did the state leave RELAYING, and when | yes (`state RELAYING -> X`) | same |
| Why: stale telemetry vs link drop (H1 vs H2) | **no** | `leaving RELAYING: conn=... stale=... frameAgeMs=...` |
| Failed reason / last state of a wheel link | no (constant message) | in `wheel link ... ended` message |
| Glasses link up/down on the phone | **no** | `glasses link A -> B` |
| Raw Bluetooth adapter state | partly | `bluetooth adapter state N -> ACTION` |
| Pipeline failure / target change / destroy | yes | same |

Known remaining gaps (not changed, to stay within scope):
- A flapping RELAYING <-> DEGRADED is collapsed by `DiagnosticLog` only for **identical consecutive**
  lines; `frameAgeMs` makes the "leaving RELAYING" lines differ, so a flap is visible rather than
  collapsed (intended), at the cost of ring space. 128 KiB x 2 files is ample for a ride.
- The wheel decoder's own view (why no new timestamp) is not logged here; if H1 is confirmed the
  next step is a wheel-side line in the A2 connection (outside this task).
- The log has no build tag per line; `[app] process start <version> (<code>)` at the head of a run
  is the only version marker, so check it first.
- `GlassesLinkState` transitions from the Rokid CXR publisher go through the same `setLinkState`
  and are covered.

## How to use the next log

1. Find the first `state RELAYING -> DEGRADED` (or `-> ATTACHING/STANDBY/STOPPED`) near the time.
2. Read the lines immediately **before** it:
   - `leaving RELAYING: conn=Ready ... stale=true` -> H1 (telemetry stall; link fine).
   - `leaving RELAYING: conn=Failed(...)` / `Disconnected` -> H2.
   - `pipeline failed:` -> H3. `target set` / `target cleared` -> H4.
   - `bluetooth adapter state ... RELEASE_PUBLISHER` -> H5.
   - `service destroyed` -> H6.
3. Check `glasses link ...` lines in the same minute to decide whether the HUD problem is the phone's
   wheel side at all.
4. Only then write "proven" in a follow-up, quoting the line.

## Code change in this PR (logging only, no behaviour change)

`BridgeService.kt`: `noteLeavingRelaying` (one record call while in RELAYING), a message detail on
`WheelLinkEnded`, a record call in `onBluetoothStateChanged`, a record call in `setLinkState` on
change. `RelayingExitReasonTest` pins the wording of the pure helpers. No state, timing, recovery or
publisher logic was touched.

## PR #57 CI repair (2026-10-09)

The original GitHub Actions run `37877126945` passed lint, build, unit tests and coverage
generation. Its SonarCloud step failed because new-code coverage was **62.5%**, below the
**80%** gate. This failure does not identify the cause of the 12:50 incident.

`BridgeServiceFramesTest` now installs a temporary diagnostic log and exercises the service's
frame collector across these transitions:

- Ready/fresh -> another fresh sample: no exit diagnostic.
- Ready/fresh -> missing telemetry -> fresh telemetry: exactly one stale-telemetry exit,
  logged before the state change, then recovery without closing the healthy wheel link.
- Ready -> Failed(BLE_LINK_LOST, status 8), and Ready -> Disconnected: last-state details
  reach the loop's catch, the address remains masked and the connection closes once.
- Glasses READY -> CONNECTED -> CONNECTED -> READY: only actual changes are logged,
  and the wheel's RELAYING state is preserved.

These tests supplement the four pure-helper tests; they do not change production behavior,
recovery timing, protocol constants or coverage exclusions. The temporary diagnostic sink is
removed after every test. The snapshot-flow completion line remains outside these scenarios.

Targeted validation:

```text
.\gradlew.bat :app:testDebugUnitTest --tests '*RelayingExitReasonTest' --tests '*BridgeServiceFramesTest'
BUILD SUCCESSFUL in 4m 13s
132 actionable tasks: 132 executed
```

The reports contain 11 `BridgeServiceFramesTest` tests and four `RelayingExitReasonTest` tests,
with zero failures or errors. Hardware behavior and the incident's root cause remain unverified.
