# T09 — Write-byte research: what we have, what we do not, and the cheapest capture for each gap

**Task:** T09 (write-byte research, findings only). **No Kotlin changed by this task.**
**Branch:** `docs/T09-write-bytes`. **Date:** 2026-10-09.
**Sources:** the PEVAppRE findings tree (read-only) plus the open-source apps already vendored in
`euc-programme/upstream/`. No device was touched, no BLE was transmitted, no write was performed.

---

## 0. Result in one paragraph

**No DarknessBot setting has bytes, and this is proven rather than assumed.** The app's business
logic is Dart AOT machine code in a 32-bit-ARM `libapp.so`; the artifact preserves *names* but
nothing maps a setting to an opcode (`DARKNESSBOT_COMMAND_ARCHITECTURE.md:439-441`,
`DARKNESSBOT_IMPLEMENTATION_2026-10-08.md:67,81`). The 47-setting vocabulary is therefore recorded
here as **47 rows with `UNKNOWN` bytes**, one row each, with the cheapest capture that would fill it.
Bytes *do* exist for the wheel families, but they are of two different kinds: (a) **RideFlux's own
16 commands**, already implemented in `:data:protocol` (table B), and (b) **documented vendor
commands with no `WheelCommand` type yet** (table C) — 34 Begode ASCII commands, 14 KingSong frames,
the LeaperKim write path, 9 Inmotion frames, 12 Ninebot-Z action/query codes. **None of them has
ever been observed on a wire** (`LEAPERKIM_PROTOCOL_GENERATIONS.md:265-266`;
`KINGSONG_WRITE_COMMANDS.md:161`), so every write row in this report has
`needs hardware to confirm = yes`.

Two count corrections were found and are stated rather than smoothed over — see §2.1.

---

## 1. Method, and the evidence ladder used in the tables

### 1.1 What was done

| # | Step | Result |
|---|---|---|
| 1 | Read the nine sources named by the task, plus `CAP-B-PLAN.md`, `CAP-B_TOOLING_FIXES.md`, `DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md` | citations below |
| 2 | Independently re-extracted the `Changing <setting> to ` literals from the real artifact (`work/flutter/db7000/lib/armeabi-v7a/libapp.so`, 12,583,516 B) | **47 distinct** strings, byte-for-byte counted — §2.1 |
| 3 | Re-read the cited line of every byte value quoted in tables B and C | every populated cell has a file+line; conflicting sources are shown as conflicts, never merged |
| 4 | Dynamic observation of DarknessBot on the owner's devices | **NOT DONE — see §1.3** |

### 1.2 Evidence levels (the workspace ladder)

`L0` string only · `L1` community / third-party static — *a lead, not proof* · `L2` spec-grade or
multiple concordant sources · `L3` observed on hardware · `L4` repeatable on hardware
(`_COMMON.md` §Evidence, `PROTOCOL_MATURITY_MATRIX.md`).

Per the task's rule, a cell that is not `L2`+ must be a **named constant marked `L1` behind an
Experimental flag** before it can enter production. Nothing in this report authorises a write.

### 1.3 The dynamic path was not used, and why that is not a shortcut

T09 allowed "dynamic observation of DarknessBot 7.0.0 on the owner's own devices only" (HCI snoop
log while the owner changes a setting). **This run had no owner device attached and no phone with the
app**, so that step is reported as not performed rather than simulated.

What that would have required is confirmed to be *possible*: the app carries **no anti-analysis
protection at all** — no anti-debug, anti-hook, root/emulator gate, signature self-check, integrity
attestation or certificate pinning; the only suspicious-looking dex functions belong to Firebase
Crashlytics and feed log metadata (`DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md:14,32-48`). The blockers
are passive: Dart AOT machine code, **armeabi-v7a only** (`.so` set at
`DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md:88-94`), and no ARM32 analyser in the local toolchain.
Nothing in that assessment is an authorisation check, so nothing here is being circumvented; the
missing ingredient is simply the owner + device + a snoop log.

**Consequence for the reader:** every DarknessBot row below is `UNKNOWN` for bytes. That is the
correct answer, not an incomplete one.

---

## 2. Table A — the DarknessBot settings vocabulary (47 rows, **all bytes `UNKNOWN`**)

Read this table as: *the app offers this control* (L1, static-verified in the artifact) and *nobody
here knows what it puts on the wire*.

Column meanings:

- **families** — which wheel family the write would target. `UNKNOWN` for every row: which model uses
  which adapter is *in code*, not in strings (`DARKNESSBOT_COMMAND_ARCHITECTURE.md:439`).
- **response/ACK** — DarknessBot has `ask*` read-back handlers for a subset of the vocabulary
  (`DARKNESSBOT_COMMAND_ARCHITECTURE.md:142-145`), but **no setting→handler mapping** was recovered
  (`:439-441`), so per-row ACK is `UNKNOWN` and is not repeated in every cell.
- **risk** — `T-` danger tier from `SAFETY_GATING_ARCHITECTURE.md` §1 + the task's own
  low / high / irreversible word. Justification in §6.
- **needs HW** — `yes` for every row: no write of any brand has ever been observed on a wire
  (`LEAPERKIM_PROTOCOL_GENERATIONS.md:265-266`).

Common cells for every row of table A (written once to keep the table readable):

| cell | value for all 47 rows |
|---|---|
| families | `UNKNOWN` (adapter-per-model is in code) |
| request bytes (hex, framing, checksum) | `UNKNOWN` |
| response / ACK | `UNKNOWN` (no setting→`ask*` mapping recovered) |
| level of the **label** | `L1` (third-party static; `DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md:6`) |
| level of the **bytes** | none — no bytes exist in any source |
| needs HW | `yes` |

### 2.1 Count reconciliation — read this before quoting "47"

The number **47 is right, but not for the reason the source table suggests**, and the source table is
off by one in two compensating ways. Independently re-derived here:

| fact | value | how it was checked |
|---|---|---|
| `Changing <name> to ` literals in `libapp.so` | **47 distinct, 0 repeats** | `grep -aoP 'Changing [A-Za-z][A-Za-z0-9 ]{1,32}? to ' libapp.so \| sort -u \| wc -l` → `47` |
| cells printed by the §3 table | **47** | `DARKNESSBOT_COMMAND_ARCHITECTURE.md:133-140` |
| *distinct* names in that table | **46** | `alarms` is listed twice — `:134` (Speed / power limits) and `:137` (Attention) |
| name present in the artifact but **missing** from the table | **`cruise control`** | literal `Changing cruise control to ` occurs once in `libapp.so`; the label also appears as a GAP row in `RIDEFLUX_COVERAGE_ROADMAP.md:101` |

So: **46 (table, de-duplicated) + 1 (`cruise control`) = 47 distinct**. This report lists **47 rows**:
the 46 table names plus `cruise control`, which is row A-16 below and is marked as an addition.

Two further honest limits on the 47:

1. **The `Changing … to` layer is not proven to be the complete set of configurable settings.**
   `DARKNESSBOT_COMMAND_ARCHITECTURE.md:354-355` records that the alarm settings are reached through
   sub-keys and *not* through a `Changing … to` message, which is why §3's list "did not surface"
   them. The 5×2 alarm matrix (`:331-352`) is therefore **wider than this table**.
2. The store listing's 「交通」(transport), 「校準」(calibration), 「喇叭」(horn) and 「藍牙密碼」
   (bluetooth password) have **no** `Changing … to` literal in the artifact; `askSpeedPassword`
   exists (1×) but no setting literal does (`DARKNESSBOT_IMPLEMENTATION_2026-10-08.md:81`).
   Those four are **not** counted as members of the 47 and are listed in §5.2 as unknowns.

### 2.2 The 12 `custom*` gains are a **subset of the 47**, not 12 extra rows

`DARKNESSBOT_COMMAND_ARCHITECTURE.md:151-159` says "The twelve `custom*` parameters are the signature
of a VESC-family wheel"; the Tuning group at `:139` holds 13 names = **12 `custom*` + `gyro level`**.
The store-recon note likewise places them *inside* the 47
(`DARKNESSBOT_IMPLEMENTATION_2026-10-08.md:81`, 「…皆落在此 47 層內」). They are rows A-31…A-42
below; they are **not** added to the total again.

### 2.3 The rows

#### Group 1 — Motion / ride (`DARKNESSBOT_COMMAND_ARCHITECTURE.md:133`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-01 `riding mode` | UNKNOWN | UNKNOWN | UNKNOWN | `DARKNESSBOT_COMMAND_ARCHITECTURE.md:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-02 `extreme mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-03 `trick mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-04 `safe mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-05 `limit mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-06 `handle mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-07 `alarms mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-08 `equalizer mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-09 `rotation control` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-10 `roll` | UNKNOWN | UNKNOWN | UNKNOWN | `:133` | L1 label / no bytes | T-CRITICAL / high | yes |

#### Group 2 — Speed / power limits (`:134`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-11 `max speed` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-12 `limit speed` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-13 `pwm limit` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-14 `alarms` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` (also `:137`) | L1 label / no bytes | T-CRITICAL / high | yes |
| A-15 `breaking amperage` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-16 `cruise control` | UNKNOWN | UNKNOWN | UNKNOWN | literal in `libapp.so` (verified §2.1); GAP row `RIDEFLUX_COVERAGE_ROADMAP.md:101` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-17 `recuperation` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-18 `charging` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-19 `discharging` | UNKNOWN | UNKNOWN | UNKNOWN | `:134` | L1 label / no bytes | T-CRITICAL / high | yes |

#### Group 3 — Lighting (`:135`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-20 `lights` | UNKNOWN | UNKNOWN | UNKNOWN | `:135` | L1 label / no bytes | T-BENIGN / low | yes |
| A-21 `lights mode` | UNKNOWN | UNKNOWN | UNKNOWN | `:135` | L1 label / no bytes | T-BENIGN / low | yes |
| A-22 `brake lights` | UNKNOWN | UNKNOWN | UNKNOWN | `:135` | L1 label / no bytes | T-BENIGN / low | yes |
| A-23 `torch` | UNKNOWN | UNKNOWN | UNKNOWN | `:135` | L1 label / no bytes | T-BENIGN / low | yes |
| A-24 `autotorch` | UNKNOWN | UNKNOWN | UNKNOWN | `:135`; client-side speed rule `:372-381` | L1 label / no bytes | T-BENIGN / low | yes |
| A-25 `strobe` | UNKNOWN | UNKNOWN | UNKNOWN | `:135` | L1 label / no bytes | T-BENIGN / low | yes |
| A-26 `brightness` | UNKNOWN | UNKNOWN | UNKNOWN | `:135` | L1 label / no bytes | T-BENIGN / low | yes |

#### Group 4 — Locking / access (`:136`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-27 `lock` | UNKNOWN | UNKNOWN | UNKNOWN | `:136` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-28 `advanced settings` | UNKNOWN | UNKNOWN | UNKNOWN | `:136` | L1 label / no bytes | not a value (menu entry) | yes |

#### Group 5 — Attention (`:137`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-29 `alarms` (2nd listing — same string as A-14) | UNKNOWN | UNKNOWN | UNKNOWN | `:137` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-30 `volume` | UNKNOWN | UNKNOWN | UNKNOWN | `:137` | L1 label / no bytes | T-BENIGN / low | yes |
| A-31 `handle` | UNKNOWN | UNKNOWN | UNKNOWN | `:137`; read-back peer `askHandle`/`askHandleMode` `:144` | L1 label / no bytes | T-CRITICAL / high | yes |

#### Group 6 — Timing (`:138`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-32 `shutdown` | UNKNOWN | UNKNOWN | UNKNOWN | `:138`; read-back peer `askShutdownTime` `:144` | L1 label / no bytes | T-CRITICAL / irreversible-in-fact | yes |

#### Group 7 — Tuning, per model (`:139`) — rows A-33…A-45, of which **12 are the `custom*` set**

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-33 `gyro level` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`; read-back peer `askGyroLevel` `:145` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-34 `customAccelerationCompensation` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-35 `customCurrentDKI` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-36 `customCurrentDKP` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-37 `customCurrentKI` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-38 `customCurrentKP` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-39 `customDynamicCompensation` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-40 `customDynamicCompensationFilter` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-41 `customHorizontalKD` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-42 `customHorizontalKI` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-43 `customHorizontalKP` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-44 `customRotationAngle` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-45 `customTurnCompensation` | UNKNOWN | UNKNOWN | UNKNOWN | `:139`, `:151-154` | L1 label / no bytes | T-CRITICAL / high | yes |

#### Group 8 — BMS configuration (`:140`)

| command | families | request bytes | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| A-46 `bms capacity` | UNKNOWN | UNKNOWN | UNKNOWN | `:140` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-47 `bms cell count` | UNKNOWN | UNKNOWN | UNKNOWN | `:140` | L1 label / no bytes | T-CRITICAL / high | yes |
| A-48 `bms type` | UNKNOWN | UNKNOWN | UNKNOWN | `:140` | L1 label / no bytes | T-CRITICAL / high | yes |

Rows A-01…A-48 are **47 distinct settings** (A-14 and A-29 are the same string), as reconciled in §2.1.

**The sentence that makes every `UNKNOWN` above a result rather than a gap** — quoted so it can be
pinned by a test or a reviewer:

> "**Recorded as vocabulary, not as implementable.** These are `community-derived` labels with no
> bytes attached, and tuning gains on a self-balancing vehicle are the highest-consequence writes in
> the set." — `DARKNESSBOT_COMMAND_ARCHITECTURE.md:158-159`

and, in the same file, the structural reason:

> "| **any write bytes or register numbers** | ❌ | in code |" — `DARKNESSBOT_COMMAND_ARCHITECTURE.md:441`

---

## 3. Table B — RideFlux's existing 16 commands: the bytes that already ship

These are **not** proposals. They are what `:data:protocol` emits today, read out of the codec
`encode()` bodies and the builders they call. T09 changes nothing here and does not re-grade the
existing code; the `level` column therefore says what the *findings* establish about that byte, not a
new grade.

### 3.1 Frame shapes in use

| family | shape | checksum / escaping | source |
|---|---|---|---|
| `G` / `GX` | bare ASCII, one or two bytes, **no wrapper** | none | `familyg/BegodeCommandBuilder.kt:52,65,74` |
| `K` | 20 B: `AA 55` + payload[14] + `cmd[16]` + `trailer[17]` + `5A 5A` | none in this class (trailer is a constant, `0x14` default) | `familyk/KingSongCommandBuilder.kt:36-38,68-85` |
| `I1` | `AA AA` + escaped 16-B CAN body + 1 checksum byte + `55 55`, body = CAN-ID (LE32) + data8 + `08 05 01 <type>` | `checksum8`, `0xA5` byte-stuffing | `familyi1/InmotionI1CommandBuilder.kt:59-80,261-272`; `familyi1/InmotionI1Codec.kt:23-25,90-93` |
| `I2` | `AA AA` + escaped(`FLAGS` `LEN` `CMD` `DATA`) + XOR byte; `LEN = data.size + 1`, `FLAGS ∈ {0x11,0x14}` | `xorChecksum8`, `0xA5` byte-stuffing | `familyi2/InmotionI2CommandBuilder.kt:41-65`; `familyi2/InmotionI2Codec.kt:29-34,63-66` |
| `V` | **no writes at all** — `encode` is `emptyList()` | — | `familyv/VeteranWheelCodec.kt:213-214` |
| `N1` / `N2` | `Raw` only; N2 additionally XOR-obscures the payload with the session keystream γ (N1 γ is the identity) | γ keystream (N2), algorithm still open | `familyn/NinebotWheelCodec.kt:154-168` |

### 3.2 The 16 commands, family by family

| command | family | request bytes (hex / ASCII, framing, checksum) | ACK | source | level | risk | needs HW |
|---|---|---|---|---|---|---|---|
| `SetHeadlight` | G | on `Q` (0x51) / off `E` (0x45) / strobe `T` (0x54); bare byte, no checksum | none — read back the status frame | `familyg/BegodeCommandBuilder.kt:74-80`; `familyg/BegodeWheelCodec.kt:220-225` | vendor app + WheelLog corroborate `Q`/`E` (`CROSS_BRAND_COMMAND_REFERENCE.md:165-166`) | T-BENIGN / low | yes |
| `SetHeadlight` | K | `0x73`; payload[0] = `0x12 + mode` (OFF=0 → `0x12`, ON=1 → `0x13`), payload[1] = `0x01`; frame `AA 55 13 01 00×12 73 14 5A 5A` | none | `familyk/KingSongCommandBuilder.kt:192-199,237`; `familyk/KingSongWheelCodec.kt:177-182` | byte 16 vendor-static, byte 17 conflict (see C2) | T-BENIGN / low | yes |
| `SetHeadlight` | I1 | data8[0] = `01`/`00`, CAN-ID `0x0F55010D` | none | `familyi1/InmotionI1CommandBuilder.kt:200-207` | not graded in findings | T-BENIGN / low | yes |
| `SetHeadlight` | I2 | `CMD 0x60`, sub `0x50`, value `01`/`00` → `AA AA 14 02 60 50 0X <XOR>` | none | `familyi2/InmotionI2CommandBuilder.kt:286` | not graded in findings | T-BENIGN / low | yes |
| `SetLedStrip` | I1 | data8[0] = `0xB2`, data8[4] = `0x0F` on / `0x10` off, CAN-ID `0x0F550116` | none | `familyi1/InmotionI1CommandBuilder.kt:167-181` | not graded | T-BENIGN / low | yes |
| `SetDecorativeLights` | I2 | `CMD 0x60`, sub `0x2D` (DRL), `01`/`00` | none | `familyi2/InmotionI2CommandBuilder.kt:210` | not graded | T-BENIGN / low | yes |
| `Beep` | G | `b` (0x62), bare byte | none | `familyg/BegodeCommandBuilder.kt:65`; codec `:219` | vendor app (`CROSS_BRAND_COMMAND_REFERENCE.md:168`) | T-BENIGN / low | yes |
| `Beep` | K | `0x88`; `AA 55 00 00 00×12 88 14 5A 5A` | none | `familyk/KingSongCommandBuilder.kt:101`; codec `:174` | byte 16 L1 (library naming, `KINGSONG_WRITE_COMMANDS.md:108`) | T-BENIGN / low | yes |
| `Beep` | I1 | data8[0] = `0xB2`, data8[4] = `0x11`, CAN-ID `0x0F550116` | none | `familyi1/InmotionI1CommandBuilder.kt:184` | not graded | T-BENIGN / low | yes |
| **`Horn`** | **none** | **no family encodes it** — permanently `Unsupported` | — | `DOMAIN_COMMAND_COVERAGE.md:35,44-59` | — | (would be T-BENIGN) | — |
| `SetVolume` | I1 | volume×100 as U16 **LE** at data8[0..1], CAN-ID `0x0F55060A`; range 0..100 | none | `familyi1/InmotionI1CommandBuilder.kt:225-232` | not graded | T-BENIGN / low | yes |
| `SetVolume` | I2 | `CMD 0x60`, sub `0x26`, value 0..100 | none | `familyi2/InmotionI2CommandBuilder.kt:179-182` | not graded | T-BENIGN / low | yes |
| `PlaySound` | I1 | data8[0] = index (0..255), CAN-ID `0x0F550609` | none | `familyi1/InmotionI1CommandBuilder.kt:237-244` | not graded | T-BENIGN / low | yes |
| `PlaySound` | I2 | `CMD 0x60`, sub `0x51`, payload `[index, 0x01]` | none | `familyi2/InmotionI2CommandBuilder.kt:293-296` | not graded | T-BENIGN / low | yes |
| `SetMaxSpeedKmh` | K | `0x85` template; frame offsets 2/4/6 = alarm1/2/3 = `0`, offset 8 = max speed (0..255), `forceWrite=true`; alarms are **overwritten with 0** | none | `familyk/KingSongCommandBuilder.kt:156-180`; codec `:183-197` | template L1 (`KINGSONG_WRITE_COMMANDS.md:58`) | T-CRITICAL / high | yes |
| `SetMaxSpeedKmh` | I1 | data8[0] = `0x01`, kmh×**1000** as U16 **BE** at data8[3..4] (range 0..65.535 km/h), CAN-ID `0x0F550115` | none | `familyi1/InmotionI1CommandBuilder.kt:109-119` | not graded | T-CRITICAL / high | yes |
| `SetMaxSpeedKmh` | I2 | `CMD 0x60`, sub `0x21`, U16BE of kmh×100 | none | `familyi2/InmotionI2CommandBuilder.kt:129-134` | not graded | T-CRITICAL / high | yes |
| **`SetTiltbackKmh`** | **none** | **no family encodes it** — the capability is documented for three brands but no encoder exists | — | `DOMAIN_COMMAND_COVERAGE.md:36,51-54` | — | (T-CRITICAL) | — |
| `SetPedalSensitivity` | I1 | data8[0] = `0x06`, raw = `(sens + 28) << 5` as U16 BE at data8[3..4], CAN-ID `0x0F550115` | none | `familyi1/InmotionI1CommandBuilder.kt:125-136` | not graded | T-CRITICAL / high | yes |
| `SetPedalSensitivity` | I2 | `CMD 0x60`, sub `0x25`, payload `[sens, sens]` | none | `familyi2/InmotionI2CommandBuilder.kt:173-176` | not graded | T-CRITICAL / high | yes |
| `SetPedalHorizontal` | I1 | data8[0] = `0x00`, raw = `angle × 6553.6` as **U32 BE** across data8[3..6], CAN-ID `0x0F550115` | none | `familyi1/InmotionI1CommandBuilder.kt:152-163` | not graded | T-CRITICAL / high | yes |
| `SetPedalHorizontal` | I2 | `CMD 0x60`, sub `0x22`, U16BE of angle×10 | none | `familyi2/InmotionI2CommandBuilder.kt:159-164` | not graded | T-CRITICAL / high | yes |
| `SetRideMode` | I1 | data8[0] = `0x0A`, data8[3] = `01` classic / `00` comfort, CAN-ID `0x0F550115` | none | `familyi1/InmotionI1CommandBuilder.kt:140-147` | not graded | T-CRITICAL / high | yes |
| `SetRideMode` | I2 | `CMD 0x60`, sub `0x23`, `01` classic / `00` comfort | none | `familyi2/InmotionI2CommandBuilder.kt:167` | not graded | T-CRITICAL / high | yes |
| `SetRideMode` | K | **explicitly `emptyList()`** — accepted limitation, not an oversight | — | `familyk/KingSongWheelCodec.kt:200` | — | — | — |
| `Calibrate` | G | `c` then `y`, **two separate writes ≈300 ms apart** (encoded as `TwoStepCommand`); merging them sends a different command | none; response packet `0x02` is the vendor's calibration-response class | `familyg/BegodeCommandBuilder.kt:129-141`; codec `:226-229` | vendor app (`CROSS_BRAND_COMMAND_REFERENCE.md:187,710`); response class `BEGODE_A2_ACTIONS.md:62` | T-CRITICAL / irreversible-in-fact | yes |
| `Calibrate` | K | `0x89`; `AA 55 00 00 00×12 89 14 5A 5A` | none | `familyk/KingSongCommandBuilder.kt:104`; codec `:175` | byte 16 L1; `u0` writes `[17]=20` (`CROSS_BRAND_COMMAND_REFERENCE.md:902`) | T-CRITICAL / irreversible-in-fact | yes |
| `Calibrate` | I1 | data8 = `32 54 76 98 00 00 00 00`, CAN-ID `0x0F550119` | none | `familyi1/InmotionI1CommandBuilder.kt:192-197` | not graded | T-CRITICAL / irreversible-in-fact | yes |
| `Calibrate` | I2 | `CMD 0x60`, sub `0x42`, payload `[01 00 01]` | none | `familyi2/InmotionI2CommandBuilder.kt:274` | not graded | T-CRITICAL / irreversible-in-fact | yes |
| `PowerOff` | K | `0x40`; `AA 55 00 00 00×12 40 14 5A 5A` | none | `familyk/KingSongCommandBuilder.kt:107`; codec `:176` | byte 16 vendor-static, `S2` writes `[17]=20` (`CROSS_BRAND_COMMAND_REFERENCE.md:901`) | T-CRITICAL / irreversible-in-fact | yes |
| `PowerOff` | I1 | data8[0] = `0xB2`, data8[4] = `0x05`, CAN-ID `0x0F550116` | none | `familyi1/InmotionI1CommandBuilder.kt:175`; codec `:204` | not graded | T-CRITICAL / irreversible-in-fact | yes |
| `PowerOff` | I2 | **two frames**: stage 1 `CMD 0x03` + `81 00`, stage 2 `CMD 0x03` + `82` (FLAGS `0x11`) | none | `familyi2/InmotionI2CommandBuilder.kt:98-103`; codec `:155-158` | not graded | T-CRITICAL / irreversible-in-fact | yes |
| `UnlockWithPin` | I1 | 6 ASCII digit bytes at data8[0..5], data8[6..7] = 0, CAN-ID `0x0F550307`; PIN length is forced to exactly 6 by the codec | none | `familyi1/InmotionI1CommandBuilder.kt:250-257`; cap `familyi1/InmotionI1WheelCodec.kt:216-221` | not graded | T-CRITICAL / high | yes |
| `Raw` | G, K, I1, I2 | caller-supplied bytes, written verbatim | none | `familyg/BegodeWheelCodec.kt:230`; `familyk/KingSongWheelCodec.kt:201`; `familyi1/InmotionI1WheelCodec.kt:222`; `familyi2/InmotionI2WheelCodec.kt:159` | — | **T-CRITICAL by construction** — a generic entry point must be tiered by the worst thing it can reach (`SAFETY_GATING_ARCHITECTURE.md:106,152`) | yes |
| `Raw` | N1 / N2 | payload XOR-obscured with the session keystream γ before write (N2); N1 γ = identity | none | `familyn/NinebotWheelCodec.kt:154-168` | γ derivation still open (`CROSS_BRAND_COMMAND_REFERENCE.md:1004-1009`) | T-CRITICAL by construction | yes |
| `Raw` | V | not encoded (`emptyList()`) | — | `familyv/VeteranWheelCodec.kt:213-214` | — | — | — |

**Grading note.** The `level` column above records what the findings establish; most of these encoders
are **not** graded anywhere in the findings tree, and T09 deliberately does not raise them. What T09
*can* add is the cross-check: the Begode bytes `Q`/`E`/`T`/`b`/`s`/`f`/`h`/`u`/`i`/`e`/`x`/`m`/`g`/
`c`+`y` in `BegodeCommandBuilder` match the vendor-app table **byte for byte**
(`CROSS_BRAND_COMMAND_REFERENCE.md:163-188,695-728`), and KingSong's `0x9B` request is vendor-confirmed
(`:903`). Two disagreements are worth flagging for T11 rather than fixing here:

1. `alarmMode(LEVEL_2)` emits `o` (0x6F), which only WheelLog corroborates; the vendor app's
   `AlarmLevelOnAll` is `0` (0x30) — a *different byte*
   (`CROSS_BRAND_COMMAND_REFERENCE.md:170,706`).
2. `TRAILER_SET_PEDALS = 0x15` is **not vendor-corroborated**: the vendor app never writes 21 at
   index 17 (`CROSS_BRAND_COMMAND_REFERENCE.md:919-924`).

### 3.3 Bytes that already exist but are **not wired into any `WheelCommand`**

This is the cheapest part of the T10/T11 vocabulary expansion, and it is easy to miss: the builders
already construct these frames correctly, but no codec's `encode()` reaches them, so the capability
flags stay `false` and the UI cannot show them.

| family | builder function | bytes / code | source |
|---|---|---|---|
| G | `unitsMiles()` / `unitsKilometres()` | `m` / `g` | `familyg/BegodeCommandBuilder.kt:68,71` |
| G | `rollAngle(SOFT/MEDIUM/HARD)` | `<` / `=` / `>` | `:83-89` |
| G | `pedalsMode(HARD/MEDIUM/SOFT)` | `h` / `f` / `s` | `:99-105` |
| G | `alarmMode(LEVEL_2/LEVEL_1/OFF)` | `o` / `u` / `i` | `:113-119` |
| G | `requestName()` / `requestFirmware()` | `N` / `V` (reads) | `:57,60` |
| K | `requestSerialNumber()` / `requestDeviceName()` / `requestAlarmSettings()` | `0x63` / `0x9B` / `0x98` | `familyk/KingSongCommandBuilder.kt:90-96` |
| K | `setPedalsMode(mode)` | `0x87`, payload[1] = `0xE0`, trailer override `0x15` (see disagreement 2) | `:122-137` |
| K | `setLedMode(mode)` / `setStrobeMode(mode)` | `0x6C` / `0x53` | `:216-233` |
| I1 | `setHandleButton(enabled)` | data8[0] = `00` enabled / `01` disabled, CAN-ID `0x0F55012E` | `familyi1/InmotionI1CommandBuilder.kt:213-217` |
| I2 | `setStandbyDelay`, `setLightBrightness`/`setBeamBrightness`, `setMute`, `setHandleButton`, `setAutoLight`, `setLockMode`, `setTransportMode`, `setGoHomeMode`, `setFanQuietMode`, `setSoundWave`, `setAlarmSpeeds`, `setSplitMode*`, `setCoolingFanOverride`, `setBermAngleMode`, `setHeadlightLegacy`, `playSoundLegacy`, `calibrationTurn`, `calibrationBalance` | `CMD 0x60` subs `0x28,0x2B,0x2C,0x2E,0x2F,0x31,0x32,0x37,0x38,0x39,0x3E,0x40,0x41,0x42,0x43,0x45,0x52` | `familyi2/InmotionI2CommandBuilder.kt:185-308` |

**T09 does not enable any of these.** They are listed because T10 is the task that turns them into
typed vocabulary, and because a reviewer checking "does T09 invent anything" should see that the
bytes already exist in the repository rather than in this report.

---

## 4. Table C — documented vendor writes with **no `WheelCommand` type** (the T10/T11 backlog)

`DOMAIN_COMMAND_COVERAGE.md:67-71` counts the vocabulary that is **missing** as Begode 15, KingSong 5,
LeaperKim 3, plus the Inmotion I2 and Ninebot Z sets. The tables below are deliberately larger: each
lists the **vendor's whole write set** for that family, including the commands RideFlux already
implements, so a reviewer can see the overlap instead of taking it on trust. Every row is a **write
that exists in a vendor implementation**; nothing here is enabled, and nothing is proposed for
release on the strength of this table.

### C1 — Begode / Gotway: 34 ASCII commands (`CROSS_BRAND_COMMAND_REFERENCE.md:158-188,695-728`)

**Framing for every row:** bare ASCII written with GATT write-without-response; **no wrapper, no
checksum** (`CROSS_BRAND_COMMAND_REFERENCE.md:486-500`). **ACK for every row: none** — "the only way
to know a write took effect is to re-read the status frame and compare" (`:411-416`), which is the
closed-loop rule of `SAFETY_GATING_ARCHITECTURE.md` §4. **Needs HW: yes, all rows.**

| # | command | request bytes | clamp / detail | source | level | risk |
|---|---|---|---|---|---|---|
| 1 | `RequestVehicleName` | `N` (`4E`) | **read, not a write** | `:163,695` | L2 (V-app + V-lib + WheelDash) | — |
| 2 | `RequestFirmwareVersion` | `V` (`56`) | **read, not a write** | `:164,696` | L2 | — |
| 3 | `LedOn` | `Q` (`51`) | light on | `:165,697` | **L2** (vendor app + WheelLog) | T-BENIGN / low |
| 4 | `LedOff` | `E` (`45`) | **upper-case E**; a previous revision wrongly said `e` | `:166,698` | **L2** | T-BENIGN / low |
| 5 | `LedFlash` | `T` (`54`) | strobe | `:167,699` | **L2** | T-BENIGN / low |
| 6 | `BeepOnce` | `b` (`62`) | beep | `:168,700`; tooling also treats `b` as a known write `CAP-B_TOOLING_FIXES.md:21` | L1 (vendor app only) | T-BENIGN / low |
| 7 | `ModeS` | `s` (`73`) | ride mode | `:169,701` | L2 (vendor + WheelLog) | T-CRITICAL / high |
| 8 | `ModeF` | `f` (`66`) | ride mode | `:169,702` | L2 | T-CRITICAL / high |
| 9 | `ModeH` | `h` (`68`) | ride mode | `:169,703` | L2 | T-CRITICAL / high |
| 10 | `AlarmLevelOff1` | `u` (`75`) | alarms off (level 1) | `:170,704` | L2 | T-CRITICAL / high |
| 11 | `AlarmLevelOff2` | `i` (`69`) | alarms off (level 2) | `:170,705` | L2 | T-CRITICAL / high |
| 12 | `AlarmLevelOnAll` | `0` (`30`) | alarms on — **conflicts** with WheelLog's `o` (`6F`) | `:170,706` | **L1, contradictory** — do not implement without hardware | T-CRITICAL / high |
| 13 | `CancelPedalSpeed` | `"` (`22`) | **corrected 2026-10-06** from `'` (`27`), which matches no vendor app | `:172,707` | L2 (three vendor apps listed) | T-CRITICAL / high |
| 14 | `SwitchMiles` | `m` (`6D`) | — | `:186,708` | L2 | T-BENIGN / low |
| 15 | `SwitchKm` | `g` (`67`) | — | `:186,709` | L2 | T-BENIGN / low |
| 16 | `Calibrate` | `c` then `y`, **≈300 ms apart**, two writes | merging them sends a different command | `:187,710` | L2 | T-CRITICAL / irreversible-in-fact |
| 17 | `BrakeCutoffOn` | `e` (`65`) | **shares its byte with #19** | `:184,711` | L2 | T-CRITICAL / high |
| 18 | `BrakeCutoffOff` | `x` (`78`) | shares its byte with #20 | `:184,712` | L2 | T-CRITICAL / high |
| 19 | `PowerBridgeOn` | `e` (`65`) | indistinguishable from #17 at the wheel | `:185,713` | L2 | T-CRITICAL / high |
| 20 | `PowerBridgeOff` | `x` (`78`) | indistinguishable from #18 | `:185,714` | L2 | T-CRITICAL / high |
| 21 | `ReductionRatio60` | `<` (`3C`) | **overloaded** with `SetTiltShutdownGear` | `:183,715` | L2 | T-CRITICAL / high |
| 22 | `ReductionRatio48` | `=` (`3D`) | overloaded | `:183,716` | L2 | T-CRITICAL / high |
| 23 | `ReductionRatio45` | `>` (`3E`) | overloaded | `:183,717` | L2 | T-CRITICAL / high |
| 24 | `SetTiltShutdownGear` | index 0→`>`, 1→`=`, 2→`<` | **inverted** mapping vs #21-23; a capture cannot tell the two apart | `:182,718` | L2 mapping / bytes shared | T-CRITICAL / high |
| 25 | `SetRunModeSwitch` | `+` `-` (`2B 2D`) | two bytes as a sequence | `:181,719` | L2 | T-CRITICAL / high |
| 26 | `SetPedalSpeed` | `W` `Y` + two decimal digits | clamp 3–90, then `normalizeStep`; **four separate writes** (posted, then 500/550/600 ms) | `:171,720,604,656-661` | L2 for ≥10; **the <10 zero-padding split is NOT_OBSERVED_ON_WIRE** (`:601`) | T-CRITICAL / high |
| 27 | `SetBeeperVolume` | `W` `B` + one digit | clamp 1–9 | `:173,721,641` | L2 (Begode computes, Gotway enumerates `WB1`…`WB9`) | T-BENIGN / low |
| 28 | `SetPowerAlarm` | `W` `P` + two digits | clamp 50–90; absent from the Gotway app | `:174,722` | L1 | T-CRITICAL / high |
| 29 | `SetAmbientMode` | `W` `M` + one digit | clamp 0–9; three writes at 500/550 ms | `:175,723,660` | L1 | T-BENIGN / low |
| 30 | `SetAngleCompensation` | `W` `R` + one digit | clamp 0–9 | `:176,724` | L1 | T-CRITICAL / high |
| 31 | `SetBridge` | `W` `U` + one digit | clamp 0–9 | `:177,725` | L1 | T-CRITICAL / high |
| 32 | `SetWeakMagnetic` | `W` `C` + one digit | clamp 0–9 | `:178,726` | L1 | T-CRITICAL / high |
| 33 | `SetTiltClose` | `W` `X` + one digit | clamp 0–9 | `:179,727` | L1 | T-CRITICAL / high |
| 34 | `SetCurrentLimit` | `W` `l` + one digit | clamp 0–9; **lower-case L, not digit 1** | `:180,728` | L1 | T-CRITICAL / high |
| — | `Raw` | caller-supplied | not a vendor command; excluded from the 34 | `:188` | — | T-CRITICAL by construction |

**Three traps recorded by the source, repeated here because T11 will hit them**
(`CROSS_BRAND_COMMAND_REFERENCE.md:190-203`): `<`/`=`/`>` are overloaded (#21-24); `e`/`x` are
shared by brake-cutoff and power-bridge (#17-20); the digit-bearing commands use **decimal ASCII
digits** (`decimalChar(v) = v + 48`), so `SetPedalSpeed(5)` is the text `WY05`, not `57 59 05`.

### C2 — KingSong: 20-byte frames, byte 16 known, byte 17 disputed

**Framing:** `AA 55` + 18 bytes + `5A 5A`, command at index 16 (`:864`, `:889-892`).
**The vendor app and the vendored library disagree about byte 17**: the vendor's Fixed-Tail Template
Class writes `0x14` for all ten commands below, while the library uses a per-command value
(8, 12, 14, 20); for ten of the vendor's builders byte 17 is a *variable payload byte* and bytes 18-19
carry a CRC-16-CCITT, but **none of the ten commands below is in that class**
(`:870-878,913-917`). RideFlux's `DEFAULT_TRAILER_MAGIC = 0x14` matches the vendor
(`:907-911`). **ACK for every row: none. Needs HW: yes, all rows.**

| command | `d[16]` | vendor frame (byte 17 = `0x14`) | library frame | source | level | risk |
|---|---|---|---|---|---|---|
| `LIGHT_ON` | `0x73` | `AA 55 13 01 00×12 73 14 5A 5A` | `… 73 08 …` | `:896`; `KINGSONG_WRITE_COMMANDS.md:47` | L2 (byte 16 vendor-static; byte 17 conflict) | T-BENIGN / low |
| `LIGHT_OFF` | `0x73` | `AA 55 12 01 00×12 73 14 5A 5A` | `… 73 08 …` | `:897`; `KINGSONG_WRITE_COMMANDS.md:48` | L2 / conflict | T-BENIGN / low |
| `SET_LIGHT_MODE` | `0x73` | payload[0] = `clamp(mode,0,2)+18` → `0x12`/`0x13`/`0x14` | same | `:898`; `KINGSONG_WRITE_COMMANDS.md:49` | L2 byte 16 / L1 operand | T-BENIGN / low |
| `LIGHT_BRIGHTNESS` | `0x73` | payload[0] ∈ {18,19,20} by brightness bucket — **overlaps `SET_LIGHT_MODE`** | same | `KINGSONG_WRITE_COMMANDS.md:50` | L1 | T-BENIGN / low |
| `SET_LED_MODE` | `0x6C` | `AA 55 -- 00 00×12 6C 14 5A 5A`; vendor `A0(int)` sets `[17]=20` | `… 6C 0C …` | `:899`; `:79,171` | L2 byte 16 (`:106`), byte 17 conflict | T-BENIGN / low |
| `BEEP` | `0x88` | `AA 55 00 00 00×12 88 14 5A 5A` | `… 88 0E …` | `:900`; `KINGSONG_WRITE_COMMANDS.md:51` | L1 (byte 16 not vendor-confirmed for `0x88`, `:108`) | T-BENIGN / low |
| `POWER_OFF` | `0x40` | `AA 55 00 00 00×12 40 14 5A 5A` | `… 40 0E …` | `:901`; `KINGSONG_WRITE_COMMANDS.md:52` | L2 byte 16 (`S2` writes `[17]=20`) | T-CRITICAL / irreversible-in-fact |
| `CALIBRATE` | `0x89` | `AA 55 00 00 00×12 89 14 5A 5A` | `… 89 0E …` | `:902`; `KINGSONG_WRITE_COMMANDS.md:53` | L1 (`:108`) | T-CRITICAL / irreversible-in-fact |
| `SET_PEDALS_MODE` | `0x87` | `d[3] = 0xE0` marker; byte 17 = `0x14`. **Vendor never writes `0x15`** | library prints 20 bytes with `d[17] = 0x15` (= 21) — internally inconsistent | `:904,919-924`; `KINGSONG_WRITE_COMMANDS.md:57` | L1; **the `0x15` override is not vendor-corroborated** | T-CRITICAL / high |
| `SET_SPEED_LIMIT` / `SET_ALARM_SPEED` | `0x85` | second template, thresholds at frame offsets 2/4/6/8; template literal `{-86, 85, 0, …, -123, 20, 90, 90}` | same | `:905`; `KINGSONG_WRITE_COMMANDS.md:33-38,58-59` | L1; two names, **one** frame | T-CRITICAL / high |
| `REQUEST_SERIAL` / name request | `0x63` / `0x9B` | `AA 55 00×14 9B 14 5A 5A` — **vendor-confirmed byte for byte** | `… 9B 0E …` | `:903`; `KINGSONG_WRITE_COMMANDS.md:244-253` | **L2** (vendor-static; §7 also retracts an earlier "0x9B in neither app" claim as a signed/unsigned grep bug) | read, not a write |
| LED colour (no recovered name) | `0x59` | `89` (`0x59`) at index 16, four RGB triples at indices 2-13 | not exposed by the library | `KINGSONG_WRITE_COMMANDS.md:92-94` | L2 byte, L0 name | T-BENIGN / low |

**Also present and deliberately not turned into vocabulary:**
`KINGSONG_WRITE_COMMANDS.md:169-192` lists **22 vendor emit methods** (`A0`…`x`) with their index-16
codes (`0x6C,0x5B,0x54,0x53,0x51,0x50,0x73,0x47,0x5E,0x44,0x45,0x65,0x66,0x4A,0x48,0x7C,0x67,0x63,0x6A,0x57,0x56`)
and length 20. **Their semantics are not recovered**, and §6 of that file is titled "the read side" —
several are reads. Guessing names for them would fabricate protocol identity, which that file warns
against explicitly (`:225-233`). `KINGSONG_COMMAND_VOCABULARY.md:113-127` adds **56 Chinese UI labels**
(the settings screens) with the blunt caveat *"Chinese UI labels are not command codes"*: they prove a
feature exists, **not** which byte carries it. All 56 are therefore aggregated into a single
bytes-`UNKNOWN` row in §5.2 rather than being given 56 invented rows here.

### C3 — LeaperKim / Veteran: ASCII commands plus one binary beep

**Framing:** magic `DC 5A 5C`; GATT `ffe0`/`ffe1`; **CRC-32 present on every outgoing MODERN command
and absent in LEGACY**; the legacy 1.0.3 app sends only GBK-encoded text with no binary frame at all
(`LEAPERKIM_PROTOCOL_GENERATIONS.md:107-111,113-126,189-195`). **Needs HW: yes, all rows.**
The file's own answer to "which writable capabilities must stay disabled?" is **"All of them… no
write has ever been observed on a wire in this project"** (`:265-266`).

| command | request bytes | source | level | risk |
|---|---|---|---|---|
| light on / off | ASCII `SetLightON` / `SetLightOFF` | `CROSS_BRAND_COMMAND_REFERENCE.md:855` | L2 (V-app + V-lib + EUC World; lineage caveat `LEAPERKIM_PROTOCOL_GENERATIONS.md:300`) | T-BENIGN / low |
| beep (gen < 3) | ASCII `b` | `CROSS_BRAND_COMMAND_REFERENCE.md:856` | L2 | T-BENIGN / low |
| beep (gen ≥ 3) | `4C 6B 41 70 0E 00 80 80 80 01` + CRC32 `CA 87 E6 6F` | `:856` | L2 (vendor app and library CRCs agree) | T-BENIGN / low |
| pedals mode 0/1/2 | ASCII `SETh` / `SETm` / `SETs` | `:857` | L2 (three sources) | T-CRITICAL / high |
| reset trip | ASCII `CLEARMETER` — **and a dynamically observed 11-byte modern form** `4C 6B 41 70 0B 00 01` + CRC-32 (`4c6b41700b0001` + CRC-32); with `005.0.60` (LYNX) / `007.0.60` (Patton-S) the app sends **24 bytes** — the gen-1 body *then* the gen-2 body, and `*_NEW` never travels alone | `:858`; legacy form `LEAPERKIM_PROTOCOL_GENERATIONS.md:189-195`; **"Confirmed in the running app (2026-10-07, Frida)"** `:64-69` | L2 for the ASCII byte; the binary form is a **dynamic observation of the app's own output**, not a logged BLE session against a wheel — it shows the bytes *produced*, not that a wheel accepted them | **T-BENIGN by reversibility, irreversible in fact** — it destroys data (`SAFETY_GATING_ARCHITECTURE.md:56`) |
| lock (the real one) | `Util.genPwdCmd`: opcode `0x12 + 7 = 0x19`, then a 7-byte body packed from three integers | `LEAPERKIM_PROTOCOL_GENERATIONS.md:212-222` | vendor-static; **body bounds UNKNOWN**; lock state read back from telemetry `data[51]` | T-CRITICAL / irreversible-in-fact |
| auto-close | `CMD_SET_CLOSE_IN_10` — **no bytes recovered**; the "10" unit (s or min) is UNKNOWN | `:209` | name only | T-CRITICAL / high |
| **do not transmit** | `CMD_LOCK_SUCCESS` = `4C 64 41 70 0A 10`, `CMD_UNLOCK_SUCCESS` = `4C 64 41 70 0A 24` | `:210,213-214,223-224` | declared but **dead**: referenced nowhere, absent from 1.0.3 and NOSFET 1.1.3 | — |
| open/close, light, clear-meter, ride mode (MODERN) | `CMD_OLDCMDB_NEW`, `CMD_SET_LIGHT_ON/OFF_NEW`, `CMD_CLEAR_METER_NEW`, `CMD_SETS/SETM/SETH_NEW` — **no bytes** | `:204-209` | names only | mixed |

### C4 — Inmotion: nine frames, all `AA AA` + XOR, **all L1**

`CROSS_BRAND_COMMAND_REFERENCE.md:934-935` notes the frame is *identical* to RideFlux's `familyi2`
framing, so this is a cross-check rather than a new format. Every row is **L1 — library only, no
vendor-static source** (`:938-947`); under the T11 rule ("any row cited L1-single-source is refused")
**none of these may be implemented as-is**.

| command | frame | source | risk |
|---|---|---|---|
| `LIGHT_ON` | `AA AA 14 04 60 50 01 01 20` | `:939` | T-BENIGN / low |
| `LIGHT_OFF` | `AA AA 14 04 60 50 00 00 20` | `:940` | T-BENIGN / low |
| `BEEP` | `AA AA 14 03 60 18 00 6F` | `:941` | T-BENIGN / low |
| `LOCK` | `AA AA 14 03 60 31 01 47` | `:942` | T-CRITICAL / high |
| `UNLOCK` | `AA AA 14 03 60 31 00 46` | `:943` | T-CRITICAL / high |
| `POWER_OFF` | `AA AA 14 03 60 77 01 01` | `:944` | T-CRITICAL / irreversible-in-fact |
| `REQUEST_SERIAL` / `REQUEST_FIRMWARE` | `AA AA 11 02 02 02 13` / `AA AA 11 02 02 06 17` | `:945-946` | read |
| `REQUEST_BATTERY_INFO` | `AA AA 14 01 04 11` — **V-lib + WheelDash + WheelLife byte-identical** | `:947` | read; the strongest row in that table |

### C5 — Ninebot Z: command codes with a framing caveat

`CROSS_BRAND_COMMAND_REFERENCE.md:1024-1064`, level **L1 (vendored library only)**. The file warns
that **"these command codes may be right while the frame they are wrapped in is wrong"** (`:1062-1064`).

| command | code | clamp | source | risk |
|---|---|---|---|---|
| `LIGHT_ON` / `LIGHT_OFF` | `0x50` value 1 / 0 | — | `:1032-1033` | T-BENIGN / low |
| `BEEP` | `0x18` value 1 | — | `:1034` | T-BENIGN / low |
| `LOCK` / `UNLOCK` | `0x31` value 1 / 0 | — | `:1035-1036` | T-CRITICAL / high |
| `SET_SPEED_LIMIT` / `SET_ALARM_SPEED` | `0x70` / `0x71` | **clamped 5–60** | `:1037-1038` | T-CRITICAL / high |
| `CALIBRATE` | `0x7A` value 1 | — | `:1039` | T-CRITICAL / irreversible-in-fact |
| query A / query B | `0x10` / `0x1A` | — | `:1040-1041` | read |
| BMS query / `CUSTOM` | **no bytes** (prebuilt constant / passthrough) | — | `:1042-1043` | read / T-CRITICAL by construction |
| ~~`POWER_OFF`~~ | **retracted** — an earlier revision listed `POWER_OFF(0x70)` next to the speed limit; only the speed limit exists | — | `:1049-1054` | — |

### C6 — Xiaomi M365: **there are no M365 writes to recover**

This is a negative result, and it is a designed one:

> "Blocks written to change scooter state. Empty for every model here: this crate is read-only for
> telemetry, and the app's write path is separate." — `M365_ROKID_HUD_CONNECTION_MODEL.md:275-277`

`writable: &[]` on every profile (`:298`, `:371`), and the verification table records
"any **write** has ever been sent to a vehicle | ❌" (`:501`). The M365 hex values in that file are
all polls (`0xB0` arg `0x20`, `0x3A` arg `0x04`, `0x25` arg `0x02`, `:312-316`). RideFlux's own docs
agree ("lock/unlock/power disabled", `docs/PROTOCOLS.md`). **Nothing for T10/T11 here.**

### C7 — Ninebot retail (ES / G30 / …): tiered, but not a T09 source

`SAFETY_GATING_ARCHITECTURE.md:110-126` grades that surface (`0x70`/`0x71` lock/unlock =
**T-CRITICAL** because the scooter resets; `0x7D` tail-light/units = T-BENIGN *only* under the
closed-loop rule; `0x78` reboot / `0x79` power-down / `0x18` serial program / `0x5C` odometer write /
`0x07-0x09` firmware update = **T-FORBIDDEN**). The bytes live in `NINEBOT_RETAIL_PROTOCOL_SPEC.md`,
which is **not** in T09's allowed-source list, so no row is transcribed here. Recorded as a pointer,
not as a gap in this table.

---

## 5. Rows with **no bytes recovered**, and the cheapest capture that would fill each

### 5.1 The 47 DarknessBot settings

All 47 rows are bytes-`UNKNOWN` for one structural reason (§2.1, `:439-441`). **One capture design
covers all 47**, because the app's write path is generic:

| question | cheapest capture | what the owner has to do |
|---|---|---|
| which opcode/frame each of the 47 settings sends | **HCI snoop log on the owner's own phone** while DarknessBot 7.0.0 changes **one** setting, with the target wheel connected; windowed per setting | connect DarknessBot to one wheel, start the snoop, change one setting, note it on screen, stop. The existing tooling already enforces window + single-peer isolation (`CAP-B_TOOLING_FIXES.md:16-20`) and the app has **no anti-analysis protection** (`DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md:14,32-48`), so nothing has to be defeated |
| the same, per model family | repeat with one wheel of each family present | the loop above, one family at a time; the adapter is chosen per model, so the same label can map to different bytes |

**Two honest caveats.** (1) A snoop log shows what the *app* sent, which is exactly what T11 needs,
but it does not prove the wheel accepted it — that needs a read-back. (2) An alternative for
the byte-level mapping is a Frida/platform-channel hook
(`DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md:166`: the VM-service strings exist but **Frida is not
installed**, and release AOT keeps the VM service off). The snoop log is cheaper and needs no new
tooling on the device.

### 5.2 Named but byte-less, per family

| missing item | why it is missing | cheapest capture |
|---|---|---|
| Begode A2 calibration / mode **request** bytes (responses `0x02`/`0x03` are named) | CAP-A contains **no vehicle writes** — the only host writes in it are the `N`/`V` queries and a CCCD enable (`CAP-B_TOOLING_FIXES.md:21,50-52`); `BEGODE_A2_ACTIONS.md:8,62` puts them out of scope by design | an HCI snoop of the **vendor app** while the owner runs one calibration on a stationary wheel. This is a *new* capture with a *different* charter: CAP-B as planned is read-only and explicitly forbids setting changes (`CAP-B-PLAN.md:102-105`), so it cannot answer this |
| Begode `W*` writes are L2/L1 but never wire-observed | no capture exists of any write | the same vendor-app snoop while changing pedals speed / beeper volume / ambient mode, then a read-back |
| KingSong `SET_PEDALS_MODE` byte 17, `SET_SPEED_LIMIT` operands, `SET_LED_MODE` byte 17, `SET_LIGHT_MODE` operands | library/vendor conflict; no wire capture | snoop of the KingSong vendor app while changing pedals mode, alarm speeds, LED mode and brightness |
| KingSong's 56 UI labels → command codes | "Chinese UI labels are not command codes" (`KINGSONG_COMMAND_VOCABULARY.md:125-127`) | the same snoop, with the tool's screenshot + screen-mark flow (`capB_shots.py`) so each write is attributed to the screen that was open |
| LeaperKim true lock body, `CMD_SET_CLOSE_IN_10` bytes, MODERN opcodes for light / pedals / clear-meter | names only (`LEAPERKIM_PROTOCOL_GENERATIONS.md:204-209`) | snoop of the LeaperKim vendor app on the owner's wheel while locking, setting the auto-close timer and switching the light |
| Inmotion I2 lock/unlock and the other seven frames are **L1, library-only** | no vendor-static source (`CROSS_BRAND_COMMAND_REFERENCE.md:938-947`) | snoop of the Inmotion app while locking / unlocking / beeping — this is the cheapest way to lift a whole family from L1 to L2+ |
| Ninebot Z write frames | "these command codes may be right while the frame they are wrapped in is wrong" (`:1062-1064`) | snoop of the Ninebot app while toggling light / lock / speed limit |
| Begode BMS detail (the vendor's cell pages, sub-types 2/3/5/6) | never decoded; the A2's `0x01` carried no BMS detail | **CAP-B screen C** — open the vendor app's battery/BMS page, pause ~10 s, screenshot with the status-bar clock visible, note voltage / SOC / cell count (`CAP-B-PLAN.md:141-152,274`) |
| M365 writes | **they do not exist** — read-only by design (`M365_ROKID_HUD_CONNECTION_MODEL.md:275-277,501`) | none; nothing to capture |
| Ninebot retail writes | outside T09's source list | out of scope for T09; see C7 |

### 5.3 What the *existing* captures can never answer

CAP-A (`BT_HCI_2026_0929_161755.cfa`, Begode A2, 11,424 frames, peer `88:25:84:F0:56:1A`) contains
**no state-changing write of any kind**. CAP-B, as planned and chartered, is also read-only:
*"No setting change of any kind — no calibration, tilt-back, speed, shutdown, pedal, transport, light
or mode change. No DFU. No L1/L2 write"* (`CAP-B-PLAN.md:102-105`). **Therefore no captured artefact
in this project can answer a write question today**, and a new capture whose charter *includes* the
vendor app performing a setting change is the only route. That is an owner decision, not a worker
one, and it is listed under `NEEDS FROM OWNER`.

**One exception, stated so it is not overread:** the LeaperKim Frida run of 2026-10-07 drove the
vendor app's own `sendData("CLEARMETER")` and recorded the bytes it produced
(`LEAPERKIM_PROTOCOL_GENERATIONS.md:64-69`). That is a dynamic observation of the *code's output*, and
it is the strongest write evidence in the tree — but no wheel was shown to accept the frame, and it
covers exactly one command.

---

## 6. Risk review against `SAFETY_GATING_ARCHITECTURE.md`

### 6.1 A task-file defect: there is no `G1`–`G5` in that file

The T09 acceptance line asks for the risk column to be "reviewed against
`SAFETY_GATING_ARCHITECTURE.md` (G1–G5)". **No `G1`–`G5` gate identifiers exist in that file** —
`grep -n 'G1\|G2\|G3\|G4\|G5'` returns nothing there. The only `G1`–`G5` in the findings tree are
(a) Begode **read-path gap** ids (`BEGODE_A2_TRUTH_TABLE.md:260`) and (b) CAP-B **tooling defect** ids
(`CAP-B_TOOLING_FIXES.md:16-20`) — both unrelated to write risk. What that file actually defines is
three independent axes plus a gate function:

| axis | vocabulary | where |
|---|---|---|
| danger tier | `T-LISTEN` / `T-BENIGN` / `T-CRITICAL` / `T-FORBIDDEN` | `:36-51` |
| evidence ladder | `L0` … `L4` | `:36-40` (and `_COMMON.md`) |
| action class | `DISPLAY` / `OPERATIONAL` / `IRREVERSIBLE` / `CRITICAL` | `:40` |
| gate | `may_send = evidence && reversibility && motion_interlock && confirmation` | `:70-75` |

The risk column above is therefore expressed as **`T-tier` + the task's own low/high/irreversible
word**, and the mapping is: `T-BENIGN` → *low*; `T-CRITICAL` with a reversible effect → *high*;
`T-CRITICAL` where the effect cannot be undone by the app (`Calibrate`, `PowerOff`, `CLEARMETER`) or
where data is destroyed → *irreversible*.

### 6.2 The three rules that decide whether any of these rows may ever be sent

1. **Motion interlock (§3, `:152,168-185`)** — *"No `T-CRITICAL` command may be transmitted while the
   vehicle is in motion, and 'in motion' is the default."* The interlock is **fail-closed and
   tri-state**; `STATIONARY_CONFIRMED` needs a resolved speed scale, **three** consecutive zero polls,
   freshness, **and a second independent zero indicator**. Every row marked `T-CRITICAL` above is
   blocked behind it.
2. **Closed-loop write (§4, `:211-223`)** — read → check → modify → write → read → compare **fields**,
   with `MISMATCH` and `UNVERIFIABLE` both being failures. `:230-237` names two of the rows in this
   report as structurally hazardous: Begode `BrakeCutoffOn/Off` vs `PowerBridgeOn/Off` **share their
   bytes** (a partial write can leave brake cut-off wrong), and the scooter `0x7D` word is read
   little-endian but written big-endian.
3. **Prohibitions (§6, `:287-300`)** — no DFU/firmware path ever; `T-FORBIDDEN` must be
   *unconstructible* ("no builder may exist", `:271`); a BMS password is never the gate; an
   unresolved scale is a blocker, not a default.

**Consequence for this report:** nothing in tables A-C may be enabled by T11 without both a
verification read and the interlock, and every `L1`-single-source row in table C is refused outright
by T11's own acceptance rule.

---

## 7. What this means for the tasks that consume it

### 7.1 T10 (`WheelCommand` vocabulary)

- The vocabulary has **three** sources, not one: the DarknessBot 47 (labels only), the vendor
  command tables in table C, and the **already-built-but-unwired frames** in §3.3 — the last are the
  cheapest, because the bytes already exist in `:data:protocol` and only the typed vocabulary and the
  capability flags are missing.
- Ranges to carry on the types come from the notes: `SetPedalSpeed` 3–90, `SetPowerAlarm` 50–90,
  `SetBeeperVolume` 1–9, six further Begode commands 0–9, `SetTiltShutdownGear` an index 0–2 with an
  **inverted** meaning (`DOMAIN_COMMAND_COVERAGE.md:92-95`).
- `Horn` and `SetTiltbackKmh` stay unencodable: `Horn` has **no evidence behind it in any of the six
  protocols** and is a candidate to become an alias of `Beep` (`:55-59`), while `SetTiltbackKmh` is
  *"the cheapest genuine encoder gap: three brands have the capability documented"* (`:107`).

### 7.2 T11 (encoders)

Applying T11's own rule — *implement at `L2`+, or `L1` from ≥ 2 independent sources; "any row cited
L1-single-source is refused"* — to table C:

| verdict | rows |
|---|---|
| **implementable** at L2 / multi-source (still behind interlock + closed loop) | most of Begode #3-5, #7-11, #13-27; KingSong byte 16 for the ten commands (byte 17 resolved in favour of the vendor's `0x14`, `:907-911`); LeaperKim light / beep / pedals-mode / `CLEARMETER` |
| **refused as L1-single-source** | Begode `b` (#6), `AlarmLevelOnAll 0` (#12, *contradictory*), `WP`/`WM`/`WR`/`WU`/`WC`/`WX`/`Wl` (#28-34); KingSong `0x88`/`0x89` byte 16 and `SET_PEDALS_MODE`'s `0x15`; **all nine Inmotion frames** (C4); **all Ninebot Z codes** (C5) |
| **no bytes at all** | all 47 DarknessBot settings (A); LeaperKim `CMD_SET_CLOSE_IN_10`; Ninebot Z `BMS query`/`CUSTOM` |
| **must not be sent** | LeaperKim `CMD_LOCK_SUCCESS`/`CMD_UNLOCK_SUCCESS` (dead constants); Ninebot Z `POWER_OFF` (does not exist); anything `T-FORBIDDEN` |

Two rows will need the closed-loop read to be *distinguishable at all* — Begode `e`/`x`
(brake-cutoff vs power-bridge) and `<`/`=`/`>` (ratios vs tilt-shutdown-gear) — because the wheel sees
identical bytes and only the mode tells them apart (`CROSS_BRAND_COMMAND_REFERENCE.md:190-203`).

---

## 8. Sources, tooling, and what was not done

### 8.1 Findings read (all under `/home/kali/PEVAppRE/euc-programme/findings/`, read-only)

`CROSS_BRAND_COMMAND_REFERENCE.md` · `DOMAIN_COMMAND_COVERAGE.md` · `KINGSONG_COMMAND_VOCABULARY.md` ·
`KINGSONG_WRITE_COMMANDS.md` · `BEGODE_A2_ACTIONS.md` · `LEAPERKIM_PROTOCOL_GENERATIONS.md` ·
`DARKNESSBOT_COMMAND_ARCHITECTURE.md` · `DARKNESSBOT_IMPLEMENTATION_2026-10-08.md` ·
`DARKNESSBOT_ANTI_ANALYSIS_ASSESSMENT.md` · `DARKNESSBOT_DART_NAME_INVENTORY.txt` ·
`darknessbot_adapter_dispatch.tsv` · `SAFETY_GATING_ARCHITECTURE.md` ·
`M365_ROKID_HUD_CONNECTION_MODEL.md` · `CAP-B-PLAN.md` · `CAP-B_TOOLING_FIXES.md` ·
`RIDEFLUX_COVERAGE_ROADMAP.md` · `BEGODE_A2_TRUTH_TABLE.md` (tier/gap vocabulary only).

Upstream apps already vendored in `euc-programme/upstream/` were **not** re-read for this report:
the facts they contribute are already written into the cross-brand table with a per-row source
column, and re-deriving them would have duplicated that work without adding an independent check.

### 8.2 Artifact verification performed here (not copied from a note)

| check | command | result |
|---|---|---|
| count the `Changing <name> to ` vocabulary | `grep -aoP 'Changing [A-Za-z][A-Za-z0-9 ]{1,32}? to ' libapp.so \| sort -u \| wc -l` | **47** |
| find a setting the §3 table omits | same, `grep cruise control` | `Changing cruise control to ` present **once** |
| de-duplicate the §3 table | `sed -n '133,140p' … \| grep -oP '\`[^\`]+\`' \| sort -u \| wc -l` | **46** distinct in **47** cells |

### 8.3 Not done

- **No dynamic analysis.** No device was connected, no BLE traffic was generated or captured, no
  write was sent, no app was instrumented. §1.3 and §5 explain what that costs and why.
- **No `NINEBOT_RETAIL_PROTOCOL_SPEC.md` transcription** (outside the task's source list) — see C7.
- **No production constant was added, moved or graded.** This document is the only file T09 changes.
- **No test or build was modified.** The `testDebugUnitTest lint` run for this branch is reported in
  the PR body, including the one pre-existing failure it surfaced.

---

## 9. Hand-off report

```
TASK: T09
BRANCH / PR: docs/T09-write-bytes (PR opened from this branch; number in the PR)
SUMMARY: Write-byte research, findings only, no Kotlin. The 47 DarknessBot settings are recorded as
  47 rows with UNKNOWN bytes, with the two count defects in the source table reconciled (47 cells,
  46 distinct, `alarms` duplicated, `cruise control` missing) and the structural reason for the gap
  quoted. RideFlux's existing 16 commands are tabulated per family with the bytes they already emit,
  plus the builder functions that exist but are not wired into any encode(). 34 Begode, 14 KingSong,
  the LeaperKim write path, 9 Inmotion and 12 Ninebot-Z vendor write rows are tabulated with source
  and level, and the L1-single-source rows T11 must refuse are listed explicitly.
FILES CHANGED: docs/agent-tasks/reports/T09-write-bytes.md (new; the only file this task touches)
TESTS: `.\gradlew.bat testDebugUnitTest lint` — run 1 (cold, machine busy) FAILED 1 of 129 tests in
  `:data:bridge:testDebugUnitTest` (`BridgeClientGattTest > scanFailureClosesFlowWithAnError`,
  `kotlinx.coroutines.test.UncompletedCoroutinesError` at `TestBuilders.kt:353`); run 2 (that class
  alone) BUILD SUCCESSFUL; run 3 (full suite) `BUILD SUCCESSFUL in 1m 58s / 362 actionable tasks:
  9 executed, 353 up-to-date / GRADLE_EXIT=0`. T09 changes no code — `git diff --stat main -- . ':!docs'`
  is empty — so the run-1 failure is timing-related, not caused by this branch.
EVIDENCE LEVEL OF NEW CONSTANTS: none — T09 adds no production constant. Every byte in the report
  carries the level of its source in-line; the report's own new facts are (a) `cruise control` as the
  47th `Changing <name> to` literal, verified directly in `libapp.so` (L1, third-party static), and
  (b) the citation-level mapping of existing RideFlux encoders, which are labelled "not graded in
  findings" rather than given a level T09 did not earn.
UNSUPPORTED / GAPS: all 47 DarknessBot settings (no opcode mapping is recoverable from the artifact);
  LeaperKim `CMD_SET_CLOSE_IN_10` and the MODERN light/pedals/clear-meter opcodes; Ninebot Z `BMS
  query` and `CUSTOM`; KingSong's 56 UI labels and 22 unnamed emit methods; M365 writes (they do not
  exist); Ninebot retail writes (outside T09's source list). Also refused: guessing names for the 22
  KingSong emit methods or a byte for any DarknessBot setting.
RISKS / THINGS I DID NOT VERIFY: no device was touched, no BLE write was performed or captured, and
  no DarknessBot dynamic observation was made, so **no byte in this report has been confirmed by the
  wheel that would receive it** — every write row is `needs HW: yes`. The `risk` column is a design
  tier from `SAFETY_GATING_ARCHITECTURE.md`, not a measurement. The task's acceptance mentions a
  `G1`–`G5` gate numbering that does not exist in that file (§6.1) — I mapped to the tiers the file
  does define, and a gatekeeper who meant something else should say so.
NEEDS FROM OWNER: (1) a decision on a **new** capture whose charter allows the vendor app to change a
  setting, since CAP-A/CAP-B are read-only by charter — that single capture design covers the
  DarknessBot 47 and the KingSong/LeaperKim/Inmotion/Ninebot bytes; (2) confirmation that the owner's
  own phone + wheel may be used for it, with DarknessBot 7.0.0 installed; (3) CAP-B as already
  planned would still settle the Begode BMS/cell-page layout (screen C) without any write.
```
