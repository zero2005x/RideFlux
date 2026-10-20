# T00 — Investigate the 12:50 RELAYING stall, prepare v0.1.11

**Needs:** nothing. **Model tier:** mid (needs log reading).

## Context
main is 0.1.11 (versionCode 12), not tagged, not uploaded. On the 2026-10-08 ride the bridge
left the RELAYING state at 12:50:13 while the A2 link stayed healthy; cause unproven. Recent
bridge work: PR #51 (diagnostic log), #54 (HUD visibility reset), #55 (hung-link 4 s timeout).

## Do
1. Read `docs/BRIDGE_PROTOCOL.md`, the bridge state machine (find `BridgeService` and the
   RELAYING state; use grep), and the diagnostic-log code from PR #51.
2. If the owner supplies a log/bugreport path, correlate 12:50:13 with the state transitions.
   If not, build the **hypothesis list** from the code: every path that leaves RELAYING
   (watchdog from PR #24–#26, hung-link timeout from #55, BT restart, permission revocation).
3. For each hypothesis state: trigger, observable log line, and whether the code makes it distinguishable.
4. Add only **missing log lines** needed to tell hypotheses apart (one small commit), with tests where cheap.
5. Do **not** change recovery behaviour in this task.

## Acceptance
- A markdown note `docs/agent-tasks/reports/T00-12-50-analysis.md` with hypotheses ranked, evidence for/against, and the exact log line that will confirm each next time.
- Any new log lines are covered by a test or justified.
- Report says explicitly: *root cause proven* or *not proven*. Never claim proven without a log line.

## Out of scope
Tagging, uploading, version bumps — the owner does those after the gatekeeper accepts.

---

# T01 — Clear the BridgeService Sonar debt

**Needs:** T00 merged. **Model tier:** cheap-mid.

## Context
Touching `BridgeService.kt` and neighbours fails Sonar's PR maintainability gate because old
issues are re-counted as new. Clearing them once makes every later PR cheaper.

## Do
1. Fetch the open Sonar issues for the files the bridge touches (the owner provides the Sonar
   project key / token path; read with `gh`-style CLI only if available, otherwise ask the owner for an export).
2. Fix **behaviour-preserving** issues only: unused code, duplicated literals, nested ifs, cognitive-complexity splits by extracting private functions, missing `when` branches.
3. One commit per file. No functional change. No renames of public API.

## Acceptance
- `.\gradlew.bat testDebugUnitTest lint` green, test count unchanged or higher.
- Sonar PR analysis shows the issue count for those files reduced and the quality gate OK.
- Diff reviewed: zero behavioural change (state this per function you split).
