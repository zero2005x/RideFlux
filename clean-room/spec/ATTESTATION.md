# Attestation

## Author

- Role: Protocol specification author.
- Mandate: Produce a standalone, human-readable Bluetooth protocol
  description usable as an input to an independent clean-room
  implementation.

## Method

- Analysis was carried out in terms of bytes, fields, units, signed-
  ness, endianness, scaling, and range constraints only.
- Where multiple interpretations were plausible, the document marks
  the ambiguity and lists all observed constraints (see
  `PROTOCOL_SPEC.md` §8).
- Test vectors in `TEST_VECTORS.md` are derived from the
  field definitions in `PROTOCOL_SPEC.md` and can be reproduced with
  nothing more than a hex editor, a calculator and a CRC-32/ISO-HDLC
  routine.

## Content restrictions

The author attests that this document, and the sibling files
`TERMS.md`, `TEST_VECTORS.md` and `CHANGELOG.md`:

1. Contain **no source code** in any programming language.
2. Contain **no pseudocode expressing control flow** (no `if`/`for`/
   `while` constructs, no function signatures, no iteration idioms).
3. Do **not reuse** identifiers (class names, method names, package
   names, variable names) from any specific implementation.
4. Do **not** refer to any specific application, library or source
   repository as their derivation source. Product and model names
   appearing in the document (Begode, Gotway, KingSong, Veteran,
   Ninebot, Inmotion, Sherman, Abrams, Patton, Lynx, Oryx, KS-18L,
   KS-S20, KS-F22P, etc.) are names of publicly available consumer
   hardware and identify protocol-compatible product families only.
5. Are expressed entirely as prose, bulleted lists, tables, byte-
   offset maps, bit-field tables and mathematical expressions.

## Downstream use

Any implementation derived from this specification must be written
without reference to any specific existing implementation. Should a
reviewer identify language in this specification that, in their
judgement, appears to leak implementation details, the offending
passage must be reported back to the author so that a corrected
revision (incrementing the document version) can be issued before
that passage is relied upon.

## Traceability

- `PROTOCOL_SPEC.md` — master specification.
- `TERMS.md`         — glossary, defines every non-obvious term used.
- `TEST_VECTORS.md`  — byte-level worked examples, one per major field
  group, suitable as fixtures for an implementation's unit tests.
- `CHANGELOG.md`     — history of revisions.

## Version

Specification version: **1.0.0** (initial).
Attestation version:   **1.0.0**.