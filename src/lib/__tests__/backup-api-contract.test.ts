import { afterEach, describe, expect, it, vi } from 'vitest'
import { downloadBackup, uploadBackup } from '../api'

vi.mock('../cloud-session', () => ({
  captureCloudSessionOwner: () => () => true,
  loadSessionToken: vi.fn().mockResolvedValue(null),
  invalidateCloudSessionOnce: vi.fn(),
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function respond(body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      json: async () => body,
    }),
  )
}

describe('backup download contract', () => {
  it.each(
    [
      [null],
      [{}],
      [{ raw_text: 42 }],
      [{ raw_text: 'Words', answer_text: [] }],
      [{ raw_text: 'Words', mode: false }],
      [{ raw_text: '', polished_text: '', output_status: 'unknown' }],
      [{ raw_text: 'Words', active_scene_prompt_truncated: 'true' }],
      [{ raw_text: 'Words', context_family: 'unknown-family' }],
      [{ raw_text: 'Words', browser_access_status: 'granted' }],
      [{ raw_text: 'Words', provider_kind: 'unknown-provider' }],
      [{ raw_text: 'Words', context_family: 12 }],
      [{ raw_text: 'Words', created_at: 42 }],
      Array.from({ length: 5001 }, () => ({ raw_text: 'Words' })),
    ].map((history) => ({ history })),
  )('rejects malformed history before restoring settings', async ({ history }) => {
    respond({ history, settings: { theme: 'dark' } })
    await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
  })

  it.each(
    [
      [{ text: 'Legacy words' }],
      [{ raw_text: 'Question', mode: 'ask', answer_text: 'Answer' }],
      [{ raw_text: 'Words', mode: 'translate' }],
      [{ raw_text: '', output_status: 'dismissed' }],
      [{ raw_text: '', output_status: 'silent' }],
      [{ raw_text: '', output_status: 'recognition_failed' }],
      [{ raw_text: 'Words', created_at: '2024-02-29T10:00:00' }],
      [{ raw_text: 'Words', created_at: '2026-11-01T10:00:00.123+08:00' }],
      [{ raw_text: 'Words', created_at: '+10000-01-01T10:00:00' }],
    ].map((history) => ({ history })),
  )('preserves valid history and recording-only states', async ({ history }) => {
    respond({ history })
    expect(await downloadBackup()).toEqual({ history })
  })

  it.each(['2026-02-30T10:00:00', '2026-11-01T25:00:00Z', 'not-a-date'])(
    'leaves timestamp semantics to mandatory native preflight: %s',
    async (created_at) => {
      const body = { history: [{ raw_text: 'Words', created_at }] }
      respond(body)
      expect(await downloadBackup()).toEqual(body)
    },
  )

  it.each([
    { dictionary: [null] },
    { dictionary: [{}] },
    { dictionary: [{ word: ' \0 ' }] },
    { dictionary: [{ word: 'x'.repeat(101) }] },
    { dictionary: [{ word: 'Term', pronunciation: 12 }] },
    { dictionary: [{ word: 'Term', auto: 'true' }] },
    { dictionary: { entries: [], correction_rules: [{ pattern: 'wrong' }] } },
    { dictionary: { entries: [], correction_rules: [], correctionRules: [] } },
    {
      dictionary: {
        entries: [],
        correction_rules: [{ pattern: 'wrong', replacement: 'right', enabled: 1 }],
      },
    },
  ])('rejects invalid dictionary rows before any restoration', async (body) => {
    respond({ settings: { theme: 'dark' }, ...body })
    await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
  })

  it.each([null, {}, { success: false }, { success: 'true' }, { success: true, error: 'failed' }])(
    'rejects an unconfirmed upload response',
    async (body) => {
      respond(body)
      await expect(uploadBackup({ settings: {} })).rejects.toThrow(
        'Backup upload was not confirmed',
      )
    },
  )

  it('accepts an explicitly confirmed upload', async () => {
    respond({ success: true })
    expect(await uploadBackup({ settings: {} })).toEqual({ success: true })
  })

  it.each([true, false])('preserves body abortion for HTTP success=%s', async (ok) => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url, options: RequestInit) => ({
        ok,
        status: ok ? 200 : 503,
        statusText: 'Service unavailable',
        json: () =>
          new Promise((_resolve, reject) => {
            const signal = options.signal as AbortSignal
            const abort = () => reject(new DOMException('Response body aborted', 'AbortError'))
            if (signal.aborted) abort()
            else signal.addEventListener('abort', abort, { once: true })
          }),
      })),
    )
    const pending = downloadBackup()
    const outcome = pending.catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(30_001)
    expect(await outcome).toMatchObject({ name: 'AbortError', message: 'Response body aborted' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps the request timeout active while reading the response body', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined
    let finish: (body: unknown) => void = () => {}
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url, options: RequestInit) => {
        signal = options.signal as AbortSignal
        return {
          ok: true,
          json: () =>
            new Promise((resolve) => {
              finish = resolve
            }),
        }
      }),
    )
    const pending = downloadBackup().catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(30_001)
    const aborted = signal?.aborted
    finish({})
    expect(await pending).toMatchObject({ name: 'AbortError' })
    expect(aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    null,
    [],
    { error: 'Unavailable' },
    { history: 'invalid' },
    { settings: [] },
    { dictionary: 'invalid' },
    { dictionary: { entries: 'invalid' } },
    { dictionary: { entries: [], correction_rules: {} } },
  ])('rejects malformed backup sections before restoration', async (body) => {
    respond(body)
    await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
  })

  it.each([
    {},
    { history: [], settings: {} },
    { history: null, dictionary: null, settings: null },
    { dictionary: [{ word: 'Legacy' }] },
    { dictionary: { entries: [], correction_rules: [] } },
    { dictionary: { entries: [], correctionRules: [] } },
  ])('preserves supported empty and legacy backup shapes', async (body) => {
    respond(body)
    expect(await downloadBackup()).toEqual(body)
  })
})
