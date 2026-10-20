# T07 — Xiaomi M365 read decoder + crypto

**Needs:** T02 merged. **Model tier:** strong (crypto + spec). Experimental label stays.

## Context
Only family with spec-grade registers: 40+ registers, `0xB0` offset, 12 writable. Session crypto
is ECDH → HKDF → AES-CCM with **published known-answer tests (KATs)**. Real-vehicle evidence exists:
Xiaomi M365 logcat and telemetry CSVs in `scooter-apps/hardware-evidence/` (read-only, in WSL
`\\wsl.localhost\kali-linux\home\kali\PEVAppRE\scooter-apps\`).

Read: `M365_ROKID_HUD_CONNECTION_MODEL.md`, `NINEBOT_XIAOMI_UPSTREAM_REFERENCE.md`,
`NINEBOT_ARCHITECTURE_MAP.md`, `docs/MI_AUTH.md`, `docs/M365_REVERSE_SPEED_2026-10-07.md`,
`scooter-apps/connection-models/`, and the existing repo M365 work (grep `M365`, `Xiaomi`, `Mi`).

## Do
1. Check what the repo already implements for M365/Mi (Phase_MI handoff docs). Do not duplicate; extend.
2. Register-map decoder: speed (with the documented reverse-speed handling), battery, voltage, current,
   temperatures, odometer. **Power is computed V×I**, not a register.
3. Crypto: ECDH (P-256 via JDK), HKDF-SHA256, AES-CCM — **JDK/Android providers only**. If CCM is missing
   on a min-SDK, implement CCM over AES/ECB per RFC 3610 with the spec KATs as tests. Do **not** copy or
   port `ninebotcrypto` or any AGPL source.
4. Replay the real logcat/CSV as regression tests (copy only the minimal fixture bytes into
   `src/test/resources` with a provenance comment).
5. Capability map: which registers are readable; mark all writable ones `NotYetEnabled` — T11 owns writes.

## Acceptance
- KAT tests pass; decoder tests pass against real captures; crypto has negative tests (bad tag, short nonce).
- No new dependency; no INTERNET permission.
- README/PROTOCOLS entry: M365 *Experimental, read-only, evidence level L2 (spec) + hardware logs*.

---

# T08 — Inmotion V2 fixes + query scheduler

**Needs:** T07 merged. **Model tier:** strong (architecture).

## Context
Findings: Inmotion V2 PWM is `data+8`, s16 little-endian, ÷100 (**L1 from EUC World — confirm
before use or flag**); current must be **signed**; the battery query uses flags `0x16` (RideFlux's
`0x11`/`0x14` currently throw); BMS slots `0x24–0x27` but only two handled. The decoder is stateless,
but V2 needs a **query schedule** (realtime every cycle, stats/battery staggered every ~20 cycles,
settings every ~120) and KingSong needs a wake-resend.

Read: `INMOTION_REFERENCE_DIFFERENTIAL.md`, `INMOTION_FULL_MODEL_MATRIX.md`, `INMOTION_I2_LAYOUT_GATE.md`,
`WHEELLIFE_ANALYSIS.md`, `INMOTION_VENDOR_APP_C10.md`, `EUCWORLD_IMPLEMENTATION_2026-10-08.md` (Inmotion section).

## Do
1. Fix the flags-throw bug first (own commit + test). Make the decoder return `Malformed` instead of throwing.
2. Signed current; four BMS slots; PWM field behind an explicit `L1` flag (see _COMMON evidence rule).
3. Scheduler in the BLE data layer: `Query(id, command, initialDelay, interval, timeout, retry,
   matchesResponse, mode = STREAM_ONLY | POLLED)`. Pure scheduling logic testable with a virtual clock.
4. Wire V2 to it; verify existing families keep the exact old behaviour (they use STREAM_ONLY).
5. Define the hook for KingSong `0x9B` wake-resend but **do not implement KingSong decoding** (deferred).

## Acceptance
- Virtual-clock tests for interval/timeout/retry/stagger.
- Regression: A2 and M365 paths untouched; test counts not lower.
- Report lists which V2 offsets are L1 and what hardware would confirm them.
