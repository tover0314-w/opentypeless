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

    it('changing hotkey mode keeps a disabled Translate hotkey disabled', () => {
      getState().updateConfig({
        hotkeys: { ...getState().config.hotkeys, translate: null, translateBindings: [] },
      })
      getState().updateConfig({ ask_hotkey: 'Ctrl+Shift+/' })
      getState().updateConfig({ hotkey_mode: 'toggle' })

      const { hotkeys } = getState().config
      expect(hotkeys.translate).toBeNull()
      expect(hotkeys.translateBindings).toEqual([])
      expect(hotkeys.dictationMode).toBe('toggle')
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
