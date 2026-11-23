import { beforeEach, describe, expect, it } from 'vitest'
import ts from 'typescript'
import storeSource from '../../stores/appStore.ts?raw'
import { useAppStore, type HotkeyMode } from '../../stores/appStore'

const originalConfigFields = [
  'stt_provider',
  'stt_api_key',
  'stt_custom_api_key',
  'stt_custom_preset',
  'stt_custom_base_url',
  'stt_custom_model',
  'stt_volcengine_resource_id',
  'stt_aliyun_qwen_region',
  'stt_language',
  'llm_provider',
  'llm_api_key',
  'llm_model',
  'llm_base_url',
  'polish_enabled',
  'context_adaptation_enabled',
  'voice_routing_flags',
  'polish_style',
  'polish_custom_prompt',
  'polish_chinese_script',
  'custom_scenes',
  'system_scene_overrides',
  'active_scene',
  'family_scene_assignments',
  'translate_enabled',
  'target_lang',
  'translation',
  'hotkey',
  'ask_hotkey',
  'hotkey_mode',
  'hotkeys',
  'output_mode',
  'insertion_strategy',
  'restore_clipboard_after_paste',
  'paste_shortcut',
  'windows_sendinput_newline_mode',
  'streaming_insert_enabled',
  'selected_text_enabled',
  'theme',
  'auto_start',
  'close_to_tray',
  'start_minimized',
  'recording_limit_mode',
  'custom_recording_limit_seconds',
  'max_recording_seconds',
  'managed_stt_capability_state',
  'history_enabled',
  'history_retention_days',
  'history_max_entries',
  'ui_language',
  'capsule_auto_hide',
]

const originalChoices: Record<string, string[]> = {
  HotkeyMode: ['hold', 'toggle'],
  OutputMode: ['keyboard', 'clipboard'],
  PasteShortcut: ['ctrlV', 'ctrlShiftV', 'shiftInsert'],
  WindowsSendInputNewlineMode: ['enter', 'shiftEnter', 'crlf'],
  InsertionStrategy: [
    'auto',
    'keyboard',
    'clipboardPaste',
    'clipboardCopyOnly',
    'windowsSendInput',
  ],
  PolishStyle: ['minimal', 'clean', 'structured', 'professional'],
  PolishChineseScript: ['preserve', 'simplified', 'traditional'],
  Theme: ['light', 'dark', 'system'],
  SceneSource: ['custom', 'builtin', 'cloud'],
}

const syntax = ts.createSourceFile('appStore.ts', storeSource, ts.ScriptTarget.Latest, true)

describe('upstream f5ad602 settings retention', () => {
  beforeEach(() => useAppStore.setState(useAppStore.getInitialState()))

  it('retains all fifty upstream configuration declarations, including optional capability state', () => {
    const declaration = syntax.statements.find(
      (node): node is ts.InterfaceDeclaration =>
        ts.isInterfaceDeclaration(node) && node.name.text === 'AppConfig',
    )!
    const fields = declaration.members.map((member) => member.name?.getText(syntax))
    expect(originalConfigFields).toHaveLength(50)
    expect(new Set(originalConfigFields).size).toBe(50)
    expect(originalConfigFields.filter((field) => !fields.includes(field))).toEqual([])
    const capability = declaration.members.find(
      (member) => member.name?.getText(syntax) === 'managed_stt_capability_state',
    )
    expect(
      capability && ts.isPropertySignature(capability) && capability.questionToken,
    ).toBeTruthy()
  })

  it.each(Object.entries(originalChoices))('retains upstream %s choices', (name, original) => {
    const declaration = syntax.statements.find(
      (node): node is ts.TypeAliasDeclaration =>
        ts.isTypeAliasDeclaration(node) && node.name.text === name,
    )!
    expect(ts.isUnionTypeNode(declaration.type)).toBe(true)
    if (!ts.isUnionTypeNode(declaration.type)) return
    const choices = declaration.type.types.flatMap((node) =>
      ts.isLiteralTypeNode(node) && ts.isStringLiteral(node.literal) ? [node.literal.text] : [],
    )
    expect(original.filter((choice) => !choices.includes(choice))).toEqual([])
  })

  it.each(['hold', 'toggle', 'auto'] as HotkeyMode[])(
    'switches %s while preserving other roles and additional bindings',
    (mode) => {
      const state = useAppStore.getState()
      const extra = { primary: '9', modifiers: ['Ctrl', 'Alt'] }
      state.updateConfig({
        hotkeys: {
          ...state.config.hotkeys,
          dictationBindings: [...state.config.hotkeys.dictationBindings, extra],
        },
      })
      const previous = useAppStore.getState().config
      useAppStore.getState().updateConfig({ hotkey_mode: mode })
      const current = useAppStore.getState().config
      expect(current.hotkey_mode).toBe(mode)
      expect(current.hotkeys.dictationMode).toBe(mode)
      expect(current.hotkeys.dictationBindings).toEqual(previous.hotkeys.dictationBindings)
      expect(current.hotkeys.askBindings).toEqual(previous.hotkeys.askBindings)
      expect(current.hotkeys.translateBindings).toEqual(previous.hotkeys.translateBindings)
      expect(current.stt_provider).toBe(previous.stt_provider)
      expect(current.llm_provider).toBe(previous.llm_provider)
      expect(current.insertion_strategy).toBe(previous.insertion_strategy)
    },
  )
})
