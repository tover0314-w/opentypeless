import { invoke } from '@tauri-apps/api/core'
import { beforeEach, expect, it, vi } from 'vitest'
import { restoreBackupData, validateBackupData } from '../tauri'
import { validRestoreBackupResponse } from '../restore-backup-validation'
import nativeFixture from '../../../src-tauri/tests/fixtures/backup_restore_response.json'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
beforeEach(() => {
  vi.mocked(invoke).mockReset()
})

it.each([undefined, false, true, {}, 'valid'])(
  'rejects malformed native preflight success %j',
  async (response) => {
    vi.mocked(invoke).mockResolvedValue(response)
    await expect(validateBackupData([], null)).rejects.toThrow('Invalid backup validation response')
  },
)

it('accepts native preflight unit response and preserves explicit rejection', async () => {
  vi.mocked(invoke).mockResolvedValue(null)
  await expect(validateBackupData([], null)).resolves.toBeUndefined()
  expect(invoke).toHaveBeenCalledExactlyOnceWith('validate_backup_data', {
    history: [],
    dictionary: null,
  })
  const failure = new Error('Synthetic invalid backup')
  vi.mocked(invoke).mockRejectedValue(failure)
  await expect(validateBackupData([], null)).rejects.toBe(failure)
})

it('accepts the actual SQLite-restored native response fixture without dropping fields', async () => {
  vi.mocked(invoke).mockResolvedValue(nativeFixture)
  expect(validRestoreBackupResponse(nativeFixture)).toBe(true)
  await expect(restoreBackupData([], null)).resolves.toEqual(nativeFixture)
})

it.each([
  null,
  {},
  { history: [], dictionary: [] },
  { history: null, dictionary: [], correctionRules: [] },
  { history: [], dictionary: [null], correctionRules: [] },
  { history: [], dictionary: [], correctionRules: [{}] },
  { history: [], dictionary: [{ id: 0, word: 'Word' }], correctionRules: [] },
  { history: [{ id: 1, raw_text: 42 }], dictionary: [], correctionRules: [] },
])('rejects malformed native restore response %j', async (response) => {
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(restoreBackupData([], null)).rejects.toThrow('Invalid backup restoration response')
})

it('accepts the three native empty sections and preserves the request', async () => {
  const response = { history: [], dictionary: [], correctionRules: [] }
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(restoreBackupData([], null)).resolves.toEqual(response)
  expect(invoke).toHaveBeenCalledExactlyOnceWith('restore_backup_data', {
    history: [],
    dictionary: null,
  })
})

it('preserves a complete restored record, task classification and sources', async () => {
  const history = {
    id: 1,
    created_at: '2026-11-20T00:00:00',
    context_profile_id: 'general.native',
    context_label: 'General',
    context_icon_key: 'general',
    context_family: 'general',
    browser_access_status: 'not_applicable',
    provider_kind: 'local',
    raw_text: 'Question',
    polished_text: 'Question',
    mode: 'ask',
    answer_text: 'Answer',
    ask_intent: 'ask_selection',
    citations: [{ url: 'https://example.invalid/source', title: 'Source' }],
    active_scene_prompt_truncated: false,
    has_audio: false,
    language: null,
    duration_ms: null,
    active_scene_id: null,
    active_scene_source: null,
    active_scene_name: null,
    active_scene_prompt_chars: null,
    output_status: null,
    output_error: null,
  }
  const response = {
    history: [history],
    dictionary: [{ id: 1, word: 'Word', pronunciation: null, auto: false }],
    correctionRules: [{ id: 1, pattern: 'old', replacement: 'new', enabled: true }],
  }
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(restoreBackupData([], null)).resolves.toEqual(response)
  for (const section of ['history', 'dictionary', 'correctionRules'] as const) {
    const entries = response[section].map((entry) => ({ ...entry, token: 'Synthetic secret' }))
    expect(validRestoreBackupResponse({ ...response, [section]: entries }), section).toBe(false)
  }
  expect(
    validRestoreBackupResponse({
      ...response,
      history: [
        {
          ...history,
          citations: [{ ...history.citations[0], content: 'Private source body' }],
        },
      ],
    }),
  ).toBe(false)
  for (const field of [
    'language',
    'duration_ms',
    'active_scene_id',
    'active_scene_source',
    'active_scene_name',
    'active_scene_prompt_chars',
    'output_status',
    'output_error',
    'answer_text',
  ]) {
    const missing: Record<string, unknown> = { ...history }
    delete missing[field]
    expect(validRestoreBackupResponse({ ...response, history: [missing] }), field).toBe(false)
  }
  expect(
    validRestoreBackupResponse({ ...response, history: [{ ...history, language: undefined }] }),
  ).toBe(false)
  vi.mocked(invoke).mockResolvedValue({ ...response, history: [history, history] })
  await expect(restoreBackupData([], null)).rejects.toThrow('Invalid backup restoration response')
})
