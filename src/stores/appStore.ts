import { create } from 'zustand'
import { normalizeLanguageVariants, type LanguageVariants } from '../lib/languageVariants'
import type { CapsuleErrorKey } from '../lib/capsuleError'
import type { CapsuleRecordingStyle } from '../lib/capsuleView'
import { TARGET_LANGUAGES } from '../lib/constants'
import { valuesEqual } from '../lib/configValuesEqual'
import { mergeHotkeyDraft } from '../lib/mergeHotkeyDraft'
import {
  ConfigCommitCursor,
  type ConfigCommit,
  type ConfigCommitDisposition,
} from '../lib/configCommit'
import { isAppConfigSnapshot } from '../lib/appConfigSnapshot'
import { mergeAppConfigDraft } from '../lib/appConfigMergePolicy'
import { pendingConfigSaves } from '../lib/configSaveRegistry'

export type PipelineState =
  | 'idle'
  | 'preparing'
  | 'recording'
  | 'transcribing'
  | 'polishing'
  | 'outputting'
  | 'ask_recording'
  | 'ask_thinking'

export type VoiceMode = 'dictate' | 'ask' | 'translate'

export type SttProvider =
  | 'deepgram'
  | 'assemblyai'
  | 'aliyun-qwen3-asr'
  | 'volcengine-doubao'
  | 'glm-asr'
  | 'openai-whisper'
  | 'groq-whisper'
  | 'siliconflow'
  | 'apple-speech'
  | 'custom-whisper'
  | 'cloud'

export type AliyunQwenRegion = 'china-mainland' | 'international'
export type LlmProvider =
  | 'zhipu'
  | 'deepseek'
  | 'siliconflow'
  | 'openai'
  | 'gemini'
  | 'moonshot'
  | 'doubao'
  | 'qwen'
  | 'groq'
  | 'claude'
  | 'ollama'
  | 'openrouter'
  | 'cloud'
export type OutputMode = 'keyboard' | 'clipboard'
export type PasteShortcut = 'ctrlV' | 'ctrlShiftV' | 'shiftInsert'
export type WindowsSendInputNewlineMode = 'enter' | 'shiftEnter' | 'crlf'
export type InsertionStrategy =
  | 'auto'
  | 'keyboard'
  | 'clipboardPaste'
  | 'clipboardCopyOnly'
  | 'windowsSendInput'
export type InsertStatus = 'inserted' | 'copiedFallback' | 'failed' | 'partiallyInserted'
export type HotkeyMode = 'hold' | 'toggle' | 'auto'
export type Theme = 'light' | 'dark' | 'system'
export type PolishChineseScript = 'preserve' | 'simplified' | 'traditional'
export type PolishStyle = 'minimal' | 'clean' | 'structured' | 'professional' | 'custom'
export type SceneSource = 'custom' | 'builtin' | 'cloud'
export type ContextFamily =
  | 'email'
  | 'work_chat'
  | 'personal_chat'
  | 'document'
  | 'project_management'
  | 'developer_collaboration'
  | 'prompt_or_code'
  | 'support'
  | 'social'
  | 'general'
export type BrowserAccessStatus = 'available' | 'needs_permission' | 'not_applicable' | 'unknown'
export type BrowserTarget = 'safari' | 'chrome' | 'edge' | 'brave' | 'arc'

export interface ShortcutBinding {
  primary: string
  modifiers: string[]
}

export interface HotkeyConfig {
  dictation: ShortcutBinding
  ask: ShortcutBinding | null
  translate: ShortcutBinding | null
  dictationBindings: ShortcutBinding[]
  askBindings: ShortcutBinding[]
  translateBindings: ShortcutBinding[]
  editSelection: ShortcutBinding | null
  switchScene: ShortcutBinding | null
  openApp: ShortcutBinding | null
  /** Re-inserts the newest transcript (Typeless "Paste last transcript"). */
  pasteLast: ShortcutBinding | null
  dictationMode: HotkeyMode
}

export interface PlatformCapabilities {
  os: 'macos' | 'windows' | 'linux' | 'unknown'
  sessionType: 'wayland' | 'x11' | 'unknown'
  globalHotkeyReliable: boolean
  keyboardOutputReliable: boolean
  clipboardAutoPasteReliable: boolean
}

export interface HistoryEntry {
  has_audio?: boolean
  id: number
  created_at: string
  context_profile_id: string
  context_label: string
  context_icon_key: string
  context_family: ContextFamily
  browser_access_status: BrowserAccessStatus
  provider_kind: 'managed_cloud' | 'byok' | 'local'
  raw_text: string
  polished_text: string
  language: string | null
  duration_ms: number | null
  active_scene_id: string | null
  active_scene_source: SceneSource | string | null
  active_scene_name: string | null
  active_scene_prompt_chars: number | null
  active_scene_prompt_truncated: boolean
  output_status: string | null
  output_error: string | null
  mode?: 'dictate' | 'translate' | 'ask'
  answer_text?: string | null
  ask_intent?: 'open_question' | 'ask_selection' | null
  citation_supports?: Array<{ start: number; end: number; sourceIndex: number }>
}

export interface ContextProfileSummary {
  profileId: string
  family: ContextFamily
  appLabel: string
  iconKey: string
  overrideId: string | null
  browserAccessStatus?: BrowserAccessStatus
  browserTarget?: BrowserTarget | null
}

export interface InsertResult {
  status: InsertStatus
  strategyUsed: InsertionStrategy
  charsInserted: number
  charsCopied: number
  warningCode: string | null
  message: string | null
}

export interface DictionaryEntry {
  id: number
  word: string
  pronunciation: string | null
  auto?: boolean
}

export interface CorrectionRule {
  id: number
  pattern: string
  replacement: string
  enabled: boolean
}

export interface CustomScene {
  id: string
  name: string
  description: string
  prompt_template: string
  created_at: string
  updated_at: string
}

export interface SystemSceneOverride {
  id: string
  prompt_template: string
}

export interface ActiveScene {
  id: string
  source: SceneSource
  name: string
  prompt_template: string
}

export interface FamilySceneAssignment {
  family: ContextFamily
  scene_id: string
}

export interface VoiceRoutingFlags {
  draft_insert: boolean
  rewrite_selection: boolean
  translate_selection: boolean
  search: boolean
}

export interface TranslationConfig {
  targets: string[]
  active_target: string
}

export interface AppConfig {
  stt_provider: SttProvider
  stt_api_key: string
  stt_custom_api_key: string
  stt_custom_preset: 'speaches' | 'custom'
  stt_custom_base_url: string
  stt_custom_model: string
  stt_volcengine_resource_id: string
  stt_aliyun_qwen_region: AliyunQwenRegion
  stt_language: string
  llm_provider: LlmProvider
  llm_api_key: string
  llm_model: string
  llm_base_url: string
  polish_enabled: boolean
  context_adaptation_enabled: boolean
  voice_routing_flags: VoiceRoutingFlags
  polish_style: PolishStyle
  polish_custom_prompt: string
  polish_chinese_script: PolishChineseScript
  language_variants: LanguageVariants
  custom_scenes: CustomScene[]
  system_scene_overrides: SystemSceneOverride[]
  active_scene: ActiveScene | null
  family_scene_assignments: FamilySceneAssignment[]
  translate_enabled: boolean
  target_lang: string
  translation: TranslationConfig
  hotkey: string
  ask_hotkey: string
  hotkey_mode: HotkeyMode
  hotkeys: HotkeyConfig
  output_mode: OutputMode
  insertion_strategy: InsertionStrategy
  restore_clipboard_after_paste: boolean
  paste_shortcut: PasteShortcut
  windows_sendinput_newline_mode: WindowsSendInputNewlineMode
  streaming_insert_enabled: boolean
  selected_text_enabled: boolean
  theme: Theme
  auto_start: boolean
  close_to_tray: boolean
  start_minimized: boolean
  recording_limit_mode: 'auto' | 'custom'
  custom_recording_limit_seconds: number
  max_recording_seconds: number
  managed_stt_capability_state?: unknown
  history_enabled: boolean
  history_retention_days: number
  history_max_entries: number
  ui_language: string
  capsule_auto_hide: boolean
  microphone_device: string
  interaction_sounds: boolean
  mute_background_audio: boolean
  /** macOS: keep the app icon in the Dock. */
  show_in_dock: boolean
  save_recording_audio: boolean
  /** macOS: learn names the user corrects right after dictating. */
  auto_learn_dictionary: boolean
}

export type TestStatus = 'idle' | 'testing' | 'success' | 'error'

export interface RecordingDeadlineSnapshot {
  sessionId: number
  recordingKind: 'dictation' | 'ask'
  startedAtUnixMs: number
  deadlineAtUnixMs: number
  effectiveMaxSeconds: number
}

interface AppState {
  // Pipeline
  pipelineState: PipelineState
  setPipelineState: (state: PipelineState) => void
  activeVoiceMode: VoiceMode | null
  setActiveVoiceMode: (mode: VoiceMode | null) => void
  recordingStyle: CapsuleRecordingStyle
  setRecordingStyle: (style: CapsuleRecordingStyle) => void

  // Recording
  audioVolume: number
  setAudioVolume: (v: number) => void
  partialTranscript: string
  setPartialTranscript: (t: string) => void
  finalTranscript: string
  setFinalTranscript: (t: string) => void
  polishedText: string
  setPolishedText: (t: string) => void
  appendPolishedChunk: (chunk: string) => void
  recordingDuration: number
  setRecordingDuration: (d: number) => void
  recordingDeadline: RecordingDeadlineSnapshot | null
  setRecordingDeadline: (deadline: RecordingDeadlineSnapshot | null) => void
  targetApp: string
  setTargetApp: (app: string) => void
  lastInsertResult: InsertResult | null
  setLastInsertResult: (result: InsertResult | null) => void
  lastContext: ContextProfileSummary | null
  setLastContext: (context: ContextProfileSummary | null) => void

  // Config
  config: AppConfig
  setConfig: (config: AppConfig) => void
  updateConfig: (partial: Partial<AppConfig>) => void
  applyPersistedConfigPatch: (patch: Partial<AppConfig>) => void
  configCommit: ConfigCommit<AppConfig> | null
  initializeConfigCommit: (commit: unknown) => boolean
  applyConfigCommit: (commit: unknown) => ConfigCommitDisposition

  // History
  history: HistoryEntry[]
  setHistory: (h: HistoryEntry[]) => void

  // Dictionary
  dictionary: DictionaryEntry[]
  setDictionary: (d: DictionaryEntry[]) => void
  correctionRules: CorrectionRule[]
  setCorrectionRules: (rules: CorrectionRule[]) => void

  // Onboarding
  onboardingCompleted: boolean
  setOnboardingCompleted: (done: boolean) => void
  onboardingStep: number
  setOnboardingStep: (step: number) => void
  onboardingMode: 'cloud' | 'byok' | null
  setOnboardingMode: (mode: 'cloud' | 'byok' | null) => void

  // Capsule
  capsuleExpanded: boolean
  setCapsuleExpanded: (expanded: boolean) => void

  // Connection test status
  sttTestStatus: TestStatus
  setSttTestStatus: (s: TestStatus) => void
  llmTestStatus: TestStatus
  setLlmTestStatus: (s: TestStatus) => void

  // Latency benchmark results (ms), null = not yet measured
  sttLatencyMs: number | null
  setSttLatencyMs: (ms: number | null) => void
  llmLatencyMs: number | null
  setLlmLatencyMs: (ms: number | null) => void

  // LLM model list cache (persists across tab switches)
  llmModels: string[]
  setLlmModels: (models: string[]) => void

  // Pipeline error
  pipelineError: string | null
  pipelineErrorKey: CapsuleErrorKey | null
  pipelineErrorMessageKey: string | null
  retryAvailable: boolean
  setRetryAvailable: (available: boolean) => void
  /** History row of the failed take shown in the error alert; Retry re-runs it in full. */
  failedTakeId: number | null
  setFailedTakeId: (id: number | null) => void
  setPipelineErrorKey: (key: CapsuleErrorKey | null) => void
  setPipelineError: (error: string | null, messageKey?: string) => void

  // macOS Accessibility permission
  accessibilityTrusted: boolean
  setAccessibilityTrusted: (trusted: boolean) => void
  platformCapabilities: PlatformCapabilities | null
  setPlatformCapabilities: (capabilities: PlatformCapabilities | null) => void
  hotkeyRegistrationError: string | null
  setHotkeyRegistrationError: (error: string | null) => void

  // Context menu
  translationTargetMenuOpen: boolean
  setTranslationTargetMenuOpen: (open: boolean) => void

  // Reset recording state
  resetRecording: () => void

  // Config snapshot for dirty detection
  savedConfig: AppConfig | null
  setSavedConfig: (config: AppConfig) => void
  resetConfig: () => void
}

export const isMacPlatform = () =>
  typeof navigator !== 'undefined' && navigator.platform.toUpperCase().indexOf('MAC') >= 0

export const isWindowsPlatform = () =>
  typeof navigator !== 'undefined' && navigator.platform.toUpperCase().indexOf('WIN') >= 0

function defaultDictationHotkey(): string {
  if (isMacPlatform()) return 'Fn'
  if (isWindowsPlatform()) return 'RightAlt'
  return 'Ctrl+/'
}

function isHotkeyMode(value: unknown): value is HotkeyMode {
  return value === 'hold' || value === 'toggle' || value === 'auto'
}

function defaultDictationHotkeyMode(): HotkeyMode {
  return isMacPlatform() ? 'toggle' : 'hold'
}

function defaultAskHotkey(): string {
  if (isMacPlatform()) return 'Fn+Space'
  if (isWindowsPlatform()) return 'RightAlt+Space'
  return 'Ctrl+.'
}

/** Typeless default: Ctrl+Cmd+V on macOS, Ctrl+Shift+V elsewhere. */
function defaultPasteLastHotkey(): string {
  return isMacPlatform() ? 'Ctrl+Command+V' : 'Ctrl+Shift+V'
}

function defaultTranslateHotkey(): string | null {
  if (isMacPlatform()) return 'Fn+LeftShift'
  if (isWindowsPlatform()) return 'RightAlt+RightShift'
  return 'Ctrl+Shift+/'
}

const modifierOrder = ['Fn', 'RightAlt', 'Command', 'Super', 'Ctrl', 'Option', 'Alt', 'Shift']

function normalizeModifier(value: string): string | null {
  switch (value.trim().toLowerCase()) {
    case 'fn':
    case 'function':
      return 'Fn'
    case 'rightalt':
    case 'right_alt':
    case 'right-alt':
    case 'altright':
    case 'alt_right':
    case 'alt-right':
      return 'RightAlt'
    case 'cmd':
    case 'command':
      return 'Command'
    case 'meta':
    case 'super':
    case 'win':
      return 'Super'
    case 'ctrl':
    case 'control':
      return 'Ctrl'
    case 'option':
      return 'Option'
    case 'alt':
      return 'Alt'
    case 'shift':
      return 'Shift'
    default:
      return null
  }
}

function normalizePrimary(value: string): string | null {
  const trimmed = value.trim()
  if (!trimmed) return null

  const lower = trimmed.toLowerCase()
  const named: Record<string, string> = {
    space: 'Space',
    tab: 'Tab',
    enter: 'Enter',
    return: 'Enter',
    escape: 'Escape',
    esc: 'Escape',
    arrowup: 'Up',
    up: 'Up',
    arrowdown: 'Down',
    down: 'Down',
    arrowleft: 'Left',
    left: 'Left',
    arrowright: 'Right',
    right: 'Right',
    home: 'Home',
    end: 'End',
    pageup: 'PageUp',
    pagedown: 'PageDown',
    insert: 'Insert',
    delete: 'Delete',
    backspace: 'Backspace',
    leftshift: 'LeftShift',
    left_shift: 'LeftShift',
    'left-shift': 'LeftShift',
    shiftleft: 'LeftShift',
    shift_left: 'LeftShift',
    'shift-left': 'LeftShift',
    rightshift: 'RightShift',
    right_shift: 'RightShift',
    'right-shift': 'RightShift',
    shiftright: 'RightShift',
    shift_right: 'RightShift',
    'shift-right': 'RightShift',
    slash: '/',
    '/': '/',
    period: '.',
    '.': '.',
    '。': '.',
    comma: ',',
    ',': ',',
    semicolon: ';',
    ';': ';',
    minus: '-',
    '-': '-',
    equal: '=',
    '=': '=',
    bracketleft: '[',
    '[': '[',
    bracketright: ']',
    ']': ']',
    backslash: '\\',
    '\\': '\\',
    quote: "'",
    "'": "'",
    backquote: '`',
    '`': '`',
  }
  const nativePrimary: Record<string, string> = {
    fn: 'Fn',
    function: 'Fn',
    rightalt: 'RightAlt',
    right_alt: 'RightAlt',
    'right-alt': 'RightAlt',
    altright: 'RightAlt',
    alt_right: 'RightAlt',
    'alt-right': 'RightAlt',
  }
  if (Object.prototype.hasOwnProperty.call(nativePrimary, lower)) return nativePrimary[lower]
  if (Object.prototype.hasOwnProperty.call(named, lower)) return named[lower]
  if (/^f([1-9]|1[0-2])$/.test(lower)) return lower.toUpperCase()
  if (/^[a-z0-9]$/.test(lower)) return lower.toUpperCase()
  return null
}

export function bindingFromHotkey(value: string): ShortcutBinding | null {
  const parts = value.split('+').map((part) => part.trim())
  if (parts.some((part) => part.length === 0)) return null

  const primary = normalizePrimary(parts[parts.length - 1])
  if (!primary) return null

  const modifiers: string[] = []
  const seen = new Set<string>()
  for (const part of parts.slice(0, -1)) {
    const modifier = normalizeModifier(part)
    if (!modifier) return null
    const semantic = modifier === 'Option' ? 'Alt' : modifier === 'Command' ? 'Super' : modifier
    if (seen.has(semantic)) return null
    seen.add(semantic)
    modifiers.push(modifier)
  }

  modifiers.sort((a, b) => modifierOrder.indexOf(a) - modifierOrder.indexOf(b))
  return { primary, modifiers }
}

export function hotkeyFromBinding(binding: ShortcutBinding): string {
  return [...binding.modifiers, binding.primary].join('+')
}

const MAX_HOTKEY_BINDINGS_PER_ROLE = 4

function normalizeBinding(binding: ShortcutBinding | null | undefined): ShortcutBinding | null {
  if (!binding) return null
  return bindingFromHotkey(hotkeyFromBinding(binding))
}

function hotkeyBindingIdentity(binding: ShortcutBinding): string {
  const modifiers = binding.modifiers.map((modifier) => {
    if (modifier === 'Option') return 'Alt'
    if (modifier === 'Command') return 'Super'
    return modifier
  })
  return [...modifiers, binding.primary].join('+')
}

function normalizeBindingList(bindings: ShortcutBinding[] | null | undefined): ShortcutBinding[] {
  const normalized: ShortcutBinding[] = []
  const seen = new Set<string>()
  for (const binding of Array.isArray(bindings) ? bindings : []) {
    const next = normalizeBinding(binding)
    if (!next) continue
    const identity = hotkeyBindingIdentity(next)
    if (seen.has(identity)) continue
    seen.add(identity)
    normalized.push(next)
    if (normalized.length === MAX_HOTKEY_BINDINGS_PER_ROLE) break
  }
  return normalized
}

function normalizeHotkeyConfig(config: AppConfig, hotkeysValue: HotkeyConfig): HotkeyConfig {
  const hotkeys = hotkeysValue as HotkeyConfig & {
    dictationBindings?: ShortcutBinding[]
    askBindings?: ShortcutBinding[]
    translateBindings?: ShortcutBinding[]
  }
  const scalarDictation = normalizeBinding(hotkeys.dictation)
  const dictationBindings = normalizeBindingList(
    hotkeys.dictationBindings?.length
      ? hotkeys.dictationBindings
      : scalarDictation
        ? [scalarDictation]
        : [],
  )
  if (dictationBindings.length === 0) {
    dictationBindings.push(bindingFromHotkey(defaultDictationHotkey())!)
  }

  const hasAskList = Array.isArray(hotkeys.askBindings)
  const askBindings = normalizeBindingList(
    hasAskList ? hotkeys.askBindings : hotkeys.ask ? [hotkeys.ask] : [],
  )
  const hasTranslateList = Array.isArray(hotkeys.translateBindings)
  const translateBindings = normalizeBindingList(
    hasTranslateList ? hotkeys.translateBindings : hotkeys.translate ? [hotkeys.translate] : [],
  )

  return {
    dictation: dictationBindings[0],
    ask: askBindings[0] ?? null,
    translate: translateBindings[0] ?? null,
    dictationBindings,
    askBindings,
    translateBindings,
    editSelection: normalizeBinding(hotkeys.editSelection),
    switchScene: normalizeBinding(hotkeys.switchScene),
    openApp: normalizeBinding(hotkeys.openApp),
    pasteLast: normalizeBinding(hotkeys.pasteLast),
    dictationMode: isHotkeyMode(hotkeys.dictationMode)
      ? hotkeys.dictationMode
      : isHotkeyMode(config.hotkey_mode)
        ? config.hotkey_mode
        : defaultDictationHotkeyMode(),
  }
}

function hotkeyConfigFromLegacy(config: AppConfig): HotkeyConfig {
  const dictation = bindingFromHotkey(config.hotkey) ?? bindingFromHotkey(defaultDictationHotkey())!
  const ask = config.ask_hotkey.trim()
    ? (bindingFromHotkey(config.ask_hotkey) ?? bindingFromHotkey(defaultAskHotkey()))
    : null
  const existingTranslate =
    config.hotkeys?.translate ??
    (defaultTranslateHotkey() ? bindingFromHotkey(defaultTranslateHotkey()!) : null)
  const translateBindings = normalizeBindingList(
    config.hotkeys?.translateBindings?.length
      ? config.hotkeys.translateBindings
      : existingTranslate
        ? [existingTranslate]
        : [],
  )
  return normalizeHotkeyConfig(config, {
    dictation,
    ask,
    translate: translateBindings[0] ?? null,
    dictationBindings: [dictation],
    askBindings: ask ? [ask] : [],
    translateBindings,
    editSelection: config.hotkeys?.editSelection ?? null,
    switchScene: config.hotkeys?.switchScene ?? null,
    openApp: config.hotkeys?.openApp ?? null,
    pasteLast:
      config.hotkeys?.pasteLast === undefined
        ? bindingFromHotkey(defaultPasteLastHotkey())
        : config.hotkeys.pasteLast,
    dictationMode: isHotkeyMode(config.hotkey_mode)
      ? config.hotkey_mode
      : defaultDictationHotkeyMode(),
  })
}

function syncLegacyHotkeysToTyped(config: AppConfig): AppConfig {
  return { ...config, hotkeys: hotkeyConfigFromLegacy(config) }
}

function syncTypedHotkeysToLegacy(config: AppConfig): AppConfig {
  const hotkeys = config.hotkeys
    ? normalizeHotkeyConfig(config, config.hotkeys)
    : hotkeyConfigFromLegacy(config)
  return {
    ...config,
    hotkey: hotkeyFromBinding(hotkeys.dictation),
    ask_hotkey: hotkeys.ask ? hotkeyFromBinding(hotkeys.ask) : '',
    hotkey_mode: hotkeys.dictationMode,
    hotkeys,
  }
}

const supportedTranslationCodes = new Set(TARGET_LANGUAGES.map((language) => language.value))

function normalizeTranslationTargets(targets: string[]): string[] {
  const normalized: string[] = []
  for (const value of targets) {
    const code = value.trim().toLowerCase()
    if (!supportedTranslationCodes.has(code) || normalized.includes(code)) continue
    normalized.push(code)
    if (normalized.length === 5) break
  }
  return normalized
}

function syncTranslationConfig(previous: AppConfig, partial: Partial<AppConfig>): AppConfig {
  const merged = { ...previous, ...partial }
  if (partial.translation) {
    const targets = normalizeTranslationTargets(partial.translation.targets)
    const requestedActive = partial.translation.active_target.trim().toLowerCase()
    if (targets.length === 0) {
      targets.push(supportedTranslationCodes.has(requestedActive) ? requestedActive : 'en')
    }
    const activeTarget = targets.includes(requestedActive) ? requestedActive : targets[0]
    return {
      ...merged,
      target_lang: activeTarget,
      translation: { targets, active_target: activeTarget },
    }
  }

  if ('target_lang' in partial) {
    const requested = partial.target_lang?.trim().toLowerCase() ?? ''
    const activeTarget = supportedTranslationCodes.has(requested)
      ? requested
      : previous.translation?.active_target || 'en'
    const targets = normalizeTranslationTargets(previous.translation?.targets ?? [activeTarget])
    if (!targets.includes(activeTarget)) {
      if (targets.length === 5) targets[targets.length - 1] = activeTarget
      else targets.push(activeTarget)
    }
    return {
      ...merged,
      target_lang: activeTarget,
      translation: { targets, active_target: activeTarget },
    }
  }

  const current = merged.translation
  if (!current) {
    const activeTarget = supportedTranslationCodes.has(merged.target_lang)
      ? merged.target_lang
      : 'en'
    return {
      ...merged,
      target_lang: activeTarget,
      translation: { targets: [activeTarget], active_target: activeTarget },
    }
  }
  return merged
}

function syncHotkeyConfig(previous: AppConfig, partial: Partial<AppConfig>): AppConfig {
  const merged = syncTranslationConfig(previous, partial)
  merged.language_variants = normalizeLanguageVariants(merged.language_variants)
  if (partial.hotkeys) {
    const hotkeys = { ...partial.hotkeys }
    const listsChanged =
      JSON.stringify(hotkeys.dictationBindings) !==
        JSON.stringify(previous.hotkeys.dictationBindings) ||
      JSON.stringify(hotkeys.askBindings) !== JSON.stringify(previous.hotkeys.askBindings) ||
      JSON.stringify(hotkeys.translateBindings) !==
        JSON.stringify(previous.hotkeys.translateBindings)
    if (!listsChanged) {
      if (JSON.stringify(hotkeys.dictation) !== JSON.stringify(previous.hotkeys.dictation)) {
        hotkeys.dictationBindings = [
          hotkeys.dictation,
          ...(hotkeys.dictationBindings ?? []).slice(1),
        ]
      }
      if (JSON.stringify(hotkeys.ask) !== JSON.stringify(previous.hotkeys.ask)) {
        hotkeys.askBindings = hotkeys.ask
          ? [hotkeys.ask, ...(hotkeys.askBindings ?? []).slice(1)]
          : []
      }
      if (JSON.stringify(hotkeys.translate) !== JSON.stringify(previous.hotkeys.translate)) {
        hotkeys.translateBindings = hotkeys.translate
          ? [hotkeys.translate, ...(hotkeys.translateBindings ?? []).slice(1)]
          : []
      }
    }
    return syncTypedHotkeysToLegacy({ ...merged, hotkeys })
  }
  if ('hotkey' in partial || 'ask_hotkey' in partial || 'hotkey_mode' in partial) {
    if (
      'hotkey_mode' in partial &&
      !('hotkey' in partial) &&
      !('ask_hotkey' in partial) &&
      merged.hotkeys
    ) {
      return syncTypedHotkeysToLegacy({
        ...merged,
        hotkeys: {
          ...merged.hotkeys,
          dictationMode: isHotkeyMode(partial.hotkey_mode)
            ? partial.hotkey_mode
            : previous.hotkeys.dictationMode,
        },
      })
    }
    return syncLegacyHotkeysToTyped(merged)
  }
  return merged.hotkeys ? merged : syncLegacyHotkeysToTyped(merged)
}

function modelStateForConfigChange(previous: AppConfig, next: AppConfig) {
  const sttChanged = (
    [
      'stt_provider',
      'stt_api_key',
      'stt_custom_api_key',
      'stt_custom_base_url',
      'stt_custom_model',
      'stt_volcengine_resource_id',
      'stt_aliyun_qwen_region',
    ] as const
  ).some((field) => previous[field] !== next[field])
  const endpointChanged =
    previous.llm_provider !== next.llm_provider ||
    previous.llm_base_url !== next.llm_base_url ||
    previous.llm_api_key !== next.llm_api_key
  const modelChanged = previous.llm_model !== next.llm_model
  return {
    ...(sttChanged ? { sttTestStatus: 'idle' as TestStatus, sttLatencyMs: null } : {}),
    ...(endpointChanged ? { llmModels: [] as string[] } : {}),
    ...(endpointChanged || modelChanged
      ? { llmTestStatus: 'idle' as TestStatus, llmLatencyMs: null }
      : {}),
  }
}

const defaultConfig: AppConfig = {
  stt_provider: 'glm-asr',
  stt_api_key: '',
  stt_custom_api_key: '',
  stt_custom_preset: 'speaches',
  stt_custom_base_url: 'http://localhost:8000/v1',
  stt_custom_model: 'Systran/faster-whisper-large-v3',
  stt_volcengine_resource_id: 'volc.seedasr.sauc.duration',
  stt_aliyun_qwen_region: 'china-mainland',
  stt_language: 'multi',
  llm_provider: 'openrouter',
  llm_api_key: '',
  llm_model: 'google/gemini-2.5-flash',
  llm_base_url: 'https://openrouter.ai/api/v1',
  polish_enabled: true,
  context_adaptation_enabled: true,
  voice_routing_flags: {
    draft_insert: true,
    rewrite_selection: true,
    translate_selection: true,
    search: true,
  },
  polish_style: 'clean',
  polish_custom_prompt: '',
  polish_chinese_script: 'preserve',
  language_variants: {},
  custom_scenes: [],
  system_scene_overrides: [],
  active_scene: null,
  family_scene_assignments: [],
  translate_enabled: false,
  target_lang: 'en',
  translation: { targets: ['en'], active_target: 'en' },
  hotkey: defaultDictationHotkey(),
  ask_hotkey: defaultAskHotkey(),
  hotkey_mode: defaultDictationHotkeyMode(),
  hotkeys: {
    dictation: bindingFromHotkey(defaultDictationHotkey())!,
    ask: bindingFromHotkey(defaultAskHotkey()),
    translate: defaultTranslateHotkey() ? bindingFromHotkey(defaultTranslateHotkey()!) : null,
    dictationBindings: [bindingFromHotkey(defaultDictationHotkey())!],
    askBindings: [bindingFromHotkey(defaultAskHotkey())!],
    translateBindings: defaultTranslateHotkey()
      ? [bindingFromHotkey(defaultTranslateHotkey()!)!]
      : [],
    editSelection: null,
    switchScene: null,
    openApp: null,
    pasteLast: bindingFromHotkey(defaultPasteLastHotkey()),
    dictationMode: defaultDictationHotkeyMode(),
  },
  output_mode: 'keyboard',
  insertion_strategy: 'auto',
  restore_clipboard_after_paste: true,
  paste_shortcut: 'ctrlV',
  windows_sendinput_newline_mode: 'enter',
  streaming_insert_enabled: false,
  selected_text_enabled: false,
  theme: 'system',
  auto_start: true,
  close_to_tray: true,
  start_minimized: false,
  recording_limit_mode: 'auto',
  custom_recording_limit_seconds: 600,
  max_recording_seconds: 30,
  history_enabled: true,
  history_retention_days: 0,
  history_max_entries: 5000,
  ui_language: 'en',
  capsule_auto_hide: true,
  microphone_device: '',
  interaction_sounds: true,
  mute_background_audio: true,
  show_in_dock: true,
  save_recording_audio: true,
  auto_learn_dictionary: true,
}

export const useAppStore = create<AppState>((set) => ({
  pipelineState: 'idle',
  setPipelineState: (pipelineState) => set({ pipelineState }),
  activeVoiceMode: null,
  setActiveVoiceMode: (activeVoiceMode) => set({ activeVoiceMode }),
  recordingStyle: 'handsFree',
  setRecordingStyle: (recordingStyle) => set({ recordingStyle }),

  audioVolume: 0,
  setAudioVolume: (audioVolume) =>
    set({
      audioVolume: Number.isFinite(audioVolume) ? Math.min(1, Math.max(0, audioVolume)) : 0,
    }),
  partialTranscript: '',
  setPartialTranscript: (partialTranscript) => set({ partialTranscript }),
  finalTranscript: '',
  setFinalTranscript: (finalTranscript) => set({ finalTranscript }),
  polishedText: '',
  setPolishedText: (polishedText) => set({ polishedText }),
  appendPolishedChunk: (chunk) => set((s) => ({ polishedText: s.polishedText + chunk })),
  recordingDuration: 0,
  setRecordingDuration: (recordingDuration) => set({ recordingDuration }),
  recordingDeadline: null,
  setRecordingDeadline: (recordingDeadline) => set({ recordingDeadline }),
  targetApp: '',
  setTargetApp: (targetApp) => set({ targetApp }),
  lastInsertResult: null,
  setLastInsertResult: (lastInsertResult) => set({ lastInsertResult }),
  lastContext: null,
  setLastContext: (lastContext) => set({ lastContext }),

  config: defaultConfig,
  configCommit: null,
  initializeConfigCommit: (incoming) => {
    let initialized = false
    set((state) => {
      const cursor = new ConfigCommitCursor<AppConfig>(isAppConfigSnapshot)
      if (state.configCommit) cursor.initialize(state.configCommit)
      const handshake = new ConfigCommitCursor<AppConfig>(isAppConfigSnapshot)
      if (!handshake.initialize(incoming)) return state
      const read = handshake.snapshot!
      if (
        state.configCommit &&
        read.configEpoch === state.configCommit.configEpoch &&
        read.revision === state.configCommit.revision &&
        read.saveId === null &&
        valuesEqual(read.config, state.configCommit.config)
      ) {
        initialized = true
        return state
      }
      if (!cursor.initialize(incoming)) return state
      const accepted = cursor.snapshot!
      initialized = true
      if (valuesEqual(state.configCommit, accepted)) return state
      try {
        const config = mergeAppConfigDraft(
          state.savedConfig ?? defaultConfig,
          state.config,
          accepted.config,
        )
        return {
          configCommit: accepted,
          config,
          savedConfig: structuredClone(accepted.config),
          ...modelStateForConfigChange(state.config, config),
        }
      } catch {
        initialized = false
        return state
      }
    })
    return initialized
  },
  applyConfigCommit: (incoming) => {
    let disposition: ConfigCommitDisposition = 'invalid'
    set((state) => {
      const cursor = new ConfigCommitCursor<AppConfig>(isAppConfigSnapshot)
      if (state.configCommit) cursor.initialize(state.configCommit)
      disposition = cursor.accept(incoming)
      if (disposition !== 'accepted') return state
      const accepted = cursor.snapshot!
      try {
        const submitted = accepted.saveId ? pendingConfigSaves.snapshot(accepted.saveId) : null
        const baseline = submitted ?? state.savedConfig ?? state.configCommit!.config
        const config = mergeAppConfigDraft(baseline, state.config, accepted.config)
        return {
          configCommit: accepted,
          config,
          savedConfig: structuredClone(accepted.config),
          ...modelStateForConfigChange(state.config, config),
        }
      } catch {
        disposition = 'invalid'
        return state
      }
    })
    return disposition
  },
  setConfig: (incoming) =>
    set((state) => {
      const config = syncHotkeyConfig(state.config, incoming)
      return { config, ...modelStateForConfigChange(state.config, config) }
    }),
  updateConfig: (partial) =>
    set((state) => {
      const config = syncHotkeyConfig(state.config, partial)
      return { config, ...modelStateForConfigChange(state.config, config) }
    }),
  applyPersistedConfigPatch: (patch) =>
    set((state) => {
      if (state.configCommit !== null) return state
      const incoming = { ...patch }
      if (state.savedConfig && !valuesEqual(state.config.hotkeys, state.savedConfig.hotkeys)) {
        const persisted = syncHotkeyConfig(state.savedConfig, patch).hotkeys
        incoming.hotkeys = mergeHotkeyDraft(
          state.config.hotkeys,
          state.savedConfig.hotkeys,
          persisted,
        )
        delete incoming.hotkey
        delete incoming.ask_hotkey
        delete incoming.hotkey_mode
      }
      const config = syncHotkeyConfig(state.config, incoming)
      return {
        config,
        savedConfig: state.savedConfig
          ? syncHotkeyConfig(state.savedConfig, patch)
          : state.savedConfig,
        ...modelStateForConfigChange(state.config, config),
      }
    }),

  history: [],
  setHistory: (history) => set({ history }),

  dictionary: [],
  setDictionary: (dictionary) => set({ dictionary }),
  correctionRules: [],
  setCorrectionRules: (correctionRules) => set({ correctionRules }),

  onboardingCompleted: false,
  setOnboardingCompleted: (onboardingCompleted) => set({ onboardingCompleted }),
  onboardingStep: 0,
  setOnboardingStep: (onboardingStep) => set({ onboardingStep }),
  onboardingMode: null,
  setOnboardingMode: (onboardingMode) => set({ onboardingMode }),

  capsuleExpanded: false,
  setCapsuleExpanded: (capsuleExpanded) => set({ capsuleExpanded }),

  sttTestStatus: 'idle',
  setSttTestStatus: (sttTestStatus) => set({ sttTestStatus }),
  llmTestStatus: 'idle',
  setLlmTestStatus: (llmTestStatus) => set({ llmTestStatus }),

  sttLatencyMs: null,
  setSttLatencyMs: (sttLatencyMs) => set({ sttLatencyMs }),
  llmLatencyMs: null,
  setLlmLatencyMs: (llmLatencyMs) => set({ llmLatencyMs }),

  llmModels: [],
  setLlmModels: (llmModels) => set({ llmModels }),

  pipelineError: null,
  pipelineErrorKey: null,
  pipelineErrorMessageKey: null,
  retryAvailable: false,
  setRetryAvailable: (retryAvailable) => set({ retryAvailable }),
  failedTakeId: null,
  setFailedTakeId: (failedTakeId) => set({ failedTakeId }),
  setPipelineErrorKey: (pipelineErrorKey) => set({ pipelineErrorKey }),
  setPipelineError: (pipelineError, messageKey) =>
    set((state) => ({
      pipelineError,
      pipelineErrorMessageKey: pipelineError === null ? null : (messageKey ?? null),
      pipelineErrorKey: pipelineError === null ? null : state.pipelineErrorKey,
      failedTakeId: pipelineError === null ? null : state.failedTakeId,
    })),

  accessibilityTrusted: true,
  setAccessibilityTrusted: (accessibilityTrusted) => set({ accessibilityTrusted }),
  platformCapabilities: null,
  setPlatformCapabilities: (platformCapabilities) => set({ platformCapabilities }),
  hotkeyRegistrationError: null,
  setHotkeyRegistrationError: (hotkeyRegistrationError) => set({ hotkeyRegistrationError }),

  translationTargetMenuOpen: false,
  setTranslationTargetMenuOpen: (translationTargetMenuOpen) => set({ translationTargetMenuOpen }),

  resetRecording: () =>
    set({
      audioVolume: 0,
      partialTranscript: '',
      finalTranscript: '',
      polishedText: '',
      recordingDuration: 0,
      recordingDeadline: null,
    }),

  savedConfig: null,
  setSavedConfig: (savedConfig) => set({ savedConfig }),
  resetConfig: () => set((s) => (s.savedConfig ? { config: { ...s.savedConfig } } : {})),
}))
