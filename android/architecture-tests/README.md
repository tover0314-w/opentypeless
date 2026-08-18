# Android architecture contracts

This dependency-free package provides the fast source-level feedback introduced by BLD-008. It
checks package placement, obvious capability imports, pure-domain Android references, and the
audited transitional source inventory before Gradle compilation.

EDT-002 adds the authoritative architecture boundary in `:architecture-gate`. That gate consumes
Gradle's actual Debug and Release `PROJECT`/`ALL` class artifacts and uses ASM to inspect JVM type
edges, inheritance, method calls, method handles, bootstraps, and the exact legacy editor-write
inventory. Generated sources and future Kotlin output therefore cannot bypass the production
check through source syntax. The source scanner remains intentionally useful, but it is not the
security boundary.

Run it directly:

```bash
python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v
python3 android/architecture-tests/architecture_contracts.py --android-root android
(
  cd android
  ./gradlew --dependency-verification=strict :architecture-gate:check
)
```

Both legacy inventories are intentionally exact and shrinking. EDT tasks must remove migrated
calls from the source and compiled inventories in the same change; adding another exception is not
an accepted migration.

CFG-001 closes `com.opentypeless.android.config` to the exact pure-Java `ProviderConfig` and
`SecretRef` family. Source and Debug/Release compiled gates require the sealed ASR/LLM/Connector
variants, generic Optional field types, bounded Endpoint, exact Secret kind enum, immutable record
surfaces, and absence of Android, serialization, persistence, networking execution, legacy
`AppSettings`, or extra config binaries. Raw key/password/token fields and public execution methods
are rejected; behavior tests separately prove ID/Unicode/URI/private-HTTP/credential-transport and
redacted `toString()` boundaries. CFG-001 does not wire or migrate the existing settings path.

CFG-002 extends that closed package with the exact `RecognitionRoute` family: one bounded route
record, immutable ordered steps, a one-or-two-attempt retry record, and closed privacy,
capability, failure, and confirmation vocabularies. Every step carries an explicit privacy class;
source and compiled gates lock the generic List/Set signatures, defensive-copy edges, 1..8-step
surface, nested enum values, and absence of Android, serialization, endpoint, SecretRef, provider
instance, legacy diagnostics route, or execution authority. JVM behavior tests prove unique
provider IDs, terminal failure denial, finite fallback structure, privacy floor, confirmed
downgrades, on-device capability consistency, input immutability, and redacted identifiers.
CFG-002 remains a value-only contract; Provider registry cross-checking and runtime selection stay
in REC-003/REC-009.

CFG-003 adds the exact generic `OverrideValue` three-state family and its versioned, bounded,
no-I/O JSON/DB seam. `Inherit` and `Disabled` remain private-constructor singletons, while only
`Value<T>` carries a non-null explicit payload, so empty strings and `false` cannot collapse into
inheritance or disablement. The canonical JSON array and four-column `DbRow` preserve a separate
presence bit, reject coercion, unknown versions/states, extra or missing fields, malformed UTF-16,
and oversized input, and redact payloads from `toString()`. Source and Debug/Release compiled gates
freeze all eight binaries, generic signatures, factory/codec call edges, `org.json` scope, and the
absence of Android, persistence, networking, serialization, provider, secret, route, or other
execution authority. CFG-003 does not create a table, read settings, or migrate existing data;
those integrations remain in CFG-004/CFG-006.

CFG-004 adds the versioned, immutable `GlobalConfig`, `AppRule`, `FieldRule`,
`RuleOverrides`, and `ProcessingMode` value contracts. The global root has exactly five typed
partitions (keyboard, voice, processing, privacy, and automation); app and field rules expose the
same five three-state override leaves without nullable maps or legacy settings authority. Source
and Debug/Release compiled gates freeze all eleven binaries, exact record components and generic
signatures, constructor validation edges, the four-value processing vocabulary, and redacted
diagnostics. Android, serialization, persistence, networking, provider/route/secret capabilities,
reflection, unbounded maps, and `AppSettings` remain forbidden. CFG-004 defines values only: it
does not resolve effective profiles, persist or migrate configuration, or wire the production
settings path.

CFG-005 adds the single pure-domain `EffectiveProfileResolver` and immutable
`EffectiveProfile` result. Ordinary leaves resolve in the exact
session → field → application → global → provider-default order; `Disabled` is terminal and
explicit `false` remains a value. Sensitive fields instead receive one complete hard-safety
profile: voice, context, history and actions are disabled while processing is `EXACT`. Every leaf
retains a closed source and content-free explanation, provider defaults cannot inherit, and app/
field rule inputs are defensively copied, duplicate-free and capped at 256/512. Source and
Debug/Release compiled gates freeze all nine binaries, record/generic/enum surfaces, exact factory
ownership and resolver edges, reject extra config binaries or I/O/settings/provider authority,
and keep diagnostics payload-redacted. CFG-005 is value-only: persistence, migration, UI wiring and
provider-registry runtime checks remain in their dependent tasks.

CFG-006 confines the Android 0.2 `AppSettings` migration to the exact package-private
`LegacyAppSettingsMigration` family and `SettingsRepository`. The migration writes one complete
format-1 `GlobalConfig` shadow plus source/version/backup markers into the existing settings file
with a synchronous `SharedPreferences.Editor.commit()`; `apply`, `clear`, `remove`, a second store,
and partial updates are forbidden. Old keys remain the rollback source. Source and Debug/Release
compiled gates freeze the migration failure vocabulary, redacted internal values, scalar codecs,
single-store adapter, all backend/mode mappings, and the exact repository load/save/recovery
edges. Secret, Provider, Context, database, file, network, serialization, and outside migration
authority cannot enter the family. This shadow is not runtime configuration authority; AppRule,
SecretRef, final ConfigStore transactions, UI, and route registry wiring remain CFG-007/008/011
and later consumer work.

CFG-007 confines the Android 0.2 `AppProfile` migration to the exact package-private
`LegacyAppProfileMigration` family and `AppProfileRepository`. It maps every legacy mode and
ordinary boolean to an explicit format-1 `AppRule` state; in particular `sendContext=false`
remains `Value(false)` rather than inheriting a potentially more permissive global value. The
exact-package rule shadow is sorted, bounded to the legacy 100-profile limit, duplicate-free and
stored beside `profiles_v1` with one synchronous commit and a retained-backup marker. Target
language, custom instructions, secrets and unknown fields stay only in the legacy rollback source.
Source and compiled gates freeze the mapping, failure vocabulary, transaction, redaction,
repository ownership and outside-authority denial. The shadow remains inert until CFG-011 and
later consumers select the new resolver as runtime authority.

CFG-008 adds the bounded final `SecretStore` and its exact Keystore adapter. Source and compiled
gates freeze the 11 store binaries, public/nest/field/failure surfaces, synchronous char-buffer
use, fixed legacy-slot bridge, SettingsRepository load/save/recovery edges, and the only permitted
calls into package-confined `SecurePreferences` snapshot/protect/decrypt/commit methods. Plaintext
getters, open storage/callback shapes, Bundle/serialization/log/network/export dependencies,
outside store consumers, unauthorized legacy-ciphertext callers, or missing Debug/Release binaries
fail closed. The three Android 0.2 ciphertext slots remain rollback/runtime authority until CFG-011;
the shadow does not authorize Provider or UI consumers.

CFG-009 adds an Android-free bounded `AppPickerModel` plus one package-confined
`InstalledAppCatalog`/`AppPickerDialog` path owned by `AppProfileActivity`. The catalog uses exactly
one `LauncherApps.getActivityList(null, Process.myUserHandle())` query, keeps a bounded ephemeral
icon map, and exposes neither persistence nor network authority. Source and Debug/Release compiled
gates reject `QUERY_ALL_PACKAGES`, `getInstalledApplications`, `getInstalledPackages`,
`queryIntentActivities`, cross-layer catalog/model references, unauthorized dialog/catalog calls,
open or serializable model shapes, missing binaries, and unredacted inventory diagnostics. The
ordinary flow therefore presents searchable launchable apps with icons, while packages without a
visible launch entry remain available only through the explicit advanced package-name field.

CFG-010 adds one Android-free `RuleExplanationModel` that accepts only an already-resolved
`EffectiveProfile`. It projects the six terminal values into typed display values while preserving each
exact `RuleSource` and `ResolutionExplanation`; the immutable precedence list is presentation vocabulary,
not a second resolver. Source and Debug/Release compiled gates require all nine model/nest binaries, exact
six-getter projection, closed feature/display shapes, redacted diagnostics, and reject Resolver requests,
Global/App/Field rule inputs, settings/Android/I/O authority, direct priority recomputation, or resolver
source/explanation vocabulary escaping to UI consumers. CFG-010 therefore explains resolver output without
reading storage or reimplementing hard-safety/session/field/app/global/provider selection.

EDT-007 adds exactly one permanent writer:
`com.opentypeless.android.editor.host.EditorTransactionManager`. It must remain a package-private
final top-level class, may not retain or return an `InputConnection`, and may invoke only
`beginBatchEdit`, `endBatchEdit`, `commitText`, `deleteSurroundingTextInCodePoints`, and
`performEditorAction` on the exact framework interface.

EDT-009 extends that same exact writer with `setComposingText(CharSequence, int)` and
`finishComposingText()`. Both composition edges are confined to the reviewed
`invokeMutator(InputConnection, EditorOperation)` dispatcher; overloads, wrappers, method
references, helper methods, capability erasure, and nested writers remain forbidden. The Debug
and Release bytecode edges are counted by source method, opcode, target owner, name, descriptor,
and occurrence; similarly named `$Evil` binaries, other host helpers, legacy adapters, and
providers do not inherit that authority.

EDT-010 keeps commit evidence outside `EditorTransactionResult` and locks the atomic
`TransactionReceipt` seam. Pure editor models may not acquire Android or serialization contracts;
`CommitRecord`, its request/envelope family, and receipt records may not carry `Throwable`, editor
capabilities, callbacks, executors, reflection, method handles, or other execution authority.
`CommitLedger` remains a package-private final owner-thread object with one direct `CommitRecord`
slot, exact-ID `resolve`/`consume`, and no `latest`/`last`/`peek`/`take`/`poll`/`current` retrieval
API. The receipt-preserving host callback is confined to the exact
`HostLease.consumeWithCurrentConnectionForReceipt(ScopedReceiptConnectionUse)` surface and may not
escape to providers or retain/return `InputConnection`. The transaction writer inventory remains
exactly seven framework edges; only the reviewed `executeBatch` descriptor changed to return its
atomic receipt.

EDT-011 adds one package-confined exact-ID Undo façade with the same four-argument descriptor in
`EditorSessionManager` and `EditorTransactionManager`: commit ID, caller CAS snapshot, live
authority supplier, and `UndoEvidenceReader`. Receipts, commit records, operations, and editor
connections are not authorization parameters or results. Only the exact transaction manager may
resolve or consume `CommitLedger`, and only `EditorSessionManager.undoCommit` may call the exact
transaction Undo façade. The evidence reader/request/result family is package-confined, redacted,
and synchronous: only its one audited `read(InputConnection, UndoEvidenceRequest)` callback may
temporarily receive the scoped connection, while its absolute selection plus text evidence types
may not retain or return it or
escape to UI/providers. `ReplaceLastCommit` may be constructed only inside the exact Undo path;
ordinary `apply` remains non-authoritative. Both ordinary and Undo batching reuse the single
`beginBatch(InputConnection)` sink and the existing `invokeMutator` delete sink, so the transaction
writer inventory remains exactly seven compiled framework edges. The legacy `SessionUndoLedger`
inventory is intentionally unchanged until its later migration task. EDT-008 extends this exact-ID
path for non-collapsed receipt origins: after the committed suffix is proved, Undo uses the same
one-shot replacement transition to delete Final and reinsert the record-bound original selected
text; no caller-supplied plaintext or `setSelection` edge is added.

EDT-012 adds the parallel package-confined four-argument `restoreRawCommit` façade without
accepting a raw string, record, receipt, operation, or editor capability from its caller. Only the
exact resolved VOICE record supplies the replacement text. Ordinary `apply` explicitly rejects
`RAW_RESTORE`; `ReplaceLastCommit` and physical RAW_RESTORE operations are authorized only inside
the exact Raw flow. Between delete and insert, an exact ESM-nestmate `RawTransition` binds its
minting owner, lifecycle authority, manager/proven-from/target selections, original session,
committed fingerprint, target replacement fingerprint, and one-shot
`COMMITTED`/`ORIGINAL`/`UNDO`/`RAW` proof state. It carries neither plaintext nor an
`InputConnection`, is redacted, and cannot escape to UI/providers or be consumed by another
caller. Raw Restore reuses the same `UndoEvidenceReader`, `beginBatch`, `finishBatch`, and
`invokeMutator` sinks; the transaction writer inventory therefore remains exactly seven compiled
framework edges. Every two-stage delete/insert step requires both a true mutator acknowledgement
and its exact live proof; false/throwing apply steps stop before any later target write and fail
closed unless EDT-013 has first proved the exact rollback basis described below.
Neither the broad legacy `OpenTypelessImeService.guardedReplace` sink inventory
nor `SessionUndoLedger` is shrunk by this core-only slice. Non-collapsed VOICE receipts are restored
through the same record-bound transition, using the original selected text only as fingerprint
context while Raw remains the exact ledger-owned target.

EDT-008 extends ordinary package-confined transactions with `ReplaceSelection` while keeping the
framework writer inventory at exactly seven edges. The operation record carries an exact range,
selected-text fingerprint, replacement text, and source. Both validation rounds bind those claims
to a single scoped `CurrentEvidenceReader` result containing live absolute selection coordinates
and bounded selected/before/after evidence. The reader/evidence family cannot escape the exact
session/transaction hosts or retain an `InputConnection`. Insert and Replace converge on the one
existing `invokeMutator` `commitText(CharSequence, int)` callsite; compiled control-flow checks
reject delete-plus-insert, `setSelection`, a second commit sink, or helper/loop bypasses.
False/throwing Replace calls remain outcome-unconfirmed and never publish a receipt: even a full
replacement hash plus bounded original context cannot prove that a periodic suffix of the old
selection was removed. Owner-bound one-shot `ReplaceTransition` tokens therefore support only
fail-closed outcome diagnosis; they are redacted, content-free, exact-owner scoped, and reject
replay, foreign consumers, selection ABA, and lifecycle drift. This is a Host primitive only:
legacy/external composing writers must be mutually exclusive before EDT-017 wires it into the
production voice route. The host slice now includes exact-ID selected-origin Undo/Raw recovery,
but production UI/voice routing remains outside EDT-008 and is still gated on EDT-017 mutual
exclusion.

EDT-013 adds one bounded rollback attempt after the second write in a selected-origin Undo or Raw
Restore fails. The transaction must first consume a one-shot `ORIGINAL` proof, then it may restore
only the ledger-bound committed Final through the existing shared `invokeMutator` commit sink. A
`RolledBack` result can be constructed only by `restoreCommittedAndClassify`, and only after a true
restore acknowledgement plus a full `COMMITTED` proof. Unsafe basis, lifecycle or connection
drift yields `RESTORE_TEXT/NOT_SAFE_TO_ATTEMPT`; false/throwing restore and failed verification
remain `RollbackFailed`. `RolledBack` retains the exact ledger slot for a later user retry, while
`RollbackFailed` revokes it. The production transition edges are counted exactly (seven prepare,
five validate, one verified `RolledBack` constructor), and no eighth framework writer edge or
`setSelection` authority is introduced.

EDT-014 adds an immutable, content-free terminal audit observation without changing any
`EditorOperation` constructor. `EditorTransactionAudit` is fixed to the exact
`OperationSource`/seven-value `EditorOperationKind`/`EditorTransactionResult` record surface; it
may not carry text, session or selection identity, fingerprints, commit IDs, receipts, Android
capabilities, throwables, callbacks, serialization contracts, or execution authority. Only the
exact `EditorTransactionManager` may construct it, and only its private best-effort `recordAudit`
helper may invoke the package-confined `AuditSink`. Ordinary receipt transactions and exact-ID
Undo/Raw paths each publish once after reaching a stable result; sink exceptions are observation
failures and cannot replace that result. Debug and Release gates count the exact helper,
constructor, source/result accessor, and sink edges while the framework writer inventory remains
seven. The audit value is diagnostic data, never write authorization; persistence, export, UI,
and `DiagnosticStore` wiring remain outside EDT-014.

EDT-015 makes the dual writer boundary an explicit, fail-closed CI contract. The source scanner
audits every production Java/Kotlin source set, rejects custom source routing, reflection and
method references, and keeps both the seven-edge transaction writer and every transitional legacy
writer on exact shrinking inventories. The compiled gate inspects actual Debug and Release
`PROJECT`/`ALL` artifacts, so generated code, Kotlin output, wrapper hierarchies, erased capability
transfers, method handles, and overload changes cannot create an unreviewed sink. Android
`InputMethodService` helpers that indirectly mutate the current editor—including extracted-text,
connectionless handwriting, editor-action, key-event, and key-character helpers—are writer sinks,
not privileged shortcuts. `editor_write_ci_gate.py` verifies that the source tests, production
scan, strict compiled `check`, both variants, and app class exports remain connected to the
non-advisory Android CI job. EDT-015 does not migrate or bless legacy writes: EDT-016/017 must
shrink both inventories as their routes move through `EditorTransactionManager`.

EDT-016 migrates the current minimal keyboard's space/punctuation, backward delete, and enter
paths through three exact `EditorSessionManager` façades. The public surface accepts only the
field-free `KeyboardHost`, a fresh snapshot, and (for insertion) bounded text; the manager itself
constructs `LATIN` Insert/Replace/Delete/Action operations and delegates to its sole transaction
child. The exact IME service is the only production façade caller. Selected insertion and delete
use `ReplaceSelection`, collapsed delete uses one code-point `DeleteBeforeCursor`, allowlisted
IME actions use `PerformEditorAction`, and the default enter path inserts `"\n"`; there is no
`KeyEvent` or indirect `InputMethodService` writer fallback. Sensitive collapsed typing retains
the zero-plaintext evidence path, while sensitive selected replacement remains fail closed.
The service legacy inventory consequently removes its former `backspace`, `commitText`, and
`sendEnter` edges; the permanent transaction inventory remains exactly seven and all voice,
Undo/Raw, and composition legacy edges remain registered for EDT-017.

EDT-017 freezes one editor-writer choice when a voice target is captured. The default-on,
synchronously persisted `VoiceEditorTransactionConfig` selects either the complete transaction
route or the complete legacy route for that session; partial/final failure never falls through to
the other writer. The transaction route owns a capability-free, generation-bound
`VoiceTransactionSession`, drops duplicate/stale partials and every post-terminal partial, and
uses six exact public `EditorSessionManager` façades for composition, final receipt, exact-ID Undo,
and exact-ID Raw Restore. Only the exact IME service methods may call those façades. V1 and V2
provider callbacks converge before editor delivery, while selected text remains preview-only until
the terminal `ReplaceSelection` transaction. Production Debug/Release gates count all ten exact
service edges, require early return before either legacy partial/final branch, require mutually
exclusive session construction, and retain the permanent seven-edge transaction sink inventory.
Legacy writer classes remain only behind the frozen rollback branch; they cannot execute in the
same voice session as the transaction writer.

CMP-004 binds that default transaction route to one service-owned `CompositionCoordinator`.
Only the capability-free `VoiceTransactionSession` may retain its exact owner-issued
`Observation`: it acquires VOICE before the session starts, advances ready/strict partial/final
states, and releases only after the Manager/ETM result proves physical commit or cancellation.
Uncertain cleanup keeps the owner fail closed until the editor lifecycle revokes the old lease.
Source and compiled gates require exactly one private final Coordinator, the exact session fields
and acquire/ready/partial/final/complete/cancel edges, and reject Coordinator state or calls in
providers, UI, or adapters.

CMP-005 extends only the exact bound Voice session with a two-phase Voice-to-Latin keyboard
preemption. The source gate freezes one `CompositionConflictPolicy`, fresh post-release Session
capture, the physical finish/cancel calls, and the single keyboard completion path. The compiled
gate fixes the opaque text-free `KeyboardPreemption` shape, exact begin/finish ticket edges,
service-to-Manager release edges, and service-to-session call counts. An uncertain release remains
pending until editor lifecycle revocation; it cannot fall back to a second writer or let the key
write against the old Voice target. The permanent ETM framework inventory remains seven edges.
Rime/Action conflict execution remains in its dependent tasks.

CMP-006 makes every editor/UI lifecycle boundary cancel-only: `onStartInput`, `onFinishInput`,
`onFinishInputView`, `onWindowHidden`, `onDestroy`, and a dynamically registered non-exported
`ACTION_SCREEN_OFF` receiver all terminalize the captured generation before calling the exact
`VoiceController.cancel()` edge. Queued route/state/ready/transcript/result/error callbacks are
dropped once that target is terminal or replaced; no lifecycle path may call `stop()` and wait for
a background Final. Receiver registration failure disables Voice recording fail closed, teardown
unregisters it exactly once, and no editor writer edge is added. Source and Debug/Release compiled
gates freeze the receiver/method shape, five lifecycle callsites, method-reference binding,
register/unregister edges, and reject the removed deferred-finalization gate.
If physical composition cleanup is not proven at screen-off or window/view hide, a private
fail-closed restart guard remains set; only a real editor-session rotation may clear it and release
the old uncertain coordinator lease.

VOC-001 introduces a data-only `VoiceController` boundary with the exact
`start/stop/cancel/state/events` surface. `VoicePipelineAdapter` is the sole compiled caller of the
legacy pipeline's corresponding core methods and maps all four compatibility states plus every
event without exposing Android UI, database, editor, recovery-store, or lifecycle capabilities.
The IME service, Voice Lab, and standard RecognitionService engine each own one controller and
construct one adapter; their recovery, explicit-discard, prewarm, and shutdown compatibility calls
remain outside the session-control interface until the later VoicePipeline decomposition tasks.
Source and Debug/Release compiled gates reject interface/enum/event drift, adapter field or edge
drift, missing production construction edges, and any direct core-pipeline bypass.

VOC-002 introduces one bounded `AudioCapture` interface for microphone attribution, opaque
session creation, VAD-backed batch capture, streaming PCM, and stop/cancel. The interface exposes
neither network/text/editor/persistence capabilities nor the mutable low-level types;
`AudioRecorder` and `RecordingSession` are package-private and reachable only through the exact
`AndroidAudioCapture` adapter family. `VoicePipelineRuntime`, local Speech Core v2, and Paraformer all
consume the same boundary, preserving the existing 5..540 second cap, silence trimming, VAD,
manual endpointing, and cancellation semantics. Source and Debug/Release compiled gates freeze
the interface/listener/frame/session shapes, adapter ownership and one-to-one delegate edges,
allowed consumers, and exact production lifecycle/record/stream call counts. VOC-002 does not
shrink the compatibility façade or change recognition routing; those remain VOC-007.

VOC-003 introduces one capability-free `TextProcessingPipeline` with exact deterministic,
local-command, optional-LLM, and integrity stages. `StagedTextProcessingPipeline` owns exactly one
implementation of each stage, while `VoicePipelineRuntime` owns one final dispatcher and routes the
existing terminal processing sequence through it without changing fallback, cancellation, or
selected-text failure semantics. The two content-bearing request records have fixed redacted
`toString()` output and cannot carry editor, Android UI, database, thread, or executor capability;
no provider, UI, adapter, or unrelated production class may retain the stage surface. Source and
Debug/Release compiled gates freeze all interface/record/stage/dispatcher shapes, the constructor
edge, and the exact `finishTranscription` call counts (deterministic twice; command, optional LLM,
and integrity once each). Provenance and independent stage implementations remain VOC-004/005/006,
and shrinking the legacy façade remains VOC-007.

VOC-004 makes `VoiceResult` the single immutable terminal text artifact. Its exact bounded fields
are Raw, deterministic, candidate, final, plus an ordered content-free `StageProvenance` list;
recovery results explicitly mark recognition recovery and skip the processing stages. The legacy
`DictationResult` envelope owns one `VoiceResult` instead of duplicate text fields, and its legacy
text/AI accessors delegate to that artifact. Only `VoicePipelineRuntime` may create processed or recovered
artifacts, while Raw restore, final delivery and encrypted history consumers must first obtain
`DictationResult.voiceResult()`. Source and Debug/Release compiled gates reject Android/editor/
serialization capabilities, plaintext provenance, non-redacted diagnostics, forged construction,
legacy string-envelope calls, consumer bypasses, missing binaries and drift in all three terminal
publication edges.

VOC-005 moves the concrete deterministic personalization implementation and its exact failure
policy out of the voice runtime into the package-confined final
`DeterministicPersonalizationStage`. The stage is capability-free, owns no mutable fields, calls
`PersonalizedTextProcessor.apply` exactly once, preserves only a 20,000-code-point bounded input
with empty match IDs for ordinary insertion failures, and propagates the same failure for
selected-text editing. `VoicePipelineRuntime` may construct exactly one stage and may no longer import or
invoke `PersonalizedTextProcessor` or carry a personalization fail-safe helper. Source and
Debug/Release compiled gates lock the stage shape, scope, constructor and processor edges while
retaining VOC-003's two deterministic dispatcher calls. Optional LLM and Integrity implementations
remain VOC-006, and façade reduction remains VOC-007.

VOC-006 moves the concrete optional-LLM and integrity implementations out of the voice runtime into
package-confined final `OpenAiOptionalLlmStage` and `TranscriptIntegrityGuardStage` classes. The
LLM stage owns only the existing shared `OpenAiCompatibleClient`, composes the existing system/user
prompts once each, and forwards cancellation and exceptions unchanged; the integrity stage is
stateless and delegates once to `TranscriptIntegrityGuard`. `VoicePipeline` constructs one of each
stage and may no longer call LLM completion, LLM prompt composition, or integrity validation
directly. Source and Debug/Release compiled gates lock both class shapes, their confined scope,
constructor and implementation edges, while VOC-003 continues to lock the terminal dispatcher call
counts. Fallback remains orchestration policy: ordinary failures publish deterministic Exact text,
while selected-text failures preserve the original selection.

VOC-007 makes `VoicePipeline` an exact compatibility facade over one package-confined final
`VoicePipelineRuntime`. The facade retains the historical constructor, listener/state types,
lifecycle methods and package-level pure compatibility seams, but owns only one private final
runtime field and delegates every call exactly once. Capture, recognition, text processing,
recovery, diagnostics, executors and mutable run state remain solely in the runtime. Source and
Debug/Release compiled gates cap the facade surface and size, reject extra capabilities or runtime
consumers, require the runtime to remain non-public, and lock every facade-to-runtime edge while
retaining all VOC-001..006 implementation gates on the runtime.

VOC-008 makes the exact same-stack `CommitRecord` (or an already persisted `HistoryEntry`) the only
Teach source. `LastVoiceCommit` may retain one final record view for the current transaction, but
its legacy copied Raw/final/package fields cannot authorize or populate Teach. The IME menu checks
the record's VOICE source, learning permission, Raw presence and nonblank committed text through
`TeachCorrectionResolver`; `teachCorrection()` passes that record to the one
`HistoryActivity.createTeachIntent(Context, CommitRecord, long)` factory and never writes plaintext
extras itself. The factory resolves current record text/scope over optional history metadata.
Source and Debug/Release compiled gates lock the record field, factory/resolver shapes, exact
callers and six production edges, reject provider/UI factory calls and copied-plaintext fallback,
and leave no-learning or sensitive commits without a Teach entry point.

VOC-011 canonicalizes the existing session-frozen Voice writer switch as `voice_engine_v2` inside
the process-local `VoiceEditorTransactionConfig`. The default remains the transaction route. A
former explicit `enabled` choice is migrated synchronously without changing its boolean value;
when both keys exist the canonical value wins and the legacy key is removed. Both reads/migration
and explicit A/B writes are serialized, use `SharedPreferences.commit()`, and reject asynchronous
`apply()`. The exact IME capture call remains the sole production read: its boolean is copied into
the immutable target, so mid-session flag changes affect only the next capture and failures never
fall through to the other writer. Source and Debug/Release compiled gates lock the three keys,
migration/read/write edge counts, synchronized surface, default-on behavior and the existing
single-writer branch; legacy writer removal remains VOC-012.

REC-001 introduces the immutable `ProviderDescriptor` and complete `ProviderCapabilities`
contract. Each built-in backend is mapped explicitly from the closed `RecognitionBackend` enum;
provider names are never inspected to infer behavior. The capability record freezes ten feature
flags together with the closed `BATCH_FINAL` / `NATIVE_STREAMING` / `PREFIX_REPLAY` implementation
kind, privacy class, bounded optional audio duration, and a closed immutable audio format set.
Constructor invariants reject contradictory streaming, prefix-replay, keyterm, on-device, privacy,
and upload declarations. Descriptor identity and display text are bounded, strictly validated,
and omitted from diagnostics. Source and Debug/Release compiled gates freeze both record shapes,
the implementation/audio vocabularies, explicit five-backend plus one prefix-replay construction
edge, dependency boundary, and redacted diagnostics. Provider interfaces, registry, health, and
routing remain REC-003 onward.

REC-002 introduces the closed, immutable `RecognitionEvent` vocabulary and the O(1)
`RecognitionEventValidator` for one provider session. The exact eight event variants carry a
positive sequence and opaque `SessionId`; Partial text/revision and Final metadata are bounded,
UTF-16-safe, and content diagnostics are always redacted. Final, Failure, and Cancelled are the
only terminal events, with cancellation excluded from the generic failure variant. The synchronized
validator rejects foreign sessions, stale or duplicate sequences, invalid partial revisions, and
all events after the first terminal event without retaining an event or transcript. Source and
Debug/Release compiled gates freeze the sealed variants, record fields, metadata bounds, closed
disposition vocabulary, sequence/terminal state updates, pure-domain dependencies, and redacted
diagnostics. Provider callback integration and registry ownership remain REC-003 onward.

REC-003 introduces the package-confined, process-local `ProviderRegistry`. Registration is bounded
to 32 exact provider IDs, rejects duplicate replacement, and advances a non-wrapping generation
before every state change. Lookup classifies unknown and disabled providers without invoking a
callback. Probe takes a descriptor/callback/generation lease under the registry monitor, executes
the callback outside that monitor, then accepts the result only if the identical entry remains
enabled at the same generation and its observed capabilities exactly match the reviewed
declaration. Probe exceptions, unavailable providers, capability drift, and enablement races map
to closed redacted outcomes; no Android, endpoint, secret, persistence, executor, or transcript
capability enters the registry. Source and Debug/Release compiled gates freeze the registry and
result shapes, fixed capacity, synchronized mutation surface, unlocked probe callback, generation
revalidation, exact capability comparison, session-only failure rejection, and diagnostic
redaction. Provider adapter wiring and health/routing policy remain later REC tasks.

REC-004 introduces the package-confined `RecognitionProvider` lifecycle contract and the final
`AndroidSystemRecognitionProvider` adapter for the reviewed system and on-device Android backends.
The adapter accepts only a bounded, defensively copied `StartRequest`, owns one active session,
marshals start/stop/cancel/destroy and all legacy callbacks through one main-thread dispatcher,
emits the closed REC-002 event sequence, and clears request/sink references at terminal delivery.
Raw Android error text is reduced to the stable failure vocabulary and never enters events or
diagnostics. The legacy `SystemSpeechRecognizer` remains available to the existing pipeline but
gains one package-only bounded bridge and endpoint callback; production voice routing is not yet
migrated. Source and Debug/Release compiled gates freeze the provider/adapter/nested-state shapes,
least-authority request, only implementation, main-thread lifecycle, exact event constructors,
terminal release, callback bridge, and bounded intent-factory edges. Registry-driven adapter
selection, health, and routing policy remain later REC tasks.

REC-005 adds the package-confined final `OpenAiCompatibleUploadProvider` around the existing
OpenAI-compatible HTTP client. A one-use `StartRequest` defensively copies at most 32 MiB of audio,
caps prompt/duration metadata, transfers ownership once, and zeroes unclaimed or terminal audio.
The adapter owns one bounded single-thread worker and one active session, checks cancellation while
uploading and reading the capped response, rejects redirects, maps only typed/redacted failures,
and emits exactly one REC-002 terminal event before clearing audio, prompt, language, and sink
references. Provider code receives credentials only through a synchronous `SecretRef`-based
`char[]` lease; it cannot import or retain `SecretStore`, plaintext keys, Android/editor
capabilities, or filesystem state. Source and Debug/Release compiled gates freeze the adapter,
request/session/worker/credential/client shapes, the sole reviewed client call, exact event gates,
closed failure vocabulary, bounded/no-redirect transport, and default-deny callers. Registry/router
selection, fallback, circuit breaking, production voice wiring, and legacy-client removal remain
later REC tasks.

REC-006 adds the package-confined final `SenseVoiceFinalProvider` around the existing
private-process `LocalOfflineRecognitionClient`. Its one-use request copies and bounds a complete
WAV, transfers ownership once, and zeroes unclaimed or terminal audio. The provider owns one
single-thread worker and one active session, probes the closed device/model availability state,
maps missing/corrupt/low-memory/ABI/system outcomes to stable failures, and emits only
Preparing/Ready/Final-or-failure/cancel events—never partial or endpoint events. Source and
Debug/Release compiled gates freeze the adapter, request/session/worker/client shapes, exact
private-process recognize/cancel/close edges, device/model probes, closed enums, final-only event
surface, terminal cleanup, bounded well-formed redacted client results, and default-deny access to
adapter internals. Registry/router selection, prefix replay, unified failure handling, model
provisioning, and production voice wiring remain later REC tasks.

REC-007 adds the package-confined final `PrefixReplayPreviewProvider` around the existing
`LocalRealtimePreview`. It explicitly declares `supportsStreaming=false`, fully revisable partials,
and `implementationKind=PREFIX_REPLAY`; it never emits Final, Endpoint, or SpeechStarted. PCM is
defensively copied, even-aligned, capped at 30 seconds, zeroed after synchronous handoff, and decoded
through the legacy preview's sole coalescing worker at 750 ms thresholds. Cancellation revokes the
session without waiting on the caller thread, drops late callbacks, and clears buffered PCM and
content-bearing references. The production backend probes the same closed device/model states as
REC-006 and lazily creates the native session on its worker. Source and Debug/Release compiled gates
freeze the capability declaration, adapter/request/session/backend shapes, exact event surface,
single-worker/fixed-buffer/zeroing edges, closed availability mapping, and default-deny internal
scope. Registry/router selection, production voice wiring, and model provisioning remain later REC
tasks; prefix replay must never be described as true streaming.

REC-008 adds the package-confined `RecognitionFailureMapper` as the single content-free boundary
for Android system/OEM, upload transport, local model/runtime, and legacy pipeline failures. The
four reviewed providers delegate to this mapper; the closed local availability enum and all 19
`RecognitionRoute.FailureClass` values are mapped explicitly, with unknown inputs failing closed to
`INTERNAL_ERROR`. Raw OEM/provider/transport messages are transient classification inputs only and
are never retained, logged, thrown, or returned by the mapper. Legacy `RecognitionFailure` now
carries the stable class plus Android compatibility code and a bounded local display message while
redacting content from diagnostics. Source and Debug/Release compiled gates freeze mapper/enum/
legacy shapes, exact provider delegation edges, stable-message coverage, redaction, and
default-deny callers. Retry, fallback, circuit breaking, and user-facing recovery policy remain
REC-009 and later tasks.

REC-009 adds the package-confined final `RecognitionRouter` as a finite decision state machine over
the immutable `RecognitionRoute` and canonical `ProviderRegistry`. Selection receives a private
owner/entry/generation route lease, cross-checks the exact enabled descriptor's privacy class and
all ten declared capabilities, and revalidates that lease before publishing an opaque attempt.
Retries remain bounded by the route's maximum of two attempts, fallback advances only through the
configured finite step list, and cancelled, permission-denied, or target-changed failures are
always terminal. Privacy downgrades and `REQUIRE_BEFORE_USE` steps stop at a redacted pending
confirmation token; REC-009 does not execute a provider or invent the REC-010 confirmation resume.
Source and Debug/Release compiled gates freeze the router/decision/token/registry-lease shapes,
synchronized identity checks, exhaustive capability surface, generation overflow handling,
redaction, and default-deny callers. Circuit breaking, effective-profile wiring, provider
execution, and production voice migration remain later tasks.

REC-010 binds that router to one exact immutable `EffectiveProfile` and a content-free
`PrivacyAuthorization`. A disabled effective route fails before registry lookup; a route ID
mismatch fails closed. Preauthorization is profile-identity-bound and capped at an explicit
maximum privacy exposure, while `REQUIRE_BEFORE_USE` always requires a fresh one-time decision.
Interactive approval accepts only the identical pending request and its original registry lease;
cancel, stale/foreign tokens, lease ABA, profile drift, and generation exhaustion never resume a
route. The approved attempt reuses that exact lease instead of reacquiring a newer generation.
Source and Debug/Release compiled gates freeze the profile/authorization/request/enums,
EffectiveProfile/OverrideValue checks, exact lease reuse, redacted diagnostics, and default-deny
scope/callers. This remains a package-confined decision seam: UI, persistence, provider execution,
circuit breaking, and production voice migration are separate tasks.

REC-011 adds one shared, bounded, process-local `ProviderCircuitBreaker` to the router. Three
consecutive provider-health failures open the exact canonical descriptor for 30 seconds; expiry
admits only one owner/epoch-bound half-open permit, whose proven success closes the breaker and
whose health failure or unresolved abandonment reopens it. No Match and Speech Timeout prove that
the provider responded and reset the health streak; user cancellation, target change, permission,
and unsupported-language outcomes never increment it. Every permit is single-use, stale/foreign
observations are ignored, clock or generation overflow fails closed, and at most the registry's 32
canonical descriptor identities are retained. Source and Debug/Release compiled gates freeze the
breaker/entry/permit/result/enum shapes, the Router's ninth final field, the permit carried by each
attempt, both exact acquire edges, every success/failure/ABA/generation resolution edge, redacted
diagnostics, and default-deny external callers. The breaker remains non-persistent and does not
execute providers; production routing and user-facing recovery remain later REC tasks.

REC-012 adds a generation-safe Android system capability and language-model download seam. API 33
support checks and download requests use a dedicated least-data intent that retains only the
selected backend and bounded language while disabling partial results, limiting results to one,
and excluding personalization, prompt, and bias phrases. API 34 download callbacks are reduced to
monotonic bounded progress plus one content-free terminal result; API 33 reports only the stable
requested state because that platform exposes no listener. Every support/download operation is
single-terminal, cancellable, timeout-bounded, and maps OEM failures directly to the closed REC-008
failure vocabulary without retaining exception text or platform error integers. The process-local
download coordinator keeps one opaque generation-bound request, one operation, and at most 16
strong lifecycle subscriptions; stale callbacks, closed subscriptions, generation exhaustion, and
synchronous terminal races fail closed. Source and Debug/Release compiled gates freeze the exact
result/callback/coordinator/evaluator shapes, bounded hostile-language handling, least-data intent
edges, Activity subscription lifecycle, redaction, and default-deny callers. REC-012 does not
migrate production recognition routing or install third-party model artifacts.

STR-001 adds the package-confined `opentypeless.streaming.v1` RecognitionEvent wire contract and
its Draft 2020-12 JSON Schema. One strict JSON object is carried unchanged by a WebSocket text
frame or SSE data event and maps only to the existing eight REC-002 variants. The codec rejects
unknown versions, event kinds, properties, explicit nulls, numeric coercion, trailing content,
malformed UTF-16, and payloads above the fixed 524,288 UTF-16-unit envelope bound. A session-bound
`Stream` is the only production decode entry: it delegates every valid event to the existing
content-free `RecognitionEventValidator`, so foreign sessions, non-monotonic sequences, invalid
partial revisions, duplicate terminals, and all post-terminal events fail closed. Source and
Debug/Release compiled gates freeze the exact codec/stream/result/schema shapes, all eight mapping
edges, redacted errors, default-deny raw-decode callers, and absence of Android, editor, audio,
network-client, execution, persistence, serialization, or Secret authority. STR-001 defines no
socket, SSE client, reconnect behavior, Provider, route migration, audio frame, or Feature Flag;
those remain STR-002 and later tasks.

STR-002 adds a package-confined, single-session `WebSocketStreamingProvider` and the narrow
`StreamingRecognitionWebSocketClient` transport bridge. PCM frames are copied, bounded to 64 KiB,
zeroed after each send, capped per session, and refused before OkHttp's outgoing queue can exceed
256 KiB. Redirects and automatic transport retry are disabled; the Provider permits at most one
fresh attempt and only before any server event, accepted audio, or stop request. Ready and finish
timeouts are explicit, cancellation is terminal, and all failures map to the closed content-free
REC-008 vocabulary. Credentials are borrowed as a bounded `char[]`, materialized only for the
request header, and cleared with the request/content references after handoff. Source and
Debug/Release compiled gates freeze the exact Provider/client/session/backend/timer shapes,
single reviewed client caller, STR-001 decoder caller, bounded queue/frame/reconnect behavior,
redacted diagnostics, and absence of Android/editor/audio-capture/persistence/serialization
authority. STR-002 is not selected by the production router or voice UI; disclosure planning,
effective-profile routing, Feature Flag migration, and production network activation remain
STR-010 and later tasks.

STR-003 adds the package-confined `Qwen3AsrVllmProvider` and public transport-only
`Qwen3AsrVllmClient` for a configured self-hosted Qwen3-ASR model served through vLLM. Capability
probing is explicit and asynchronous: `RecognitionProvider.probe()` is cache-only, while one
generation-bound capacity-one worker checks the exact configured model through a 256 KiB,
128-model, depth-16 `/v1/models` response. Realtime audio uses only `/v1/realtime`, PCM16 mono
16 kHz frames bounded to 64 KiB, a 256 KiB outgoing queue, and the closed
`session.created`/`session.update`/audio append/final commit/delta/done/error vocabulary. Redirects
and automatic retries are disabled, credentials cross only one synchronous bounded `char[]` lease,
and endpoint, model, session, transcript, server body, and throwable details remain redacted.
Source and Debug/Release compiled gates freeze the exact client/provider/probe/backend shapes,
required binaries, single reviewed callers, shared STR-002 delegate binding, bounded worker and
transport surfaces, stable failure mapping, and absence of Android/editor/audio-capture,
persistence, or serialization authority. The adapter is not registered or selected by production
routing in STR-003; router, disclosure-plan, profile, UI, and Feature Flag wiring remain STR-010 and
later tasks. Fake protocol tests do not constitute a real Qwen model accuracy claim.

STR-005 adds the package-confined, single-session `LocalStreamingProvider` for the STR-004-selected
Streaming Paraformer zh/en INT8 candidate at exact revision
`8e40c43232a1c5c66c82111efc5820d3accca11b`. Provider start, copied PCM delivery, and finish are
serialized on one private worker; frames are capped at 64 KiB, queued PCM at 256 KiB, and the
540-second PCM16 mono 16 kHz session at 17,280,000 bytes. The Provider opens only the existing
private-process `LocalRealtimeRecognitionClient`, uses its actual Ready callback, and validates
bounded revisable partials and one final through the REC-002/REC-008 vocabulary. The exact encoder,
decoder, and token sizes and SHA-256 identities remain pinned and atomically verified by the
existing private model store/downloader. Source and Debug/Release compiled gates freeze the
Provider/client/model shapes, single reviewed backend caller, device/model probes, one-worker and
timer lifecycle, queue bounds, defensive copying/zeroing, redacted failures, and absence of
network, editor, audio-capture, Secret, persistence, or serialization authority. STR-005 does not
register the Provider, activate a production route, capture microphone audio, or add finalization;
those remain STR-006/STR-010 and later tasks.

STR-006 adds the package-confined `TwoStageStreamingProvider`: the exact STR-005 local streaming
child owns bounded revisable preview events, while the exact REC-006 SenseVoice child receives one
zeroed-after-use WAV claim and owns the unique terminal final. The composite retains at most
17,280,000 PCM bytes, serializes finalizer launch on one private worker, validates the final against
the last safe preview through `TranscriptIntegrityGuard`, and falls back to that preview when the
fact guard cannot prove the replacement safe. Source and Debug/Release compiled gates freeze the
two exact child request/session surfaces, one-use redacted request and transient state shapes,
bounded defensive PCM copies and zeroing, single worker shutdown, one fact-guarded final, failure
redaction, and absence of network, editor, microphone-capture, Secret, persistence, or serialization
authority. The composite remains deliberately absent from production registration and routing;
VoiceController/Router selection, microphone ownership, configuration, UI, and Feature Flag wiring
remain STR-010.

STR-010 adds the public-final `RecognitionRouterVoiceController` as the production decision bridge
in front of the existing `VoicePipelineAdapter` execution boundary. Every start resolves one
`EffectiveProfile`, registers one canonical descriptor, obtains one identity-bound Router attempt,
and only then permits the compatibility executor to open its existing backend. All five legacy
backend values, including the existing local two-stage and network streaming executions, are
mapped exhaustively; sensitive/disabled, unavailable, capability-drifted, stale, cancelled, and
circuit-open routes never reach the delegate. A synchronous private preference selects the entire
controller once in each of the IME, Voice Lab, and RecognitionService engine owners; it cannot run
both paths or fall back after a rejected routed start. Source and Debug/Release compiled gates
freeze the exact controller/nestmate/config shapes, one descriptor registration and Router start,
stable failure mapping, lifecycle generation, single delegate start, exact three selector callers,
one constructor edge, default-deny Router/Breaker/mapper scope, and the absence of editor, audio,
Secret, network, persistence, serialization, or raw throwable authority in the decision bridge.
