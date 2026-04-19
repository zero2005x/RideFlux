# Clean-Room Workspace

This directory holds the artifacts of the clean-room rewrite process defined
in [../CLEAN_ROOM_PROCESS.md](../CLEAN_ROOM_PROCESS.md).

## Layout

- `spec/` — Team A deliverables. **Team B must not read anything else in the
  reference repository; Team B reads only the tagged snapshots of this
  folder.**
- `impl/` — Team B design notes and implementation-side documentation. Team A
  must not author anything here.
- `audit/` — Coordinator checklist and signed audit log.
- `prompts/` — The canonical prompts used when driving each team via an AI
  assistant. Team A's prompt and Team B's prompt must be run in **separate
  sessions** that do not share context.

## Access Rules (summary)

| Folder | Team A | Team B | Coordinator |
| --- | :---: | :---: | :---: |
| `spec/` | write | read (tagged snapshots only) | review |
| `impl/` | — | write | review |
| `audit/` | — | — | write |
| `prompts/` | read | read | write |

See [../PROVENANCE.md](../PROVENANCE.md) for the full permitted/forbidden
input lists.
