import { beforeEach, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../../stores/appStore'
import { pendingConfigSaves } from '../configSaveRegistry'
import { saveOnboardingConfig } from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
const configEpoch = '11111111-1111-4111-8111-111111111111'

beforeEach(() => {
  vi.mocked(invoke).mockReset()
  useAppStore.setState(useAppStore.getInitialState())
  const config = {
    ...structuredClone(useAppStore.getState().config),
    managed_stt_capability_state: null,
  }
  useAppStore.getState().initializeConfigCommit({ configEpoch, revision: 1, saveId: null, config })
})

it('rejects conflicting onboarding shortcuts before saving any provider credentials', async () => {
  const config = structuredClone(useAppStore.getState().config)
  config.stt_provider = 'openai-whisper'
  config.stt_api_key = 'synthetic-stt-secret'
  config.llm_api_key = 'synthetic-llm-secret'
  config.ask_hotkey = config.hotkey
  config.hotkeys.ask = config.hotkeys.dictation
  config.hotkeys.askBindings = [...config.hotkeys.dictationBindings]
  await expect(saveOnboardingConfig(config)).rejects.toThrow('Invalid shortcut configuration')
  expect(invoke).not.toHaveBeenCalled()
})

it('registers a redacted submission and accepts its owned receipt without unrelated readback', async () => {
  const submitted = { ...useAppStore.getState().config, custom_recording_limit_seconds: 999 }
  let identity = ''
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    expect(command).toBe('update_config')
    const input = args as { config: typeof submitted; saveId: string }
    identity = input.saveId
    expect(identity).toMatch(/^[0-9a-f-]{36}$/i)
    expect(pendingConfigSaves.snapshot(identity)).toEqual(submitted)
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const config = { ...input.config, custom_recording_limit_seconds: 600 }
    return {
      config,
      savedRevision: 2,
      inputActivation: 'active',
      commit: { configEpoch, revision: 2, saveId: identity, config },
    } as never
  })
  const saved = await saveOnboardingConfig(submitted)
  expect(saved.custom_recording_limit_seconds).toBe(600)
  expect(useAppStore.getState().config.theme).toBe('dark')
  expect(useAppStore.getState().configCommit?.revision).toBe(2)
  expect(pendingConfigSaves.snapshot(identity)).toBeNull()
  expect(invoke).toHaveBeenCalledOnce()
})

it('rejects stale onboarding success rather than returning a later unrelated configuration', async () => {
  const submitted = useAppStore.getState().config
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    if (command === 'get_config') return submitted as never
    const input = args as { saveId: string }
    useAppStore.getState().applyConfigCommit({
      configEpoch,
      revision: 3,
      saveId: null,
      config: { ...submitted, microphone_device: 'newer-device' },
    })
    return {
      config: submitted,
      savedRevision: 2,
      inputActivation: 'active',
      commit: { configEpoch, revision: 2, saveId: input.saveId, config: submitted },
    } as never
  })
  await expect(saveOnboardingConfig(submitted)).rejects.toThrow()
  expect(useAppStore.getState().savedConfig?.microphone_device).toBe('newer-device')
  expect(vi.mocked(invoke).mock.calls.some(([command]) => command === 'get_config')).toBe(false)
})

it('does not accept a legacy save result into an initialized stream', async () => {
  vi.mocked(invoke).mockResolvedValue(undefined)
  await expect(saveOnboardingConfig(useAppStore.getState().config)).rejects.toThrow()
  expect(useAppStore.getState().configCommit?.revision).toBe(1)
})

it('rejects a legacy readback if initialization finishes while the old save is pending', async () => {
  const snapshot = useAppStore.getState().configCommit!
  useAppStore.setState({ configCommit: null })
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === 'update_config') {
      useAppStore.getState().initializeConfigCommit(snapshot)
      return undefined as never
    }
    return snapshot.config as never
  })
  await expect(saveOnboardingConfig(snapshot.config)).rejects.toThrow()
  expect(vi.mocked(invoke).mock.calls.some(([command]) => command === 'get_config')).toBe(false)
})

it.each(['saveId', 'revision', 'secret'] as const)(
  'rejects a malformed owned receipt %s and retires its submission',
  async (invalid) => {
    let identity = ''
    const baseline = useAppStore.getState().savedConfig
    vi.mocked(invoke).mockImplementation(async (_command, args) => {
      const input = args as { config: typeof baseline; saveId: string }
      identity = input.saveId
      const config = { ...input.config, ...(invalid === 'secret' ? { llm_api_key: 'secret' } : {}) }
      return {
        config,
        savedRevision: 2,
        inputActivation: 'active',
        commit: {
          configEpoch,
          revision: invalid === 'revision' ? 3 : 2,
          saveId: invalid === 'saveId' ? '22222222-2222-4222-8222-222222222222' : identity,
          config,
        },
      } as never
    })
    await expect(saveOnboardingConfig(useAppStore.getState().config)).rejects.toThrow()
    expect(useAppStore.getState().savedConfig).toBe(baseline)
    expect(pendingConfigSaves.snapshot(identity)).toBeNull()
  },
)
