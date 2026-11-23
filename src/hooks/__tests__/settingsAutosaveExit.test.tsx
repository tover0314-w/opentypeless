import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  enqueueConfigSave,
  saveDirtyConfig,
  useAutoSaveConfig,
} from '../../components/Settings/shared/useAutoSaveConfig'
import { useAppStore } from '../../stores/appStore'
import {
  getConfig,
  getConfigCommit,
  getHotkeyRegistrationError,
  setAutoStart,
  updateConfig,
} from '../../lib/tauri'
import { toast } from '../../components/toast-service'
import { pendingConfigSaves } from '../../lib/configSaveRegistry'

vi.mock('../../lib/tauri', () => ({
  updateConfig: vi.fn().mockResolvedValue(undefined),
  getConfig: vi.fn(),
  getConfigCommit: vi.fn(),
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

describe('autosave with an initialized configuration stream', () => {
  const configEpoch = '11111111-1111-4111-8111-111111111111'

  function initialize() {
    const config = {
      ...structuredClone(useAppStore.getState().config),
      managed_stt_capability_state: null,
    }
    expect(
      useAppStore.getState().initializeConfigCommit({
        configEpoch,
        revision: 5,
        saveId: null,
        config,
      }),
    ).toBe(true)
    return config
  }

  it('rejects a merged shortcut collision before applying native side effects and keeps the draft', async () => {
    const baseline = initialize()
    const shortcut = { primary: 'K', modifiers: ['Ctrl'] }
    useAppStore.getState().updateConfig({
      auto_start: !baseline.auto_start,
      hotkeys: {
        ...baseline.hotkeys,
        dictation: shortcut,
        dictationBindings: [shortcut],
      },
    })
    const remote = {
      ...baseline,
      ask_hotkey: 'Ctrl+K',
      hotkeys: { ...baseline.hotkeys, ask: shortcut, askBindings: [shortcut] },
    }
    expect(
      useAppStore.getState().applyConfigCommit({
        configEpoch,
        revision: 6,
        saveId: null,
        config: remote,
      }),
    ).toBe('accepted')
    const draft = useAppStore.getState().config
    expect(await saveDirtyConfig()).toBe(false)
    expect(updateConfig).not.toHaveBeenCalled()
    expect(setAutoStart).not.toHaveBeenCalled()
    expect(getConfigCommit).not.toHaveBeenCalled()
    expect(useAppStore.getState().config).toBe(draft)
    expect(useAppStore.getState().savedConfig).toEqual(remote)
    expect(toast).toHaveBeenCalledOnce()
  })

  it('rejects a late owned receipt after a newer commit without regressing untouched fields', async () => {
    const baseline = initialize()
    useAppStore.getState().updateConfig({ theme: 'dark' })
    vi.mocked(updateConfig).mockImplementationOnce(async (config, saveId) => {
      useAppStore.getState().applyConfigCommit({
        configEpoch,
        revision: 7,
        saveId: null,
        config: { ...baseline, theme: 'dark', microphone_device: 'newer-device' },
      })
      return {
        config,
        savedRevision: 6,
        inputActivation: 'active',
        commit: { configEpoch, revision: 6, saveId: saveId!, config },
      }
    })
    expect(await saveDirtyConfig()).toBe(false)
    expect(useAppStore.getState().configCommit?.revision).toBe(7)
    expect(useAppStore.getState().savedConfig?.microphone_device).toBe('newer-device')
  })

  it('advances the shared watermark using an owned normalized receipt', async () => {
    initialize()
    useAppStore.getState().updateConfig({ custom_recording_limit_seconds: 999 })
    vi.mocked(updateConfig).mockImplementationOnce(async (submitted, saveId) => {
      useAppStore.getState().updateConfig({ theme: 'dark' })
      const config = { ...submitted, custom_recording_limit_seconds: 600 }
      return {
        config,
        savedRevision: 6,
        inputActivation: 'pending',
        commit: { configEpoch, revision: 6, saveId: saveId!, config },
      }
    })
    expect(await saveDirtyConfig()).toBe(true)
    expect(useAppStore.getState().configCommit?.revision).toBe(6)
    expect(useAppStore.getState().config.custom_recording_limit_seconds).toBe(600)
    expect(useAppStore.getState().config.theme).toBe('dark')
  })

  it('treats the same broadcast and receipt as idempotent', async () => {
    initialize()
    useAppStore.getState().updateConfig({ theme: 'dark' })
    let savedBaseline: unknown
    vi.mocked(updateConfig).mockImplementationOnce(async (config, saveId) => {
      const commit = { configEpoch, revision: 6, saveId: saveId!, config }
      expect(useAppStore.getState().applyConfigCommit(commit)).toBe('accepted')
      savedBaseline = useAppStore.getState().savedConfig
      useAppStore.getState().updateConfig({ theme: 'light' })
      return { config, savedRevision: 6, inputActivation: 'active', commit }
    })
    expect(await saveDirtyConfig()).toBe(true)
    expect(useAppStore.getState().savedConfig).toBe(savedBaseline)
    expect(useAppStore.getState().config.theme).toBe('light')
  })

  it.each(['legacy', 'wrongEpoch'] as const)(
    'does not mix an initialized stream with a %s receipt',
    async (kind) => {
      initialize()
      useAppStore.getState().updateConfig({ theme: 'dark' })
      const saved = useAppStore.getState().savedConfig
      vi.mocked(updateConfig).mockImplementationOnce(async (config, saveId) => ({
        config,
        savedRevision: 6,
        inputActivation: 'active',
        ...(kind === 'wrongEpoch'
          ? {
              commit: {
                configEpoch: '33333333-3333-4333-8333-333333333333',
                revision: 6,
                saveId: saveId!,
                config,
              },
            }
          : {}),
      }))
      expect(await saveDirtyConfig()).toBe(false)
      expect(useAppStore.getState().savedConfig).toBe(saved)
      expect(useAppStore.getState().configCommit?.revision).toBe(5)
    },
  )

  it('uses versioned failure readback without replacing a newer commit or local draft', async () => {
    const baseline = initialize()
    useAppStore.getState().updateConfig({ theme: 'dark' })
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    vi.mocked(getConfigCommit).mockImplementationOnce(async () => {
      useAppStore.getState().applyConfigCommit({
        configEpoch,
        revision: 7,
        saveId: null,
        config: { ...baseline, microphone_device: 'newer-device' },
      })
      return { configEpoch, revision: 6, saveId: null, config: baseline }
    })
    expect(await saveDirtyConfig()).toBe(false)
    expect(getConfigCommit).toHaveBeenCalledOnce()
    expect(getConfig).not.toHaveBeenCalled()
    expect(useAppStore.getState().configCommit?.revision).toBe(7)
    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(useAppStore.getState().savedConfig?.microphone_device).toBe('newer-device')
  })

  it('adopts newer failure readback as saved truth while keeping the unsaved draft', async () => {
    const baseline = initialize()
    useAppStore.getState().updateConfig({ theme: 'dark' })
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    vi.mocked(getConfigCommit).mockResolvedValueOnce({
      configEpoch,
      revision: 6,
      saveId: null,
      config: { ...baseline, microphone_device: 'readback-device' },
    })
    expect(await saveDirtyConfig()).toBe(false)
    expect(useAppStore.getState().configCommit?.revision).toBe(6)
    expect(useAppStore.getState().savedConfig?.theme).toBe('system')
    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(useAppStore.getState().config.microphone_device).toBe('readback-device')
    expect(getConfig).not.toHaveBeenCalled()
  })

  it.each([6, 7])(
    'preserves a conflicting shortcut draft after rejected save and revision %s readback',
    async (readbackRevision) => {
      const baseline = initialize()
      const localBinding = { primary: 'K', modifiers: ['Ctrl'] }
      useAppStore.getState().updateConfig({
        theme: 'dark',
        hotkeys: {
          ...baseline.hotkeys,
          dictation: localBinding,
          dictationBindings: [localBinding],
        },
      })
      const remote = {
        ...baseline,
        ask_hotkey: 'Ctrl+K',
        hotkeys: {
          ...baseline.hotkeys,
          ask: localBinding,
          askBindings: [localBinding],
        },
      }
      const submitted = useAppStore.getState().config
      const rejection = 'Dictation and Ask hotkeys must use different shortcuts'
      let submittedIdentity = ''
      vi.mocked(updateConfig).mockImplementationOnce(async (config, saveId) => {
        submittedIdentity = saveId!
        expect(config).toEqual(submitted)
        expect(pendingConfigSaves.snapshot(submittedIdentity)).toEqual(submitted)
        expect(
          useAppStore.getState().applyConfigCommit({
            configEpoch,
            revision: 6,
            saveId: null,
            config: remote,
          }),
        ).toBe('accepted')
        throw new Error(rejection)
      })
      const readback = {
        ...remote,
        microphone_device: readbackRevision === 7 ? 'newer-device' : remote.microphone_device,
      }
      vi.mocked(getConfigCommit).mockResolvedValueOnce({
        configEpoch,
        revision: readbackRevision,
        saveId: null,
        config: readback,
      })

      expect(await saveDirtyConfig()).toBe(false)
      const failed = useAppStore.getState()
      expect(failed.savedConfig).toEqual(readback)
      expect(failed.configCommit?.revision).toBe(readbackRevision)
      expect(failed.config.hotkeys.dictation).toEqual(localBinding)
      expect(failed.config.hotkeys.ask).toEqual(localBinding)
      expect(failed.config.theme).toBe('dark')
      expect(failed.savedConfig?.theme).toBe(baseline.theme)
      expect(failed.config.microphone_device).toBe(readback.microphone_device)
      expect(pendingConfigSaves.snapshot(submittedIdentity)).toBeNull()
      expect(toast).toHaveBeenCalledWith(rejection, 'error')
      expect(getConfig).not.toHaveBeenCalled()

      const correctedBinding = { primary: 'J', modifiers: ['Ctrl'] }
      failed.updateConfig({
        hotkeys: {
          ...failed.config.hotkeys,
          dictation: correctedBinding,
          dictationBindings: [correctedBinding],
        },
      })
      vi.mocked(updateConfig).mockImplementationOnce(async (config, saveId) => ({
        config,
        savedRevision: readbackRevision + 1,
        inputActivation: 'active',
        commit: {
          configEpoch,
          revision: readbackRevision + 1,
          saveId: saveId!,
          config,
        },
      }))
      expect(await saveDirtyConfig()).toBe(true)
      const recovered = useAppStore.getState()
      expect(recovered.savedConfig?.hotkeys.dictation).toEqual(correctedBinding)
      expect(recovered.savedConfig?.hotkeys.ask).toEqual(localBinding)
      expect(recovered.savedConfig?.theme).toBe('dark')
      expect(recovered.savedConfig?.microphone_device).toBe(readback.microphone_device)
      expect(recovered.config).toEqual(recovered.savedConfig)
      expect(recovered.configCommit?.revision).toBe(readbackRevision + 1)
      expect(updateConfig).toHaveBeenCalledTimes(2)
      const recoveryIdentity = vi.mocked(updateConfig).mock.calls[1][1]!
      expect(recoveryIdentity).not.toBe(submittedIdentity)
      expect(pendingConfigSaves.snapshot(recoveryIdentity)).toBeNull()
    },
  )

  it('does not apply a legacy receipt if initialization occurs during the hotkey refresh', async () => {
    useAppStore.getState().updateConfig({ theme: 'dark' })
    vi.mocked(updateConfig).mockResolvedValueOnce({
      config: useAppStore.getState().config,
      savedRevision: 6,
      inputActivation: 'active',
    })
    vi.mocked(getHotkeyRegistrationError).mockImplementationOnce(async () => {
      initialize()
      return null
    })
    expect(await saveDirtyConfig()).toBe(false)
    expect(useAppStore.getState().configCommit?.revision).toBe(5)
    expect(useAppStore.getState().savedConfig?.theme).toBe('dark')
  })
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

it('does not supersede an in-flight valid save when a later draft fails preflight', async () => {
  useAppStore.getState().updateConfig({ theme: 'dark', max_recording_seconds: 30 })
  const submitted = useAppStore.getState().config
  const persisted = { ...submitted, max_recording_seconds: 600 }
  let finishSave: (() => void) | undefined
  vi.mocked(updateConfig).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finishSave = () =>
          resolve({ config: persisted, savedRevision: 7, inputActivation: 'pending' })
      }),
  )
  const originalSave = saveDirtyConfig()
  const shortcut = { primary: 'K', modifiers: ['Ctrl'] }
  useAppStore.getState().updateConfig({
    hotkeys: {
      ...submitted.hotkeys,
      dictation: shortcut,
      dictationBindings: [shortcut],
      ask: shortcut,
      askBindings: [shortcut],
    },
  })
  expect(await saveDirtyConfig()).toBe(false)
  expect(updateConfig).toHaveBeenCalledOnce()
  finishSave?.()
  expect(await originalSave).toBe(true)
  expect(useAppStore.getState().savedConfig).toEqual(persisted)
  expect(useAppStore.getState().config.hotkeys.dictation).toEqual(shortcut)
  expect(useAppStore.getState().config.hotkeys.ask).toEqual(shortcut)
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
