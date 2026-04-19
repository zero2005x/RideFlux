# Provenance

This document records the inputs, outputs, and boundaries of the clean-room
rewrite performed in this repository. It is intended to make the origin of
every clean-room artifact auditable.

See [CLEAN_ROOM_PROCESS.md](CLEAN_ROOM_PROCESS.md) for the governing process.

---

## 1. Scope

Modules covered by the clean-room rewrite:

- Bluetooth protocol decoding layer (telemetry frame parsing, command frame
  encoding, checksums/CRCs, obfuscation/crypto steps).
- Any additional modules later added to this list by amendment.

Modules **not** covered (authored independently of any external reference):

- _List here as the project evolves._

### 1.1 Quarantined Legacy Files (suspected derivative)

The following paths are **off-limits to Team B** until the clean-room rewrite
is merged and the Coordinator clears them. They are suspected of being
derivative of the external reference project (notably, all use the
`com.wheellog.next` namespace, which is reserved to the reference project's
upstream identity and is forbidden by
[CLEAN_ROOM_PROCESS.md](CLEAN_ROOM_PROCESS.md) §7).

Broad module roots:

- [data/protocol/](data/protocol/) — entire module (sources and tests).
- [data/ble/](data/ble/) — entire module (sources and tests).

Additional locations that reference `wheellog` and must be reviewed before
any Team B contact:

- [app/build.gradle.kts](app/build.gradle.kts) — `namespace` and
  `applicationId` set to `com.wheellog.next`.
- [feature/device-scan/build.gradle.kts](feature/device-scan/build.gradle.kts)
  — `namespace` set to `com.wheellog.next.feature.devicescan`.
- [domain/src/test/kotlin/com/wheellog/next/domain/usecase/TripRecorderTest.kt](domain/src/test/kotlin/com/wheellog/next/domain/usecase/TripRecorderTest.kt)
  and any other source tree paths matching `**/com/wheellog/next/**`.
- All `**/bin/**` and `**/build/**` artifacts under modules above (build
  output mirroring quarantined sources; do not surface to Team B).

Quarantine handling:

- Team B's working environment must not expose these paths. When invoking
  any AI session as Team B, scope the workspace, file pickers, and search
  tools to exclude the paths above.
- The Coordinator may grant temporary read access to a single quarantined
  file only for the purpose of recording a finding in this document; such
  access must be logged in
  [clean-room/audit/AUDIT_LOG.md](clean-room/audit/AUDIT_LOG.md).
- Once the clean-room implementation under `com.rideflux.*` is merged, the
  quarantined files are scheduled for deletion or rewrite as a separate,
  Coordinator-approved task. Until then they remain in the tree but are
  excluded from the rewrite's permitted inputs.

## 2. Permitted Inputs

### Team A (Specification)

- The external reference repository and its commit history (read-only).
- Public vendor documentation and hardware datasheets.
- Observed device behavior captured via packet captures or logs produced on
  hardware the team legally owns or is authorized to test.

### Team B (Implementation)

- The tagged specification bundle produced by Team A, consisting of:
  - [clean-room/spec/PROTOCOL_SPEC.md](clean-room/spec/PROTOCOL_SPEC.md)
  - [clean-room/spec/TERMS.md](clean-room/spec/TERMS.md)
  - [clean-room/spec/TEST_VECTORS.md](clean-room/spec/TEST_VECTORS.md)
- Public vendor documentation and hardware datasheets.
- Standard language, framework, and library documentation.

## 3. Forbidden Inputs

### Team B must not consult

- The external reference repository, its mirrors, its diffs, or file listings.
- Any legacy files in this repository identified as potentially derivative
  until the rewrite is merged and the Coordinator clears them.
- Any draft spec that still carries code-derived identifiers or
  "confirmed from code" annotations.

### Team A must not produce

- Source code for this project.
- Verbatim copies of reference tables, constants, or identifiers.
- Pseudocode that mirrors reference control flow.

## 4. Artifact Location

| Artifact | Path |
| --- | --- |
| Process governance | [CLEAN_ROOM_PROCESS.md](CLEAN_ROOM_PROCESS.md) |
| Provenance (this file) | [PROVENANCE.md](PROVENANCE.md) |
| Specification bundle | [clean-room/spec/](clean-room/spec/) |
| Implementation notes | [clean-room/impl/](clean-room/impl/) |
| Audit checklist & log | [clean-room/audit/](clean-room/audit/) |
| Team prompts | [clean-room/prompts/](clean-room/prompts/) |

The production implementation itself lives in the normal module tree
(`core/`, `data/`, `domain/`, `feature/`, etc.) under the `com.rideflux.*`
namespace.

## 5. Reproducing the Tests

1. Read [clean-room/spec/TEST_VECTORS.md](clean-room/spec/TEST_VECTORS.md).
2. Each test vector lists a raw frame in hexadecimal plus the expected
   decoded field values.
3. The Team B unit tests load these vectors and assert that the decoder
   produces the documented output. Running the project's standard test
   command executes them.

## 6. Licensing

This repository is released under **GPL-3.0** for the clean-room modules,
consistent with the reference project's license. See `LICENSE` at the repo
root (add if not yet present) and per-file copyright headers.

## 7. Change Log

Amendments to the scope, permitted inputs, or forbidden inputs must be
recorded here with a date and the Coordinator's initials.

- _YYYY-MM-DD — initial draft._
