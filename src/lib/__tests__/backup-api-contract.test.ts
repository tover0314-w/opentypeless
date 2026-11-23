import { afterEach, describe, expect, it, vi } from 'vitest'
import { downloadBackup, uploadBackup } from '../api'
import { createBackupSettings } from '../backup-settings'
import { useAppStore } from '../../stores/appStore'

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
      [{ url: 'https://example.invalid', title: 'Source' }],
      [{ url: 'https://example.invalid/source', title: ' Source ' }],
      [
        { url: 'https://example.invalid/source', title: 'Source' },
        { url: 'https://example.invalid/source', title: 'Duplicate' },
      ],
    ].map((citations) => ({ citations })),
  )(
    'rejects support-bearing sources that native restore would normalize: %j',
    async ({ citations }) => {
      respond({ success: true })
      await expect(
        uploadBackup({
          history: [
            {
              raw_text: 'Question',
              mode: 'ask',
              answer_text: `Read ${citations[0].url}`,
              citations,
              citation_supports: [{ start: 5, end: 5 + citations[0].url.length, sourceIndex: 0 }],
            },
          ],
        }),
      ).rejects.toThrow('Invalid backup upload data')
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it.each([
    [{ start: 0, end: 4, sourceIndex: 0 }],
    [{ start: 5, end: 34, sourceIndex: 99 }],
    [{ start: 5, end: 34, sourceIndex: 0, content: 'Private extra' }],
  ])('rejects invalid backup citation supports before uploading: %j', async (citation_supports) => {
    respond({ success: true })
    await expect(
      uploadBackup({
        history: [
          {
            raw_text: 'Question',
            mode: 'ask',
            answer_text: 'Read https://example.invalid/source',
            citations: [{ url: 'https://example.invalid/source', title: 'Source' }],
            citation_supports,
          },
        ],
      }),
    ).rejects.toThrow('Invalid backup upload data')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('preserves verified citation ranges in downloaded history', async () => {
    const answer_text = 'Read https://example.invalid/source'
    const body = {
      history: [
        {
          raw_text: 'Question',
          mode: 'ask',
          answer_text,
          citations: [{ url: 'https://example.invalid/source', title: 'Source' }],
          citation_supports: [{ start: 5, end: answer_text.length, sourceIndex: 0 }],
        },
      ],
    }
    respond(body)
    expect(await downloadBackup()).toEqual(body)
  })

  it.each([
    { llm_api_key: 'synthetic-secret' },
    { nested: [{ Refresh_Token: 'synthetic-secret' }] },
    { custom_app_mappings: [{ matcher: { bundle_id: 'synthetic.app' } }] },
    { llm_base_url: 'https://example.invalid/v1?api_key=synthetic-secret' },
    { sttCustomBaseUrl: 'https://user:synthetic-secret@example.invalid/v1' },
    { llm_base_url: 'https://example.invalid/v1#synthetic-secret' },
    { llm_base_url: 'file:///synthetic-endpoint' },
  ])('rejects sensitive settings before any network request: %j', async (settings) => {
    const data = { settings }
    const before = structuredClone(data)
    respond({ success: true })
    await expect(uploadBackup(data)).rejects.toThrow('Invalid backup upload data')
    expect(fetch).not.toHaveBeenCalled()
    expect(data).toEqual(before)
  })

  it('validates serialized settings rather than trusting a credential-hiding toJSON', async () => {
    respond({ success: true })
    await expect(
      uploadBackup({
        settings: { theme: 'dark' },
        toJSON: () => ({ settings: { access_token: 'synthetic-secret' } }),
      } as Parameters<typeof uploadBackup>[0]),
    ).rejects.toThrow('Invalid backup upload data')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('preserves safe account backup settings and authorized history verbatim', async () => {
    const config = structuredClone(useAppStore.getInitialState().config)
    config.llm_api_key = 'synthetic-local-secret'
    config.stt_api_key = 'synthetic-local-secret'
    config.llm_base_url = 'http://localhost:8080/v1?region=local'
    const data = {
      settings: createBackupSettings(config),
      history: [{ raw_text: 'User said token and password', mode: 'dictate' }],
      dictionary: [{ word: 'password' }],
    }
    respond({ success: true })
    await expect(uploadBackup(data)).resolves.toEqual({ success: true })
    expect(vi.mocked(fetch).mock.calls[0][1]?.body).toBe(JSON.stringify(data))
    expect(config.llm_api_key).toBe('synthetic-local-secret')
    expect(config.stt_api_key).toBe('synthetic-local-secret')
  })

  it.each([64, 65])('bounds serialized settings traversal at depth %s', async (depth) => {
    let settings: Record<string, unknown> = {}
    for (let level = 0; level < depth; level += 1) settings = { nested: settings }
    respond({ success: true })
    if (depth === 64) {
      await expect(uploadBackup({ settings })).resolves.toEqual({ success: true })
    } else {
      await expect(uploadBackup({ settings })).rejects.toThrow('Invalid backup upload data')
      expect(fetch).not.toHaveBeenCalled()
    }
  })

  it('rejects unknown top-level fields instead of transmitting unvalidated material', async () => {
    respond({ success: true })
    await expect(
      uploadBackup({
        settings: { theme: 'dark' },
        session_token: 'synthetic-secret',
      } as Parameters<typeof uploadBackup>[0]),
    ).rejects.toThrow('Invalid backup upload data')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('rejects an oversized UTF8 upload before requesting cloud backup replacement', async () => {
    const data = { settings: { synthetic: '字'.repeat(1_400_000) } }
    expect(JSON.stringify(data).length).toBeLessThan(4 * 1024 * 1024)
    respond({ success: true })
    await expect(uploadBackup(data)).rejects.toThrow('Backup upload payload too large')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('uploads a payload exactly at the serialized byte boundary without truncating it', async () => {
    const empty = { settings: { synthetic: '' } }
    const overhead = new TextEncoder().encode(JSON.stringify(empty)).length
    const data = { settings: { synthetic: 'x'.repeat(4 * 1024 * 1024 - overhead) } }
    respond({ success: true })
    await expect(uploadBackup(data)).resolves.toEqual({ success: true })
    expect(vi.mocked(fetch).mock.calls[0][1]?.body).toBe(JSON.stringify(data))
  })

  it.each([
    { history: [{ raw_text: 42 }] },
    { dictionary: [{ word: '' }] },
    { dictionary: { entries: [], correction_rules: [{}] } },
    { settings: [] },
  ])('rejects invalid upload content before any network request: %j', async (data) => {
    respond({ success: true })
    await expect(uploadBackup(data)).rejects.toThrow('Invalid backup upload data')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('rejects source URLs that exceed the byte limit only after normalization', async () => {
    const url = `https://example.invalid/${'字'.repeat(300)}`
    expect(new TextEncoder().encode(url).length).toBeLessThan(2048)
    expect(new URL(url).href.length).toBeGreaterThan(2048)
    respond({
      history: [
        {
          raw_text: 'Question',
          mode: 'ask',
          answer_text: 'Answer',
          citations: [{ url, title: 'Source' }],
        },
      ],
    })
    await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
  })

  it.each(['open_question', 'ask_selection'])(
    'preserves saved task classification %s',
    async (intent) => {
      const body = {
        history: [{ raw_text: 'Question', mode: 'ask', answer_text: 'Answer', ask_intent: intent }],
      }
      respond(body)
      expect(await downloadBackup()).toEqual(body)
    },
  )

  it.each(['search', ['ask_selection'], 42])(
    'rejects invalid saved task classification %j',
    async (intent) => {
      respond({
        history: [{ raw_text: 'Question', mode: 'ask', answer_text: 'Answer', ask_intent: intent }],
      })
      await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
    },
  )

  it('rejects source URLs over the native UTF8 byte limit even when character count is smaller', async () => {
    const url = `https://example.invalid/${'字'.repeat(700)}`
    expect(url.length).toBeLessThan(2048)
    expect(new TextEncoder().encode(url).length).toBeGreaterThan(2048)
    respond({
      history: [
        {
          raw_text: 'Question',
          mode: 'ask',
          answer_text: 'Answer',
          citations: [{ url, title: 'Source' }],
        },
      ],
    })
    await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
  })

  it('preserves a source URL exactly at the native UTF8 byte limit', async () => {
    const prefix = 'https://example.invalid/'
    const url = `${prefix}${'x'.repeat(2048 - prefix.length)}`
    const body = {
      history: [
        {
          raw_text: 'Question',
          mode: 'ask',
          answer_text: 'Answer',
          citations: [{ url, title: 'Source' }],
        },
      ],
    }
    respond(body)
    expect(await downloadBackup()).toEqual(body)
  })

  it('preserves a saved Ask answer longer than twenty thousand characters', async () => {
    const body = {
      history: [{ raw_text: 'Question', mode: 'ask', answer_text: '字'.repeat(20_001) }],
    }
    respond(body)
    expect(await downloadBackup()).toEqual(body)
  })

  it('rejects a saved answer above the existing history text limit', async () => {
    respond({
      history: [{ raw_text: 'Question', mode: 'ask', answer_text: 'x'.repeat(1_000_001) }],
    })
    await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
  })

  it('rejects malformed citation metadata before a backup restore', async () => {
    for (const citations of [
      'not an array',
      [{ url: 'javascript:alert(1)', title: 'Unsafe' }],
      [{ url: 'https://example.invalid', title: 42 }],
      Array.from({ length: 21 }, () => ({ url: 'https://example.invalid', title: 'Source' })),
    ]) {
      respond({
        history: [{ raw_text: 'Question', mode: 'ask', answer_text: 'Answer', citations }],
      })
      await expect(downloadBackup()).rejects.toThrow('Invalid backup response')
    }
  })

  it('preserves citations in a valid Ask backup without changing the answer', async () => {
    const body = {
      history: [
        {
          raw_text: 'Question',
          mode: 'ask',
          answer_text: 'Answer',
          citations: [{ url: 'https://example.invalid/source', title: 'Source' }],
        },
      ],
    }
    respond(body)
    expect(await downloadBackup()).toEqual(body)
  })
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
