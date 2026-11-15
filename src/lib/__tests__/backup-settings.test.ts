import { afterEach, describe, expect, it } from 'vitest'
import type { AppConfig } from '../../stores/appStore'
import { useAppStore } from '../../stores/appStore'
import { createBackupSettings, mergeBackupSettings } from '../backup-settings'

it('restores legacy Chinese script preferences through the current language variants', () => {
  const current = {
    ...useAppStore.getInitialState().config,
    language_variants: { en: 'en-GB', zh: 'zh-HK' },
  }
  for (const [legacy, expected] of [
    ['simplified', 'zh-CN'],
    ['traditional', 'zh-TW'],
  ]) {
    const restored = mergeBackupSettings(current, { polish_chinese_script: legacy })
    expect(restored.language_variants).toEqual({ en: 'en-GB', zh: expected })
    expect(restored.polish_chinese_script).toBe('preserve')
    expect(current.language_variants.zh).toBe('zh-HK')
  }
})

it('keeps explicit backup variants authoritative over legacy script preferences', () => {
  const current = useAppStore.getInitialState().config
  for (const variants of [{}, { zh: 'zh-HK' }]) {
    const restored = mergeBackupSettings(current, {
      polish_chinese_script: 'simplified',
      language_variants: variants,
    })
    expect(restored.language_variants).toEqual(variants)
    expect(restored.polish_chinese_script).toBe('preserve')
  }
})

afterEach(() => useAppStore.setState(useAppStore.getInitialState()))

describe('createBackupSettings', () => {
  it('restores a custom polish style with its instructions', () => {
    const current = useAppStore.getInitialState().config
    const restored = mergeBackupSettings(current, {
      polish_style: 'custom',
      polish_custom_prompt: 'Keep short sentences.',
    })
    expect(restored.polish_style).toBe('custom')
    expect(restored.polish_custom_prompt).toBe('Keep short sentences.')
  })

  it.each([
    'api_key',
    'api-key',
    'apiKey',
    'access_token',
    'Authorization',
    '%61pi_key',
    'token',
    'password',
    'secret',
    'client_secret',
    'signature',
    'sig',
    'key',
    'API_KEY',
  ])('keeps endpoint credentials named %s local during backup and restore', (parameter) => {
    const endpoint = `https://example.com/v1?api-version=2026-01-01&${parameter}=private-secret`
    const current = {
      ...useAppStore.getInitialState().config,
      llm_base_url: 'http://localhost:11434/v1',
      stt_custom_base_url: 'http://localhost:8000/v1',
    }
    const draft = { ...current, llm_base_url: endpoint, stt_custom_base_url: endpoint }
    const backup = createBackupSettings(draft)
    expect(backup.llm_base_url).toBeUndefined()
    expect(backup.stt_custom_base_url).toBeUndefined()
    expect(JSON.stringify(backup)).not.toContain('private-secret')
    expect(draft.llm_base_url).toBe(endpoint)
    const restored = mergeBackupSettings(current, {
      llm_base_url: endpoint,
      stt_custom_base_url: endpoint,
    })
    expect(restored.llm_base_url).toBe(current.llm_base_url)
    expect(restored.stt_custom_base_url).toBe(current.stt_custom_base_url)
  })

  it('rejects a credential parameter even when a harmless parameter precedes it twice', () => {
    const current = useAppStore.getInitialState().config
    const endpoint = 'https://example.com/v1?tenant=one&tenant=two&api_key=&api_key=private-secret'
    const backup = createBackupSettings({ ...current, llm_base_url: endpoint })
    expect(backup.llm_base_url).toBeUndefined()
    expect(mergeBackupSettings(current, { llm_base_url: endpoint }).llm_base_url).toBe(
      current.llm_base_url,
    )
  })

  it.each([
    'https://person:private-password@example.com/v1',
    'https://person@example.com/v1',
    'https://example.com/v1#private-fragment',
    'file:///private/config',
  ])('keeps unsafe endpoint %s out of backups and restores', (endpoint) => {
    const current = useAppStore.getInitialState().config
    const settings = createBackupSettings({
      ...current,
      llm_base_url: endpoint,
      stt_custom_base_url: endpoint,
    })
    expect(settings.llm_base_url).toBeUndefined()
    expect(settings.stt_custom_base_url).toBeUndefined()
    const restored = mergeBackupSettings(current, {
      llm_base_url: endpoint,
      stt_custom_base_url: endpoint,
    })
    expect(restored.llm_base_url).toBe(current.llm_base_url)
    expect(restored.stt_custom_base_url).toBe(current.stt_custom_base_url)
  })

  it('preserves valid self-hosted endpoints and their query parameters', () => {
    const endpoint = 'http://localhost:8000/v1?tenant=example&api-version=2026-01-01'
    const current = { ...useAppStore.getInitialState().config, stt_custom_base_url: endpoint }
    const backup = createBackupSettings(current)
    expect(backup.stt_custom_base_url).toBe(endpoint)
    expect(
      mergeBackupSettings(useAppStore.getInitialState().config, backup).stt_custom_base_url,
    ).toBe(endpoint)
  })

  it('preserves complete Unicode characters at scene metadata limits', () => {
    const current = useAppStore.getInitialState().config
    const name = `${'a'.repeat(79)}😀`
    const prompt = `${'b'.repeat(3999)}𠮷`
    const sceneId = `${'c'.repeat(119)}😀`
    const merged = mergeBackupSettings(current, {
      active_scene: {
        id: 'unicode',
        name: `${name}tail`,
        prompt_template: `${prompt}tail`,
        source: 'custom',
      },
      system_scene_overrides: [{ id: 'system_email', prompt_template: `${prompt}tail` }],
      family_scene_assignments: [{ family: 'email', scene_id: `${sceneId}tail` }],
    })
    expect(merged.active_scene?.name).toBe(name)
    expect(merged.active_scene?.prompt_template).toBe(prompt)
    expect(merged.system_scene_overrides[0].prompt_template).toBe(prompt)
    expect(merged.family_scene_assignments[0].scene_id).toBe(sceneId)
  })

  it('keeps current provider and mode values when backup enums are unknown', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      stt_provider: 'unknown-service',
      llm_provider: 'unknown-model-service',
      output_mode: 'execute',
      insertion_strategy: 'shell',
      theme: 'invisible',
      polish_style: 'invent',
      hotkey_mode: 'invalid',
      stt_aliyun_qwen_region: 'unknown-region',
      paste_shortcut: 'unknown',
      windows_sendinput_newline_mode: 'unknown',
      polish_chinese_script: 'unknown',
      recording_limit_mode: 'unknown',
      ui_language: 'unknown',
    })
    for (const key of [
      'stt_provider',
      'llm_provider',
      'output_mode',
      'insertion_strategy',
      'theme',
      'polish_style',
      'hotkey_mode',
      'stt_aliyun_qwen_region',
      'paste_shortcut',
      'windows_sendinput_newline_mode',
      'polish_chinese_script',
      'recording_limit_mode',
      'ui_language',
    ] as const) {
      expect(merged[key], key).toBe(current[key])
    }
  })

  it.each([-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])(
    'keeps current numeric limits when the backup value is invalid: %s',
    (invalid) => {
      const current = useAppStore.getInitialState().config
      const merged = mergeBackupSettings(current, {
        history_retention_days: invalid,
        history_max_entries: invalid,
        custom_recording_limit_seconds: invalid,
        max_recording_seconds: invalid,
      })
      expect(merged.history_retention_days).toBe(current.history_retention_days)
      expect(merged.history_max_entries).toBe(current.history_max_entries)
      expect(merged.custom_recording_limit_seconds).toBe(current.custom_recording_limit_seconds)
      expect(merged.max_recording_seconds).toBe(current.max_recording_seconds)
    },
  )

  it('preserves zero as the unlimited history retention choice', () => {
    const current = { ...useAppStore.getInitialState().config, history_retention_days: 7 }
    expect(mergeBackupSettings(current, { history_retention_days: 0 }).history_retention_days).toBe(
      0,
    )
  })

  it('restores a complete safe backup through the real config store without losing original features', () => {
    const defaults = useAppStore.getInitialState().config
    const bindings = ['J', 'K', 'L', 'M'].map((primary) => ({
      primary,
      modifiers: ['Ctrl', 'Shift'],
    }))
    const scene = {
      id: 'custom-notes',
      name: 'Notes',
      description: 'Personal notes style',
      prompt_template: 'Keep my wording.',
      created_at: '2026-11-01T00:00:00Z',
      updated_at: '2026-11-01T00:00:00Z',
    }
    const source: AppConfig = {
      ...defaults,
      stt_provider: 'custom-whisper',
      stt_custom_base_url: 'http://localhost:8000/v1',
      stt_custom_model: 'local-model',
      llm_provider: 'ollama',
      llm_base_url: 'http://localhost:11434/v1',
      llm_model: 'local-llm',
      language_variants: { en: 'en-GB', zh: 'zh-TW' },
      translation: { targets: ['ja', 'fr'], active_target: 'fr' },
      target_lang: 'fr',
      custom_scenes: [scene],
      active_scene: {
        id: scene.id,
        source: 'custom',
        name: scene.name,
        prompt_template: scene.prompt_template,
      },
      family_scene_assignments: [{ family: 'document', scene_id: scene.id }],
      system_scene_overrides: [{ id: 'system_email', prompt_template: 'Use a warm tone.' }],
      hotkeys: {
        ...defaults.hotkeys,
        dictation: bindings[0],
        dictationBindings: bindings,
        ask: null,
        askBindings: [],
        pasteLast: { primary: 'V', modifiers: ['Ctrl', 'Shift'] },
      },
      save_recording_audio: false,
      auto_learn_dictionary: false,
      restore_clipboard_after_paste: false,
      interaction_sounds: false,
      mute_background_audio: false,
      show_in_dock: false,
    }
    const destination = {
      ...defaults,
      stt_api_key: 'device-stt-secret',
      stt_custom_api_key: 'device-custom-secret',
      llm_api_key: 'device-llm-secret',
      microphone_device: 'Device microphone',
      interaction_sounds: true,
      mute_background_audio: true,
      show_in_dock: true,
    }
    const backup = JSON.parse(JSON.stringify(createBackupSettings(source)))
    useAppStore.getState().setConfig(mergeBackupSettings(destination, backup))
    const restored = useAppStore.getState().config
    expect(restored.hotkeys.dictationBindings).toEqual(bindings)
    expect(restored.hotkey).toBe('Ctrl+Shift+J')
    expect(restored.hotkeys.askBindings).toEqual([])
    expect(restored.ask_hotkey).toBe('')
    expect(restored.translation).toEqual(source.translation)
    expect(restored.target_lang).toBe('fr')
    expect(restored.language_variants).toEqual(source.language_variants)
    expect(restored.custom_scenes).toEqual([scene])
    expect(restored.active_scene).toEqual(source.active_scene)
    expect(restored.family_scene_assignments).toEqual(source.family_scene_assignments)
    expect(restored.system_scene_overrides).toEqual(source.system_scene_overrides)
    expect(restored.stt_custom_model).toBe('local-model')
    expect(restored.llm_model).toBe('local-llm')
    expect(restored.save_recording_audio).toBe(false)
    expect(restored.auto_learn_dictionary).toBe(false)
    expect(restored.restore_clipboard_after_paste).toBe(false)
    expect(restored.interaction_sounds).toBe(false)
    expect(restored.mute_background_audio).toBe(false)
    expect(restored.show_in_dock).toBe(false)
    expect(restored.microphone_device).toBe('Device microphone')
    expect(restored.stt_api_key).toBe('device-stt-secret')
    expect(restored.stt_custom_api_key).toBe('device-custom-secret')
    expect(restored.llm_api_key).toBe('device-llm-secret')
  })

  it('ignores malformed active scenes and scene assignments', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      active_scene: { id: 42, source: 'unknown', name: [], prompt_template: null },
      family_scene_assignments: [null, { family: 'unknown', scene_id: 42 }],
      system_scene_overrides: [null, { id: 'unknown', prompt_template: [] }],
    })
    expect(merged.active_scene).toEqual(current.active_scene)
    expect(merged.family_scene_assignments).toEqual(current.family_scene_assignments)
    expect(merged.system_scene_overrides).toEqual(current.system_scene_overrides)
  })

  it('restores valid scene metadata without unknown fields', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      active_scene: {
        id: ' custom-one ',
        source: 'custom',
        name: ' One ',
        prompt_template: ' Clear ',
        privateField: 'ignored',
      },
      family_scene_assignments: [
        { family: 'email', scene_id: ' builtin_professional_email ', privateField: 'ignored' },
      ],
      system_scene_overrides: [
        { id: 'system_email', prompt_template: ' Clear ', privateField: 'ignored' },
      ],
    })
    expect(merged.active_scene).toEqual({
      id: 'custom-one',
      source: 'custom',
      name: 'One',
      prompt_template: 'Clear',
    })
    expect(merged.family_scene_assignments).toEqual([
      { family: 'email', scene_id: 'builtin_professional_email' },
    ])
    expect(merged.system_scene_overrides).toEqual([
      { id: 'system_email', prompt_template: 'Clear' },
    ])
  })

  it('preserves valid custom scene identifiers and content on a backup round trip', () => {
    const current = useAppStore.getInitialState().config
    const scene = {
      id: 'custom-existing',
      name: 'Existing',
      description: 'A custom writing style',
      prompt_template: 'Write clearly.',
      created_at: '2026-11-01T00:00:00Z',
      updated_at: '2026-11-01T00:00:00Z',
    }
    const configured = { ...current, custom_scenes: [scene] }
    expect(mergeBackupSettings(current, createBackupSettings(configured)).custom_scenes).toEqual([
      scene,
    ])
    expect(mergeBackupSettings(configured, { custom_scenes: [] }).custom_scenes).toEqual([])
  })

  it('keeps current scenes when the backup contains no valid scene records', () => {
    const scene = {
      id: 'custom-existing',
      name: 'Existing',
      description: '',
      prompt_template: 'Write clearly.',
      created_at: '2026-11-01T00:00:00Z',
      updated_at: '2026-11-01T00:00:00Z',
    }
    const current = { ...useAppStore.getInitialState().config, custom_scenes: [scene] }
    const merged = mergeBackupSettings(current, {
      custom_scenes: [null, { id: 'invalid', name: 42, prompt_template: [] }],
    })
    expect(merged.custom_scenes).toEqual([scene])
  })

  it('restores valid scenes through the existing bounded scene import rules', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      custom_scenes: [
        {
          id: 'custom-restored',
          name: ' Restored ',
          description: '',
          prompt_template: ` Clear\0${'x'.repeat(5000)} `,
          created_at: '2026-11-01T00:00:00Z',
          updated_at: '2026-11-01T00:00:00Z',
          privateField: 'ignored',
        },
      ],
    })
    expect(merged.custom_scenes[0].id).toBe('custom-restored')
    expect(merged.custom_scenes[0].name).toBe('Restored')
    expect(merged.custom_scenes[0].prompt_template).toHaveLength(4000)
    expect(merged.custom_scenes[0].prompt_template).not.toContain('\0')
    expect(merged.custom_scenes[0]).not.toHaveProperty('privateField')
  })

  it('preserves required and optional shortcuts when a backup binding is malformed', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      hotkeys: {
        dictation: { primary: 'Fn', modifiers: 'Ctrl' },
        dictationBindings: [null],
        ask: { primary: 42, modifiers: [] },
        pasteLast: { primary: 'UnknownKey', modifiers: [] },
      },
    })
    expect(merged.hotkeys).toEqual(current.hotkeys)
    expect(() => useAppStore.getState().setConfig(merged)).not.toThrow()
  })

  it('restores a partial shortcut backup without clearing unrelated voice bindings', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      hotkeys: { pasteLast: { primary: 'V', modifiers: ['Ctrl', 'Shift'] }, unknown: 'ignored' },
    })
    expect(merged.hotkeys).toEqual({
      ...current.hotkeys,
      pasteLast: { primary: 'V', modifiers: ['Ctrl', 'Shift'] },
    })
  })

  it('keeps all four voice bindings through a safe backup round trip', () => {
    const current = useAppStore.getInitialState().config
    const bindings = ['J', 'K', 'L', 'M'].map((primary) => ({
      primary,
      modifiers: ['Ctrl', 'Shift'],
    }))
    const configured = {
      ...current,
      hotkeys: { ...current.hotkeys, dictation: bindings[0], dictationBindings: bindings },
    }
    const merged = mergeBackupSettings(current, createBackupSettings(configured))
    expect(merged.hotkeys.dictationBindings).toEqual(bindings)
    expect(merged.hotkeys.dictation).toEqual(bindings[0])
  })

  it.each([
    { targets: 'en', active_target: 'en' },
    { targets: [42], active_target: 'en' },
    { targets: [], active_target: null },
  ])('preserves current translation when the backup structure is invalid', (translation) => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, { translation })
    expect(merged.translation).toEqual(current.translation)
    expect(() => useAppStore.getState().setConfig(merged)).not.toThrow()
  })

  it('normalizes supported translation targets and repairs an absent active target', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      translation: { targets: [' JA ', 'ja', 'unsupported', 'fr'], active_target: 'de' },
    })
    expect(merged.translation).toEqual({ targets: ['ja', 'fr'], active_target: 'ja' })
    expect(merged.target_lang).toBe('ja')
  })

  it('restores only known boolean voice routing flags without discarding current settings', () => {
    const current = useAppStore.getInitialState().config
    const merged = mergeBackupSettings(current, {
      voice_routing_flags: {
        search: false,
        rewrite_selection: 'true',
        unknown_action: true,
      },
    })
    expect(merged.voice_routing_flags).toEqual({
      ...current.voice_routing_flags,
      search: false,
    })
    expect(current.voice_routing_flags.search).toBe(true)
  })

  it('backs up and restores recognized language variants without accepting injected values', () => {
    const current = useAppStore.getInitialState().config
    const backup = createBackupSettings({
      ...current,
      language_variants: { en: 'en-GB', zh: 'zh-TW' },
    })
    expect(backup.language_variants).toEqual({ en: 'en-GB', zh: 'zh-TW' })
    expect(mergeBackupSettings(current, backup).language_variants).toEqual(backup.language_variants)
    expect(
      mergeBackupSettings(current, { language_variants: { en: '<prompt>', pt: 'pt-BR' } })
        .language_variants,
    ).toEqual({ pt: 'pt-BR' })
  })

  it('uses an explicit allow list and keeps only sync-safe family scene assignments', () => {
    const malicious = {
      stt_provider: 'glm-asr',
      stt_api_key: 'stt-secret',
      stt_custom_api_key: 'custom-secret',
      stt_language: 'multi',
      llm_provider: 'openrouter',
      llm_api_key: 'llm-secret',
      llm_model: 'test-model',
      llm_base_url: 'https://example.com/v1',
      context_adaptation_enabled: true,
      custom_scenes: [],
      system_scene_overrides: [{ id: 'system_email', prompt_template: 'Use a warm email body.' }],
      active_scene: null,
      family_scene_assignments: [{ family: 'email', scene_id: 'builtin_professional_email' }],
      custom_app_mappings: [{ matcher: { exactWebHost: 'private.example.com' } }],
      customAppMappings: [{ nativeBundleId: 'com.private.writer' }],
      matcher: { executable: 'private.exe' },
      windowTitle: 'Private document title',
      browserHost: 'private.example.com',
    } as unknown as AppConfig

    const settings = createBackupSettings(malicious)
    const serialized = JSON.stringify(settings)

    expect(settings.family_scene_assignments).toEqual([
      { family: 'email', scene_id: 'builtin_professional_email' },
    ])
    expect(settings.context_adaptation_enabled).toBe(true)
    expect(settings.system_scene_overrides).toEqual([
      { id: 'system_email', prompt_template: 'Use a warm email body.' },
    ])
    for (const forbidden of [
      'stt-secret',
      'custom-secret',
      'llm-secret',
      'custom_app_mappings',
      'customAppMappings',
      'matcher',
      'exactWebHost',
      'nativeBundleId',
      'executable',
      'windowTitle',
      'browserHost',
      'private.example.com',
      'com.private.writer',
      'private.exe',
    ]) {
      expect(serialized).not.toContain(forbidden)
    }
  })

  it('merges only allow-listed settings and preserves local credentials and app matchers', () => {
    const current = {
      ...useAppStore.getState().config,
      llm_api_key: 'local-secret',
      stt_api_key: 'local-stt-secret',
    }

    const merged = mergeBackupSettings(current, {
      polish_enabled: false,
      system_scene_overrides: [{ id: 'system_email', prompt_template: 'Use concise paragraphs.' }],
      llm_api_key: 'cloud-secret',
      stt_api_key: 'cloud-stt-secret',
      custom_app_mappings: [{ matcher: 'private.example.com' }],
    })

    expect(merged.polish_enabled).toBe(false)
    expect(merged.system_scene_overrides).toEqual([
      { id: 'system_email', prompt_template: 'Use concise paragraphs.' },
    ])
    expect(merged.llm_api_key).toBe('local-secret')
    expect(merged.stt_api_key).toBe('local-stt-secret')
    expect(merged).not.toHaveProperty('custom_app_mappings')
  })

  it('round-trips recording-limit intent while retaining the compatibility mirror', () => {
    const configured = {
      ...useAppStore.getState().config,
      recording_limit_mode: 'custom',
      custom_recording_limit_seconds: 300,
      max_recording_seconds: 300,
    } as AppConfig

    const settings = createBackupSettings(configured)
    const restored = mergeBackupSettings(useAppStore.getState().config, settings)

    expect(settings).toMatchObject({
      recording_limit_mode: 'custom',
      custom_recording_limit_seconds: 300,
      max_recording_seconds: 300,
    })
    expect(restored).toMatchObject({
      recording_limit_mode: 'custom',
      custom_recording_limit_seconds: 300,
      max_recording_seconds: 300,
    })
  })
})
