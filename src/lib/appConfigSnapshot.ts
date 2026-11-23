import {
  bindingFromHotkey,
  hotkeyFromBinding,
  useAppStore,
  type AppConfig,
  type HotkeyConfig,
} from '../stores/appStore'
import { APP_CONFIG_FIELD_POLICY } from './appConfigMergePolicy'
import { LLM_PROVIDERS, STT_PROVIDERS, TARGET_LANGUAGES } from './constants'
import { valuesEqual } from './configValuesEqual'
import { BUILTIN_SCENES } from './scenes/builtinScenes'

const contextFamilies = [
  'email',
  'work_chat',
  'personal_chat',
  'document',
  'project_management',
  'developer_collaboration',
  'prompt_or_code',
  'support',
  'social',
  'general',
] as const

function consistentSceneAssignments(value: Record<string, unknown>): boolean {
  if (!Array.isArray(value.custom_scenes) || !Array.isArray(value.family_scene_assignments)) {
    return false
  }
  const identifiers = new Set<string>(BUILTIN_SCENES.map((scene) => scene.id))
  for (const family of contextFamilies) {
    if (family !== 'general') identifiers.add(`system_${family}`)
  }
  for (const scene of value.custom_scenes) {
    if (record(scene) && typeof scene.id === 'string') identifiers.add(scene.id)
  }
  const seen = new Set<string>()
  return value.family_scene_assignments.every((assignment) => {
    if (
      !record(assignment) ||
      typeof assignment.family !== 'string' ||
      !contextFamilies.some((family) => family === assignment.family) ||
      seen.has(assignment.family) ||
      typeof assignment.scene_id !== 'string' ||
      !identifiers.has(assignment.scene_id)
    ) {
      return false
    }
    seen.add(assignment.family)
    return true
  })
}

const enumValues: Partial<Record<keyof AppConfig, readonly string[]>> = {
  stt_provider: STT_PROVIDERS.map((provider) => provider.value),
  llm_provider: LLM_PROVIDERS.map((provider) => provider.value),
  hotkey_mode: ['auto', 'hold', 'toggle'],
  output_mode: ['keyboard', 'clipboard'],
  insertion_strategy: [
    'auto',
    'keyboard',
    'clipboardPaste',
    'clipboardCopyOnly',
    'windowsSendInput',
  ],
  paste_shortcut: ['ctrlV', 'ctrlShiftV', 'shiftInsert'],
  windows_sendinput_newline_mode: ['enter', 'shiftEnter', 'crlf'],
  theme: ['light', 'dark', 'system'],
  polish_style: ['minimal', 'clean', 'structured', 'professional', 'custom'],
  polish_chinese_script: ['preserve', 'simplified', 'traditional'],
  recording_limit_mode: ['auto', 'custom'],
  stt_custom_preset: ['speaches', 'custom'],
  stt_aliyun_qwen_region: ['china-mainland', 'international'],
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function stringFields(value: unknown, fields: readonly string[]): boolean {
  return (
    record(value) &&
    Object.keys(value).length === fields.length &&
    fields.every((field) => typeof value[field] === 'string')
  )
}

function binding(value: unknown): boolean {
  if (
    !(
      record(value) &&
      Object.keys(value).length === 2 &&
      typeof value.primary === 'string' &&
      Array.isArray(value.modifiers) &&
      value.modifiers.every((modifier) => typeof modifier === 'string')
    )
  )
    return false
  const parsed = bindingFromHotkey(
    hotkeyFromBinding(value as unknown as import('../stores/appStore').ShortcutBinding),
  )
  if (!parsed) return false
  const semantic = parsed.modifiers.map((modifier) =>
    modifier === 'Command' ? 'Super' : modifier === 'Option' ? 'Alt' : modifier,
  )
  return new Set(semantic).size === semantic.length
}

function bindingIdentity(value: import('../stores/appStore').ShortcutBinding): string {
  const canonical = bindingFromHotkey(hotkeyFromBinding(value))!
  return [
    ...canonical.modifiers
      .map((modifier) =>
        modifier === 'Command' ? 'Super' : modifier === 'Option' ? 'Alt' : modifier,
      )
      .sort(),
    canonical.primary,
  ].join('+')
}

function hotkeys(value: unknown): boolean {
  if (!record(value)) return false
  const optional = ['ask', 'translate', 'editSelection', 'switchScene', 'openApp', 'pasteLast']
  const lists = ['dictationBindings', 'askBindings', 'translateBindings']
  return (
    Object.keys(value).length === 11 &&
    binding(value.dictation) &&
    optional.every((field) => value[field] === null || binding(value[field])) &&
    lists.every((field) => Array.isArray(value[field]) && value[field].every(binding)) &&
    ['auto', 'hold', 'toggle'].includes(value.dictationMode as string)
  )
}

function consistentHotkeys(config: Record<string, unknown>): boolean {
  if (!hotkeys(config.hotkeys)) return false
  const shortcuts = config.hotkeys as unknown as HotkeyConfig
  const identities = new Set<string>()
  const allBindings = [
    ...shortcuts.dictationBindings,
    ...shortcuts.askBindings,
    ...shortcuts.translateBindings,
    shortcuts.editSelection,
    shortcuts.switchScene,
    shortcuts.openApp,
    shortcuts.pasteLast,
  ]
  for (const shortcut of allBindings) {
    if (!shortcut) continue
    const identity = bindingIdentity(shortcut)
    if (identities.has(identity)) return false
    identities.add(identity)
  }
  for (const role of ['dictation', 'ask', 'translate'] as const) {
    const bindings = shortcuts[`${role}Bindings`]
    if (bindings.length > 4 || (role === 'dictation' && bindings.length === 0)) return false
    if (!valuesEqual(shortcuts[role], bindings[0] ?? null)) return false
    if (new Set(bindings.map(bindingIdentity)).size !== bindings.length) return false
  }
  return (
    config.hotkey === hotkeyFromBinding(shortcuts.dictation) &&
    config.ask_hotkey === (shortcuts.ask ? hotkeyFromBinding(shortcuts.ask) : '') &&
    config.hotkey_mode === shortcuts.dictationMode
  )
}

export function isHotkeyConfigurationConsistent(config: AppConfig): boolean {
  return consistentHotkeys(config as unknown as Record<string, unknown>)
}

export function isAppConfigSnapshot(value: unknown): value is AppConfig {
  if (!record(value)) return false
  const baseline = useAppStore.getInitialState().config
  const fields = Object.keys(APP_CONFIG_FIELD_POLICY) as (keyof AppConfig)[]
  if (
    Object.keys(value).some((field) => !fields.includes(field as keyof AppConfig)) ||
    fields.some(
      (field) =>
        field !== 'managed_stt_capability_state' &&
        !Object.prototype.hasOwnProperty.call(value, field),
    )
  ) {
    return false
  }
  for (const field of fields) {
    const expected = baseline[field]
    const incoming = value[field]
    if (field === 'managed_stt_capability_state') continue
    if (typeof expected === 'string' || typeof expected === 'boolean') {
      if (typeof incoming !== typeof expected) return false
    } else if (typeof expected === 'number') {
      if (!Number.isSafeInteger(incoming) || (incoming as number) < 0) return false
    }
    if (APP_CONFIG_FIELD_POLICY[field] === 'credentials' && incoming !== '') return false
    const allowed = enumValues[field]
    if (allowed && !allowed.includes(incoming as string)) return false
  }
  const translation = value.translation
  const flags = value.voice_routing_flags
  const variants = value.language_variants
  return (
    consistentHotkeys(value) &&
    consistentSceneAssignments(value) &&
    record(translation) &&
    Object.keys(translation).length === 2 &&
    typeof translation.active_target === 'string' &&
    Array.isArray(translation.targets) &&
    translation.targets.length >= 1 &&
    translation.targets.length <= 5 &&
    new Set(translation.targets).size === translation.targets.length &&
    translation.targets.every(
      (target) =>
        typeof target === 'string' &&
        TARGET_LANGUAGES.some((language) => language.value === target),
    ) &&
    translation.targets.includes(translation.active_target) &&
    value.target_lang === translation.active_target &&
    record(flags) &&
    Object.keys(flags).length === 4 &&
    ['draft_insert', 'rewrite_selection', 'translate_selection', 'search'].every(
      (field) => typeof flags[field] === 'boolean',
    ) &&
    record(variants) &&
    Object.entries(variants).every(
      ([base, variant]) =>
        ['en', 'zh', 'es', 'fr', 'pt'].includes(base) && typeof variant === 'string',
    ) &&
    Array.isArray(value.custom_scenes) &&
    value.custom_scenes.every((scene) =>
      stringFields(scene, [
        'id',
        'name',
        'description',
        'prompt_template',
        'created_at',
        'updated_at',
      ]),
    ) &&
    Array.isArray(value.system_scene_overrides) &&
    value.system_scene_overrides.every((scene) => stringFields(scene, ['id', 'prompt_template'])) &&
    (value.active_scene === null ||
      stringFields(value.active_scene, ['id', 'source', 'name', 'prompt_template'])) &&
    Array.isArray(value.family_scene_assignments) &&
    value.family_scene_assignments.every((assignment) =>
      stringFields(assignment, ['family', 'scene_id']),
    )
  )
}
