# Project Todo — Recordings Frontend (Lane B)

- [x] 1. tauri.ts: add RecordingEntry interface + 4 typed wrappers
- [x] 2. appStore: add save_recordings + recording_format to AppConfig + defaults
- [x] 3. router.ts: add 'recordings' route + parseHash guard
- [x] 4. App.tsx: render <Recordings/> for route
- [x] 5. MainLayout: add Recordings nav item (Mic icon)
- [x] 6. Recordings/index.tsx: new screen mirroring History (audio, retranscribe, delete)
- [x] 7. Settings GeneralPane: save_recordings toggle + recording_format dropdown
- [x] 8. i18n: recordings block + nav.recordings in all 10 locales (identical keys)
- [x] 9. npm run build GREEN + npm run lint clean (0 errors) + 125 tests pass
- [x] 10. commit on feat/recordings-frontend

## Review
Shipped:
- Recordings screen mirroring History: date grouping, search, empty state, jelly springs, Toast.
- Per-row <audio controls> via convertFileSrc(getRecordingPath(id)), Re-transcribe (spinner + row update), Delete (confirm).
- Settings: Save recordings toggle + Recording format dropdown (wav/flac/mp3), persisted via existing DirtyBar/update_config path.
- 4 typed tauri wrappers + RecordingEntry interface.
- i18n: recordings block + nav.recordings + 3 settings keys across all 10 locales, identical key sets.

Verification: tsc clean, vite build OK, eslint 0 errors (3 pre-existing warnings in untouched files), 125/125 tests pass, locale parity verified (15 recordings keys each).

Deferred / notes for integrator:
- RecordingEntry.duration_ms typed as `number | null` to mirror HistoryEntry convention (not used in UI yet).
- Recordings are fetched on mount in the component (not preloaded in App.tsx), unlike History which is preloaded into the store.

## Lessons
None — no corrections received.
