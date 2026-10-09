# T02 — GATT signature + generation detection (A2 behaviour unchanged)

**Needs:** T01 merged. **Release:** 0.1.12. **Model tier:** strong-mid (design-heavy).

## Context
Today the wheel family is inferred from a single service UUID. FFE0+FFE1 is shared by Begode,
KingSong and Veteran-class devices; Nordic UART is shared by VESC, Inmotion V2 and Ninebot Z.
EUC World matches a full **GATT fingerprint** (service count equal, every service present,
every characteristic present, first match wins) after connect.

Read first (WSL findings): `GATT_FINGERPRINT_IDENTIFICATION.md`, `GATT_V_FAMILY_BEGODE_COLLISION.md`,
`EUCWORLD_IMPLEMENTATION_2026-10-08.md` §fingerprints, `BEGODE_PROFILE_SELECTION.md`,
`LEAPERKIM_PROFILE_SELECTION.md`, `APP_FINGERPRINT_DB.md`.
Then the current code: grep for the family-detection code and the current UUID table.

## Do
1. Introduce a `GattSignature` model: ordered list of (service UUID, set of characteristic UUIDs)
   plus an exact-service-count flag. Pure Kotlin, no Android types, so it is unit-testable.
2. Add a `FamilyDetector` taking the discovered services (as plain data) and returning
   `Detection(family, generation?, confidence)` where confidence is `EXACT | PROBABLE | AMBIGUOUS`.
   Ambiguous results must **not** auto-select; they fall back to the existing behaviour and
   log which candidates tied.
3. Seed it **only** with signatures backed by notes at L2+ or by the A2. Everything else:
   leave the existing UUID inference in place and mark `// TODO(T08): probe` — do not invent
   signatures. Record unproven signatures in the report.
4. Define (do not yet use for V2/VESC) a `ProbeStrategy` interface for active disambiguation
   (e.g. VESC `GetPkgInfo 0x65 0x00` over NUS) so T07/T08 can plug in. No probe is sent in this task.
5. Wire the detector in so A2 resolves identically to today. Add a regression test that feeds the
   A2's recorded service list and asserts family unchanged.

## Acceptance
- All existing tests green and unchanged; A2 regression test present.
- Tests: exact match, extra service (no match), missing characteristic, two candidates tie → AMBIGUOUS.
- Docs: add a short section to `docs/PROTOCOLS.md` describing the detector and its confidence levels.
- Report lists every signature added with its evidence level and source file.

## Out of scope
Active probes, new families, query scheduler.

---

# T03 — Begode BMS packets 1/2/3/5/6

**Needs:** T02 merged (H1 capture strongly preferred). **Release:** 0.1.12. **Model tier:** strong-mid.

## Context
The Begode/Gotway decoder ignores packet types 1/2/3/5/6, which carry BMS data (cell voltages,
temperatures, pack totals). This is also the root cause of "8 of 18 requested metrics missing".
Packet framing: `55 AA … 5A 5A 5A 5A`, 24 bytes, no checksum.

Read first: `begode-readpath.md`, `GOTWAY_APP_READ_PATH.md`, `BEGODE_A2_TRUTH_TABLE.md`,
`BEGODE_PROTOCOL_EVOLUTION.md`, `begode-frame-conflicts.md`, `w2-begode-packet4-7.md`,
`SMART_BMS_PROTOCOL_SPEC.md`, `CAP-A-a2-live-capture.md`, `BEGODE_NULL_PROPAGATION_AUDIT.md`.
Test data: WheelLog Begode fixtures already credited in `NOTICE`; and any capture the owner
placed under `docs/agent-tasks/reports/H1-*` (if present, it outranks every note).

## Do
1. Define pure `BmsFrame` types for each packet type: pack voltage/current, per-cell voltages (with
   cell count from the frame or config), temperatures, min/max/delta, SOC if present.
2. Implement parsers for types 1, 2, 3, 5, 6 **only where offsets are stated in a note at L2+
   or confirmed by the H1 capture**. For any type whose layout is ambiguous across notes, parse
   nothing, emit a `Raw` frame for logging, and list the conflict in the report.
3. Add the Begode request path for BMS pages **only if** the notes give the request bytes at L2+;
   otherwise stop at passive parsing (the A2 may already emit these unsolicited — state what you observed in fixtures).
4. Surface fields in the telemetry model as nullable; unknown stays null (see null-propagation audit).
5. Do not display anything new in UI in this task except the Parameters page raw list, if one exists.

## Acceptance
- Table in the PR body: packet type → fields → offsets → level → source note.
- Tests for each implemented type with real bytes plus short/garbled frames; no exceptions.
- A2 existing telemetry tests unchanged.
- Report names every layout conflict and what capture would resolve it.
