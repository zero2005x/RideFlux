# T09 — Write-byte research (findings only, no app code)

**Needs:** nothing (runs in parallel). **Model tier:** mid-strong. **Output:** a markdown table + evidence. **No Kotlin.**

## Context
The owner wants every write implemented, including PID/`custom*` gains. Many writes have **names
but no bytes** (DarknessBot labels). This task finds the bytes from lawful sources so T11 can
implement real encoders, and states clearly what remains unknown.

## Allowed sources
1. Open-source apps already in `euc-programme/upstream/` (WheelLog, WheelDash, EUC-free-app) —
   **read the protocol facts and re-derive; do not copy code or comments**.
2. PEVAppRE findings: `CROSS_BRAND_COMMAND_REFERENCE.md`, `DOMAIN_COMMAND_COVERAGE.md`,
   `KINGSONG_COMMAND_VOCABULARY.md`, `KINGSONG_WRITE_COMMANDS.md`, `BEGODE_A2_ACTIONS.md`,
   `LEAPERKIM_PROTOCOL_GENERATIONS.md`, `DARKNESSBOT_COMMAND_ARCHITECTURE.md`, `SAFETY_GATING_ARCHITECTURE.md`,
   `M365_ROKID_HUD_CONNECTION_MODEL.md` (spec-grade M365 writes).
3. **Dynamic observation of DarknessBot 7.0.0 on the owner's own devices only** (HCI snoop log from
   the owner's phone while the owner changes a setting). Record byte formats only.
   **Forbidden:** bypassing licence/auth checks, defeating anti-analysis or encryption protection,
   touching anyone else's device. See `DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md`; if blocked, stop and report.

## Deliverable: `docs/agent-tasks/reports/T09-write-bytes.md`
One row per write in the 47 DarknessBot settings + 12 `custom*` + RideFlux's existing 16 commands:

| command | families | request bytes (hex, framing, checksum) | response/ACK | source | level (L0–L4) | risk (low/high/irreversible) | needs hardware to confirm |

Plus: a list of rows with **no bytes recovered**, and for each, the cheapest capture that would recover them.
Do not fill a cell with a guess; write `UNKNOWN`.

## Acceptance
- Every populated cell cites a file+line or a snoop log. Unknown cells are `UNKNOWN`.
- Risk column reviewed against `SAFETY_GATING_ARCHITECTURE.md` (G1–G5).

---

# T10 — `WheelCommand` vocabulary expansion (all Unsupported)

**Needs:** T05 merged. **Release:** 0.2.0 prep. **Model tier:** mid.

## Do
1. Extend `WheelCommand` from 16 to the full vocabulary from the T09 table: lock, limit mode, gyro level,
   transport mode, cruise, KERS/recuperation, brake light, shutdown timer, max roll, PWM limit, alarms,
   strobe, BMS capacity/cell count, tiltback, horn, plus Begode/KingSong/LeaperKim specifics, plus `custom*` gains.
2. Each type carries: `risk` (LOW/HIGH/IRREVERSIBLE), `needsZeroSpeed`, parameter range with units,
   and `supportedBy(profile)`. Ranges come from the notes (e.g. `SetPedalSpeed` 3–90) with level.
3. Every encoder returns `Unsupported` in this task. The command gate and capability UI must treat
   `Unsupported` as "hidden", never as an error dialog.
4. Fix `Horn` and `SetTiltbackKmh` being unencodable: make the unencodability explicit and tested.

## Acceptance
- Exhaustive `when` over `WheelCommand` everywhere (compiler enforces); tests for risk/zero-speed metadata.
- No bytes are emitted by anything new. Gate test: every HIGH/IRREVERSIBLE type requires the zero-speed interlock.

---

# T11 — Write encoders, flavours and safeguards (release 0.2.0)

**Needs:** T07, T09, T10 merged. **Model tier:** strong. **Highest risk task: the gatekeeper reviews line by line.**

## Decisions already made (do not re-open)
- Implement every write for which T09 recovered bytes at L2+, or L1 from ≥ 2 independent sources.
  Everything else stays `Unsupported` (no guessing). PID/`custom*` included **iff** bytes exist.
- Safeguards that must exist: (1) writes for non-Verified models are **off by default**, enabled per
  wheel in its settings, (2) the existing "3 fresh zero-speed readings" interlock for HIGH/IRREVERSIBLE,
  (3) every write logged locally (time, model, command, bytes, ACK seen or not). No extra confirm
  text step for PID/shutdown (owner's choice).
- Flavours: `play` exposes writes for **A2 only**; `github` exposes all supported. Gradle product
  flavour or equivalent; the Play build must contain **no code path** that enables other writes
  (compile-time constant, not a runtime flag).
- Keep `NOTICE`/README wording honest: "Experimental, owner-accepted risk".

## Do
1. Encoders per family (framing + checksum per notes). One file per family, KATs from the notes as tests.
2. Read-back where the vendor has it (ask-after-set symmetry: askAlarms/askBattery…); show the wheel's
   value, not the value we sent.
3. Capability UI: show a command only if encoder exists, model enabled, flavour allows.
4. Log viewer entry in settings; included in ZIP backup (no secrets).
5. Test matrix: encoder bytes vs recorded vendor-app bytes; gate refuses at speed > 0; refused when model not enabled; Play flavour compile-check.

## Acceptance
- Gatekeeper-run review of every encoder against its T09 row. Any row cited L1-single-source is refused.
- Report lists, per command: bytes, source, level, and "tested on hardware: yes/no". Untested-on-hardware items ship GitHub-only.
