# Speech Core v2 test and acceptance plan

Date: 2026-08-11
Status: Binding gate for the v2 default switch

## Test philosophy

Speech Core v2 is accepted on data integrity first, interaction latency second and recognition
quality third. A lower CER/WER cannot waive a duplicate, lost, wrong-field or unrecoverable result.

All accuracy comparisons separate:

- lexical error (CER/WER/MER);
- punctuation F1;
- entity recall and negative-control replacement;
- live interaction behaviour;
- raw ASR quality versus deterministic/quality/AI transformations.

One recorded take is replayed across compatible engines for accuracy. Live microphone tests are
reported as interaction tests, not fair model leaderboards.

## Layer 1 — pure reducer tests

Mandatory transition coverage:

| ID | Scenario | Pass condition |
| --- | --- | --- |
| R-01 | Monotonic live revisions | Newer full-segment text replaces older text once. |
| R-02 | Duplicate revision | State and rendered output remain byte-identical. |
| R-03 | Out-of-order revision | Lower/equal revisions are ignored. |
| R-04 | Blank partial after text | Existing non-blank text remains visible. |
| R-05 | Soft boundary reopen | Provisional punctuation can be removed without losing lexical text. |
| R-06 | Hard boundary | Closed audio segment cannot accept another live revision. |
| R-07 | Quality final out of order | Segment order remains stable while quality jobs finish in reverse order. |
| R-08 | Sealed segment | Late model events cannot change sealed text. |
| R-09 | User lock | Manual revision wins over every later model revision. |
| R-10 | Session generation | An event from session A cannot affect session B. |
| R-11 | Bounds | Segment/text/revision overflow fails closed and preserves accepted prefix. |
| R-12 | Explicit discard | Draft becomes discarded and all later events are no-ops. |

Add generated/property tests over event permutations, duplicate injection and random legal/illegal
transitions. Persist failing seeds as regression fixtures.

## Layer 2 — engine adapter and trace replay

- System recognizer partial/final/error traces;
- current local Streaming Paraformer full-hypothesis traces;
- SenseVoice quality results;
- DashScope stable/unstable sentence traces;
- batch final-only results;
- malformed, oversized, empty and late callbacks;
- missing timestamps/stability/confidence;
- provider restart and route-change boundaries.

Adapters must never synthesize unavailable capabilities. Trace files contain no private transcript,
credential, endpoint token or package context.

## Layer 3 — audio segmentation

Use generated PCM and consented/pinned recordings covering:

- silence, click, cough and 120 ms short speech;
- one-to-four Chinese characters and one-to-three English words;
- 0.3/0.6/1.0/2.0/5.0 second pauses;
- hesitation, self-correction and resumed clauses;
- Chinese/English code switching at a boundary;
- words spanning the detected boundary;
- maximum duration and text limits;
- cancellation during a frame, soft boundary and hard-boundary handoff.

Pass conditions:

- soft pause never stops capture;
- hard boundary creates exactly one ordered audio segment;
- overlap never duplicates or truncates the reference word after text reconciliation;
- explicit finish flushes the final tail;
- explicit discard prevents every pending journal/model write.

## Layer 4 — transformation tests

- Provisional punctuation may change; sealed punctuation is stable.
- Exact aliases/corrections are idempotent over repeated revisions.
- Disabled/out-of-scope rules never apply.
- Negative controls remain unchanged.
- Numbers, dates, URLs, email, code and protected entities pass integrity guards.
- Locale-aware ITN is evaluated independently and remains disabled where it regresses controls.
- LLM failure/timeout/schema/integrity rejection returns the refined non-LLM text.
- Selected-text commands never enter the ordinary draft projection.

## Layer 5 — journal and process-death tests

| ID | Scenario | Pass condition |
| --- | --- | --- |
| J-01 | Capture checkpoint then process death | Ordered session/segment audio or text is recoverable. |
| J-02 | Quality final replaces audio | Completed text is durable before result delivery. |
| J-03 | Discard races write | No file/draft reappears after restart. |
| J-04 | Two quality jobs finish backwards | Each updates only its own segment generation. |
| J-05 | Corrupt/truncated entry | Entry is quarantined/bounded; safe sessions can continue. |
| J-06 | Keystore/disk failure | Visible in-process draft remains; reduced durability is explicit. |
| J-07 | Quota/TTL | Old entries are bounded and safely evicted without deleting an active draft. |
| J-08 | Plaintext scan | Seeded content is absent from files, temp files, DB/WAL/SHM and backups. |

## Layer 6 — EditorProjection tests

Use a programmable fake `InputConnection` that can return false, throw, delay and emit selection
callbacks.

- composing replacement and exactly-once final commit;
- committed-prefix plus composing-tail projection;
- cursor move and repeated surrounding text;
- app/field/connection/epoch change;
- editor rejecting composing, commit, finish or delete;
- target change while quality result is queued;
- user-locked text followed by a late model result;
- hide/switch IME, Home/resume, view recreation and service destruction;
- short/long dictation undo ledger;
- password, no-learning and selected-text safety;
- explicit discard at every capture/recognition/delivery phase.

The pass condition is always zero cross-field writes, zero silent deletion and one acknowledged
logical insertion at most.

## Layer 7 — Android instrumentation matrix

Run at least API 26, 33, 35 and 36 builds; execute device tests on available API 26/33/35/36
targets where CI capacity permits.

- Binder/service same-UID and non-exported contract;
- stream/quality/punctuation process isolation and kill/rebind;
- encrypted journal with real Android Keystore;
- InputMethodService lifecycle and view recreation;
- light/dark, gesture/three-button, portrait/landscape;
- 320/360 dp and font scale 1.0/1.3/2.0;
- TalkBack labels, live regions and minimum 48 dp targets;
- install/update/migration/rollback;
- no model weight or secret in APK/backup/logs.

## Layer 8 — recognition evaluation

PR screening:

- pinned ASCEND Mandarin/English/mixed subset;
- pinned FLEURS Mandarin/US-English subset;
- deterministic synthetic entity positives and negative controls;
- short-utterance and punctuation fixtures.

Nightly/release expansion:

- complete ASCEND and FLEURS splits;
- AISHELL-1 and LibriSpeech test sets where licensing/download gates permit;
- noise/RIR mixes, low-level speech, accents and long pauses;
- unseen consented 500–1,000-utterance phone-microphone set.

Report raw streaming, refined quality and final transformed text separately. Public-set overlap is
possible and cannot replace the unseen mobile set.

## Layer 9 — performance and resource acceptance

Report cold and warm distributions, not one successful take. The following are engineering targets
for Xiaomi 15 and must be revised only from recorded evidence, never to hide a regression:

| Metric | Target |
| --- | --- |
| Hot gesture to true ready | p50 <150 ms, p95 <250 ms |
| Speech onset to first visible live revision | p50 <350 ms, p95 <700 ms |
| Soft pause to provisional punctuation | p95 <400 ms after boundary detection |
| Hard boundary to refined segment | p95 <1.2 s |
| Explicit finish to final editor commit | p95 <1.5 s; target <500 ms when no refinement is pending |
| Empty/truncated one-to-four-character prompts | no more than 1/20 per prompt/route |
| Wrong-field, duplicate or silent-loss rate | exactly 0 |

Resources are process-bounded:

- app/IME, stream worker, quality worker and punctuation worker PSS separately;
- cold peak, warm peak, steady listening and 30 seconds after release;
- CPU time, thermal state, battery delta and network bytes;
- 1/5/15-minute sustained sessions and ten cancel/retry cycles;
- no severe-or-higher thermal state, idle CPU loop, microphone lock or monotonic memory growth.

The current Xiaomi P0 guardrails remain the outer release bounds until v2 establishes stricter
physical baselines.

## Xiaomi 15 end-to-end acceptance

Reuse every XM-P0-01 through XM-P0-19 scenario and add:

| ID | Scenario | Pass condition |
| --- | --- | --- |
| XM-V2-01 | Pause and resume | 0.6/1/2/5 s pauses do not stop long capture or lose text. |
| XM-V2-02 | Earlier-word revision | Current open segment can revise an earlier wrong word in place. |
| XM-V2-03 | Provisional punctuation | Pause adds punctuation; resumed speech may revise it without lexical loss. |
| XM-V2-04 | Concurrent segment refinement | Segment N refines while N+1 remains live; order and cursor stay correct. |
| XM-V2-05 | Late quality after target change | Old editor is frozen/recoverable; new editor remains untouched. |
| XM-V2-06 | User correction protection | A manual correction is never overwritten by a late model event. |
| XM-V2-07 | Process pressure | Killing stream/quality workers preserves IME and recoverable draft. |
| XM-V2-08 | Warm model lifecycle | Repeated short dictation avoids cold load within policy and releases after TTL/pressure. |
| XM-V2-09 | Journal recovery | Force-stop/restart restores ordered segments with no duplicate insertion. |
| XM-V2-10 | Resource policy | Concurrent/sequential strategy and any quality delay are truthful and within bounds. |

Record exact APK hash, Git commit, model hashes, HyperOS build, locale, navigation, font scale,
battery mode, selected/actual route and runtime strategy. Evidence remains redacted according to
the existing Xiaomi acceptance policy.

## Stable-release default decision

Speech Core v2 must not be described as a stable store-release default while any of the following
is true:

- an invariant or mandatory lifecycle/recovery scenario fails;
- Xiaomi evidence is missing or tied to a different APK/commit;
- v2 silently fabricates provider capabilities or changes network/privacy routing;
- public/unseen results show a material lexical, punctuation or entity regression without a clear
  user-controlled trade-off;
- resource measurements exceed the supported-device policy;
- signed upgrade/rollback and recovery migration are unverified.

Passing automated tests may authorize an explicitly labelled engineering/debug cutover with a
tested rollback switch; it does not by itself authorize a stable release claim.

## Executed evidence — 2026-08-12

| Gate | Result | Notes |
| --- | --- | --- |
| Pure Android JVM | PASS — 415/415 | Zero failure/error/skip; includes reducer/audio/transform/runtime/journal/projection, punctuation integrity, model specification, production routing and final-projection regressions. |
| Offline benchmark tooling | PASS — 60/60 | Python 3.11 with pinned `numpy==2.4.6`, `sherpa-onnx==1.13.4`; includes exact streaming-Paraformer runner tests. |
| Debug / Release lint | PASS with one intentional warning | Zero errors. `ChromeOsAbiSupport` warns about the optional arm64-only delivery property; universal builds still include x86_64. |
| APK assembly | PASS | Debug, unsigned minified Release, AndroidTest and arm64 delivery APK built. |
| API 36 arm64 ordinary device suite | PASS — 41 pass / 1 designed skip | Keystore journal, Android `InputConnection`, visual navigation/runtime, migration, service contracts, lifecycle and provisioned native worker tests passed. The only skip is the separately invoked large-download E2E. |
| Provisioned exact native models | PASS — separate opt-in E2E 1/1 | The app downloaded and hash-verified Streaming Paraformer, SenseVoice and CT-Transformer punctuation. Private `:local_stream`/`:local_quality` produced non-empty partial/final text from a deterministic 16 kHz Mandarin smoke WAV. Private `:local_punctuation` produced `我们都是木头人，不会说话，不会动。`, survived rebind/reload, and exited after its session lease. |
| Public real-speech screening | PASS as evidence; model capability gate FAIL | 200 pinned ASCEND/FLEURS cases. Current stream model: zh CER 12.5%, en WER 40.2%, mixed MER 22.9%, partial coverage 95.5%, 0 earlier-text rewrites across 1,682 changed hypotheses. |
| Physical Xiaomi 15 for this exact APK | NOT RUN | No physical device is currently visible to ADB; older user measurements are diagnostic context, not acceptance for this hash. |

### Default-switch verdict

**YES for the engineering APK; NO stable-release claim yet.** Real v2 capture, continuous
segmentation, a retained streaming worker, an isolated on-demand quality worker, independently
modelled and lexically guarded provisional/final punctuation, encrypted recovery and target-bound editor projection now form the ordinary local
keyboard route. A missing streaming model fails visibly; v1 can be selected only by the explicit
emergency preference.

The exact Paraformer model still does not revise earlier words by itself. Earlier text changes come
from bounded segment punctuation, deterministic personalization and the SenseVoice quality result,
which is the capability the product truthfully exposes. Xiaomi 15 XM-V2-01 through XM-V2-10 must be
rerun against the final APK hash before this engineering cutover can be promoted to a stable signed
release.
