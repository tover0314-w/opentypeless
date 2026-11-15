import { describe, it, expect, beforeEach } from 'vitest'
import { useAppStore } from '../appStore'
import type { HistoryEntry, DictionaryEntry, CorrectionRule } from '../appStore'

function getState() {
  return useAppStore.getState()
}

describe('appStore', () => {
  beforeEach(() => {
    // Reset store to initial state
    useAppStore.setState(useAppStore.getInitialState())
  })

  it.each(['\\', "'", '`'])(
    'preserves the %s binding when configuration is reloaded',
    (primary) => {
      const original = getState().config
      const binding = { primary, modifiers: ['Ctrl', 'Shift'] }
      const bindings = [binding, { primary: '9', modifiers: ['Ctrl', 'Alt'] }]
      const config = {
        ...original,
        hotkey: `Ctrl+Shift+${primary}`,
        hotkeys: {
          ...original.hotkeys,
          dictation: binding,
          dictationBindings: bindings,
        },
      }
      getState().setConfig(JSON.parse(JSON.stringify(config)))
      expect(getState().config.hotkeys.dictationBindings).toEqual(bindings)
      expect(getState().config.hotkeys.dictation).toEqual(binding)
      expect(getState().config.hotkey).toBe(`Ctrl+Shift+${primary}`)
    },
  )

  it.each([
    { volume: Number.NaN, expected: 0 },
    { volume: Number.POSITIVE_INFINITY, expected: 0 },
    { volume: -0.5, expected: 0 },
    { volume: 1.5, expected: 1 },
    { volume: 0.025, expected: 0.025 },
  ])('normalizes microphone volume $volume without amplifying audio', ({ volume, expected }) => {
    getState().setAudioVolume(volume)
    expect(getState().audioVolume).toBe(expected)
  })

  it.each(['setConfig', 'updateConfig', 'applyPersistedConfigPatch'] as const)(
    'clears model cache and connection status on endpoint changes through %s',
    (action) => {
      useAppStore.setState({
        llmModels: ['old-endpoint-model'],
        llmTestStatus: 'success',
        llmLatencyMs: 100,
      })
      const config = { ...getState().config, llm_base_url: 'https://new.example/v1' }
      getState()[action](config)
      expect(getState().llmModels).toEqual([])
      expect(getState().llmTestStatus).toBe('idle')
      expect(getState().llmLatencyMs).toBeNull()
    },
  )

  it('preserves model cache and test results for unrelated appearance changes', () => {
    useAppStore.setState({
      llmModels: ['current-model'],
      llmTestStatus: 'success',
      llmLatencyMs: 100,
    })
    getState().updateConfig({ theme: 'dark' })
    expect(getState().llmModels).toEqual(['current-model'])
    expect(getState().llmTestStatus).toBe('success')
  })

  it.each(['setConfig', 'updateConfig', 'applyPersistedConfigPatch'] as const)(
    'clears STT connection results when service configuration changes through %s',
    (action) => {
      useAppStore.setState({ sttTestStatus: 'success', sttLatencyMs: 100 })
      getState()[action]({ ...getState().config, stt_provider: 'openai-whisper' })
      expect(getState().sttTestStatus).toBe('idle')
      expect(getState().sttLatencyMs).toBeNull()
    },
  )

  describe('pipeline state', () => {
    it('defaults to idle', () => {
      expect(getState().pipelineState).toBe('idle')
    })

    it('updates pipeline state', () => {
      getState().setPipelineState('recording')
      expect(getState().pipelineState).toBe('recording')
    })

    it('tracks the last structured insert result', () => {
      expect(getState().lastInsertResult).toBeNull()

      getState().setLastInsertResult({
        status: 'inserted',
        strategyUsed: 'keyboard',
        charsInserted: 5,
        charsCopied: 0,
        warningCode: null,
        message: null,
      })

      expect(getState().lastInsertResult).toEqual({
        status: 'inserted',
        strategyUsed: 'keyboard',
        charsInserted: 5,
        charsCopied: 0,
        warningCode: null,
        message: null,
      })
    })
  })

  describe('config', () => {
    it('has sensible defaults', () => {
      const { config } = getState()
      const isMac =
        typeof navigator !== 'undefined' && navigator.platform.toUpperCase().includes('MAC')
      const isWindows =
        typeof navigator !== 'undefined' && navigator.platform.toUpperCase().includes('WIN')
      expect(config.theme).toBe('system')
      expect(config.hotkey).toBe(isMac ? 'Fn' : 'Ctrl+/')
      expect(config.ask_hotkey).toBe(isMac ? 'Fn+Space' : 'Ctrl+.')
      expect(config.hotkeys.dictation).toEqual(
        isMac ? { primary: 'Fn', modifiers: [] } : { primary: '/', modifiers: ['Ctrl'] },
      )
      expect(config.hotkeys.ask).toEqual(
        isMac ? { primary: 'Space', modifiers: ['Fn'] } : { primary: '.', modifiers: ['Ctrl'] },
      )
      expect(config.hotkeys.dictationBindings).toEqual([config.hotkeys.dictation])
      expect(config.hotkeys.askBindings).toEqual([config.hotkeys.ask])
      expect(config.hotkeys.translateBindings).toEqual(
        config.hotkeys.translate ? [config.hotkeys.translate] : [],
      )
      expect(config.hotkeys.dictationMode).toBe(isMac || isWindows ? 'toggle' : 'hold')
      expect(config.output_mode).toBe('keyboard')
      expect(config.insertion_strategy).toBe('auto')
      expect(config.windows_sendinput_newline_mode).toBe('enter')
      expect(config.polish_enabled).toBe(true)
      expect(config.polish_style).toBe('clean')
      expect(config.polish_custom_prompt).toBe('')
      expect(config.polish_chinese_script).toBe('preserve')
      expect(config.custom_scenes).toEqual([])
      expect(config.active_scene).toBeNull()
      expect(config.translation).toEqual({ targets: ['en'], active_target: 'en' })
      expect(config.target_lang).toBe('en')
      expect(config.stt_custom_api_key).toBe('')
      expect(config.capsule_auto_hide).toBe(true)
      expect(config.auto_start).toBe(true)
    })

    it('setConfig replaces entire config', () => {
      const newConfig = { ...getState().config, theme: 'dark' as const }
      getState().setConfig(newConfig)
      expect(getState().config.theme).toBe('dark')
    })

    it('updateConfig merges partial config immutably', () => {
      const original = getState().config
      getState().updateConfig({ theme: 'dark' })
      const updated = getState().config

      expect(updated.theme).toBe('dark')
      expect(updated.hotkey).toBe(original.hotkey) // unchanged
      expect(updated).not.toBe(original) // new object
    })

    it('updateConfig keeps legacy and typed hotkey fields in sync', () => {
      getState().updateConfig({
        hotkey: 'Ctrl+Shift+;',
        ask_hotkey: 'Ctrl+.',
        hotkey_mode: 'toggle',
      })

      const { config } = getState()
      expect(config.hotkeys.dictation).toEqual({
        primary: ';',
        modifiers: ['Ctrl', 'Shift'],
      })
      expect(config.hotkeys.ask).toEqual({
        primary: '.',
        modifiers: ['Ctrl'],
      })
      expect(config.hotkeys.dictationMode).toBe('toggle')
    })

    it('keeps the auto press/hold mode through legacy and typed updates', () => {
      getState().updateConfig({ hotkey_mode: 'auto' })
      expect(getState().config.hotkeys.dictationMode).toBe('auto')
      expect(getState().config.hotkey_mode).toBe('auto')

      getState().updateConfig({
        hotkeys: { ...getState().config.hotkeys, dictationMode: 'auto' },
      })
      expect(getState().config.hotkey_mode).toBe('auto')
    })

    it('updateConfig keeps native single-key hotkeys in sync', () => {
      getState().updateConfig({
        hotkey: 'RightAlt',
        ask_hotkey: 'Ctrl+.',
        hotkey_mode: 'toggle',
      })

      const { config } = getState()
      expect(config.hotkey).toBe('RightAlt')
      expect(config.hotkeys.dictation).toEqual({
        primary: 'RightAlt',
        modifiers: [],
      })
      expect(config.hotkeys.dictationMode).toBe('toggle')
    })

    it('updateConfig keeps disabled typed Ask hotkey disabled in legacy fields', () => {
      getState().updateConfig({
        hotkeys: {
          ...getState().config.hotkeys,
          ask: null,
        },
      })

      const { config } = getState()
      expect(config.hotkeys.ask).toBeNull()
      expect(config.ask_hotkey).toBe('')
    })

    it('updateConfig treats empty legacy Ask hotkey as disabled', () => {
      getState().updateConfig({ ask_hotkey: '' })

      const { config } = getState()
      expect(config.ask_hotkey).toBe('')
      expect(config.hotkeys.ask).toBeNull()
    })

    it('normalizes ordered hotkey binding lists and mirrors index zero', () => {
      getState().updateConfig({
        hotkeys: {
          ...getState().config.hotkeys,
          dictationBindings: [
            { primary: 'D', modifiers: ['Shift', 'control'] },
            { primary: 'D', modifiers: ['Ctrl', 'Shift'] },
            { primary: 'F8', modifiers: [] },
            { primary: 'F9', modifiers: [] },
            { primary: 'F10', modifiers: [] },
          ],
          askBindings: [],
          translateBindings: [
            { primary: 'T', modifiers: ['Ctrl', 'Shift'] },
            { primary: 'F7', modifiers: [] },
          ],
        },
      })

      const { config } = getState()
      expect(config.hotkeys.dictationBindings).toEqual([
        { primary: 'D', modifiers: ['Ctrl', 'Shift'] },
        { primary: 'F8', modifiers: [] },
        { primary: 'F9', modifiers: [] },
        { primary: 'F10', modifiers: [] },
      ])
      expect(config.hotkeys.dictation).toEqual(config.hotkeys.dictationBindings[0])
      expect(config.hotkey).toBe('Ctrl+Shift+D')
      expect(config.hotkeys.askBindings).toEqual([])
      expect(config.hotkeys.ask).toBeNull()
      expect(config.ask_hotkey).toBe('')
      expect(config.hotkeys.translate).toEqual(config.hotkeys.translateBindings[0])
    })

    it('wraps legacy hotkeys into binding lists', () => {
      getState().updateConfig({
        hotkey: 'Ctrl+Shift+;',
        ask_hotkey: '',
        hotkey_mode: 'toggle',
      })

      const { hotkeys } = getState().config
      expect(hotkeys.dictationBindings).toEqual([{ primary: ';', modifiers: ['Ctrl', 'Shift'] }])
      expect(hotkeys.askBindings).toEqual([])
      expect(hotkeys.dictationMode).toBe('toggle')
    })

    it('keeps ordered translation targets and the legacy target mirror in sync', () => {
      getState().updateConfig({ target_lang: 'ja' })
      expect(getState().config.translation).toEqual({
        targets: ['en', 'ja'],
        active_target: 'ja',
      })

      getState().updateConfig({
        translation: {
          targets: ['fr', 'fr', 'xx', 'ja', 'de', 'es', 'pt', 'it'],
          active_target: 'ja',
        },
      })
      expect(getState().config.translation).toEqual({
        targets: ['fr', 'ja', 'de', 'es', 'pt'],
        active_target: 'ja',
      })
      expect(getState().config.target_lang).toBe('ja')
    })
  })

  describe('history', () => {
    it('defaults to empty array', () => {
      expect(getState().history).toEqual([])
    })

    it('setHistory replaces history', () => {
      const entries: HistoryEntry[] = [
        {
          id: 1,
          created_at: '2025-01-01',
          context_profile_id: 'general.native',
          context_label: 'General',
          context_icon_key: 'general',
          context_family: 'general',
          browser_access_status: 'not_applicable',
          provider_kind: 'local',
          raw_text: 'hello',
          polished_text: 'Hello.',
          language: 'en',
          duration_ms: 1200,
          active_scene_id: null,
          active_scene_source: null,
          active_scene_name: null,
          active_scene_prompt_chars: null,
          active_scene_prompt_truncated: false,
          output_status: null,
          output_error: null,
        },
      ]
      getState().setHistory(entries)
      expect(getState().history).toHaveLength(1)
      expect(getState().history[0].raw_text).toBe('hello')
    })
  })

  describe('dictionary', () => {
    it('defaults to empty array', () => {
      expect(getState().dictionary).toEqual([])
      expect(getState().correctionRules).toEqual([])
    })

    it('setDictionary replaces dictionary', () => {
      const entries: DictionaryEntry[] = [{ id: 1, word: 'API', pronunciation: null }]
      getState().setDictionary(entries)
      expect(getState().dictionary).toHaveLength(1)
      expect(getState().dictionary[0].word).toBe('API')
    })

    it('setCorrectionRules replaces correction rules', () => {
      const rules: CorrectionRule[] = [
        { id: 1, pattern: '拓肯', replacement: 'Token', enabled: true },
      ]
      getState().setCorrectionRules(rules)
      expect(getState().correctionRules).toHaveLength(1)
      expect(getState().correctionRules[0].replacement).toBe('Token')
    })
  })

  describe('recording state', () => {
    it('resetRecording clears all recording fields', () => {
      getState().setAudioVolume(0.8)
      getState().setPartialTranscript('partial')
      getState().setFinalTranscript('final')
      getState().setPolishedText('polished')
      getState().setRecordingDuration(5000)

      getState().resetRecording()

      expect(getState().audioVolume).toBe(0)
      expect(getState().partialTranscript).toBe('')
      expect(getState().finalTranscript).toBe('')
      expect(getState().polishedText).toBe('')
      expect(getState().recordingDuration).toBe(0)
    })

    it('appendPolishedChunk appends to existing text', () => {
      getState().setPolishedText('Hello')
      getState().appendPolishedChunk(' world')
      expect(getState().polishedText).toBe('Hello world')
    })
  })

  describe('savedConfig / resetConfig', () => {
    it('merges persisted untouched roles while retaining local dictation edits', () => {
      const original = getState().config
      getState().setSavedConfig(original)
      getState().updateConfig({ hotkey: 'Ctrl+Shift+K' })
      const ask = { primary: 'J', modifiers: ['Ctrl', 'Shift'] }
      getState().applyPersistedConfigPatch({
        hotkeys: { ...original.hotkeys, ask, askBindings: [ask] },
        ask_hotkey: 'Ctrl+Shift+J',
      })
      expect(getState().config.hotkeys.dictation.primary).toBe('K')
      expect(getState().config.hotkeys.ask).toEqual(ask)
      expect(getState().config.hotkeys.askBindings).toEqual([ask])
      expect(getState().config.ask_hotkey).toBe('Ctrl+Shift+J')
      expect(getState().savedConfig?.hotkeys.dictation).toEqual(original.hotkeys.dictation)
      expect(getState().savedConfig?.hotkeys.ask).toEqual(ask)
    })

    it('preserves dirty shortcut edits while advancing persisted shortcuts', () => {
      const original = getState().config
      getState().setSavedConfig(original)
      getState().updateConfig({ hotkey: 'Ctrl+Shift+K' })
      const local = getState().config
      const incoming = {
        ...original.hotkeys,
        dictation: { primary: 'J', modifiers: ['Ctrl', 'Shift'] },
        dictationBindings: [{ primary: 'J', modifiers: ['Ctrl', 'Shift'] }],
      }
      getState().applyPersistedConfigPatch({
        hotkeys: incoming,
        hotkey: 'Ctrl+Shift+J',
        history_enabled: false,
      })
      expect(getState().config.hotkeys).toEqual(local.hotkeys)
      expect(getState().config.hotkey).toBe('Ctrl+Shift+K')
      expect(getState().savedConfig?.hotkey).toBe('Ctrl+Shift+J')
      expect(getState().savedConfig?.hotkeys.dictation.primary).toBe('J')
      expect(getState().config.history_enabled).toBe(false)
    })

    it('applyPersistedConfigPatch updates config and savedConfig for patched fields', () => {
      const saved = { ...getState().config, capsule_auto_hide: false }
      getState().setSavedConfig(saved)
      getState().applyPersistedConfigPatch({ capsule_auto_hide: true })

      expect(getState().config.capsule_auto_hide).toBe(true)
      expect(getState().savedConfig?.capsule_auto_hide).toBe(true)
    })

    it('applyPersistedConfigPatch preserves unrelated dirty fields', () => {
      const saved = { ...getState().config, theme: 'system' as const, capsule_auto_hide: false }
      getState().setSavedConfig(saved)
      getState().updateConfig({ theme: 'dark' })

      getState().applyPersistedConfigPatch({ capsule_auto_hide: true })

      expect(getState().config.theme).toBe('dark')
      expect(getState().savedConfig?.theme).toBe('system')
      expect(getState().config.capsule_auto_hide).toBe(true)
      expect(getState().savedConfig?.capsule_auto_hide).toBe(true)
    })

    it('applyPersistedConfigPatch lets persisted patch win for the same dirty field', () => {
      const saved = { ...getState().config, capsule_auto_hide: false }
      getState().setSavedConfig(saved)
      getState().updateConfig({ capsule_auto_hide: true })

      getState().applyPersistedConfigPatch({ capsule_auto_hide: false })

      expect(getState().config.capsule_auto_hide).toBe(false)
      expect(getState().savedConfig?.capsule_auto_hide).toBe(false)
    })

    it('resetConfig restores to savedConfig', () => {
      const saved = { ...getState().config }
      getState().setSavedConfig(saved)

      getState().updateConfig({ theme: 'dark', polish_enabled: false })
      expect(getState().config.theme).toBe('dark')

      getState().resetConfig()
      expect(getState().config.theme).toBe('system')
      expect(getState().config.polish_enabled).toBe(true)
    })

    it('resetConfig is a no-op when savedConfig is null', () => {
      getState().updateConfig({ theme: 'dark' })
      getState().resetConfig()
      // Should remain dark since savedConfig is null
      expect(getState().config.theme).toBe('dark')
    })
  })

  describe('onboarding', () => {
    it('defaults to not completed', () => {
      expect(getState().onboardingCompleted).toBe(false)
      expect(getState().onboardingStep).toBe(0)
    })

    it('tracks onboarding progress', () => {
      getState().setOnboardingStep(2)
      getState().setOnboardingCompleted(true)
      expect(getState().onboardingStep).toBe(2)
      expect(getState().onboardingCompleted).toBe(true)
    })
  })
})
