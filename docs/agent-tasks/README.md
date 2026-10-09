# RideFlux agent task pack

Roadmap agreed 2026-10-09 (grilling session, decisions Q1–Q21). Work is split into small
tasks that cheaper models can pick up and hand to one another. **Claude (Fable/Sonnet) is the
gatekeeper**: it writes and reviews prompts, never the bulk code.

## How the relay works

1. The owner (or gatekeeper) pastes `_COMMON.md` **plus one task section** (the `# Txx` block
   inside the matching `Txx-*.md` file; several tasks share a file) into a worker agent.
   Suggested tier: cheap model for T01/T12, mid for T00/T04/T06/T10/T13, strong for T02/T03/T07/T08/T09/T11.
2. The worker does exactly that task, opens a PR (never merges), and fills in the
   *Hand-off report* at the bottom of its task file's template (see `_COMMON.md` §Report).
3. The gatekeeper reviews the PR against the task's **Acceptance** list and the
   **Gatekeeper checklist** in `_COMMON.md`, then either:
   - replies `ACCEPT` → owner merges (merges are blocked for agents), or
   - replies `REWORK: <numbered defects>` → same worker (or the next one) continues.
4. A task may start only when every task in its `Needs:` line is **merged**.
5. If a worker is stuck for more than one attempt on the same defect, escalate to the
   gatekeeper instead of guessing. Guessing protocol bytes is the one unforgivable failure.

## Task index and order

| ID | Title | Needs | Release | Human step |
|---|---|---|---|---|
| T00 | Investigate the 12:50 RELAYING stall, tag v0.1.11 | – | 0.1.11 | owner tags/uploads |
| T01 | Clear BridgeService Sonar debt | T00 | – | – |
| H1 | **Owner**: short A2 capture (charge-idle + a short ride) | – | – | owner rides |
| T02 | GATT signature + generation detection (A2 unchanged) | T01 | 0.1.12 | – |
| T03 | Begode BMS packets 1/2/3/5/6 | T02 (H1 optional) | 0.1.12 | A2 device test |
| T04 | Battery model (dual pack, health, cycles, V×I power, range) | T03 | 0.1.13 | A2 device test |
| T05 | Alarm engine (5 quantities × 2 states) | T04 | 0.1.14 | A2 device test |
| T06 | Rule engine, autoTorch / autoVolume first | T05 | 0.1.14 | – |
| T07 | M365 read decoder + crypto (JDK only) with KATs | T02 | after 0.1.14 | – |
| T08 | Inmotion V2 fixes + query scheduler | T07 | after 0.1.14 | – |
| T09 | Write-byte research (open source + DarknessBot dynamic) | – (parallel) | – | owner's own devices |
| T10 | `WheelCommand` vocabulary, all `Unsupported` | T05 | 0.2.0 prep | – |
| T11 | Write encoders, flavors, safeguards | T09, T10, T07 | 0.2.0 | owner device tests |
| T12 | i18n glossary + 18-language fill | runs with every PR | – | – |
| T13 | Release procedure | any release | – | owner uploads |

Deferred (do **not** start): Ninebot retail (ES/G30/GT2), KingSong/Veteran/VESC decoders,
anything needing INTERNET permission, cloud sync.

## Status board (gatekeeper updates)

| ID | State | PR | Gatekeeper note |
|---|---|---|---|
| T00 | todo | | |
| T01 | todo | | |
| H1 | todo | | |
| T02 | todo | | |
| T03 | todo | | |
| T04 | todo | | |
| T05 | todo | | |
| T06 | todo | | |
| T07 | todo | | |
| T08 | todo | | |
| T09 | todo | | |
| T10 | todo | | |
| T11 | todo | | |
| T12 | todo | | |
| T13 | todo | | |

## Decisions that bind every task (from the grilling session)

- Depth on A2 first, then M365, then Inmotion V2. KingSong/Veteran/VESC later.
- Non-A2 wheels: read-only decoders, Experimental label, unit tests only.
- Writes: implement everything that has real bytes, including PID/`custom*`; items with no
  recovered bytes stay `Unsupported` until T09 finds them. Defences kept: non-Verified models
  off by default, 3 fresh zero-speed readings before dangerous writes, every write logged.
  Play flavour exposes A2 writes only; GitHub flavour exposes all.
- No INTERNET permission, ever. No cloud, no electro.club.
- L1 sources (EUC World, DarknessBot reverse engineering) are *leads*, never production
  constants. A constant needs A2 hardware, a public spec, or an owner capture.
- Dynamic analysis only on the owner's own devices; record byte formats only; never bypass
  authorisation or encryption protection.
- Crypto is hand-written from spec with JDK/Android providers. Do not copy `ninebotcrypto`
  (AGPL, provenance unrecorded) or EUC World's A1 0.74–1.554 multipliers.
- New strings: machine-translate all 18 languages in the same PR, using the glossary (T12).
- Sonar new-code coverage ≥ 80 % and gate must pass.
