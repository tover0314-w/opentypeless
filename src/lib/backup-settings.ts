import { normalizeLanguageVariants, type LanguageVariants } from './languageVariants'
import { safeBackupEndpoint } from './backup-settings-privacy'
import { LLM_PROVIDERS, STT_PROVIDERS, TARGET_LANGUAGES, UI_LANGUAGES } from './constants'
import { bindingFromHotkey, hotkeyFromBinding } from '../stores/appStore'
import { importCustomScenesJson } from './scenes/sceneImportExport'
import {
  isReservedSceneId,
  restoreActiveScene,
  restoreFamilyAssignments,
  restoreSystemOverrides,
} from './scenes/backupSceneMetadata'
import type {
  ActiveScene,
  AppConfig,
  CustomScene,
  FamilySceneAssignment,
  HotkeyConfig,
  ShortcutBinding,
  SystemSceneOverride,
  TranslationConfig,
  VoiceRoutingFlags,
} from '../stores/appStore'

type SafeScalarKey =
  | 'stt_provider'
  | 'stt_language'
  | 'stt_custom_preset'
  | 'stt_custom_base_url'
  | 'stt_custom_model'
  | 'stt_volcengine_resource_id'
  | 'stt_aliyun_qwen_region'
  | 'llm_provider'
  | 'llm_model'
  | 'llm_base_url'
  | 'polish_enabled'
  | 'context_adaptation_enabled'
  | 'polish_style'
  | 'polish_custom_prompt'
  | 'polish_chinese_script'
  | 'translate_enabled'
  | 'target_lang'
  | 'hotkey'
  | 'ask_hotkey'
  | 'hotkey_mode'
  | 'output_mode'
  | 'insertion_strategy'
  | 'restore_clipboard_after_paste'
  | 'paste_shortcut'
  | 'windows_sendinput_newline_mode'
  | 'streaming_insert_enabled'
  | 'selected_text_enabled'
  | 'theme'
  | 'auto_start'
  | 'close_to_tray'
  | 'start_minimized'
  | 'recording_limit_mode'
  | 'custom_recording_limit_seconds'
  | 'max_recording_seconds'
  | 'history_enabled'
  | 'history_retention_days'
  | 'history_max_entries'
  | 'ui_language'
  | 'capsule_auto_hide'
  | 'interaction_sounds'
  | 'mute_background_audio'
  | 'show_in_dock'
  | 'save_recording_audio'
  | 'auto_learn_dictionary'

export type BackupSettings = Partial<Pick<AppConfig, SafeScalarKey>> & {
  language_variants?: LanguageVariants
  voice_routing_flags?: VoiceRoutingFlags
  custom_scenes?: CustomScene[]
  system_scene_overrides?: SystemSceneOverride[]
  active_scene?: ActiveScene | null
  family_scene_assignments?: FamilySceneAssignment[]
  translation?: TranslationConfig
  hotkeys?: HotkeyConfig
}

function safeBinding(binding: ShortcutBinding | null | undefined): ShortcutBinding | null {
  if (!binding) return null
  return {
    primary: binding.primary,
    modifiers: Array.isArray(binding.modifiers) ? [...binding.modifiers] : [],
  }
}

export function createBackupSettings(config: AppConfig): BackupSettings {
  const settings: BackupSettings = {
    language_variants: normalizeLanguageVariants(config.language_variants),
    stt_provider: config.stt_provider,
    stt_language: config.stt_language,
    stt_custom_preset: config.stt_custom_preset,
    stt_custom_base_url: safeBackupEndpoint(config.stt_custom_base_url),
    stt_custom_model: config.stt_custom_model,
    stt_volcengine_resource_id: config.stt_volcengine_resource_id,
    stt_aliyun_qwen_region: config.stt_aliyun_qwen_region,
    llm_provider: config.llm_provider,
    llm_model: config.llm_model,
    llm_base_url: safeBackupEndpoint(config.llm_base_url),
    polish_enabled: config.polish_enabled,
    context_adaptation_enabled: config.context_adaptation_enabled,
    polish_style: config.polish_style,
    polish_custom_prompt: config.polish_custom_prompt,
    polish_chinese_script: config.polish_chinese_script,
    custom_scenes: Array.isArray(config.custom_scenes)
      ? config.custom_scenes.map((scene) => ({
          id: scene.id,
          name: scene.name,
          description: scene.description,
          prompt_template: scene.prompt_template,
          created_at: scene.created_at,
          updated_at: scene.updated_at,
        }))
      : [],
    system_scene_overrides: Array.isArray(config.system_scene_overrides)
      ? config.system_scene_overrides.map((scene) => ({
          id: scene.id,
          prompt_template: scene.prompt_template,
        }))
      : [],
    active_scene: config.active_scene
      ? {
          id: config.active_scene.id,
          source: config.active_scene.source,
          name: config.active_scene.name,
          prompt_template: config.active_scene.prompt_template,
        }
      : null,
    family_scene_assignments: Array.isArray(config.family_scene_assignments)
      ? config.family_scene_assignments.map((assignment) => ({
          family: assignment.family,
          scene_id: assignment.scene_id,
        }))
      : [],
    translate_enabled: config.translate_enabled,
    target_lang: config.target_lang,
    translation: config.translation
      ? {
          targets: Array.isArray(config.translation.targets) ? [...config.translation.targets] : [],
          active_target: config.translation.active_target,
        }
      : undefined,
    hotkey: config.hotkey,
    ask_hotkey: config.ask_hotkey,
    hotkey_mode: config.hotkey_mode,
    hotkeys: config.hotkeys
      ? {
          dictation: safeBinding(config.hotkeys.dictation)!,
          ask: safeBinding(config.hotkeys.ask),
          translate: safeBinding(config.hotkeys.translate),
          dictationBindings: (Array.isArray(config.hotkeys.dictationBindings)
            ? config.hotkeys.dictationBindings
            : [config.hotkeys.dictation]
          )
            .map((binding) => safeBinding(binding))
            .filter((binding): binding is ShortcutBinding => Boolean(binding)),
          askBindings: (Array.isArray(config.hotkeys.askBindings)
            ? config.hotkeys.askBindings
            : config.hotkeys.ask
              ? [config.hotkeys.ask]
              : []
          )
            .map((binding) => safeBinding(binding))
            .filter((binding): binding is ShortcutBinding => Boolean(binding)),
          translateBindings: (Array.isArray(config.hotkeys.translateBindings)
            ? config.hotkeys.translateBindings
            : config.hotkeys.translate
              ? [config.hotkeys.translate]
              : []
          )
            .map((binding) => safeBinding(binding))
            .filter((binding): binding is ShortcutBinding => Boolean(binding)),
          editSelection: safeBinding(config.hotkeys.editSelection),
          switchScene: safeBinding(config.hotkeys.switchScene),
          openApp: safeBinding(config.hotkeys.openApp),
          pasteLast: safeBinding(config.hotkeys.pasteLast),
          dictationMode: config.hotkeys.dictationMode,
        }
      : undefined,
    output_mode: config.output_mode,
    insertion_strategy: config.insertion_strategy,
    restore_clipboard_after_paste: config.restore_clipboard_after_paste,
    paste_shortcut: config.paste_shortcut,
    windows_sendinput_newline_mode: config.windows_sendinput_newline_mode,
    streaming_insert_enabled: config.streaming_insert_enabled,
    selected_text_enabled: config.selected_text_enabled,
    theme: config.theme,
    auto_start: config.auto_start,
    close_to_tray: config.close_to_tray,
    start_minimized: config.start_minimized,
    recording_limit_mode: config.recording_limit_mode,
    custom_recording_limit_seconds: config.custom_recording_limit_seconds,
    max_recording_seconds: config.max_recording_seconds,
    history_enabled: config.history_enabled,
    history_retention_days: config.history_retention_days,
    history_max_entries: config.history_max_entries,
    ui_language: config.ui_language,
    capsule_auto_hide: config.capsule_auto_hide,
    interaction_sounds: config.interaction_sounds,
    mute_background_audio: config.mute_background_audio,
    show_in_dock: config.show_in_dock,
    save_recording_audio: config.save_recording_audio,
    auto_learn_dictionary: config.auto_learn_dictionary,
  }

  if (config.voice_routing_flags) {
    settings.voice_routing_flags = {
      draft_insert: config.voice_routing_flags.draft_insert,
      rewrite_selection: config.voice_routing_flags.rewrite_selection,
      translate_selection: config.voice_routing_flags.translate_selection,
      search: config.voice_routing_flags.search,
    }
  }

  return settings
}

const SAFE_SCALAR_KEYS: readonly SafeScalarKey[] = [
  'stt_provider',
  'stt_language',
  'stt_custom_preset',
  'stt_custom_base_url',
  'stt_custom_model',
  'stt_volcengine_resource_id',
  'stt_aliyun_qwen_region',
  'llm_provider',
  'llm_model',
  'llm_base_url',
  'polish_enabled',
  'context_adaptation_enabled',
  'polish_style',
  'polish_custom_prompt',
  'polish_chinese_script',
  'translate_enabled',
  'target_lang',
  'hotkey',
  'ask_hotkey',
  'hotkey_mode',
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
  'history_enabled',
  'history_retention_days',
  'history_max_entries',
  'ui_language',
  'capsule_auto_hide',
  'interaction_sounds',
  'mute_background_audio',
  'show_in_dock',
  'save_recording_audio',
  'auto_learn_dictionary',
]

const ENUM_VALUES: Partial<Record<SafeScalarKey, readonly string[]>> = {
  stt_provider: STT_PROVIDERS.map((provider) => provider.value),
  llm_provider: LLM_PROVIDERS.map((provider) => provider.value),
  output_mode: ['keyboard', 'clipboard'],
  insertion_strategy: [
    'auto',
    'keyboard',
    'clipboardPaste',
    'clipboardCopyOnly',
    'windowsSendInput',
  ],
  theme: ['light', 'dark', 'system'],
  polish_style: ['minimal', 'clean', 'structured', 'professional', 'custom'],
  hotkey_mode: ['hold', 'toggle', 'auto'],
  stt_aliyun_qwen_region: ['china-mainland', 'international'],
  paste_shortcut: ['ctrlV', 'ctrlShiftV', 'shiftInsert'],
  windows_sendinput_newline_mode: ['enter', 'shiftEnter', 'crlf'],
  polish_chinese_script: ['preserve', 'simplified', 'traditional'],
  recording_limit_mode: ['auto', 'custom'],
  ui_language: UI_LANGUAGES.map((language) => language.value),
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function restoredBinding(value: unknown): ShortcutBinding | null {
  if (
    !isRecord(value) ||
    typeof value.primary !== 'string' ||
    !Array.isArray(value.modifiers) ||
    !value.modifiers.every((modifier) => typeof modifier === 'string')
  ) {
    return null
  }
  return bindingFromHotkey([...value.modifiers, value.primary].join('+'))
}

function uniqueRestoredBindings(bindings: ShortcutBinding[]): ShortcutBinding[] {
  const seen = new Set<string>()
  return bindings
    .filter((binding) => {
      const modifiers = binding.modifiers
        .map((modifier) =>
          modifier === 'Command' ? 'Super' : modifier === 'Option' ? 'Alt' : modifier,
        )
        .sort()
      const identity = [...modifiers, binding.primary].join('+')
      if (seen.has(identity)) return false
      seen.add(identity)
      return true
    })
    .slice(0, 4)
}

function restoreHotkeys(current: HotkeyConfig, incoming: Record<string, unknown>): HotkeyConfig {
  const next = { ...current }
  for (const role of ['dictation', 'ask', 'translate'] as const) {
    const listKey = `${role}Bindings` as 'dictationBindings' | 'askBindings' | 'translateBindings'
    const list = incoming[listKey]
    if (Array.isArray(list)) {
      const parsed = list.map(restoredBinding)
      if (parsed.some((binding) => binding === null) || (role === 'dictation' && !parsed.length)) {
        continue
      }
      const bindings = parsed.filter((binding): binding is ShortcutBinding => binding !== null)
      const unique = uniqueRestoredBindings(bindings)
      next[listKey] = unique
      if (role === 'dictation') next.dictation = unique[0]
      else next[role] = unique[0] ?? null
    } else if (list === undefined && role in incoming) {
      const binding = restoredBinding(incoming[role])
      if (binding) {
        next[role] = binding
        next[listKey] = uniqueRestoredBindings([binding, ...current[listKey].slice(1)])
      } else if (role !== 'dictation' && incoming[role] === null) {
        next[role] = null
        next[listKey] = []
      }
    }
  }
  for (const role of ['editSelection', 'switchScene', 'openApp', 'pasteLast'] as const) {
    if (incoming[role] === null) next[role] = null
    else {
      const binding = restoredBinding(incoming[role])
      if (binding) next[role] = binding
    }
  }
  const mode = incoming.dictationMode
  if (mode === 'hold' || mode === 'toggle' || mode === 'auto') next.dictationMode = mode
  return next
}

export function mergeBackupSettings(current: AppConfig, backup: unknown): AppConfig {
  if (!isRecord(backup)) return current

  const next = { ...current }
  if ('language_variants' in backup) {
    next.language_variants = normalizeLanguageVariants(backup.language_variants)
  } else if (
    backup.polish_chinese_script === 'simplified' ||
    backup.polish_chinese_script === 'traditional'
  ) {
    next.language_variants = {
      ...normalizeLanguageVariants(current.language_variants),
      zh: backup.polish_chinese_script === 'simplified' ? 'zh-CN' : 'zh-TW',
    }
  }
  const writable = next as unknown as Record<string, unknown>
  for (const key of SAFE_SCALAR_KEYS) {
    const incoming = backup[key]
    if (
      (key === 'llm_base_url' || key === 'stt_custom_base_url') &&
      (typeof incoming !== 'string' || safeBackupEndpoint(incoming) === undefined)
    ) {
      continue
    }
    const allowed = ENUM_VALUES[key]
    if (allowed && (typeof incoming !== 'string' || !allowed.includes(incoming))) continue
    if (typeof incoming === 'number' && (!Number.isSafeInteger(incoming) || incoming < 0)) {
      continue
    }
    if (typeof incoming === typeof current[key]) writable[key] = incoming
  }
  if (
    backup.polish_chinese_script === 'simplified' ||
    backup.polish_chinese_script === 'traditional'
  ) {
    next.polish_chinese_script = 'preserve'
  }

  if (isRecord(backup.voice_routing_flags)) {
    next.voice_routing_flags = { ...current.voice_routing_flags }
    for (const key of [
      'draft_insert',
      'rewrite_selection',
      'translate_selection',
      'search',
    ] as const) {
      const incoming = backup.voice_routing_flags[key]
      if (typeof incoming === 'boolean') next.voice_routing_flags[key] = incoming
    }
  }
  if (Array.isArray(backup.custom_scenes)) {
    const imported = importCustomScenesJson(JSON.stringify(backup.custom_scenes), {
      existingIds: new Set(),
      createId: () => `custom_${crypto.randomUUID()}`,
      nowIso: () => new Date().toISOString(),
    })
    if (imported.report.renamedConflicts > 0) {
      throw new Error('Backup scene identifier is ambiguous')
    }
    if (
      imported.scenes.some(
        (scene) =>
          isReservedSceneId(scene.id) &&
          !current.custom_scenes.some((existing) => existing.id === scene.id),
      )
    ) {
      throw new Error('Backup custom scene identifier is reserved')
    }
    if (backup.custom_scenes.length === 0 || imported.scenes.length > 0) {
      next.custom_scenes = imported.scenes
    }
  }
  if (Array.isArray(backup.system_scene_overrides)) {
    const overrides = restoreSystemOverrides(backup.system_scene_overrides)
    if (backup.system_scene_overrides.length === 0 || overrides.length > 0) {
      next.system_scene_overrides = overrides
    }
  }
  if (backup.active_scene === null) {
    next.active_scene = null
  } else {
    const active = restoreActiveScene(backup.active_scene)
    if (active) next.active_scene = active
  }
  if (Array.isArray(backup.family_scene_assignments)) {
    const assignments = restoreFamilyAssignments(backup.family_scene_assignments)
    if (backup.family_scene_assignments.length === 0 || assignments.length > 0) {
      next.family_scene_assignments = assignments
    }
  }
  next.target_lang = current.translation.active_target
  if (typeof backup.target_lang === 'string') {
    const target = backup.target_lang.trim().toLowerCase()
    if (TARGET_LANGUAGES.some((language) => language.value === target)) {
      next.translation = {
        targets: current.translation.targets.includes(target)
          ? [...current.translation.targets]
          : [target, ...current.translation.targets].slice(0, 5),
        active_target: target,
      }
      next.target_lang = target
    }
  }
  if (isRecord(backup.translation)) {
    const { targets, active_target: requestedActive } = backup.translation
    if (
      Array.isArray(targets) &&
      targets.every((target) => typeof target === 'string') &&
      typeof requestedActive === 'string'
    ) {
      const supported = new Set(TARGET_LANGUAGES.map((language) => language.value))
      const normalized = [...new Set(targets.map((target) => target.trim().toLowerCase()))]
        .filter((target) => supported.has(target))
        .slice(0, 5)
      if (normalized.length > 0) {
        const active = requestedActive.trim().toLowerCase()
        const activeTarget = normalized.includes(active) ? active : normalized[0]
        next.translation = { targets: normalized, active_target: activeTarget }
        next.target_lang = activeTarget
      }
    }
  }
  next.hotkeys = { ...current.hotkeys }
  if (typeof backup.hotkey === 'string') {
    const binding = bindingFromHotkey(backup.hotkey)
    if (binding) {
      next.hotkeys.dictation = binding
      next.hotkeys.dictationBindings = [binding]
    }
  }
  if (typeof backup.ask_hotkey === 'string') {
    const binding = bindingFromHotkey(backup.ask_hotkey)
    if (binding || !backup.ask_hotkey.trim()) {
      next.hotkeys.ask = binding
      next.hotkeys.askBindings = binding ? [binding] : []
    }
  }
  if (['auto', 'hold', 'toggle'].includes(backup.hotkey_mode as string)) {
    next.hotkeys.dictationMode = backup.hotkey_mode as HotkeyConfig['dictationMode']
  }
  if (isRecord(backup.hotkeys)) {
    next.hotkeys = restoreHotkeys(next.hotkeys, backup.hotkeys)
  }
  next.hotkey = hotkeyFromBinding(next.hotkeys.dictation)
  next.ask_hotkey = next.hotkeys.ask ? hotkeyFromBinding(next.hotkeys.ask) : ''
  next.hotkey_mode = next.hotkeys.dictationMode

  return next
}
