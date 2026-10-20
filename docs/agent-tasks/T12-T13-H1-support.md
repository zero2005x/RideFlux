# T12 — i18n glossary + 18-language fill (cross-cutting, run per PR)

**Model tier:** cheap. **Needs:** none; first run creates the glossary.

## Do (first run)
1. Read `docs/LOCALIZATION.md` and list the 18 languages and where strings live.
2. Create `docs/GLOSSARY.md`: protocol/safety terms with the fixed translation in every language
   (BMS, cell, PWM, tiltback, cutoff, regen, odometer, pedal sensitivity, lock/unlock, calibrate, power off,
   zero speed). English and Traditional Chinese by hand; for the other 16 mark the entry `machine` and
   keep terms short. Terms where a mistranslation could mislead a rider are marked `SAFETY` — those get a native-speaker flag.

## Do (every later PR)
- For each new English key, add all 18 languages using the glossary; consistent placeholders and plurals;
  a script/test that fails when any language misses a key (create it if absent).

## Acceptance
- No missing keys in any language; no raw key shown; glossary referenced from `docs/LOCALIZATION.md`.

---

# T13 — Release procedure

**Needs:** gatekeeper ACCEPT on all tasks of the release. **Model tier:** mid. **The owner uploads/tags; agents never do.**

## Do
1. Bump `versionName`/`versionCode`; write `docs/releases/<version>.md` from merged PR titles (match 0.1.11 style).
2. Run the release build with the real signing config **without printing `local.properties`**; run the full test suite.
3. Install on the owner's phone only if the owner asks (needs release-signed APK: debug builds cannot install over release-signed).
4. Prepare the tag command for the owner; do not push the tag (it starts the public GitHub Release workflow).

## Acceptance
- Notes list limitations honestly (Experimental families, untested-on-hardware items).
- Sonar gate OK on main; CI green.

---

# H1 — Owner task: short A2 capture

**Not for agents.** Owner steps (run when convenient; ~30 minutes):
1. Read `CAP-B-PLAN.md` and `CAP-B_TOOLING_FIXES.md` in WSL findings; the tooling is already written and tested.
2. Capture A: wheel on a charger, idle, 5 minutes (BMS pages, 100 % behaviour).
3. Capture B: a short ride (a few km) with a speed variation and one stop. Note the odometer before/after and the dash trip meter.
4. Save raw captures under `docs/agent-tasks/reports/H1-*` (strip anything sensitive) and tell the gatekeeper.
Outcome: unblocks upgrading Begode PWM / mileage / BMS / 66.88 V constants from L1 to L3.
