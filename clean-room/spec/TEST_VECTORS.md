# Test Vectors

Each entry is a self-contained test case consisting of a raw frame in
hexadecimal and the expected decoded values. Team B's unit tests are
driven exclusively from this file.

No source code appears here. No reference-derived identifiers appear here.

---

## Format

Each vector block uses the following shape:

```
### TV-<NNN> — <short title>

Family: <device family>
Frame (hex): <space-separated bytes>
Direction:   device → app | app → device
Length:      <N> bytes

Expected decode:
  <field name>: <value> <unit>
  <field name>: <value> <unit>
  ...

Checksum:
  Scope: bytes [<start>..<end>]
  Expected: 0x<hex>

Notes:
  <free text, no code>
```

---

## Vectors

### TV-001 — _placeholder_

Family: _TBD_
Frame (hex): _TBD_
Direction: device → app
Length: _TBD_

Expected decode:
  _field_: _value_ _unit_

Checksum:
  Scope: bytes [_start_.._end_]
  Expected: 0x____

Notes:
  Replace this placeholder with the first real vector once §4 of the spec
  is filled in.
