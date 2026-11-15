import { hotkeyFromBinding, type AppConfig } from '../stores/appStore'
import { mergeConfigDraft, type ConfigFieldGroup } from './configCommit'
import { mergeHotkeyDraft } from './mergeHotkeyDraft'

type FieldPolicy = 'editable' | 'native' | 'credentials' | 'hotkeys' | 'translation' | 'scenes'

export const APP_CONFIG_FIELD_POLICY = {
  stt_provider: 'editable',
  stt_api_key: 'credentials',
  stt_custom_api_key: 'credentials',
  stt_custom_preset: 'editable',
  stt_custom_base_url: 'editable',
  stt_custom_model: 'editable',
  stt_volcengine_resource_id: 'editable',
  stt_aliyun_qwen_region: 'editable',
  stt_language: 'editable',
  llm_provider: 'editable',
  llm_api_key: 'credentials',
  llm_model: 'editable',
  llm_base_url: 'editable',
  polish_enabled: 'editable',
  context_adaptation_enabled: 'editable',
  voice_routing_flags: 'editable',
  polish_style: 'editable',
  polish_custom_prompt: 'editable',
  polish_chinese_script: 'editable',
  language_variants: 'editable',
  custom_scenes: 'scenes',
  system_scene_overrides: 'scenes',
  active_scene: 'scenes',
  family_scene_assignments: 'scenes',
  translate_enabled: 'editable',
  target_lang: 'translation',
  translation: 'translation',
  hotkey: 'hotkeys',
  ask_hotkey: 'hotkeys',
  hotkey_mode: 'hotkeys',
  hotkeys: 'hotkeys',
  output_mode: 'editable',
  insertion_strategy: 'editable',
  restore_clipboard_after_paste: 'editable',
  paste_shortcut: 'editable',
  windows_sendinput_newline_mode: 'editable',
  streaming_insert_enabled: 'editable',
  selected_text_enabled: 'editable',
  theme: 'editable',
  auto_start: 'editable',
  close_to_tray: 'editable',
  start_minimized: 'editable',
  recording_limit_mode: 'editable',
  custom_recording_limit_seconds: 'editable',
  max_recording_seconds: 'native',
  managed_stt_capability_state: 'native',
  history_enabled: 'editable',
  history_retention_days: 'editable',
  history_max_entries: 'editable',
  ui_language: 'editable',
  capsule_auto_hide: 'editable',
  microphone_device: 'editable',
  interaction_sounds: 'editable',
  mute_background_audio: 'editable',
  show_in_dock: 'editable',
  save_recording_audio: 'editable',
  auto_learn_dictionary: 'editable',
} as const satisfies Record<keyof AppConfig, FieldPolicy>

const associatedGroups = new Map<FieldPolicy, (keyof AppConfig)[]>()
const groups: ConfigFieldGroup<AppConfig>[] = []
for (const field of Object.keys(APP_CONFIG_FIELD_POLICY) as (keyof AppConfig)[]) {
  const policy = APP_CONFIG_FIELD_POLICY[field]
  if (policy === 'editable' || policy === 'native') {
    groups.push({ fields: [field], authority: policy })
  } else {
    const fields = associatedGroups.get(policy) ?? []
    fields.push(field)
    associatedGroups.set(policy, fields)
  }
}
for (const [policy, fields] of associatedGroups) {
  groups.push({ fields, authority: policy === 'credentials' ? 'native' : 'editable' })
}

export const APP_CONFIG_FIELD_GROUPS: readonly ConfigFieldGroup<AppConfig>[] = groups

export function mergeAppConfigDraft(
  baseline: AppConfig,
  draft: AppConfig,
  persisted: AppConfig,
): AppConfig {
  const withCapability = (config: AppConfig): AppConfig => ({
    ...config,
    managed_stt_capability_state: config.managed_stt_capability_state ?? null,
  })
  const merged = mergeConfigDraft(
    withCapability(baseline),
    withCapability(draft),
    withCapability(persisted),
    APP_CONFIG_FIELD_GROUPS,
  )
  const hotkeys = structuredClone(
    mergeHotkeyDraft(draft.hotkeys, baseline.hotkeys, persisted.hotkeys),
  )
  return {
    ...merged,
    hotkeys,
    hotkey: hotkeyFromBinding(hotkeys.dictation),
    ask_hotkey: hotkeys.ask ? hotkeyFromBinding(hotkeys.ask) : '',
    hotkey_mode: hotkeys.dictationMode,
  }
}
