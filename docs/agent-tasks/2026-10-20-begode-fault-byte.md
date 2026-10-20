# Begode type-0x04 fault byte: byte 14, not 15 (2026-10-20)

**Verdict: confirmed bug, high confidence for the byte (14) and medium-high for the bit names.**
`main` (`d15d26c`) reads the Family G type-0x04 fault/alert bits from byte 15 and the light from
bytes 14 and 17. Every external implementation found on this machine reads the fault bits from
byte 14 and the headlight from byte 15. On `main`, a headlight mode of 2 or higher (any value that sets bit 1 or 2
of byte 15) produces a false `OverSpeed` fault and a `TiltBack` alert. A real fault in byte 14 is
never reported.

Fix: branch `claude/begode-fault-byte-8c09b6`, cut from `main` `d15d26c`. It is local only: not
pushed, no PR. Tests: `:data:protocol:test` passes 412/412, Family G 41/41, 8 of them new.

## 1. What each source says about type 0x04 bytes 13-17

| byte | RideFlux `main` | com.begode.app 0.1.23 (new vendor app) | com.zzcyi.monotroch 1.0.49 (old vendor app) | WheelLog `GotwayAdapter.java` (code) | WheelLog spec comment | CAP-A (A2, 2,856 frames) |
|---|---|---|---|---|---|---|
| 13 | — | `ambientLightModeRaw` (low byte of ch5) | (part of `getShort(13,12)` IMU temp in type 0; not read in type 4) | `ledMode = buff[13]` | "LED mode" | 4 in every frame |
| **14** | `ledMode` | **`stateBits = (ch6 >> 8) & 0xFF`** | **`byteToBitString(bArr4[14])` → fault list** | **`alert = buff[14]`** | "unknown" | 0 in every frame |
| **15** | **`alertBitmap`** (faults) | **`headlightMode = ch6 & 0xFF`** | **`this.lightMode = bArr4[15]`** (0 off, 1/2/3 on) | **`lightMode = buff[15] & 0x03`** | "unknown" | 0 in every frame |
| 16-17 | `lightMode = u8@17 & 0x03` | `vehicleIdRaw` (ch7, u16BE) | `devideType = getShort2(17,16)` (u16BE) | not read | "unknown" | 18 (`00 12`) in every frame |

The vendor app builds its channels big-endian: in `WheelProtocolParser.decodeFrame`, channel *i* is
`raw[2i+2] << 8 | raw[2i+3]`. So ch6 is bytes 14-15, with its high byte at frame byte 14. The old
app's `HexUtil.byteToBitString` is MSB-first, so `substring(7)` is bit 0.

**Who disagrees with whom:** on the byte, `main` disagrees with all three external sources, which
agree with one another. `main`'s comments cite `PROTOCOL_SPEC.md` §3.1.4/§4.1. That file is not in
the repo, its git history or the research database. The byte-15 read dates to the initial commit
`dddd45b`, and it looks like WheelLog's byte map shifted by one byte.

### Bit meanings (byte 14)

| bit | new vendor app | old vendor app | WheelLog code | `main` (applied to byte 15) | this fix |
|---|---|---|---|---|---|
| 0 | unnamed | `alarm_power_h` (high power) | `setWheelAlarm`, "HighPower" commented out | wheel alarm, not a fault | `stateHighPowerWarning`, not a fault |
| 1 | `hasMosFault` | `"mos"` | "Speed2" | speed level 2 → `OverSpeed` + `TiltBack` | MOS fault → `WheelFault.Unknown("G.stateBit1.mosFault", 1)` |
| 2 | `hasGyroFault` | `alarm_gyro`, **shown only while session max speed < 15 km/h** | "Speed1" | speed level 1 → `OverSpeed` + `TiltBack` | gyro → `WheelFault.ImuSensor` |
| 3 | low voltage | `alarm_volt_l` | LowVoltage | low voltage | `LowBattery` (+ alert) |
| 4 | high voltage | `alarm_volt_h` | OverVoltage | over voltage | `OverVoltage` |
| 5 | high temp | `alarm_temp_h` | OverTemperature | over temp | `MosOverTemperature` (+ alert) |
| 6 | hall | `alarm_hall` | errHallSensors | hall | `MotorHallSensor` |
| 7 | `isLocked` (a state; not in `activeFaults`) | `alarm_lock` (after 3 frames) | TransportMode | transport mode, not a fault | `stateLocked`, not a fault |

All three sources agree on bits 3-6. On bits 1 and 2, the two vendor apps (MOS/gyro) disagree with
WheelLog (speed alarms). Neither reading supports a **tilt-back** alert from these bits.

## 2. Wire and real-frame evidence

- **CAP-A** (the owner's A2 capture, `notify_stream.bin`; re-counted today): all 2,856 type-0x04 frames read byte 13 = 4, byte 14 = 0, byte 15 = 0, bytes 16-17 = 18.
  This fits both hypotheses, so it cannot settle the question.
- **WheelLog's own test frames**, recorded from real wheels (`GotwayAdapterTest.kt`):
  - "decode with 2020 board data" and "decode strange board data": parked wheels (speed 0 and
    about 0) both send bytes 14-15 = `00 07`. Under `main` that is "wheel alarm + speed L1 + speed
    L2" on a standing wheel, which is implausible. Under the vendor reading it is headlight 7 with no
    state bits. `main`'s own `BegodeDecoderTest` vector is WheelLog's spec frame B with the same
    `00 07`, and it asserted the speed alarms.
  - "decode with new board data 2": at about 66.6 km/h, bytes 14-15 = `01 00`, so byte 14 bit 0 is
    set (high-power warning) and byte 15 = 0. At 48 km/h ("new board data") both bytes are 0.
    Bit 0 rising only at high speed fits "high power" in byte 14. Bytes 16-17 = 18 in both frames,
    the same as the A2.

## 3. Verdict and confidence

- **Byte 14 = state bits, byte 15 = headlight: confirmed, about 95 %.** Three independent
  implementations agree, including both vendor apps. `main`'s reading has no surviving source.
  WheelLog's real parked-wheel frames contradict it. The remaining 5 % covers a firmware generation
  that swaps the bytes; no source suggests one exists.
- **Bits 3-6: confirmed** (unanimous).
- **Bits 1/2 = MOS/gyro, not speed alarms: medium-high, about 80 %.** Both vendor apps say so for
  the firmware they ship with. WheelLog's "Speed2/Speed1" may describe older Gotway firmware. The old
  vendor app hides the gyro text once the session's top speed reaches 15 km/h. That may mean bit 2
  sets while riding on some firmware, which would match WheelLog's Speed1. So the fix's
  `ImuSensor` from bit 2 could be noisy while riding on such a wheel. It cannot become a speed alarm
  or a tilt-back either way.
- **Bytes 16-17 = device type: confirmed by both vendor apps.** No source reads byte 17 as a light
  mode, and WheelLog reads the light from byte 15.

## 4. The fix (local, not pushed)

- `BegodeDecoder.decodeSettingsAndOdometer`: `stateBits = u8@14`, `headlightMode = u8@15`,
  `deviceType = u16BE@16` (replaces `ledMode`, `alertBitmap`, `lightMode`; none of them had a consumer
  outside the codec).
- `BegodeFrame.SettingsAndOdometer`: `state*` properties use the vendor names (table above).
- `BegodeWheelCodec`:
  - `buildFaults` uses the vendor map. Bit 1 becomes `BegodeWheelCodec.MOS_FAULT`, because
    `WheelFault` has no MOS variant. Bit 2 becomes `ImuSensor`. Bits 0 and 7 are not faults.
    `OverSpeed` is no longer produced.
  - The `TiltBack` alert driven by bits 1/2 is removed: no state bit is known to mean tilt-back.
- Tests:
  - `BegodeDecoderTest`: the section-2 vector now pins `stateBits 0`, `headlightMode 7` and
    `deviceType 8`, with every state bit clear.
  - New `BegodeStateBitsTest` (8 tests):
    - byte 15 = `0xFF` gives no faults and no alerts;
    - WheelLog's parked 2020-board frame gives no faults;
    - the A2 CAP-A frame decodes as 0/0/18;
    - over-temperature follows byte 14 and not byte 15;
    - `0x7E` maps to the vendor fault set without `OverSpeed`;
    - `0x06` maps to MOS + IMU with no `TiltBack`;
    - `0x81` gives no faults;
    - WheelLog's 66.6 km/h frame has the high-power bit set but no fault.
  - `NOTICE` credits the two extra WheelLog frames.
- Adapted from `tmp/begode-apply-check` `df35e5a` and the device-type half of `c2191a9`. The
  `rideMode`/`pedalsMode` change and the rest of that branch were not imported.
- Interaction with PR #98: that PR edits the same three files but not the type-0x04 or fault code.
  Expect small textual conflicts only. Its capture replay reads `autoPowerOffSeconds`, which is
  unchanged.

## 5. Remaining hardware check (CAP-B add-on, A2, stationary, read-only)

Record type-0x04 frames (HCI snoop under the CAP-B rules, or RideFlux's raw log) while doing the
following, about 10 s per step, and write down the time of each step:

| step | vendor reading (this fix) predicts | `main`'s reading predicts |
|---|---|---|
| 1. Headlight off → each mode in turn → off (vendor app light button) | **byte 15** steps through 0 → 1/2/3 → 0. Byte 14 stays 0. Bytes 16-17 stay `00 12` | byte 14 or the low bits of byte 17 change. Byte 15 stays 0 |
| 2. Ambient/LED light mode changed in the vendor app | **byte 13** changes (now 4). Bytes 14-15 unchanged | — (byte 13 not read) |
| 3. Lock the wheel in the vendor app, wait 5 s, unlock | **byte 14 = `0x80`** while locked, then 0 | byte 15 = `0x80` ("transport mode") |

Step 1 alone settles the byte question. Step 3 is the only safe way to make byte 14 non-zero on
the wire; low voltage, over-temperature, MOS, gyro and hall faults cannot be induced safely. If step
3 shows byte 14 = `0x80`, the byte is proven on the A2.

**Optional, while riding:** log one ride with a hard acceleration above 50 km/h and note whether byte
14 bit 0 (high power) or bit 2 ever sets. If bit 2 sets with no gyro fault, WheelLog's "Speed1"
reading applies to this firmware. Then bit 2 should not map to `ImuSensor` (or only below 15 km/h,
as the old vendor app does).

## 6. Sources

- The owner's protocol research notes (not published): the Begode protocol-evolution findings, §4, §4.2, §4.3.
- com.begode.app 0.1.23: `com.euc.android.protocol.Packet4Telemetry` and `WheelProtocolParser`.
- com.zzcyi.monotroch 1.0.49: `com.zzcyi.monotroch.ui.home.HomeFragment` (the type-0x04 handler) and
  `ble.util.HexUtil.byteToBitString`.
- WheelLog.Android at `1b37079`: `app/src/main/java/com/cooper/wheellog/utils/GotwayAdapter.java`
  lines 282-336 and 845-875; `app/src/test/java/com/cooper/wheellog/utils/GotwayAdapterTest.kt`
  lines 100-250.
- Branch `tmp/begode-apply-check` commits `df35e5a` and `c2191a9` (local, never pushed).
