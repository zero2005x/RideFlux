# T04 — Battery model

**Needs:** T03 merged. **Release:** 0.1.13. **Model tier:** mid.

## Context
Missing today: dual-pack concept, pack health, charge cycles, corrected SOC, computed power
(Begode gives no power register: it is V×I), remaining-range prediction, charge/discharge time.
Several values are **computed, not read** — keep raw and corrected values side by side.

Read: `DARKNESSBOT_COMMAND_ARCHITECTURE.md` §4bis (`battery*`/`battery2*`, `correctedBatteryLevel`,
`updateDynamicEstimatedMileage`), `EUCWORLD_IMPLEMENTATION_2026-10-08.md` (SOC = piecewise-linear
lookup + clamp; do **not** copy the A1 multipliers), `BEGODE_A2_TRUTH_TABLE.md`, `CAP-B-PLAN.md`
(the 66.88 V / 100 % question is **open**: the raw word is 16S-referenced).

## Do
1. `BatteryPack` and `BatteryState` domain types: pack list (1–2), voltage, current, cells, delta,
   temperatures, cycles?, health?, each nullable.
2. `PowerCalc`: power = V × I (document sign convention; regen negative).
3. `SocEstimator`: piecewise-linear voltage→% table **per wheel profile**, empty/full voltage as
   profile data, `Custom` profile with no voltages shows `—` (never 0 %). A2 table values come only
   from a note at L2+ or the H1 capture; otherwise keep today's behaviour and mark the table `L1`.
4. `RangeEstimator`: remaining distance from recent Wh/km EMA; show `—` below a minimum distance sample.
5. UI: add the new tiles to the live dashboard, hidden when the source is null. Do not change existing tiles.

## Acceptance
- Pure-function tests including boundary voltages, null packs, regen, zero-speed divide-by-zero.
- Existing SOC display for the A2 unchanged unless H1 data justifies a change (cite it).
- Strings complete in 18 languages.

---

# T05 — Alarm engine (5 quantities × 2 states)

**Needs:** T04 merged. **Release:** 0.1.14. **Model tier:** mid-strong.

## Context
DarknessBot's matrix: quantities `{current, PWM, power, speed, temperature}` × states
`{configured threshold, exceeded}`. RideFlux has scattered overspeed/overtemp/low-battery/high-PWM
alerts with no common model. Alerts must keep punching through a blanked HUD.

Read: `DARKNESSBOT_COMMAND_ARCHITECTURE.md` (alarm matrix), `EUCWORLD_IMPLEMENTATION_2026-10-08.md`
(12 alarms → 11 vibrations; **motor load ≠ PWM**, load threshold default 0 = disabled).

## Do
1. `AlarmRule(quantity, threshold, hysteresis, minDurationMs, severity)` and `AlarmEngine` consuming the
   telemetry stream, producing `AlarmEvent(rule, state)`; edge-triggered with hysteresis so it does not chatter.
2. Migrate each existing alert onto the engine with **identical default thresholds and behaviour**.
3. Settings: thresholds editable per quantity; defaults unchanged.
4. HUD: reuse the existing alert pass-through path; do not change the bridge protocol version.
5. PWM source: only where the model has a trustworthy PWM. If PWM is unavailable (I1, Begode estimated),
   the PWM rule is disabled for that profile, not guessed.

## Acceptance
- Tests: threshold crossing, hysteresis, min-duration, disabled rule, null input.
- Regression test proving each legacy alert fires at the same inputs as before.
- No bridge protocol change (diff `BRIDGE_PROTOCOL.md` must be empty).

---

# T06 — Rule engine (condition → action), autoTorch / autoVolume first

**Needs:** T05 merged. **Release:** 0.1.14. **Model tier:** mid.

## Context
Zero-protocol feature: client-side rules over telemetry. DarknessBot's `autoTorchOn/OffSpeed`,
`musicVolumeControlMax/MinSpeed/MaxLevel/MinLevel`. The alarm engine already evaluates conditions;
reuse its condition model.

## Do
1. `Rule(condition, action, hysteresis)`; conditions: speed, battery %, temperature, PWM (where trustworthy).
   Actions: set headlight (only if supported by the connected profile), set volume (ditto),
   HUD notice, phone vibration.
2. Presets: *Auto headlight by speed* (on/off speeds), *Auto volume by speed* (min/max speed, min/max level).
3. Hard rule: an action that is a **wheel write** goes through the existing command gate and
   capability check; rules never bypass the zero-speed interlock. Headlight/volume are low-risk but still logged.
4. Persisted in the existing settings store; included in ZIP backup.

## Acceptance
- Tests: preset on/off behaviour, hysteresis, unsupported profile hides the preset, rule cannot fire a write the gate refuses.
- Backup/restore round-trip test.
