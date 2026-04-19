# Audit Checklist

The Coordinator reviews each specification snapshot before it is handed to
Team B, and again before the implementation is merged. Every item below
must be **Pass** for the rewrite to be considered compliant.

Record results in [AUDIT_LOG.md](AUDIT_LOG.md).

---

## A. Team Separation

- [ ] Team B has no access (and has had no access) to the external reference
      repository, mirrors, diffs, or file listings.
- [ ] Team B has no access to legacy files in this repository that are
      suspected of being derivative, until the rewrite is merged.
- [ ] Team A and Team B do not share chat sessions, prompt history, or
      machines in a way that could leak reference content to Team B.
- [ ] All communication between Team A and Team B about implementation
      details is routed through the Coordinator.

## B. Specification Hygiene

- [ ] The specification contains **no source code** and no pseudocode that
      mirrors reference control flow.
- [ ] The specification contains no reference-derived identifiers.
- [ ] The specification contains no "confirmed from code",
      "reverse-engineered from source", or equivalent phrasing in its body.
- [ ] Every numeric constant, lookup table, or state machine is justified
      by the spec or by a cited public document.
- [ ] Ambiguities are marked explicitly with observed constraints.
- [ ] `ATTESTATION.md` is signed by the specification authors and witnessed
      by the Coordinator.

## C. Implementation Hygiene

- [ ] All new packages use `com.rideflux.*` (or another independent
      namespace). No occurrence of `wheellog` anywhere in the code.
- [ ] Identifiers follow conventional Kotlin naming and are not traceable
      to the reference project.
- [ ] The implementation only contains behavior described in the spec.
      Any "quirk" is backed by a spec clause or public-doc citation in
      [../impl/IMPLEMENTATION_NOTES.md](../impl/IMPLEMENTATION_NOTES.md).
- [ ] Unit tests cover every vector in
      [../spec/TEST_VECTORS.md](../spec/TEST_VECTORS.md) and pass.

## D. Repository Hygiene

- [ ] Git history is incremental and descriptive; no single giant initial
      commit for the rewrite.
- [ ] Commit messages reference only the spec document and public sources.
- [ ] `LICENSE` (GPL-3.0) is present and per-file copyright headers have
      been added to all new clean-room files.
- [ ] [../../PROVENANCE.md](../../PROVENANCE.md) is up to date.
