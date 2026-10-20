# T03 — Begode BMS: preserve evidence, withhold disputed readings

Base: `f3c0543` (latest main, T02 merged). Date: 2026-10-10.
No H1 files exist in `docs/agent-tasks/reports/` at implementation time.
Sources below are the owner's read-only PEVAppRE `euc-programme/findings/` notes;
only the minimal owner's CAP-A fixture was copied, not vendor application code.

## Packet evidence table

Offsets are zero-based in the 24-byte frame. Rows describing candidate physical fields
are **withheld**, not implemented constants. Conflicting notes are recorded together.

| Packet | Fields / decision | Offset | Byte order | Scaling | Evidence | Source file:line |
|---|---|---|---|---|---|---|
| all | Envelope: 24 bytes, header 55 AA, footer 5A×4; retained whole | header 0–1; footer 20–23 | byte sequence | none | L3 owner CAP-A + L2 vendor-static | BEGODE_A2_TRUTH_TABLE.md:27–38 |
| 1/2/3/5/6 | Type and discriminator only; Raw | type 18; discriminator 19 | u8 | none | L2 vendor-static routing inventory; type 1 also owner hardware | CROSS_BRAND_COMMAND_REFERENCE.md:236–250; BEGODE_A2_TRUTH_TABLE.md:33–38 |
| 1 | Pack voltage/current, temperatures, status: **Raw due to layout conflicts** | table puts actual voltage/current at 2/4, temps 8/10, pack voltage 12; other note puts them at 6/8, temps 10/12, pack voltage 14; apply() uses channel 6 | BE u16/s16 candidates | names suggest ÷10 but interpretation unresolved; CAP-A channel 2 ×0.08 matches type 0 voltage, not adopted for BMS | L2 vendor-static conflicting; L3 CAP-A discriminator/value relation | BEGODE_A2_TRUTH_TABLE.md:52–56,112–139; CROSS_BRAND_COMMAND_REFERENCE.md:227 |
| 2 | Cell page or calibration response: **Raw** | candidate channels 2–17; selector 19 | BE u16 candidate | cell voltage scale/count unknown | vendor-static, no wire cell-page evidence; superseded note not promoted | BEGODE_A2_TRUTH_TABLE.md:214–232; begode-frame-conflicts.md:114,121–123 |
| 3 | Cell page or mode response: **Raw** | candidate channels 2–17; selector 19 | BE u16 candidate | cell voltage scale/count unknown | same as type 2 | BEGODE_A2_TRUTH_TABLE.md:214–232; begode-frame-conflicts.md:115,121–123 |
| 5 | Cell page versus apparent no-op: **Raw** | no verified physical-field offsets | unknown | unknown | conflicting vendor-static notes; no hardware | begode-frame-conflicts.md:117; CROSS_BRAND_COMMAND_REFERENCE.md:240,248–250 |
| 6 | Candidate group-3 cell page: **Raw** | candidate channels 2–17; selector 19 | BE u16 candidate | cell voltage scale/count unknown | vendor-static only; superseded note not promoted | begode-frame-conflicts.md:118,121–123; BEGODE_A2_TRUTH_TABLE.md:230–232 |

`SMART_BMS_PROTOCOL_SPEC.md:3–14` covers JBD, Ant, and Ninebot internal BMS;
their offsets, scales and request bytes cannot establish Begode BMS semantics.
No unambiguous complete Begode BMS physical layout or L2+ request bytes were found
in the required notes or the additional cross-brand routing reference. Parsing is passive.

## Conflicts and the capture that would resolve them

1. **Type 1 field ordering:** truth table channel order versus cross-brand channel order.
   Capture type 1 alongside the vendor battery/BMS page showing voltage, signed current and
   temperatures, during charge-idle and a short ride; retain model, firmware and byte 19.
2. **Type 1 pack voltage:** channel 5 (`@12`) named pack voltage versus channel 6 (`@14`)
   used by apply(). Both were zero in CAP-A, so that capture cannot choose. A nonzero
   pack-voltage reading paired with the vendor BMS screen is required.
3. **Type 1 discriminator:** CAP-A always has byte 19 == 0; the legacy app uses a different
   body for 0 versus 24. Need both observed variants (if the wheel emits them) with screen
   correspondence. The exact channel-2 voltage relation alone does not settle BMS semantics.
4. **Community type 1 quantities:** `EUCWORLD_LINEAGE_DIFF.md:209,293` records offset 14
   half-pack voltage versus auxiliary temperature, and offset 8 current versus temperature.
   Offset 2 is also disputed as PWM-limit versus battery percentage
   (`EUCWORLD_LINEAGE_DIFF.md:208`). L1 leads only; use a dual-pack BMS capture with both pack voltages and temperatures,
   then nonzero signed current while riding/charging to distinguish quantities.
5. **Types 2/3 multiplexing:** byte 19 == 24 is a calibration/mode response; other values
   route to BMS. No response or cell-page capture exists here. Need a passive vendor BMS
   page session with cell count, page sequence and per-cell display. Control-triggered
   response capture is outside this task and must remain with the owner's later write work.
6. **Type 5:** early note reports apparent no-op; later routing note says applyBms. Need
   a real type-5 frame and corresponding vendor group/page, not an invented group-2 map.
7. **Types 2/3/5/6 units/count/group/page:** the superseded note suggests 8 slots/page,
   page 0–7 and groups 0/1/3. It gives no proven voltage conversion or configured cell count;
   another reference range-checks the selector <4 in a BMS context. Need complete pages
   with model/FW, BMS group ID, configured cell count and displayed cell voltages. Do not
   reuse a type-1 group selector as a cell-page count.

## Fixture observations and behavior

The existing NOTICE-credited WheelLog fixtures in BegodeDecoderTest are type 0/4 only.
The owner's CAP-A corpus (`evidence/capA_fixtures.json:1–11`) reports 2,856 each of
types 0/1/4/7 and no 2/3/5/6. It records only N/V identification requests
(`BEGODE_A2_TRUTH_TABLE.md:236–244`), so type 1 is unsolicited during that session.
The type-1 first frame at stream offset 24 has byte 19 == 0 and channels
80,0,836,0,0,0,0,0. This is not evidence of actual smart-BMS cell reporting or
proof that this A2 can ever supply cell pages (`BEGODE_A2_TRUTH_TABLE.md:230–232`).

Only the first 96-byte cycle is committed. Its stream/type-1 hashes and offsets are
in the resource header. Types 2/3/5/6 tests are explicitly synthetic envelope tests,
not hardware regression claims. Arbitrary payload bytes inside a valid envelope remain
Raw: this protocol has no checksum, so payload corruption cannot be detected honestly.
Short/oversized/header/footer-invalid standalone frames return typed Malformed;
the existing stream assembler continues buffering fragments and resynchronizing garbage.

Raw events update only the latest `bmsFrame`; they do not refresh timestamps, reuse an old
speed sample for the stationary interlock, change alerts, identify a wheel, or satisfy the
handshake watchdog. Subsequent ordinary telemetry keeps the raw frame, and disconnect
clears it. No derived min/max/delta, power, battery model, writes or user-visible strings.
Raw storage is latest-frame only, not a persisted multi-page log; pages can be consumed
from codec events, but no UI/history export is added in T03.

## Hand-off report

TASK: T03
BRANCH / PR: feat/T03-begode-bms (PR linked in the worker hand-off)
SUMMARY:
Experimental passive Raw support for types 1/2/3/5/6; disputed payloads stay uninterpreted.
Pure Kotlin BmsFrame provides nullable pack/cell/temperature/extrema/SOC quantities.
Complete raw bytes reach telemetry without affecting freshness, identity or stationary safety.
Owner CAP-A replay and malformed/synthetic-envelope regression tests added; existing tests unchanged.
FILES CHANGED:
- domain/src/main/kotlin/com/rideflux/domain/telemetry/BmsFrame.kt
- domain/src/main/kotlin/com/rideflux/domain/telemetry/WheelTelemetry.kt
- domain/src/main/kotlin/com/rideflux/domain/codec/WheelCodec.kt
- data/protocol/src/main/kotlin/com/rideflux/protocol/familyg/BegodeBmsDecoder.kt
- data/protocol/src/main/kotlin/com/rideflux/protocol/familyg/BegodeWheelCodec.kt
- data/protocol/src/test/kotlin/com/rideflux/protocol/familyg/BegodeBmsDecoderTest.kt
- data/protocol/src/test/resources/begode/cap-a-first-cycle.hex
- data/ble/src/main/kotlin/com/rideflux/data/ble/WheelConnectionImpl.kt
- data/ble/src/test/kotlin/com/rideflux/data/ble/BegodeRawBmsConnectionTest.kt
- docs/PROTOCOLS.md (new T03 subsection only)
- docs/agent-tasks/reports/T03-begode-bms.md
TESTS:
```text
.\gradlew.bat testDebugUnitTest lint :data:protocol:test :domain:test jacocoTestReport --max-workers=2 --console=plain
> Task :jacocoTestReport
BUILD SUCCESSFUL in 6m 47s
370 actionable tasks: 37 executed, 333 up-to-date
Configuration cache entry reused.
```
New tests: 7 protocol + 2 BLE, zero failures. Existing A2 tests are unchanged and pass.
Local aggregate JaCoCo XML intersected with staged added production lines:
31/31 executable lines, 11/12 branches; combined 42/43 = **97.67%**.
This is a local changed-line calculation, not a claim of a SonarCloud Quality Gate result.
Initial targeted run: 396 protocol tests, 1 failure in the new replay harness because its
test codec address was blank. Corrected only the new test's setup; the 7 new protocol
tests then passed. The 2 new BLE tests passed on both runs.
Initial full run: existing BridgeClientGattTest.scanFailureClosesFlowWithAnError timed out
with UncompletedCoroutinesError; BUILD FAILED in 6m 25s, 326 actionable tasks: 63 executed,
263 up-to-date. No existing source/test was altered; retry uses --max-workers=2.
EVIDENCE LEVEL OF NEW CONSTANTS:
BMS_TYPES 0x01/0x02/0x03/0x05/0x06 -> L2 vendor-static routing only -> CROSS_BRAND_COMMAND_REFERENCE.md:236–250.
No new payload offsets, physical scales, request bytes or L1 production constants.
Envelope/type/discriminator reuse existing decoder -> L3 owner capture + L2 -> BEGODE_A2_TRUTH_TABLE.md:27–38.
UNSUPPORTED / GAPS:
All physical BMS quantities; all seven conflicts above; verified cell counts/scales/groups/pages;
active requests; BMS SOC/status; type 2/3 response semantics; hardware frames for 2/3/5/6.
RISKS / THINGS I DID NOT VERIFY:
No device test, no H1 capture, no SonarCloud gate confirmation. Latest Raw frame is not a page history.
Existing unrelated A2 decoding discrepancies noted in the research remain outside T03.
NEEDS FROM OWNER:
H1 charge-idle/short-ride capture with model/FW and vendor BMS screen; full cell pages if available;
gatekeeper ACCEPT or REWORK. Owner merges after acceptance.
