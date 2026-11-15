import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  enqueueConfigSave,
  saveDirtyConfig,
  useAutoSaveConfig,
} from '../../components/Settings/shared/useAutoSaveConfig'
import { useAppStore } from '../../stores/appStore'
import { getConfig, getHotkeyRegistrationError, setAutoStart, updateConfig } from '../../lib/tauri'
import { toast } from '../../components/toast-service'
import { pendingConfigSaves } from '../../lib/configSaveRegistry'

vi.mock('../../lib/tauri', () => ({
  updateConfig: vi.fn().mockResolvedValue(undefined),
  getConfig: vi.fn(),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  setAutoStart: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../components/toast-service', () => ({ toast: vi.fn() }))

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  useAppStore.getState().setSavedConfig(useAppStore.getState().config)
})

it('registers the submitted snapshot before IPC and retires it after completion', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark' })
  const submitted = useAppStore.getState().config
  let identity = ''
  vi.mocked(updateConfig).mockImplementationOnce(async (_config, saveId) => {
    identity = saveId!
    expect(pendingConfigSaves.snapshot(identity)).toEqual(submitted)
    useAppStore.getState().updateConfig({ theme: 'light' })
    expect(pendingConfigSaves.snapshot(identity)?.theme).toBe('dark')
  })
  expect(await saveDirtyConfig()).toBe(true)
  expect(pendingConfigSaves.snapshot(identity)).toBeNull()
})

it('retires the submitted snapshot after IPC failure', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark' })
  let identity = ''
  vi.mocked(updateConfig).mockImplementationOnce(async (_config, saveId) => {
    identity = saveId!
    expect(pendingConfigSaves.snapshot(identity)).not.toBeNull()
    throw new Error('Synthetic save failure')
  })
  expect(await saveDirtyConfig()).toBe(false)
  expect(pendingConfigSaves.snapshot(identity)).toBeNull()
})

it('assigns a distinct UUID to each actual autosave submission', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark' })
  expect(await saveDirtyConfig()).toBe(true)
  useAppStore.getState().updateConfig({ theme: 'light' })
  expect(await saveDirtyConfig()).toBe(true)
  const identities = vi.mocked(updateConfig).mock.calls.map(([, saveId]) => saveId)
  expect(identities).toHaveLength(2)
  for (const identity of identities) {
    expect(identity).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  }
  expect(identities[0]).not.toBe(identities[1])
})

it.each(['identity', 'revision', 'config', 'epoch', 'unsafeRevision'] as const)(
  'does not commit a versioned receipt with an invalid %s',
  async (invalid) => {
    const baseline = useAppStore.getState().savedConfig
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const draft = useAppStore.getState().config
    vi.mocked(updateConfig).mockImplementationOnce(async (_config, saveId) => {
      const commit = {
        configEpoch: '11111111-1111-4111-8111-111111111111',
        revision: 7,
        saveId: saveId ?? null,
        config: draft,
      }
      if (invalid === 'identity') commit.saveId = '22222222-2222-4222-8222-222222222222'
      if (invalid === 'revision') commit.revision = 8
      if (invalid === 'config') commit.config = { ...draft, theme: 'light' }
      if (invalid === 'epoch') commit.configEpoch = 'invalid'
      if (invalid === 'unsafeRevision') commit.revision = Number.MAX_SAFE_INTEGER + 1
      return { config: draft, savedRevision: 7, inputActivation: 'pending', commit }
    })
    expect(await saveDirtyConfig()).toBe(false)
    expect(useAppStore.getState().savedConfig).toBe(baseline)
    expect(useAppStore.getState().config).toBe(draft)
    expect(getConfig).not.toHaveBeenCalled()
    expect(toast).toHaveBeenCalledWith(expect.any(String), 'error')
  },
)

it('accepts a versioned receipt owned by the current save', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark' })
  vi.mocked(updateConfig).mockImplementationOnce(async (config, saveId) => ({
    config,
    savedRevision: 7,
    inputActivation: 'active',
    commit: {
      configEpoch: '11111111-1111-4111-8111-111111111111',
      revision: 7,
      saveId: saveId ?? null,
      config,
    },
  }))
  expect(await saveDirtyConfig()).toBe(true)
  expect(useAppStore.getState().savedConfig?.theme).toBe('dark')
})

it.each(['secret', 'shape'] as const)(
  'rejects a matching versioned receipt with an invalid snapshot %s',
  async (invalid) => {
    const baseline = useAppStore.getState().savedConfig
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const draft = useAppStore.getState().config
    let identity = ''
    vi.mocked(updateConfig).mockImplementationOnce(async (_config, saveId) => {
      identity = saveId!
      const malformed = { ...draft } as Record<string, unknown>
      if (invalid === 'secret') malformed.stt_api_key = 'synthetic-secret'
      else delete malformed.translation
      return {
        config: malformed,
        savedRevision: 7,
        inputActivation: 'pending',
        commit: {
          configEpoch: '11111111-1111-4111-8111-111111111111',
          revision: 7,
          saveId,
          config: malformed,
        },
      } as never
    })
    expect(await saveDirtyConfig()).toBe(false)
    expect(useAppStore.getState().savedConfig).toBe(baseline)
    expect(useAppStore.getState().config).toBe(draft)
    expect(pendingConfigSaves.snapshot(identity)).toBeNull()
    expect(getConfig).not.toHaveBeenCalled()
  },
)

it('uses the normalized configuration returned by the save receipt', async () => {
  useAppStore.getState().updateConfig({ max_recording_seconds: 30 })
  const normalized = { ...useAppStore.getState().config, max_recording_seconds: 600 }
  vi.mocked(updateConfig).mockResolvedValueOnce({
    config: normalized,
    savedRevision: 7,
    inputActivation: 'pending',
  } as never)
  expect(await saveDirtyConfig()).toBe(true)
  expect(useAppStore.getState().savedConfig?.max_recording_seconds).toBe(600)
  expect(useAppStore.getState().config.max_recording_seconds).toBe(600)
})

it('accepts the normalized receipt after the same save broadcasts a persisted patch', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark', max_recording_seconds: 30 })
  const submitted = useAppStore.getState().config
  vi.mocked(updateConfig).mockImplementationOnce(async () => {
    useAppStore.getState().applyPersistedConfigPatch({ theme: 'dark' })
    return {
      config: { ...submitted, max_recording_seconds: 600 },
      savedRevision: 7,
      inputActivation: 'pending',
    }
  })
  expect(await saveDirtyConfig()).toBe(true)
  expect(useAppStore.getState().savedConfig?.max_recording_seconds).toBe(600)
  expect(useAppStore.getState().config.max_recording_seconds).toBe(600)
})

it('does not overwrite an unrelated persisted patch with the earlier receipt', async () => {
  useAppStore.getState().updateConfig({ max_recording_seconds: 30 })
  const submitted = useAppStore.getState().config
  vi.mocked(updateConfig).mockImplementationOnce(async () => {
    useAppStore.getState().applyPersistedConfigPatch({ microphone_device: 'new-device' })
    return {
      config: { ...submitted, max_recording_seconds: 600 },
      savedRevision: 7,
      inputActivation: 'pending',
    }
  })
  expect(await saveDirtyConfig()).toBe(false)
  expect(useAppStore.getState().savedConfig?.microphone_device).toBe('new-device')
  expect(useAppStore.getState().config.microphone_device).toBe('new-device')
})

it('does not apply an older normalized receipt after a newer save succeeds', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark', max_recording_seconds: 30 })
  const original = useAppStore.getState().config
  let finishOriginal: (() => void) | undefined
  vi.mocked(updateConfig).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finishOriginal = () =>
          resolve({
            config: { ...original, max_recording_seconds: 600 },
            savedRevision: 7,
            inputActivation: 'pending',
          })
      }),
  )
  const originalSave = saveDirtyConfig()
  useAppStore.getState().updateConfig({ theme: 'light', max_recording_seconds: 300 })
  const replacement = useAppStore.getState().config
  vi.mocked(updateConfig).mockResolvedValueOnce({
    config: replacement,
    savedRevision: 8,
    inputActivation: 'active',
  })
  expect(await saveDirtyConfig()).toBe(true)
  finishOriginal?.()
  expect(await originalSave).toBe(false)
  expect(useAppStore.getState().savedConfig).toEqual(replacement)
  expect(useAppStore.getState().config).toEqual(replacement)
})

it('keeps newer edits while committing the original normalized save receipt', async () => {
  useAppStore.getState().updateConfig({ max_recording_seconds: 30 })
  const submitted = useAppStore.getState().config
  vi.mocked(updateConfig).mockImplementationOnce(async () => {
    useAppStore.getState().updateConfig({ theme: 'dark' })
    return {
      config: { ...submitted, max_recording_seconds: 600 },
      savedRevision: 7,
      inputActivation: 'pending',
    }
  })
  expect(await saveDirtyConfig()).toBe(true)
  expect(useAppStore.getState().savedConfig?.max_recording_seconds).toBe(600)
  expect(useAppStore.getState().config.theme).toBe('dark')
  expect(useAppStore.getState().config.max_recording_seconds).toBe(600)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('settings autosave on navigation', () => {
  it('omits private queue diagnostics and allows subsequent saves', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const state = vi.spyOn(useAppStore, 'getState')
    state.mockImplementationOnce(() => {
      throw new Error('synthetic-private-queue-detail')
    })
    try {
      await expect(enqueueConfigSave()).rejects.toThrow('synthetic-private-queue-detail')
      expect(log).toHaveBeenCalledExactlyOnceWith('[settings] autosave queue failed')
      useAppStore.getState().updateConfig({ theme: 'dark' })
      expect(await enqueueConfigSave()).toBe(true)
      expect(useAppStore.getState().savedConfig?.theme).toBe('dark')
    } finally {
      state.mockRestore()
      log.mockRestore()
    }
  })

  it('omits private diagnostics from hotkey refresh logs without failing the save', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(getHotkeyRegistrationError).mockRejectedValueOnce(
      new Error('synthetic-private-credential'),
    )
    useAppStore.getState().updateConfig({ theme: 'dark' })
    try {
      expect(await saveDirtyConfig()).toBe(true)
      expect(log).toHaveBeenCalledExactlyOnceWith(
        '[settings] failed to refresh hotkey registration error',
      )
      expect(useAppStore.getState().savedConfig?.theme).toBe('dark')
    } finally {
      log.mockRestore()
    }
  })

  it('does not replace a newer saved baseline when an older save succeeds late', async () => {
    let finishOld: (() => void) | undefined
    vi.mocked(updateConfig).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishOld = resolve
        }),
    )
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const oldSave = saveDirtyConfig()
    useAppStore.getState().updateConfig({ theme: 'light' })
    expect(await saveDirtyConfig()).toBe(true)
    finishOld?.()
    expect(await oldSave).toBe(false)
    expect(useAppStore.getState().savedConfig?.theme).toBe('light')
    expect(useAppStore.getState().config.theme).toBe('light')
  })

  it('accepts a late save when a newer saved baseline has the same values', async () => {
    let finishOld: (() => void) | undefined
    vi.mocked(updateConfig).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishOld = resolve
        }),
    )
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const submitted = useAppStore.getState().config
    const oldSave = saveDirtyConfig()
    const newer = {
      ...submitted,
      hotkeys: { ...submitted.hotkeys },
    }
    useAppStore.getState().setSavedConfig(newer)
    finishOld?.()
    expect(await oldSave).toBe(true)
    expect(useAppStore.getState().savedConfig).toBe(newer)
  })

  it('does not overwrite a newer saved baseline with late failure readback', async () => {
    const previous = useAppStore.getState().config
    let finishRead: ((config: typeof previous) => void) | undefined
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    vi.mocked(getConfig).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishRead = resolve
        }),
    )
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const pending = saveDirtyConfig()
    await Promise.resolve()
    const newer = { ...previous, theme: 'light' as const }
    useAppStore.getState().setConfig(newer)
    useAppStore.getState().setSavedConfig(newer)
    finishRead?.(previous)
    expect(await pending).toBe(false)
    expect(useAppStore.getState().savedConfig).toEqual(newer)
    expect(useAppStore.getState().config).toEqual(newer)
  })

  it('reports auto-start rollback failure together with the original settings error', async () => {
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ auto_start: !previous.auto_start })
    vi.mocked(setAutoStart)
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('Synthetic rollback failure'))
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    vi.mocked(getConfig).mockResolvedValueOnce(previous)
    expect(await saveDirtyConfig()).toBe(false)
    expect(toast).toHaveBeenCalledWith(
      expect.stringContaining('Synthetic rollback failure'),
      'error',
    )
    expect(toast).toHaveBeenCalledWith(expect.stringContaining('Synthetic save failure'), 'error')
  })

  it('does not roll back auto-start after a newer persisted choice replaces its baseline', async () => {
    let failSave: ((error: Error) => void) | undefined
    const previous = useAppStore.getState().config
    vi.mocked(updateConfig).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failSave = reject
        }),
    )
    useAppStore.getState().updateConfig({ auto_start: !previous.auto_start })
    const submitted = useAppStore.getState().config
    const pending = saveDirtyConfig()
    await Promise.resolve()
    useAppStore.getState().setSavedConfig(submitted)
    vi.mocked(getConfig).mockResolvedValueOnce(submitted)
    failSave?.(new Error('Old settings save failed'))
    expect(await pending).toBe(false)
    expect(setAutoStart).toHaveBeenCalledOnce()
    expect(setAutoStart).toHaveBeenCalledWith(submitted.auto_start)
  })

  it('reports failure even when backend configuration can be reloaded', async () => {
    const previous = useAppStore.getState().config
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    vi.mocked(getConfig).mockResolvedValueOnce(previous)
    useAppStore.getState().updateConfig({ theme: 'dark' })
    expect(await saveDirtyConfig()).toBe(false)
    expect(useAppStore.getState().config.theme).toBe(previous.theme)
    expect(await saveDirtyConfig()).toBe(true)
  })

  it('preserves newer edits when an older failed save reloads backend truth', async () => {
    let failSave: (error: Error) => void = () => {}
    const original = useAppStore.getState().config
    vi.mocked(getConfig).mockResolvedValueOnce(original)
    vi.mocked(updateConfig).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failSave = reject
      }),
    )
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const saving = saveDirtyConfig()
    useAppStore.getState().updateConfig({ theme: 'light' })
    failSave(new Error('save failed'))
    await saving
    expect(useAppStore.getState().config.theme).toBe('light')
    expect(useAppStore.getState().savedConfig?.theme).toBe(original.theme)
    await saveDirtyConfig()
    expect(vi.mocked(updateConfig).mock.calls[1][0].theme).toBe('light')
  })

  it('saves the latest edit when leaving before the debounce finishes', async () => {
    const { unmount } = renderHook(() => useAutoSaveConfig())
    act(() => useAppStore.getState().updateConfig({ theme: 'dark' }))
    await act(async () => unmount())
    expect(updateConfig).toHaveBeenCalledTimes(1)
    expect(vi.mocked(updateConfig).mock.calls[0][0].theme).toBe('dark')
    expect(useAppStore.getState().savedConfig?.theme).toBe('dark')
  })

  it('does not save unchanged configuration on navigation', async () => {
    const { unmount } = renderHook(() => useAutoSaveConfig())
    await act(async () => unmount())
    expect(updateConfig).not.toHaveBeenCalled()
  })

  it('queues a newer edit behind an in-flight save when leaving', async () => {
    let finishSave: () => void = () => {}
    vi.mocked(updateConfig).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve
      }),
    )
    const { unmount } = renderHook(() => useAutoSaveConfig())
    act(() => useAppStore.getState().updateConfig({ theme: 'dark' }))
    await act(async () => vi.advanceTimersByTime(400))
    expect(updateConfig).toHaveBeenCalledTimes(1)
    act(() => useAppStore.getState().updateConfig({ theme: 'light' }))
    unmount()
    await act(async () => finishSave())
    expect(vi.mocked(updateConfig).mock.calls.map(([config]) => config.theme)).toEqual([
      'dark',
      'light',
    ])
    expect(useAppStore.getState().savedConfig?.theme).toBe('light')
  })
})
