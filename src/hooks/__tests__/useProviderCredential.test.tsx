import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useProviderCredential } from '../useProviderCredential'
import { readCredential, setCredential } from '../../lib/tauri'

vi.mock('../../lib/tauri', () => ({
  readCredential: vi.fn().mockResolvedValue(null),
  setCredential: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
})

afterEach(async () => {
  await act(async () => cleanup())
  vi.useRealTimers()
})

describe('provider credential draft', () => {
  it('reports a current vault read failure without exposing backend details', async () => {
    vi.mocked(readCredential).mockRejectedValueOnce(new Error('synthetic-private-vault-detail'))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { result } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
      await act(async () => {})
      expect(result.current.error).toBe('Could not read credential from the system vault')
      expect(result.current.value).toBe('')
      expect(setCredential).not.toHaveBeenCalled()
    } finally {
      log.mockRestore()
    }
  })

  it('does not replace new input feedback with a late vault read failure', async () => {
    let rejectRead: ((error: Error) => void) | undefined
    vi.mocked(readCredential).mockImplementationOnce(
      () =>
        new Promise<string | null>((_resolve, reject) => {
          rejectRead = reject
        }),
    )
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { result } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
      await act(async () => {})
      act(() => result.current.edit('synthetic-new-key'))
      await act(async () => rejectRead?.(new Error('late failure')))
      expect(result.current.error).toBeNull()
      expect(result.current.value).toBe('synthetic-new-key')
    } finally {
      log.mockRestore()
    }
  })

  it.each(['', '   ', new Error(''), new Error('\n\t')])(
    'keeps blank rejection %j visible as the existing save failure',
    async (failure) => {
      vi.mocked(setCredential).mockRejectedValueOnce(failure)
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const { result } = renderHook(() => useProviderCredential('stt', 'deepgram', '', true))
        act(() => result.current.edit('synthetic-key'))
        await act(async () => result.current.flush())
        expect(result.current.error).toBe('Could not save credential in the system vault')
        await act(async () => result.current.flush())
        expect(result.current.error).toBeNull()
      } finally {
        log.mockRestore()
      }
    },
  )

  it.each([null, {}, ['synthetic-private-detail'], 42])(
    'does not coerce malformed rejection %j into credential feedback',
    async (failure) => {
      vi.mocked(setCredential).mockRejectedValueOnce(failure)
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const { result } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
        act(() => result.current.edit('synthetic-key'))
        await act(async () => result.current.flush())
        expect(result.current.error).toBe('Could not save credential in the system vault')
        await act(async () => result.current.flush())
        expect(result.current.error).toBeNull()
      } finally {
        log.mockRestore()
      }
    },
  )

  it('preserves native string errors without changing the retry behavior', async () => {
    vi.mocked(setCredential).mockRejectedValueOnce('Credential save could not be verified')
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { result } = renderHook(() => useProviderCredential('stt', 'deepgram', '', true))
      act(() => result.current.edit('synthetic-key'))
      await act(async () => result.current.flush())
      expect(result.current.error).toBe('Credential save could not be verified')
    } finally {
      log.mockRestore()
    }
  })

  it('redacts the submitted key from error feedback and omits details from logs', async () => {
    const secret = 'synthetic+$密钥'
    vi.mocked(setCredential).mockRejectedValueOnce(
      new Error(`Rejected ${secret}; repeated ${secret}`),
    )
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { result } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
      act(() => result.current.edit(secret))
      await act(async () => result.current.flush())
      expect(result.current.error).toBe('Rejected [redacted]; repeated [redacted]')
      expect(log).toHaveBeenCalledExactlyOnceWith(
        '[credentials] failed to save provider credential',
      )
      await act(async () => result.current.flush())
      expect(result.current.error).toBeNull()
      expect(setCredential).toHaveBeenCalledTimes(2)
    } finally {
      log.mockRestore()
    }
  })

  it('serializes saves across two instances for the same provider', async () => {
    let finishFirst: () => void = () => {}
    vi.mocked(setCredential).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishFirst = resolve
      }),
    )
    const first = renderHook(() => useProviderCredential('llm', 'openai', '', true))
    act(() => first.result.current.edit('first-key'))
    await act(async () => vi.advanceTimersByTime(350))
    first.unmount()
    const second = renderHook(() => useProviderCredential('llm', 'openai', '', true))
    act(() => second.result.current.edit('second-key'))
    await act(async () => vi.advanceTimersByTime(350))
    expect(setCredential).toHaveBeenCalledTimes(1)
    await act(async () => finishFirst())
    expect(vi.mocked(setCredential).mock.calls.map((call) => call[2])).toEqual([
      'first-key',
      'second-key',
    ])
    expect(second.result.current.value).toBe('second-key')
  })

  it('waits for a previous instance save before restoring its credential', async () => {
    let finishSave: () => void = () => {}
    vi.mocked(setCredential).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve
      }),
    )
    const first = renderHook(() => useProviderCredential('stt', 'deepgram', '', true))
    await act(async () => {})
    act(() => first.result.current.edit('saved-key'))
    first.unmount()
    await act(async () => {})
    vi.mocked(readCredential).mockClear()
    vi.mocked(readCredential).mockResolvedValueOnce('saved-key')
    const second = renderHook(() => useProviderCredential('stt', 'deepgram', '', true))
    await act(async () => {})
    expect(readCredential).not.toHaveBeenCalled()
    await act(async () => finishSave())
    expect(second.result.current.value).toBe('saved-key')
  })

  it('flushes an edited draft to its original provider on navigation', async () => {
    const { result, unmount } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
    act(() => result.current.edit('edited-key'))
    await act(async () => unmount())
    expect(setCredential).toHaveBeenCalledWith('llm', 'openai', 'edited-key')
  })

  it('does not write an untouched legacy value on blur or navigation', async () => {
    const { result, unmount } = renderHook(() =>
      useProviderCredential('stt', 'deepgram', 'legacy', true),
    )
    await act(async () => result.current.flush())
    await act(async () => unmount())
    expect(setCredential).not.toHaveBeenCalled()
  })

  it('preserves new input against a late vault read', async () => {
    let finishRead: (value: string) => void = () => {}
    vi.mocked(readCredential).mockReturnValueOnce(
      new Promise<string>((resolve) => {
        finishRead = resolve
      }),
    )
    const { result } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
    act(() => result.current.edit('edited-key'))
    await act(async () => finishRead('old-key'))
    expect(result.current.value).toBe('edited-key')
  })

  it('flushes the old provider draft and reads the new provider without carrying its error', async () => {
    const { result, rerender } = renderHook(
      ({ provider }) => useProviderCredential('stt', provider, '', true),
      { initialProps: { provider: 'deepgram' } },
    )
    act(() => result.current.edit('first-key'))
    await act(async () => rerender({ provider: 'assemblyai' }))
    expect(setCredential).toHaveBeenCalledWith('stt', 'deepgram', 'first-key')
    expect(readCredential).toHaveBeenCalledWith('stt', 'assemblyai')
    expect(result.current.value).toBe('')
    expect(result.current.error).toBeNull()
  })

  it('does not show a delayed save error on another provider', async () => {
    let failSave: (error: Error) => void = () => {}
    vi.mocked(setCredential).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failSave = reject
      }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { result, rerender } = renderHook(
        ({ provider }) => useProviderCredential('llm', provider, '', true),
        { initialProps: { provider: 'openai' } },
      )
      act(() => result.current.edit('edited-key'))
      await act(async () => vi.advanceTimersByTime(350))
      await act(async () => rerender({ provider: 'deepseek' }))
      await act(async () => failSave(new Error('old save failed')))
      expect(result.current.error).toBeNull()
    } finally {
      warning.mockRestore()
    }
  })

  it('serializes edits within the same credential draft', async () => {
    let finishFirst: () => void = () => {}
    vi.mocked(setCredential).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishFirst = resolve
      }),
    )
    const { result } = renderHook(() => useProviderCredential('llm', 'openai', '', true))
    act(() => result.current.edit('first-key'))
    await act(async () => vi.advanceTimersByTime(350))
    act(() => result.current.edit('second-key'))
    await act(async () => vi.advanceTimersByTime(350))
    expect(setCredential).toHaveBeenCalledTimes(1)
    await act(async () => finishFirst())
    expect(vi.mocked(setCredential).mock.calls.map((call) => call[2])).toEqual([
      'first-key',
      'second-key',
    ])
  })
})
