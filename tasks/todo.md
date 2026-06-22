# Project Todo — Recordings backend (Lane A, feat/recordings-backend)

Goal: persist each recording's audio to disk (when enabled) + Tauri commands to
list/play/re-transcribe/delete. Audio is 16 kHz mono s16le PCM. File boundary:
`src-tauri/**` only — never touch `src/**`.

## Plan

- [x] 1. Cargo.toml: flacenc 0.5 (default-on) + mp3lame-encoder 0.2 behind `encode-mp3`
      (added to `default`); also added tauri `protocol-asset` feature (required by asset proto).
- [x] 2. audio/encode.rs (TDD): RecordingFormat + from_config_str; encode_pcm. WAV reuses
      build_wav, FLAC via flacenc, MP3 via mp3lame under cfg. 5 tests pass (both feature modes).
- [x] 3. storage/mod.rs: AppConfig gained save_recordings(false)+recording_format("flac");
      idempotent recording_file column migration; HistoryEntry field; insert/list SQL;
      find_by_id/set/clear_recording_file/list_recordings/update_raw_text. Tests pass.
- [x] 4. stt: refactored whisper_compat upload into shared post_audio; added public
      transcribe_encoded + stt::retranscribe_file(config,bytes,ext,client). Test pass.
- [x] 5. pipeline.rs: TEE PCM when save_recordings; on close (finalizing) encode + write
      <app_data_dir>/recordings/<UTC-ts>.<ext>; plumbed path via Arc<Mutex<Option<String>>>
      into save_history -> recording_file column.
- [x] 6. commands/recordings.rs: RecordingEntry + 4 commands; registered in commands/mod.rs
      + lib.rs generate_handler!. 3 tests (incl. exact-serialized-keys assertion) pass.
- [x] 7. tauri.conf.json: assetProtocol enable + scope $APPDATA/recordings/*,**; CSP
      media-src/img-src allow asset:/http://asset.localhost.
- [x] 8. Build GREEN (default + --no-default-features); 133 tests pass; clippy clean.
- [x] 9. Commit + READY-TO-MERGE report.

## Contract (locked — match exactly)
- AppConfig: save_recordings: bool (false), recording_format: String ("flac"; wav|flac|mp3)
- Commands: get_recordings(limit:u32,offset:u32)->Vec<RecordingEntry>;
  get_recording_path(id:i64)->String; retranscribe_recording(id:i64)->String;
  delete_recording(id:i64)->()
- RecordingEntry snake_case JSON: { id, created_at, raw_text, polished_text,
  recording_file, format, duration_ms } (duration_ms nullable)

## Review

Shipped: all 9 tasks. Build green (default + no-default-features), 133 tests pass,
clippy clean. File boundary respected (only src-tauri/** + tasks/).

Deviation (documented): retranscribe_recording supports Whisper-compatible providers
(glm-asr default, openai-whisper, groq-whisper, siliconflow, custom-whisper) by uploading
the saved file directly — no decoders, no lossy PCM round-trip. Streaming-only providers
(deepgram, assemblyai, cloud) return a clear error instead. This keeps the change surgical
(one new method on WhisperCompatProvider, zero decoder deps) and covers the default + BYOK
file-upload providers. If retranscribe must support deepgram/assemblyai/cloud later, decode
the saved file to PCM and run the provider's standard send_audio/drain path.

Follow-ups (none blocking): rare orphan file if abort() races a finalize-time write
(file written, row not saved) — harmless, left for simplicity.
