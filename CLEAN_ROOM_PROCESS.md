# Clean-Room Rewrite Process

This document defines the governance, roles, and procedures that apply to any
clean-room rewrite work performed in this repository — in particular the
Bluetooth protocol decoding layer and any other logic whose provenance can be
traced to an external reference project.

The goal is a **legally defensible** rewrite with clear provenance, auditable
separation of duties, and license compliance.

---

## 1. Purpose

Produce a new implementation of the targeted modules such that:

- The implementation is written **only** from an intermediate, code-free
  specification.
- The people who read the reference source and the people who write the new
  code are strictly separated (the "Chinese Wall").
- The resulting repository has clean Git history, explicit licensing, and an
  attestation trail.

## 2. Team Definitions

### Team A — Specification / Reverse Engineering (Readers)

- **May read**: the external reference repository, its commit history, and any
  public hardware documentation.
- **Must produce**: a code-free, human-readable protocol specification plus
  test vectors expressed as raw bytes and expected decoded values.
- **Must not**:
  - Write any production code in this repository.
  - Leak reference identifiers (package names, class names, method names,
    variable names) into the specification or into conversations with Team B.
  - Describe algorithms in a form that mirrors reference control flow.

### Team B — Implementation (Writers)

- **May read**: only Team A's published specification and public vendor /
  hardware documentation.
- **Must produce**: a fresh implementation that satisfies the specification,
  plus unit tests driven by the published test vectors.
- **Must not**:
  - View the reference repository, diffs, file names, or any derivative
    artifacts.
  - View prior files in this repository that are known or suspected to have
    been influenced by the reference source, until the rewrite is complete.

### Clean-Room Coordinator (recommended)

- Ensures process compliance.
- Maintains an access log (who saw what, and when).
- Reviews Team A deliverables for policy violations (source-derived names,
  copied tables, "confirmed from code" phrasing, etc.) **before** the
  deliverables are handed to Team B.

## 3. Access Controls

- Team B must not have access to the reference repository or to any legacy
  files in this project that are suspected of being derivative until the
  rewrite is merged.
- Team A and Team B should work from separate machines, profiles, or at
  minimum separate accounts.
- Team A writes only into [clean-room/spec/](clean-room/spec/).
- Team B writes only into [clean-room/impl/](clean-room/impl/) and, once
  approved, into the main source tree.
- The Coordinator writes into [clean-room/audit/](clean-room/audit/).

## 4. Handoff Procedure (Spec → Code)

1. Team A finalizes a versioned snapshot of the specification bundle
   (`PROTOCOL_SPEC.md`, `TERMS.md`, `TEST_VECTORS.md`, `CHANGELOG.md`,
   `ATTESTATION.md`).
2. Coordinator reviews the snapshot against the audit checklist
   ([clean-room/audit/AUDIT_CHECKLIST.md](clean-room/audit/AUDIT_CHECKLIST.md)).
3. On approval, the snapshot is tagged (e.g. `spec-v0.1`) and published to
   Team B.
4. Team B implements strictly from the tagged snapshot, using the Team B
   prompt in [clean-room/prompts/TEAM_B_PROMPT.md](clean-room/prompts/TEAM_B_PROMPT.md).
5. Any clarification from Team B must be routed through the Coordinator, who
   either answers from the spec or requests a spec amendment from Team A.
   Direct Team A ↔ Team B contact about code is prohibited.

## 5. Deliverables

### From Team A

- [clean-room/spec/PROTOCOL_SPEC.md](clean-room/spec/PROTOCOL_SPEC.md)
- [clean-room/spec/TERMS.md](clean-room/spec/TERMS.md)
- [clean-room/spec/TEST_VECTORS.md](clean-room/spec/TEST_VECTORS.md)
- [clean-room/spec/CHANGELOG.md](clean-room/spec/CHANGELOG.md)
- [clean-room/spec/ATTESTATION.md](clean-room/spec/ATTESTATION.md)

### From Team B

- New source code under an independent namespace (e.g. `com.rideflux.*`).
- [clean-room/impl/IMPLEMENTATION_NOTES.md](clean-room/impl/IMPLEMENTATION_NOTES.md)
- Automated tests that consume Team A's test vectors.

## 6. Specification Content Requirements

The specification must be:

- **Code-free**: no Java/Kotlin snippets; no pseudocode that mirrors reference
  control flow.
- **Name-sanitized**: describe generic concepts (e.g. "read an unsigned 16-bit
  little-endian integer"), never reference-derived function or field names.
- **Semantics-first**: define messages, fields, units, ranges, scaling, and
  signedness.
- **Explicit about ambiguity**: mark uncertain behavior and list observed
  constraints instead of guessing.
- **Independent in wording**: avoid phrases like "confirmed from code" or
  "reverse-engineered from source".

## 7. Implementation Rules

- Independent namespace: do not use any package containing `wheellog`. Use
  `com.rideflux.*` consistently.
- Industry-standard naming: prefer `readInt16LE`, `readUInt16LE`, etc.
- No unexplained quirks: every constant, lookup table, or state machine must
  be justified by the specification or public vendor docs.
- Transparent Git history: incremental, descriptive commits. No single giant
  initial commit.
- Provenance in commit messages: reference only the spec document and public
  sources.

## 8. Licensing Strategy

The reference project is **GPL-3.0**. This repository adopts the conservative
strategy:

- **Strategy A (selected)**: release the rewritten modules under **GPL-3.0**.
  Add `LICENSE` and copyright headers, and provide source to users as
  required.

Strategy B (non-GPL licensing via true black-box reverse engineering where
Team A never reads source) is **not** in effect here. Switching to Strategy B
would require restarting Team A's work under black-box rules and obtaining
legal counsel.

See [PROVENANCE.md](PROVENANCE.md) for the concrete inputs and outputs of this
process.

## 9. Audit

The rewrite is considered "passed" only if every item in
[clean-room/audit/AUDIT_CHECKLIST.md](clean-room/audit/AUDIT_CHECKLIST.md) is
satisfied. The Coordinator signs off in
[clean-room/audit/AUDIT_LOG.md](clean-room/audit/AUDIT_LOG.md).
