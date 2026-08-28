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

export type BackupSettings = Partial<Pick<AppConfig, SafeScalarKey>> & {
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
    stt_provider: config.stt_provider,
    stt_language: config.stt_language,
    stt_custom_preset: config.stt_custom_preset,
    stt_custom_base_url: config.stt_custom_base_url,
    stt_custom_model: config.stt_custom_model,
    stt_volcengine_resource_id: config.stt_volcengine_resource_id,
    stt_aliyun_qwen_region: config.stt_aliyun_qwen_region,
    llm_provider: config.llm_provider,
    llm_model: config.llm_model,
    llm_base_url: config.llm_base_url,
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
]

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function mergeBackupSettings(current: AppConfig, backup: unknown): AppConfig {
  if (!isRecord(backup)) return current

  const next = { ...current }
  const writable = next as unknown as Record<string, unknown>
  for (const key of SAFE_SCALAR_KEYS) {
    const incoming = backup[key]
    if (typeof incoming === typeof current[key]) writable[key] = incoming
  }

  if (isRecord(backup.voice_routing_flags)) {
    next.voice_routing_flags = backup.voice_routing_flags as unknown as VoiceRoutingFlags
  }
  if (Array.isArray(backup.custom_scenes)) {
    next.custom_scenes = backup.custom_scenes as CustomScene[]
  }
  if (Array.isArray(backup.system_scene_overrides)) {
    next.system_scene_overrides = backup.system_scene_overrides as SystemSceneOverride[]
  }
  if (backup.active_scene === null || isRecord(backup.active_scene)) {
    next.active_scene = backup.active_scene as ActiveScene | null
  }
  if (Array.isArray(backup.family_scene_assignments)) {
    next.family_scene_assignments = backup.family_scene_assignments as FamilySceneAssignment[]
  }
  if (isRecord(backup.translation)) {
    next.translation = backup.translation as unknown as TranslationConfig
  }
  if (isRecord(backup.hotkeys)) {
    next.hotkeys = backup.hotkeys as unknown as HotkeyConfig
  }

  return next
}
