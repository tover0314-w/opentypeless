# TalkMore Cloud Usage Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove idle subscription polling and reduce account-status reads so Neon compute can scale to zero without making active STT/LLM/Ask interactions feel slower. The atomic managed-usage rewrite is retained as a future plan, not part of the current cutover.

**Architecture:** TalkMore remains the authorization and billing source of truth. One read-only account-snapshot query replaces the current multi-query status route. The desktop removes fixed polling, refreshes only on meaningful events, and warms the account path concurrently with active Cloud recording. A generic database-atomic quota service remains designed for a later isolated-PostgreSQL phase; current production quota mutations stay unchanged.

**Tech Stack:** Next.js App Router, TypeScript, Drizzle ORM, PostgreSQL/Neon, Vitest, Rust 2021, Tauri 2, Zustand.

## Global Constraints

> **Current scope decision (2026-07-22):** The owner is not adding an isolated test database now. Task 2 and Tasks 4–6 are deferred. The deferred Drizzle schema and migration artifacts are excluded from the current release because legacy `select()`/`returning()` calls would otherwise reference unapplied columns implicitly. Production routes continue using the existing billing implementation. This deferral does not block the status-query, polling, warming, recording-limit, or managed-upload work.

- Work in an isolated TalkMore worktree created from `origin/main`; do not mix with the existing SEO branch or dirty `tests/api-routes.test.ts`.
- Keep authentication behavior and all legacy response fields compatible with released desktop clients.
- Do not use process memory, Redis, Vercel cache, or a desktop cache as billing authority.
- `/api/subscription/status` must be read-only after authentication and use one business-data database round trip.
- For the future atomic phase, each reserve, settle, or release call must be one atomic business-data database round trip and safe under retries and concurrent devices.
- Never start a paid upstream call before successful authorization and reservation.
- A failed current-release post-use refresh, or a failed future snapshot update, must not remove already streamed/generated user content.
- Do not add an idle timer, cron, or background request that keeps Neon awake.
- Treat the existing legacy STT-seconds and LLM-token paths as a compatibility meter, not a license to change current plan limits.

---

### Task 1: Create an Isolated TalkMore Worktree and Capture Baseline

**Files:**
- Create: a sibling worktree for branch `codex/issues-81-cloud-usage`
- Inspect: `package.json`, `vitest.config.ts`, `drizzle.config.ts`

- [x] **Step 1: Fetch and create the worktree from production main**

Run:

```bash
git fetch origin main
git worktree add ../talkmore-cloud-usage -b codex/issues-81-cloud-usage origin/main
```

Expected: the new worktree is clean and does not contain the current SEO branch's uncommitted files.

- [x] **Step 2: Install with the repository lockfile and run focused baseline tests**

Run: `npm ci`

Run: `npm test -- --run tests/contract-opentypeless.test.ts tests/api-routes.test.ts tests/p0-entitlement-regression.test.ts`

Expected: baseline passes. If production-main tests fail, record the exact failure before changing implementation.

### Task 2: Add the Additive Atomic-Meter Schema — Deferred

Do not execute or ship this task in the current release. Resume it together with Tasks 4–6 when an isolated real PostgreSQL endpoint is available. The current branch contains a regression test proving the deferred columns and migration artifacts are absent.

**Files:**
- Modify: `src/db/schema.ts`
- Create: `drizzle/0004_cloud_usage_atomic.sql`
- Modify: `drizzle/meta/_journal.json`
- Create/update mechanically: `drizzle/meta/0004_snapshot.json`
- Test: `tests/cloud-usage-schema.test.ts`

**Interfaces:**
- Produces: `quota.usageRevision`
- Produces: `cloudUsageOperation.quotaModel`
- Produces: `cloudUsageOperationStage.customerMeter`
- Produces: `reservedCustomerUnits`, `settledCustomerUnits`, `reservationExpiresAt`, `replayCount`

- [ ] **Step 1: Add a failing schema-contract test**

Assert that the Drizzle columns exist, have non-null defaults where specified, and that the generated SQL retains the old cloud-word columns for rollback.

Run: `npm test -- --run tests/cloud-usage-schema.test.ts`

Expected: fail because the generic-meter columns do not exist.

- [ ] **Step 2: Add the exact additive columns**

```ts
// quota
usageRevision: bigint('usage_revision', { mode: 'number' }).notNull().default(0)

// operation
quotaModel: text('quota_model').notNull().default('cloud_words')

// stage
customerMeter: text('customer_meter').notNull().default('cloud_words')
reservedCustomerUnits: integer('reserved_customer_units').notNull().default(0)
settledCustomerUnits: integer('settled_customer_units').notNull().default(0)
reservationExpiresAt: timestamp('reservation_expires_at')
replayCount: integer('replay_count').notNull().default(0)
```

Backfill `reserved_customer_units` from `reserved_cloud_words` and `settled_customer_units` from `settled_cloud_words_delta`. Add an index supporting expired-reservation lookup, but do not schedule background cleanup in this release.

- [ ] **Step 3: Generate and inspect migration artifacts**

Run: `npx drizzle-kit generate --name cloud_usage_atomic`

Expected: one additive migration. Inspect for destructive statements; remove none of the old columns or indexes.

- [ ] **Step 4: Re-run schema tests and typecheck**

Run: `npm test -- --run tests/cloud-usage-schema.test.ts`

Run: `npm run typecheck`

Expected: pass.

- [ ] **Step 5: Commit the migration separately**

```bash
git add src/db/schema.ts drizzle tests/cloud-usage-schema.test.ts
git commit -m "feat: add atomic cloud usage meter schema"
```

### Task 3: Build One-Read Account Snapshots

**Files:**
- Create: `src/lib/account-snapshot.ts`
- Modify: `src/app/api/subscription/status/route.ts`
- Modify: `src/lib/entitlements.ts` only to expose pure helpers if required
- Test: `tests/account-snapshot.test.ts`
- Modify: `tests/contract-opentypeless.test.ts`
- Modify: `tests/p0-entitlement-regression.test.ts`

**Interfaces:**

```ts
export interface UsageSnapshotV1 {
  userId: string
  periodStart: string
  revision: string
  quotaModel: 'cloud_words' | 'legacy_dual_meter'
  displayWordsUsedEstimate: number
  displayWordsLimit: number
  cloudWordsUsed: number
  cloudWordsLimit: number
  sttSecondsUsed: number
  sttSecondsLimit: number
  llmTokensUsed: number
  llmTokensLimit: number
  resetAt: string | null
}

export interface AccountSnapshotV1 {
  schemaVersion: 1
  userId: string
  generatedAt: string
  plan: EffectivePlan
  source: SubscriptionSource
  displayName: string
  subscriptionEnd: string | null
  subscriptionStatus: string | null
  licenseStatus: LicenseStatus | null
  usage: UsageSnapshotV1
  managedSttCapabilities: ManagedSttCapabilityV2 | null
  byokUnlimited: true
}

export async function readAccountSnapshot(userId: string): Promise<AccountSnapshotV1>
```

- [x] **Step 1: Write failing pure and route-contract tests**

Cover free, Pro subscription, active/refunded AppSumo, direct lifetime, absent quota row, old-client lifetime remapping, and the old flat response fields. Assert the status route never calls `getOrCreateQuota` or performs an insert/update.

Run: `npm test -- --run tests/account-snapshot.test.ts tests/contract-opentypeless.test.ts tests/p0-entitlement-regression.test.ts`

Expected: fail on the new snapshot contract and read-only invariant.

- [x] **Step 2: Implement one business-data SQL statement**

Use a single parameterized query/CTE after `requireAuth()` that selects the user, best current subscription, active/current license, and current-or-sentinel quota row. Compute defaults in SQL or the pure snapshot mapper without inserting a quota row.

The query must return at most one row. In the current release it must not reference the staged `quota.usage_revision` column; return compatibility revision `0` for every status snapshot. Reading and incrementing real revisions is deferred with Tasks 4–6.

- [x] **Step 3: Return snapshot plus legacy flat fields**

The status JSON is:

```ts
{
  ...legacyFlatFields(snapshot),
  accountSnapshot: snapshot,
}
```

Preserve the current legacy lifetime remap for old desktop versions. Add `Cache-Control: private, no-store` because the response is personalized and must not be shared.

- [ ] **Step 4: Add query-count evidence**

In the route test, mock/spy at the account-snapshot boundary and prove exactly one business-data read after authentication. In a database integration test, enable Drizzle query logging and assert one statement for `readAccountSnapshot`.

The route-boundary count is covered. The live Drizzle logger assertion remains open because the owner is not provisioning a separate database and production will not be used as a test target.

- [x] **Step 5: Run focused tests and commit**

Run: `npm test -- --run tests/account-snapshot.test.ts tests/contract-opentypeless.test.ts tests/p0-entitlement-regression.test.ts`

Run: `npm run typecheck`

```bash
git add src/lib/account-snapshot.ts src/app/api/subscription/status/route.ts \
  src/lib/entitlements.ts tests/account-snapshot.test.ts \
  tests/contract-opentypeless.test.ts tests/p0-entitlement-regression.test.ts
git commit -m "refactor: serve account status in one read"
```

### Task 4: Replace Process-Local Quota with Atomic Operations — Deferred

Do not execute this task in the current release. Resume it only when an isolated real PostgreSQL endpoint is available. Unit tests or production-database experiments are not acceptable substitutes for the concurrency and replay tests below.

**Files:**
- Create: `src/lib/quota-service.ts`
- Modify: `src/lib/api-utils.ts`
- Modify: `src/lib/cloud-quota.ts`
- Create: `tests/quota-service.test.ts`
- Create: `tests/quota-service.integration.test.ts`

**Interfaces:**

```ts
type CustomerMeter = 'cloud_words' | 'stt_seconds' | 'llm_tokens'

interface UsageReservation {
  operationId: string
  stage: 'stt' | 'llm' | 'ask'
  stageKey: string
  customerMeter: CustomerMeter
  reservedCustomerUnits: number
  usageSnapshot: UsageSnapshotV1
}

reserveUsage(input): Promise<UsageReservation>
settleUsage(input): Promise<{ usageSnapshot: UsageSnapshotV1; customerUnitsDelta: number }>
releaseUsage(input): Promise<{ usageSnapshot: UsageSnapshotV1 | null }>
```

- [ ] **Step 1: Write failing transaction and idempotency tests**

Unit tests cover normalization and stable errors. Integration tests, enabled by `TEST_DATABASE_URL`, run concurrent `Promise.all` calls and prove:

- two requests cannot jointly exceed quota;
- repeating the same `(user, operationId, stage, stageKey)` does not double charge;
- settle is monotonic and replay-safe;
- release is idempotent and never underflows;
- reserve/settle/release each increment `usage_revision` exactly once for a real mutation;
- expired reservations can be reclaimed lazily during a later reservation without a cron.

Run: `TEST_DATABASE_URL=... npm test -- --run tests/quota-service.integration.test.ts`

Expected: fail because the service does not exist.

- [ ] **Step 2: Implement each mutation as one SQL call**

Use one `db.execute(sql`...`)` statement per public mutation, with CTEs and `INSERT ... ON CONFLICT`/conditional `UPDATE ... RETURNING`. Lock only the target quota/stage rows. The final CTE returns the complete `UsageSnapshotV1`, so routes do not perform a second status read.

Do not call `flushQuotaToDb`, do not depend on `setInterval`, and do not authorize from `quotaMem`.

- [ ] **Step 3: Adapt legacy dual-meter helpers**

Keep the public route-facing reservation shape temporarily, but delegate its `adjust` and `release` methods to the atomic service. Remove quota memory, row-exists sets, the flush interval, and explicit request-end flushing only after all routes use the service.

- [ ] **Step 4: Adapt AppSumo cloud-word operations**

`reserveAppSumoCloudOperationStage` becomes a compatibility wrapper around the generic service. Preserve current operation/stage keys and the monotonic word-settlement policy.

- [ ] **Step 5: Run unit and real PostgreSQL concurrency tests**

Run: `npm test -- --run tests/quota-service.test.ts tests/api-routes.test.ts tests/contract-opentypeless.test.ts`

Run: `TEST_DATABASE_URL=... npm test -- --run tests/quota-service.integration.test.ts`

Expected: pass; no skipped concurrency claim is accepted as production evidence.

- [ ] **Step 6: Commit the quota service**

```bash
git add src/lib/quota-service.ts src/lib/api-utils.ts src/lib/cloud-quota.ts tests
git commit -m "refactor: make cloud usage accounting atomic"
```

### Task 5: Attach Fresh Usage to Every Managed Response — Deferred

This task depends on Task 4's verified atomic mutation result. In the current release, preserve managed-response contracts and issue one deduplicated status refresh only after user output is delivered.

**Files:**
- Modify: `src/app/api/proxy/stt/route.ts`
- Modify: `src/app/api/proxy/llm/route.ts`
- Modify: `src/app/api/proxy/ask/route.ts`
- Modify: `tests/api-routes.test.ts`
- Modify: `tests/contract-opentypeless.test.ts`

- [ ] **Step 1: Write failing response-contract tests**

Assert JSON STT/LLM/Ask responses include `usageSnapshot`. For SSE, assert a final event named `opentypeless_usage` arrives after content and before `DONE`. Assert a settlement/snapshot serialization failure does not delete content already enqueued.

- [ ] **Step 2: Reserve before upstream and settle once**

Remove the cached-session branch that starts Groq/OpenRouter concurrently with quota authorization. Warm-up performed during recording will recover cold-path latency; correctness requires the upstream call to start only after reserve succeeds.

All terminal paths settle or release exactly once. Stable errors retain their current status/code compatibility.

- [ ] **Step 3: Emit snapshots without a follow-up query**

Use the snapshot returned by the atomic mutation. Non-streaming JSON adds `usageSnapshot`; streaming sends:

```text
event: opentypeless_usage
data: {"usageSnapshot":{...}}

data: [DONE]
```

- [ ] **Step 4: Remove process-local flush calls**

Run: `rg -n "flushQuotaToDb|quotaMem|QUOTA_FLUSH_INTERVAL" src`

Expected: no production references.

- [ ] **Step 5: Run routes, typecheck, build, and commit**

Run: `npm test -- --run tests/api-routes.test.ts tests/contract-opentypeless.test.ts`

Run: `npm run typecheck`

Run: `npm run build`

```bash
git add src/app/api/proxy src/lib/api-utils.ts tests
git commit -m "feat: return usage snapshots from cloud requests"
```

### Task 6: Persist and Broadcast the Snapshot in Rust — Deferred

Defer this task with Tasks 4–5. The current release continues using the existing frontend account store and status response, without presenting local data as billing authority.

**Files:**
- Create: `src-tauri/src/account_snapshot.rs`
- Modify: `src-tauri/src/lib.rs`
- Modify: `src-tauri/src/storage/mod.rs`
- Modify: `src-tauri/src/stt/cloud.rs`
- Modify: `src-tauri/src/llm/cloud.rs`
- Modify: `src-tauri/src/commands/ask.rs`
- Modify: `src-tauri/src/commands/auth.rs` or the existing session command module
- Test: the same Rust modules

**Interfaces:**
- Produces: `AccountSnapshotV1` and `UsageSnapshotV1` serde models
- Produces: `AccountSnapshotStore::{load, merge_if_newer, clear}`
- Produces Tauri commands: `get_account_snapshot`, `refresh_account_snapshot`
- Produces event: `account-snapshot-updated`

- [ ] **Step 1: Add failing persistence and merge tests**

Cover restart persistence, user-id scoping, sign-out deletion, schema-version rejection, newer revision wins, and equal-revision/newer-generatedAt tie breaking. Cached data may render a returning paid state but may never authorize a server request.

- [ ] **Step 2: Implement crash-safe local persistence**

Store the snapshot using the existing application-data/storage abstraction, not WebView `localStorage`. Include `user_id`, `version`, `revision`, and `observed_at`. Write atomically with the project's existing storage semantics.

- [ ] **Step 3: Merge snapshots from all managed clients**

Deserialize optional `usageSnapshot` from STT, LLM, Ask JSON/SSE responses. Merge usage into the current account snapshot after content processing, emit once through the Tauri app handle, and treat merge failure as non-fatal to already successful user output.

- [ ] **Step 4: Add explicit refresh and sign-out clearing**

The refresh command fetches `/api/subscription/status`, persists it, and emits the event. Sign-out clears token and snapshot together.

- [ ] **Step 5: Run Rust tests and commit**

Run: `cd src-tauri && cargo test account_snapshot stt::cloud llm::cloud commands::ask -- --nocapture`

Run: `cd src-tauri && cargo fmt --check`

```bash
git add src-tauri/src/account_snapshot.rs src-tauri/src/lib.rs src-tauri/src/storage/mod.rs \
  src-tauri/src/stt/cloud.rs src-tauri/src/llm/cloud.rs src-tauri/src/commands
git commit -m "feat: persist managed account snapshots"
```

### Task 7: Make the Desktop Event-Driven and Remove Polling

**Files:**
- Modify: `src/lib/api.ts`
- Modify: `src/lib/tauri.ts`
- Modify: `src/stores/authStore.ts`
- Modify: `src/stores/__tests__/authStore.test.ts`
- Modify: `src/App.tsx`
- Modify: `src/hooks/useTauriEvents.ts`
- Modify: `src/lib/deep-link.ts`

- [x] **Step 1: Add failing store/lifecycle tests**

Assert:

- the Ask WebView does not initialize auth independently;
- there is no five-minute interval;
- sign-in, deep-link checkout/license activation, explicit account refresh, and focus after a pending checkout do refresh;
- concurrent refresh calls are singleflight;
- one post-use refresh starts after managed output completes and its failure cannot affect that output.

- [x] **Step 2: Keep account refresh ownership centralized**

Keep auth-session initialization and status refresh ownership in the main frontend store. Do not add a second independent polling or persistence owner in Ask or Capsule.

- [x] **Step 3: Remove fixed polling and focus churn**

Delete the 5-minute `setInterval`. A normal window focus does not refresh status. Retain event refreshes for sign-in, successful purchase/deep link/license activation, pending-checkout focus, explicit account-page action, and one post-use refresh after managed output completes.

- [x] **Step 4: Run frontend tests and commit**

Run: `npm test -- --run src/stores/__tests__/authStore.test.ts src/lib/__tests__/deep-link.test.ts`

Run: `npm run build`

```bash
git add src/lib src/stores src/App.tsx src/hooks
git commit -m "refactor: synchronize account usage without polling"
```

### Task 8: Hide Neon Wake-Up During Active Cloud Intent

**Files:**
- Modify: `src-tauri/src/pipeline.rs`
- Modify: `src-tauri/src/commands/ask.rs`
- Modify: `src-tauri/src/stt/cloud.rs`
- Test: those Rust modules

**Interfaces:**
- Produces: `warm_managed_account_path(intent, session_id)`

- [x] **Step 1: Write failing warming-policy tests**

Prove warming starts only for authenticated managed Cloud intent, is deduplicated per session, never blocks audio readiness, and repeats only at minute 4 and minute 8 while a long managed recording is active. BYOK/local recordings trigger no TalkMore traffic.

- [x] **Step 2: Start warming concurrently with recording preparation**

At Cloud dictation/Ask start, fire one bounded authenticated status read on a separate async task. Do not await it in the microphone-ready UI path. The current release discards the body after warming; the real operation remains authoritative.

- [x] **Step 3: Add long-recording refresh points**

For active Cloud recordings only, refresh at 4 and 8 minutes so Neon's usual five-minute idle window does not expire immediately before final upload. Cancel timers on stop/error. Do not create a process-global periodic task.

- [ ] **Step 4: Verify latency and commit**

Measure cold and warm `recording stop -> first transcript byte` across at least 20 trials. Acceptance: common short-recording warm p95 does not regress by more than 100 ms; cold Neon wake is normally absorbed by speaking time; local/BYOK paths show no added request or latency.

Implementation and automated tests are committed. The 20-trial measurement and real-device matrix remain release gates, so this step stays open.

```bash
git add src-tauri/src/pipeline.rs src-tauri/src/commands/ask.rs src-tauri/src/stt/cloud.rs
git commit -m "perf: warm cloud account checks during recording"
```

### Task 9: Full Verification and Server-First Rollout

**Files:**
- Modify only if needed: observability/deployment documentation

- [x] **Step 1: Run TalkMore verification**

Run: `npm test -- --run`

Run: `npm run typecheck`

Run: `npm run build`

Do not run production-destructive concurrency tests. Confirm instead that the deferred atomic service is not wired into production routes.

- [x] **Step 2: Run desktop verification**

Run: `cd src-tauri && cargo test --lib`

Run: `cd src-tauri && cargo clippy --all-targets --all-features -- -D warnings`

Run: `npm test -- --run`

Run: `npm run build`

- [x] **Step 3: Deploy TalkMore compatibility before desktop**

Deploy the compatible server route and status-read changes, then verify Vercel Pro logs/Neon metrics. The deferred additive migration is not present in this release, and no current production route depends on it. Old desktop clients must continue to receive flat fields and use their existing flows.

Production checkpoint: Vercel deployment `talkmore-d02ldydr8-tovers-projects.vercel.app` is `READY` and serves the production aliases after the default Turbopack build. The STT function reports a 210-second timeout with Fluid Compute enabled. Public homepage/Features browser checks pass, and the post-deploy window has no error-level or HTTP 500 logs. `MANAGED_STT_V2_ENABLED` remains absent/disabled, so current desktop clients retain the existing managed path. Rollback target: `talkmore-o6mr5rgsa-tovers-projects.vercel.app`.

- [ ] **Step 4: Observe cost and UX gates**

For 24–48 hours confirm:

- idle upgraded desktops generate no periodic status traffic;
- Neon active compute time and statement volume fall;
- quota overrun/double-charge count remains zero;
- STT/LLM/Ask p50/p95 first-output latency stays within the stated budget;
- post-use status-refresh failures do not correlate with lost or delayed user output.

- [ ] **Step 5: Release desktop gradually**

Ship to a small cohort first, then expand. Keep the server's legacy fields and old schema columns through at least one full desktop rollback window.

Desktop checkpoint: the Apple Silicon macOS debug `.app` compiles, bundles, launches, and renders the Cloud recording-limit fallback UI. The owner approved Accessibility and microphone access, and a controlled authenticated eight-second managed-Cloud WAV run completed with 107 ms capture readiness and 4,539 ms from stop to completed STT; the owner confirmed the first-audio experience passed. AI Polish was disabled only for the isolated STT measurement and restored afterward. This is one macOS sample, not the 20-trial latency/quality gate. Updater signing remains blocked by the unavailable `TAURI_SIGNING_PRIVATE_KEY`. A macOS-hosted Windows `cargo check` stopped at the missing Windows MSVC C sysroot and is not a Windows result. Native Windows/Linux validation, the managed long-Ogg test, and all signed release/cohort steps remain open.

Fresh local verification checkpoint: TalkMore passes 121 test files / 717 tests and TypeScript checking; OpenTypeless passes the production frontend build, 44 test files / 392 tests on rerun, and 499 Rust library tests. One pre-existing app-mapping UI test failed once and then passed both in isolation and in the full rerun, so it is recorded as a nondeterministic test race rather than treated as a product pass/fail signal for Issues #81/#83.
