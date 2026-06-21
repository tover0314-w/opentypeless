# Project Lessons — Recordings backend (Lane A)

Purpose: capture corrections so they aren't repeated. Lessons override defaults.

- 2026-06-22 — Never edit `src/**` (Lane B owns the frontend); only touch `src-tauri/**`.
  Why: parallel lanes in separate worktrees; collisions break the merge.
  Apply when: any file edit in this task.
- 2026-06-22 — Match Lane B's locked contract names/keys EXACTLY (command names, arg
  keys, RecordingEntry snake_case JSON fields). Why: frontend invokes them verbatim.
  Apply when: defining commands or serialized structs.
