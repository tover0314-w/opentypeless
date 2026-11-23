import { describe, expect, it } from 'vitest'
import { ConfigCommitCursor, mergeConfigDraft } from '../configCommit'

interface SyntheticConfig {
  theme: string
}

const epoch = '11111111-1111-4111-8111-111111111111'
const replacementEpoch = '22222222-2222-4222-8222-222222222222'
const validConfig = (value: unknown): value is SyntheticConfig =>
  value !== null &&
  typeof value === 'object' &&
  Object.keys(value).length === 1 &&
  'theme' in value &&
  typeof value.theme === 'string'

const commit = (revision: number, theme = 'dark', configEpoch = epoch) => ({
  configEpoch,
  revision,
  saveId: null,
  config: { theme },
})

describe('configuration draft merging', () => {
  const baseline = { theme: 'system', device: '', limit: 30 }
  const groups = [
    { fields: ['theme'], authority: 'editable' },
    { fields: ['device'], authority: 'editable' },
    { fields: ['limit'], authority: 'native' },
  ] as const

  it('preserves edited fields while adopting untouched and native fields', () => {
    const draft = { theme: 'dark', device: '', limit: 900 }
    const persisted = { theme: 'light', device: 'microphone', limit: 600 }
    expect(mergeConfigDraft(baseline, draft, persisted, groups)).toEqual({
      theme: 'dark',
      device: 'microphone',
      limit: 600,
    })
  })

  it('uses a matching submitted draft to adopt normalization without losing later edits', () => {
    const submitted = { theme: 'dark', device: '', limit: 30 }
    const draft = { ...submitted, device: 'new-local-device' }
    const persisted = { theme: 'normalized-dark', device: 'old-device', limit: 600 }
    expect(mergeConfigDraft(submitted, draft, persisted, groups)).toEqual({
      theme: 'normalized-dark',
      device: 'new-local-device',
      limit: 600,
    })
  })

  it('merges associated fields as one group rather than mixing contradictory values', () => {
    const previous = { target: 'en', translation: { active: 'en' } }
    const draft = { target: 'ja', translation: { active: 'ja' } }
    const persisted = { target: 'fr', translation: { active: 'fr' } }
    const merged = mergeConfigDraft(previous, draft, persisted, [
      { fields: ['target', 'translation'], authority: 'editable' },
    ])
    expect(merged).toEqual(draft)
    merged.translation.active = 'mutated'
    expect(draft.translation.active).toBe('ja')
    expect(persisted.translation.active).toBe('fr')
  })

  it('rejects incomplete or overlapping field policies instead of guessing authority', () => {
    expect(() => mergeConfigDraft(baseline, baseline, baseline, [])).toThrow()
    expect(() =>
      mergeConfigDraft(baseline, baseline, baseline, [
        ...groups,
        { fields: ['theme'], authority: 'editable' },
      ]),
    ).toThrow()
  })

  it('rejects unknown authority and fields rather than applying an unverified policy', () => {
    expect(() =>
      mergeConfigDraft(baseline, baseline, baseline, [
        { fields: ['theme', 'device', 'limit'], authority: 'unknown' as 'editable' },
      ]),
    ).toThrow()
    expect(() =>
      mergeConfigDraft(baseline, baseline, baseline, [
        ...groups,
        { fields: ['unknown' as 'theme'], authority: 'editable' },
      ]),
    ).toThrow()
  })
})

describe('configuration commit cursor', () => {
  it('does not roll back a same-epoch cursor with a delayed initialization response', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize(commit(1))
    cursor.accept(commit(12, 'light'))
    expect(cursor.initialize(commit(2))).toBe(false)
    expect(cursor.initialize(commit(12, 'dark'))).toBe(false)
    expect(cursor.snapshot?.config.theme).toBe('light')
    expect(cursor.initialize(commit(12, 'light'))).toBe(true)
  })

  it('requires an initialization handshake before accepting events', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    expect(cursor.accept(commit(1))).toBe('uninitialized')
    expect(cursor.initialize(commit(0))).toBe(true)
    expect(cursor.accept(commit(1))).toBe('accepted')
  })

  it('rejects older revisions and treats matching deliveries as duplicates', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize(commit(1))
    expect(cursor.accept(commit(12, 'light'))).toBe('accepted')
    expect(cursor.accept(commit(11))).toBe('stale')
    expect(cursor.accept(commit(12, 'light'))).toBe('duplicate')
    expect(cursor.accept(commit(12, 'dark'))).toBe('conflict')
    expect(cursor.snapshot?.config.theme).toBe('light')
  })

  it('accepts a new epoch only through an explicit initialization handshake', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize(commit(12))
    expect(cursor.accept(commit(100, 'light', replacementEpoch))).toBe('wrongEpoch')
    expect(cursor.initialize(commit(0, 'light', replacementEpoch))).toBe(true)
    expect(cursor.accept(commit(13))).toBe('wrongEpoch')
    expect(cursor.accept(commit(1, 'dark', replacementEpoch))).toBe('accepted')
  })

  it.each([-1, 0.5, '2', Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1])(
    'rejects an invalid revision %s without changing the snapshot',
    (revision) => {
      const cursor = new ConfigCommitCursor(validConfig)
      cursor.initialize(commit(1))
      expect(cursor.accept({ ...commit(2), revision })).toBe('invalid')
      expect(cursor.snapshot?.revision).toBe(1)
    },
  )

  it('rejects malformed envelopes and caller-invalidated configuration', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize(commit(1))
    for (const value of [
      null,
      [],
      { ...commit(2), configEpoch: '' },
      { ...commit(2), saveId: 42 },
      { ...commit(2), config: { theme: 'light', secret: 'synthetic' } },
      { ...commit(2), extra: true },
    ]) {
      expect(cursor.accept(value)).toBe('invalid')
    }
    expect(cursor.initialize(null)).toBe(false)
    expect(cursor.snapshot?.revision).toBe(1)
  })

  it('detects conflicting save identities for the same persisted revision', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize({ ...commit(1), saveId: replacementEpoch })
    expect(cursor.accept({ ...commit(1), saveId: epoch })).toBe('conflict')
    expect(cursor.accept({ ...commit(1), saveId: replacementEpoch })).toBe('duplicate')
  })

  it('treats an identical anonymous notification as duplicate without retiring the owned identity', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize({ ...commit(1), saveId: replacementEpoch })
    expect(cursor.accept(commit(1))).toBe('duplicate')
    expect(cursor.snapshot?.saveId).toBe(replacementEpoch)
    expect(cursor.accept(commit(1, 'different'))).toBe('conflict')
    expect(cursor.accept({ ...commit(1), saveId: epoch })).toBe('conflict')
  })

  it('does not grant an unobserved save identity to an anonymous persisted revision', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    cursor.initialize(commit(1))
    expect(cursor.accept({ ...commit(1), saveId: replacementEpoch })).toBe('conflict')
    expect(cursor.snapshot?.saveId).toBeNull()
  })

  it('owns snapshot data rather than retaining mutable input or exposing internal objects', () => {
    const cursor = new ConfigCommitCursor(validConfig)
    const initial = commit(1)
    cursor.initialize(initial)
    initial.config.theme = 'mutated input'
    const snapshot = cursor.snapshot!
    snapshot.config.theme = 'mutated output'
    expect(cursor.snapshot?.config.theme).toBe('dark')
  })
})
