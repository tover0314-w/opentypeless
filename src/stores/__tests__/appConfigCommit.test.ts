import { beforeEach, describe, expect, it } from 'vitest'
import { useAppStore, type AppConfig } from '../appStore'
import type { ConfigCommit } from '../../lib/configCommit'
import { pendingConfigSaves } from '../../lib/configSaveRegistry'
import { isAppConfigSnapshot } from '../../lib/appConfigSnapshot'

const configEpoch = '11111111-1111-4111-8111-111111111111'
const saveId = '22222222-2222-4222-8222-222222222222'

function commit(revision: number, config: AppConfig): ConfigCommit<AppConfig> {
  return { configEpoch, revision, saveId: null, config }
}

function configuration(): AppConfig {
  return {
    ...structuredClone(useAppStore.getInitialState().config),
    managed_stt_capability_state: null,
  }
}

describe('store configuration commits', () => {
  it('characterizes a local dictation draft colliding with a valid remote Ask binding', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(1, baseline))
    const localBinding = { primary: 'K', modifiers: ['Ctrl'] }
    useAppStore.getState().updateConfig({
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
    expect(isAppConfigSnapshot(useAppStore.getState().config)).toBe(true)
    expect(isAppConfigSnapshot(remote)).toBe(true)
    expect(useAppStore.getState().applyConfigCommit(commit(2, remote))).toBe('accepted')
    const current = useAppStore.getState()
    expect(current.savedConfig?.hotkeys.dictation).toEqual(baseline.hotkeys.dictation)
    expect(current.savedConfig?.hotkeys.ask).toEqual(localBinding)
    expect(current.config.hotkeys.dictation).toEqual(localBinding)
    expect(current.config.hotkeys.ask).toEqual(localBinding)
    expect(current.configCommit?.revision).toBe(2)
    expect(isAppConfigSnapshot(current.config)).toBe(false)
    expect(isAppConfigSnapshot(current.savedConfig)).toBe(true)
  })

  beforeEach(() => {
    useAppStore.setState(useAppStore.getInitialState())
    pendingConfigSaves.retire(saveId)
  })

  it('preserves edits made before the first handshake without adopting draft native limits', () => {
    const persisted = { ...configuration(), theme: 'light' as const, max_recording_seconds: 600 }
    useAppStore
      .getState()
      .updateConfig({ microphone_device: 'local-device', max_recording_seconds: 999 })
    expect(useAppStore.getState().initializeConfigCommit(commit(1, persisted))).toBe(true)
    expect(useAppStore.getState().config.microphone_device).toBe('local-device')
    expect(useAppStore.getState().config.theme).toBe('light')
    expect(useAppStore.getState().config.max_recording_seconds).toBe(600)
  })

  it('does not accept unversioned patches after establishing the version stream', () => {
    useAppStore.getState().initializeConfigCommit(commit(1, configuration()))
    const previous = useAppStore.getState()
    useAppStore.getState().applyPersistedConfigPatch({ theme: 'dark' })
    expect(useAppStore.getState()).toBe(previous)
  })

  it('accepts a same-version handshake read without a saveId after an owned event', () => {
    const config = configuration()
    const owned = { ...commit(1, config), saveId }
    useAppStore.getState().initializeConfigCommit(owned)
    const previous = useAppStore.getState()
    expect(useAppStore.getState().initializeConfigCommit(commit(1, config))).toBe(true)
    expect(useAppStore.getState()).toBe(previous)
  })

  it('ignores an anonymous echo without losing the submitted identity or newer draft', () => {
    const config = configuration()
    const owned = { ...commit(1, config), saveId }
    useAppStore.getState().initializeConfigCommit(owned)
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const previous = useAppStore.getState()
    expect(useAppStore.getState().applyConfigCommit(commit(1, config))).toBe('duplicate')
    expect(useAppStore.getState()).toBe(previous)
    expect(useAppStore.getState().configCommit?.saveId).toBe(saveId)
    expect(useAppStore.getState().applyConfigCommit(owned)).toBe('duplicate')
    expect(useAppStore.getState().config.theme).toBe('dark')
  })

  it('requires an explicit initialization handshake before accepting events', () => {
    expect(useAppStore.getState().applyConfigCommit(commit(1, configuration()))).toBe(
      'uninitialized',
    )
    expect(useAppStore.getState().savedConfig).toBeNull()
  })

  it('rejects stale, duplicate, conflicting and wrong-process events without rewriting state', () => {
    const baseline = configuration()
    expect(useAppStore.getState().initializeConfigCommit(commit(10, baseline))).toBe(true)
    const newer = { ...baseline, theme: 'dark' as const }
    expect(useAppStore.getState().applyConfigCommit(commit(12, newer))).toBe('accepted')
    const accepted = useAppStore.getState()
    expect(accepted.config.theme).toBe('dark')
    expect(accepted.savedConfig).toEqual(newer)
    expect(accepted.configCommit?.revision).toBe(12)
    expect(useAppStore.getState().applyConfigCommit(commit(11, baseline))).toBe('stale')
    expect(useAppStore.getState().applyConfigCommit(commit(12, newer))).toBe('duplicate')
    expect(useAppStore.getState().applyConfigCommit(commit(12, baseline))).toBe('conflict')
    expect(
      useAppStore.getState().applyConfigCommit({
        ...commit(99, baseline),
        configEpoch: '33333333-3333-4333-8333-333333333333',
      }),
    ).toBe('wrongEpoch')
    expect(useAppStore.getState()).toBe(accepted)
  })

  it('merges untouched remote fields while preserving newer editable drafts and native authority', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(1, baseline))
    useAppStore.getState().updateConfig({ theme: 'dark', max_recording_seconds: 999 })
    const persisted = {
      ...baseline,
      theme: 'light' as const,
      microphone_device: 'synthetic-device',
      max_recording_seconds: 600,
    }
    expect(useAppStore.getState().applyConfigCommit(commit(2, persisted))).toBe('accepted')
    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(useAppStore.getState().config.microphone_device).toBe('synthetic-device')
    expect(useAppStore.getState().config.max_recording_seconds).toBe(600)
    expect(useAppStore.getState().savedConfig).toEqual(persisted)
  })

  it('uses the submitted draft for owned normalization but preserves edits made after submission', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(1, baseline))
    useAppStore.getState().updateConfig({ custom_recording_limit_seconds: 999 })
    const submitted = structuredClone(useAppStore.getState().config)
    pendingConfigSaves.register(saveId, submitted)
    try {
      useAppStore.getState().updateConfig({ theme: 'dark' })
      const persisted = { ...submitted, custom_recording_limit_seconds: 600 }
      expect(useAppStore.getState().applyConfigCommit({ ...commit(2, persisted), saveId })).toBe(
        'accepted',
      )
      expect(useAppStore.getState().config.custom_recording_limit_seconds).toBe(600)
      expect(useAppStore.getState().config.theme).toBe('dark')
      expect(useAppStore.getState().savedConfig?.theme).toBe('system')
    } finally {
      pendingConfigSaves.retire(saveId)
    }
  })

  it('keeps the watermark across unrelated store changes and rejects malformed snapshots', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(3, baseline))
    useAppStore.getState().setPipelineState('recording')
    const previous = useAppStore.getState()
    expect(
      useAppStore.getState().applyConfigCommit(commit(4, { ...baseline, llm_api_key: 'secret' })),
    ).toBe('invalid')
    expect(useAppStore.getState()).toBe(previous)
    expect(useAppStore.getState().configCommit?.revision).toBe(3)
  })

  it('accepts a new epoch only through reinitialization and preserves existing drafts', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(4, baseline))
    useAppStore.getState().updateConfig({ theme: 'dark' })
    const restarted = {
      ...commit(0, { ...baseline, microphone_device: 'restarted-device' }),
      configEpoch: '33333333-3333-4333-8333-333333333333',
    }
    expect(useAppStore.getState().initializeConfigCommit(restarted)).toBe(true)
    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(useAppStore.getState().config.microphone_device).toBe('restarted-device')
    expect(useAppStore.getState().applyConfigCommit(commit(100, baseline))).toBe('wrongEpoch')
  })

  it('does not let a delayed initialization read move the current epoch backwards', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(5, baseline))
    const previous = useAppStore.getState()
    expect(useAppStore.getState().initializeConfigCommit(commit(4, baseline))).toBe(false)
    expect(
      useAppStore.getState().initializeConfigCommit(commit(5, { ...baseline, theme: 'dark' })),
    ).toBe(false)
    expect(useAppStore.getState()).toBe(previous)
  })

  it('owns separate snapshots for the watermark, saved baseline and editable draft', () => {
    const initial = commit(1, configuration())
    useAppStore.getState().initializeConfigCommit(initial)
    initial.config.translation.targets.push('ja')
    expect(useAppStore.getState().configCommit?.config.translation.targets).not.toContain('ja')
    expect(useAppStore.getState().savedConfig?.translation.targets).not.toContain('ja')
    expect(useAppStore.getState().config.translation.targets).not.toContain('ja')
    const draft = structuredClone(useAppStore.getState().config)
    draft.translation = { targets: ['en', 'ja'], active_target: 'ja' }
    draft.target_lang = 'ja'
    useAppStore.getState().setConfig(draft)
    expect(useAppStore.getState().configCommit?.config.translation.targets).not.toContain('ja')
    expect(useAppStore.getState().savedConfig?.translation.targets).not.toContain('ja')
  })

  it('does not advance the watermark if the existing local draft cannot be safely merged', () => {
    const baseline = configuration()
    useAppStore.getState().initializeConfigCommit(commit(1, baseline))
    useAppStore.setState({
      config: { ...baseline, unclassifiedField: true } as AppConfig,
    })
    const previous = useAppStore.getState()
    expect(useAppStore.getState().applyConfigCommit(commit(2, baseline))).toBe('invalid')
    expect(useAppStore.getState()).toBe(previous)
    useAppStore.setState({ config: baseline })
    expect(useAppStore.getState().applyConfigCommit(commit(2, baseline))).toBe('accepted')
  })
})
