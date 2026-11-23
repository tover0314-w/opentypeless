import { describe, expect, it } from 'vitest'
import { useAppStore, type AppConfig } from '../../stores/appStore'
import {
  APP_CONFIG_FIELD_GROUPS,
  APP_CONFIG_FIELD_POLICY,
  mergeAppConfigDraft,
} from '../appConfigMergePolicy'
import { mergeConfigDraft } from '../configCommit'

function configuration(): AppConfig {
  return {
    ...structuredClone(useAppStore.getInitialState().config),
    managed_stt_capability_state: null,
  }
}

describe('application configuration merge policy', () => {
  it('adopts a native scene switch without overwriting an unrelated model draft', () => {
    const baseline = configuration()
    const draft = { ...baseline, llm_model: 'synthetic-draft-model' }
    const persisted: AppConfig = {
      ...baseline,
      active_scene: {
        id: 'builtin_meeting_notes',
        source: 'builtin',
        name: 'Meeting notes',
        prompt_template: 'Synthetic prompt',
      },
    }
    const merged = mergeAppConfigDraft(baseline, draft, persisted)
    expect(merged.active_scene).toEqual(persisted.active_scene)
    expect(merged.llm_model).toBe('synthetic-draft-model')
    expect(baseline.active_scene).toBeNull()
  })

  it('accepts a legacy configuration with absent native capability without restoring draft state', () => {
    const baseline = structuredClone(useAppStore.getInitialState().config)
    delete baseline.managed_stt_capability_state
    const draft = {
      ...baseline,
      theme: 'dark' as const,
      managed_stt_capability_state: { source: 'untrusted-draft' },
    }
    const persisted = { ...baseline, microphone_device: 'new-device' }
    const merged = mergeAppConfigDraft(baseline, draft, persisted)
    expect(merged.theme).toBe('dark')
    expect(merged.microphone_device).toBe('new-device')
    expect(merged.managed_stt_capability_state).toBeNull()
    expect(Object.prototype.hasOwnProperty.call(baseline, 'managed_stt_capability_state')).toBe(
      false,
    )
  })

  it('adopts submitted shortcut normalization while retaining a newer local Ask edit', () => {
    const submitted = configuration()
    const localAsk = { primary: 'J', modifiers: ['Ctrl'] }
    const draft: AppConfig = {
      ...submitted,
      ask_hotkey: 'Ctrl+J',
      hotkeys: { ...submitted.hotkeys, ask: localAsk, askBindings: [localAsk] },
    }
    const persisted: AppConfig = {
      ...submitted,
      hotkey_mode: 'hold',
      hotkeys: { ...submitted.hotkeys, dictationMode: 'hold' },
    }
    const merged = mergeAppConfigDraft(submitted, draft, persisted)
    expect(merged.hotkey_mode).toBe('hold')
    expect(merged.hotkeys.dictationMode).toBe('hold')
    expect(merged.hotkeys.ask).toEqual(localAsk)
    expect(merged.ask_hotkey).toBe('Ctrl+J')
  })

  it('retains local dictation edits while accepting an untouched remote Ask role', () => {
    const baseline = configuration()
    const localBinding = { primary: 'K', modifiers: ['Ctrl'] }
    const remoteBinding = { primary: 'J', modifiers: ['Ctrl'] }
    const draft: AppConfig = {
      ...baseline,
      hotkey: 'Ctrl+K',
      hotkeys: {
        ...baseline.hotkeys,
        dictation: localBinding,
        dictationBindings: [localBinding],
      },
    }
    const persisted: AppConfig = {
      ...baseline,
      ask_hotkey: 'Ctrl+J',
      hotkeys: {
        ...baseline.hotkeys,
        ask: remoteBinding,
        askBindings: [remoteBinding],
      },
    }
    const merged = mergeAppConfigDraft(baseline, draft, persisted)
    expect(merged.hotkeys.dictation).toEqual(localBinding)
    expect(merged.hotkeys.ask).toEqual(remoteBinding)
    expect(merged.hotkey).toBe('Ctrl+K')
    expect(merged.ask_hotkey).toBe('Ctrl+J')
    merged.hotkeys.ask!.modifiers.push('Shift')
    expect(persisted.hotkeys.ask!.modifiers).toEqual(['Ctrl'])
  })

  it('classifies every configuration field exactly once including native capability state', () => {
    const config = configuration()
    const fields = APP_CONFIG_FIELD_GROUPS.flatMap((group) => group.fields)
    expect([...fields].sort()).toEqual(Object.keys(config).sort())
    expect(new Set(fields).size).toBe(fields.length)
    expect(Object.keys(APP_CONFIG_FIELD_POLICY).sort()).toEqual(Object.keys(config).sort())
  })

  it('adopts native limits and capabilities without dropping editable recording intent', () => {
    const baseline = configuration()
    const draft: AppConfig = {
      ...baseline,
      recording_limit_mode: 'custom',
      custom_recording_limit_seconds: 300,
      max_recording_seconds: 999,
      managed_stt_capability_state: { source: 'untrusted-draft' },
    }
    const persisted: AppConfig = {
      ...baseline,
      max_recording_seconds: 600,
      managed_stt_capability_state: { source: 'synthetic-native' },
    }
    const merged = mergeConfigDraft(baseline, draft, persisted, APP_CONFIG_FIELD_GROUPS)
    expect(merged.recording_limit_mode).toBe('custom')
    expect(merged.custom_recording_limit_seconds).toBe(300)
    expect(merged.max_recording_seconds).toBe(600)
    expect(merged.managed_stt_capability_state).toEqual({ source: 'synthetic-native' })
  })

  it('keeps translation and legacy target fields together', () => {
    const baseline = configuration()
    const draft: AppConfig = {
      ...baseline,
      translation: { targets: ['en', 'ja'], active_target: 'ja' },
      target_lang: 'ja',
    }
    const persisted: AppConfig = {
      ...baseline,
      translation: { targets: ['en', 'fr'], active_target: 'fr' },
      target_lang: 'fr',
    }
    const merged = mergeConfigDraft(baseline, draft, persisted, APP_CONFIG_FIELD_GROUPS)
    expect(merged.translation).toEqual(draft.translation)
    expect(merged.target_lang).toBe('ja')
  })

  it('cannot resurrect draft credentials from a sanitized committed configuration', () => {
    const baseline = configuration()
    const draft: AppConfig = {
      ...baseline,
      stt_api_key: 'synthetic-stt',
      stt_custom_api_key: 'synthetic-custom',
      llm_api_key: 'synthetic-llm',
    }
    const merged = mergeConfigDraft(baseline, draft, baseline, APP_CONFIG_FIELD_GROUPS)
    expect(merged.stt_api_key).toBe('')
    expect(merged.stt_custom_api_key).toBe('')
    expect(merged.llm_api_key).toBe('')
  })

  it('keeps typed shortcuts and their legacy mode together', () => {
    const baseline = configuration()
    const draft: AppConfig = {
      ...baseline,
      hotkey: 'Ctrl+K',
      hotkey_mode: 'hold',
      hotkeys: {
        ...baseline.hotkeys,
        dictation: { primary: 'K', modifiers: ['Ctrl'] },
        dictationBindings: [{ primary: 'K', modifiers: ['Ctrl'] }],
        dictationMode: 'hold',
      },
    }
    const persisted: AppConfig = {
      ...baseline,
      hotkey_mode: 'toggle',
      hotkeys: { ...baseline.hotkeys, dictationMode: 'toggle' },
    }
    const merged = mergeConfigDraft(baseline, draft, persisted, APP_CONFIG_FIELD_GROUPS)
    expect(merged.hotkeys).toEqual(draft.hotkeys)
    expect(merged.hotkey).toBe('Ctrl+K')
    expect(merged.hotkey_mode).toBe('hold')
  })

  it('accepts normalized editable intent when the supplied baseline is the submitted draft', () => {
    const submitted: AppConfig = {
      ...configuration(),
      recording_limit_mode: 'custom',
      custom_recording_limit_seconds: 900,
    }
    const persisted: AppConfig = {
      ...submitted,
      custom_recording_limit_seconds: 600,
      max_recording_seconds: 600,
    }
    const merged = mergeConfigDraft(submitted, submitted, persisted, APP_CONFIG_FIELD_GROUPS)
    expect(merged.custom_recording_limit_seconds).toBe(600)
    expect(merged.max_recording_seconds).toBe(600)
  })
})
