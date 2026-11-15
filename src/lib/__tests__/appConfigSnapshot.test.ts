import { expect, it } from 'vitest'
import { useAppStore } from '../../stores/appStore'
import { isAppConfigSnapshot } from '../appConfigSnapshot'
import { LLM_PROVIDERS, STT_PROVIDERS, TARGET_LANGUAGES } from '../constants'

it('rejects invalid bindings and semantic duplicates without dropping supported native keys', () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  for (const binding of [
    { primary: '', modifiers: [] },
    { primary: 'UnknownKey', modifiers: [] },
    { primary: 'J', modifiers: ['UnknownModifier'] },
    { primary: 'J', modifiers: ['Ctrl', 'Ctrl'] },
    { primary: 'J', modifiers: ['Command', 'Super'] },
    { primary: 'J', modifiers: ['Alt', 'Option'] },
  ]) {
    expect(
      isAppConfigSnapshot({ ...config, hotkeys: { ...config.hotkeys, editSelection: binding } }),
    ).toBe(false)
  }
  for (const binding of [
    { primary: 'Fn', modifiers: [] },
    { primary: 'RightAlt', modifiers: [] },
    { primary: 'Space', modifiers: ['Fn'] },
    { primary: 'LeftShift', modifiers: ['RightAlt'] },
    { primary: 'J', modifiers: ['Ctrl', 'Command'] },
  ]) {
    expect(
      isAppConfigSnapshot({ ...config, hotkeys: { ...config.hotkeys, editSelection: binding } }),
    ).toBe(true)
  }
  const first = { primary: 'J', modifiers: ['Command'] }
  const alias = { primary: 'J', modifiers: ['Super'] }
  expect(
    isAppConfigSnapshot({
      ...config,
      hotkeys: {
        ...config.hotkeys,
        dictationBindings: [config.hotkeys.dictation, first, alias],
      },
    }),
  ).toBe(false)
})

it('rejects contradictory shortcut primaries, lists and legacy fields', () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  const different = { primary: 'J', modifiers: ['Ctrl'] }
  for (const value of [
    { ...config, hotkey: 'Ctrl+J' },
    { ...config, ask_hotkey: 'Ctrl+J' },
    { ...config, hotkey_mode: config.hotkey_mode === 'hold' ? 'toggle' : 'hold' },
    { ...config, hotkeys: { ...config.hotkeys, dictation: different } },
    { ...config, hotkeys: { ...config.hotkeys, dictationBindings: [] } },
    { ...config, hotkeys: { ...config.hotkeys, ask: null } },
    {
      ...config,
      hotkeys: {
        ...config.hotkeys,
        dictationBindings: [config.hotkeys.dictation, config.hotkeys.dictation],
      },
    },
  ]) {
    expect(isAppConfigSnapshot(value)).toBe(false)
  }
  expect(
    isAppConfigSnapshot({
      ...config,
      ask_hotkey: '',
      hotkeys: { ...config.hotkeys, ask: null, askBindings: [] },
    }),
  ).toBe(true)
  expect(
    isAppConfigSnapshot({
      ...config,
      hotkeys: {
        ...config.hotkeys,
        dictationBindings: [config.hotkeys.dictation, different],
      },
    }),
  ).toBe(true)
})

it('requires a consistent supported translation target list', () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  for (const language of TARGET_LANGUAGES) {
    expect(
      isAppConfigSnapshot({
        ...config,
        target_lang: language.value,
        translation: { targets: [language.value], active_target: language.value },
      }),
    ).toBe(true)
  }
  for (const translation of [
    { targets: [], active_target: 'en' },
    { targets: ['en', 'en'], active_target: 'en' },
    { targets: ['en'], active_target: 'fr' },
    { targets: ['unsupported'], active_target: 'unsupported' },
    { targets: ['en', 'zh', 'ja', 'ko', 'fr', 'de'], active_target: 'en' },
    { targets: ['fr'], active_target: 'fr' },
  ]) {
    expect(isAppConfigSnapshot({ ...config, target_lang: 'en', translation })).toBe(false)
  }
})

it('retains every supported provider while rejecting unknown enum values', () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  for (const provider of STT_PROVIDERS) {
    expect(isAppConfigSnapshot({ ...config, stt_provider: provider.value })).toBe(true)
  }
  for (const provider of LLM_PROVIDERS) {
    expect(isAppConfigSnapshot({ ...config, llm_provider: provider.value })).toBe(true)
  }
  for (const field of [
    'stt_provider',
    'llm_provider',
    'hotkey_mode',
    'output_mode',
    'insertion_strategy',
    'paste_shortcut',
    'windows_sendinput_newline_mode',
    'theme',
    'polish_style',
    'polish_chinese_script',
    'recording_limit_mode',
    'stt_custom_preset',
    'stt_aliyun_qwen_region',
  ]) {
    expect(isAppConfigSnapshot({ ...config, [field]: 'unsupported' })).toBe(false)
  }
})

it('accepts complete redacted snapshots and the optional native capability', () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  expect(isAppConfigSnapshot(config)).toBe(true)
  expect(isAppConfigSnapshot({ ...config, managed_stt_capability_state: null })).toBe(true)
  expect(isAppConfigSnapshot({ ...config, managed_stt_capability_state: { version: 2 } })).toBe(
    true,
  )
})

it('rejects incomplete, extra, mistyped, unsafe and secret-bearing snapshots', () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  const incomplete = { ...config } as Record<string, unknown>
  delete incomplete.theme
  for (const value of [
    null,
    [],
    incomplete,
    { ...config, unexpected: true },
    { ...config, history_enabled: 'false' },
    { ...config, history_retention_days: -1 },
    { ...config, max_recording_seconds: Number.MAX_SAFE_INTEGER + 1 },
    { ...config, stt_api_key: 'synthetic-secret' },
    { ...config, stt_custom_api_key: 'synthetic-secret' },
    { ...config, llm_api_key: 'synthetic-secret' },
    { ...config, translation: { targets: [1], active_target: 'en' } },
    { ...config, hotkeys: { ...config.hotkeys, ask: { primary: 1, modifiers: [] } } },
    { ...config, voice_routing_flags: { ...config.voice_routing_flags, search: 'true' } },
    { ...config, custom_scenes: [{ id: 'incomplete' }] },
    { ...config, language_variants: { zh: 123 } },
  ]) {
    expect(isAppConfigSnapshot(value)).toBe(false)
  }
})
