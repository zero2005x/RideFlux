# Common rules — paste this above every task prompt

You are a worker agent on RideFlux (Android EUC/scooter companion + Rokid HUD, Kotlin, multi-module
Gradle). A gatekeeper reviews your PR. Follow these rules exactly; they come from real failures.

## Environment (Windows, this machine)

- Use **PowerShell** for commands. The Bash tool exits 53 here; do not use it.
- Gradle: set `JAVA_HOME` to a **JDK 21** (the default JDK 25 is rejected by Gradle 8.13) and
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp` (create `C:\gtmp` if missing), then `.\gradlew.bat`.
- Never print `local.properties` (signing and Rokid secrets). Never commit anything under `secrets/`.
- **Never read-modify-write source files with `Get-Content`/`Set-Content`** — it corrupts UTF-8 and
  adds a BOM. Edit with the editor/Edit tool only. If you see mojibake, stop and report.
- Pass commit text with `git commit -F <file>` and PR text with `gh pr create --body-file <file>`
  (quotes in `-m`/`--body` get split in PowerShell).
- PowerShell variables are case-insensitive: `$p` and `$P` are the same variable.
- The working tree is shared with other tools and may hold unrelated uncommitted files
  (for example `GEMINI_*.md`). Run `git status` first. Work on a new branch from `main`
  (or use a worktree). **Stage explicit paths only** — never `git add -A` or `git add .`.
- You may open a PR. **You may not merge.** `gh pr merge` is blocked; the owner merges.

## Evidence (read-only reference)

Reverse-engineering notes live in WSL: `\\wsl.localhost\kali-linux\home\kali\PEVAppRE\euc-programme\findings\`
(read with PowerShell `Get-Content -Encoding UTF8`, view-only). Key files are named in each task.
Every note carries an evidence level: L0 string only, **L1 community/derived — a lead, not proof**,
L2 spec-grade or multiple concordant sources, L3 observed on hardware, L4 repeatable on hardware.
Rules:
- A byte offset, scale factor or register address may enter production code only if it is
  L2+ or the task names an A2 capture that confirms it. Otherwise write it as a **named,
  documented constant marked `L1`**, behind an Experimental flag, with a test that pins the
  source sentence, and say so in the PR.
- **If you do not have the bytes, do not invent them.** Return `Unsupported` and list the gap
  in your report. Fabricated protocol bytes can hurt a rider.
- Do not copy code from `ninebotcrypto` (AGPL) or WheelLog/EUC World source. Re-derive from the
  written spec; cite the spec file in a comment.

## Code rules

- Match surrounding code: naming, comment density, module boundaries (see `docs/ARCHITECTURE.md`,
  `docs/PROTOCOLS.md`). Decoders are pure functions; keep them free of Android types.
- Decoders must be total: malformed or short input returns a typed error/`Malformed` flag, never throws
  and never yields plausible-looking wrong values.
- No new dependencies unless the task says so. No INTERNET permission. No network code.
- Every behaviour change needs unit tests; new code coverage target ≥ 80 % (Sonar gate).
  Prefer real byte captures from `*/src/test/resources` or WheelLog test data (credited in `NOTICE`).
- New user-visible strings: add English + Traditional Chinese by hand, and the other 16 languages
  using the glossary from T12 (`docs/LOCALIZATION.md`). Do not leave raw keys in any language.
- Do not touch unrelated files, do not reformat, do not bump versions (T13 does that).

## Definition of done (run before you report)

```
.\gradlew.bat testDebugUnitTest lint
```
plus whatever extra task commands are listed. Paste the real tail of the output in the report.
If something fails, say so with the output; never write "should pass".

## Report (end your work with exactly this, in the PR body and in your reply)

```
TASK: Txx
BRANCH / PR:
SUMMARY: (3-6 lines)
FILES CHANGED: (explicit list)
TESTS: (command + result tail)
EVIDENCE LEVEL OF NEW CONSTANTS: (each constant -> level -> source file)
UNSUPPORTED / GAPS: (everything you refused to guess)
RISKS / THINGS I DID NOT VERIFY:
NEEDS FROM OWNER: (devices, captures, decisions)
```

## Gatekeeper checklist (what the reviewer will check)

1. Only the task's files changed; explicit staging; no secrets; no BOM/mojibake.
2. Every new protocol constant has a level and a source; none invented.
3. Decoders total and tested with real bytes; negative tests present.
4. A2 behaviour unchanged unless the task says otherwise (existing tests untouched and green).
5. No INTERNET permission, no new dependency, no write path enabled by accident.
6. Strings complete in 18 languages; no hard-coded UI text.
7. Report complete and honest about gaps.
