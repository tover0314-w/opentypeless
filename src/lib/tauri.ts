import { invoke } from '@tauri-apps/api/core'
import type {
  AppConfig,
  HistoryEntry,
  DictionaryEntry,
  CorrectionRule,
  PlatformCapabilities,
  TranslationConfig,
  ContextFamily,
  FamilySceneAssignment,
  BrowserAccessStatus,
  BrowserTarget,
} from '../stores/appStore'

// Pipeline commands
export interface PracticeSnapshot {
  session: string
  phase:
    | 'preparing'
    | 'recording'
    | 'transcribing'
    | 'polishing'
    | 'complete'
    | 'error'
    | 'cancelled'
  rawText: string
  polishPreview: string
  result: string
  level: number
  error: { code: string; details?: string | null; retry_count: number } | null
}

export const startPractice = () => invoke<string>('start_practice')
export const stopPractice = (session: string) => invoke<void>('stop_practice', { session })
export const cancelPractice = (session: string) => invoke<void>('cancel_practice', { session })
export const getPractice = (session: string) =>
  invoke<PracticeSnapshot>('get_practice', { session })

export async function startRecording(): Promise<void> {
  return invoke('start_recording')
}

export async function stopRecording(): Promise<void> {
  return invoke('stop_recording')
}

export async function abortRecording(): Promise<void> {
  return invoke('abort_recording')
}

export async function setActiveTranslationTarget(code: string): Promise<TranslationConfig> {
  return invoke('set_active_translation_target', { code })
}

export type AppMatcherType = 'native_bundle_id' | 'native_executable' | 'exact_web_host'

export interface MappingCandidateView {
  generation: number
  matcherType: AppMatcherType
  displayValue: string
  suggestedLabel: string
  currentFamily: ContextFamily
  iconKey: string
}

export interface CustomAppMappingView {
  id: string
  label: string
  matcherType: AppMatcherType
  displayValue: string
  family: ContextFamily
  sceneId: string | null
  enabled: boolean
  iconKey: string
}

export interface SaveCustomAppMappingInput {
  candidateGeneration: number
  label: string
  family: ContextFamily
  sceneId: string | null
}

export interface UpdateCustomAppMappingInput {
  id: string
  label: string
  family: ContextFamily
  sceneId: string | null
  enabled: boolean
}

export async function getLatestMappingCandidate(): Promise<MappingCandidateView | null> {
  return invoke('get_latest_mapping_candidate')
}

export async function listCustomAppMappings(): Promise<CustomAppMappingView[]> {
  return invoke('list_custom_app_mappings')
}

export async function saveCustomAppMapping(
  input: SaveCustomAppMappingInput,
): Promise<CustomAppMappingView> {
  return invoke('save_custom_app_mapping', { input })
}

export async function updateCustomAppMapping(
  input: UpdateCustomAppMappingInput,
): Promise<CustomAppMappingView> {
  return invoke('update_custom_app_mapping', { input })
}

export async function setCustomAppMappingEnabled(id: string, enabled: boolean): Promise<void> {
  return invoke('set_custom_app_mapping_enabled', { id, enabled })
}

export async function deleteCustomAppMapping(id: string): Promise<void> {
  return invoke('delete_custom_app_mapping', { id })
}

export async function resetCustomAppMappings(): Promise<void> {
  return invoke('reset_custom_app_mappings')
}

export async function setFamilySceneAssignment(
  family: ContextFamily,
  sceneId: string | null,
): Promise<FamilySceneAssignment[]> {
  return invoke('set_family_scene_assignment', { input: { family, sceneId } })
}

export async function requestBrowserAccess(target: BrowserTarget): Promise<BrowserAccessStatus> {
  return invoke('request_browser_access', { target })
}

// Config commands
export async function getConfig(): Promise<AppConfig> {
  return invoke('get_config')
}

export async function getConfigCommit(): Promise<import('./configCommit').ConfigCommit<AppConfig>> {
  return invoke('get_config_commit')
}

export interface ConfigurationSaveReceipt {
  config: AppConfig
  savedRevision: number
  inputActivation: 'active' | 'pending'
  commit?: import('./configCommit').ConfigCommit<AppConfig>
}

export async function updateConfig(
  config: AppConfig,
  saveId?: string,
): Promise<ConfigurationSaveReceipt | void> {
  // Normal settings saves must never replay onboarding credentials.
  return invoke('update_config', {
    config: configWithoutProviderSecrets(config),
    ...(saveId === undefined ? {} : { saveId }),
  })
}

export function configWithoutProviderSecrets(config: AppConfig): AppConfig {
  return { ...config, stt_api_key: '', stt_custom_api_key: '', llm_api_key: '' }
}

export async function saveOnboardingConfig(config: AppConfig): Promise<AppConfig> {
  if (config.stt_provider === 'custom-whisper' && config.stt_custom_api_key?.trim()) {
    await setCredential('stt', 'custom-whisper', config.stt_custom_api_key)
  } else if (
    config.stt_api_key?.trim() &&
    config.stt_provider !== 'cloud' &&
    config.stt_provider !== 'custom-whisper' &&
    config.stt_provider !== 'apple-speech'
  ) {
    await setCredential('stt', config.stt_provider, config.stt_api_key)
  }
  if (config.llm_api_key?.trim() && config.llm_provider !== 'cloud') {
    await setCredential('llm', config.llm_provider, config.llm_api_key)
  }
  const sanitized = configWithoutProviderSecrets(config)
  await updateConfig(sanitized)
  return getConfig()
}

export type RecordingLimitMode = 'auto' | 'custom'
export type SttTransport = 'fileUpload' | 'streaming' | 'localBuffered' | 'managedUpload'
export type RecordingLimitSource =
  | 'provider'
  | 'managedProduct'
  | 'clientBuffer'
  | 'productSafety'
  | 'unknownUpstream'

export interface SttRecordingCapability {
  registryVersion: number
  providerId: string
  transport: SttTransport
  recommendedMaxSeconds: number
  hardMaxSeconds: number
  maxUploadBytes: number | null
  source: RecordingLimitSource
  explanationKey: string
}

export interface ResolvedSttRecordingLimit {
  capability: SttRecordingCapability
  mode: RecordingLimitMode
  requestedSeconds: number
  effectiveMaxSeconds: number
}

export async function getSttRecordingCapability(
  provider: string,
  mode: RecordingLimitMode,
  customSeconds: number,
): Promise<ResolvedSttRecordingLimit> {
  return invoke('get_stt_recording_capability', { provider, mode, customSeconds })
}

export type LlmModelCapability = 'certified' | 'best_effort' | 'unknown'

export async function getLlmModelCapability(
  provider: string,
  baseUrl: string,
  model: string,
): Promise<LlmModelCapability> {
  return invoke('get_llm_model_capability', { provider, baseUrl, model })
}

export interface CredentialStatus {
  namespace: string
  provider: string
  hasSecret: boolean
  updatedAt: string | null
  storage: 'unavailable' | 'os-vault' | 'session-only' | 'legacy-warning'
}

export async function getCredentialStatus(
  namespace: 'stt' | 'llm',
  provider: string,
): Promise<CredentialStatus> {
  return invoke('get_credential_status', { namespace, provider })
}

export async function readCredential(
  namespace: 'stt' | 'llm',
  provider: string,
): Promise<string | null> {
  return invoke('read_credential', { namespace, provider })
}

export async function setCredential(
  namespace: 'stt' | 'llm',
  provider: string,
  value: string,
): Promise<void> {
  return invoke('set_credential', { namespace, provider, value })
}

export async function clearCredential(namespace: 'stt' | 'llm', provider: string): Promise<void> {
  return invoke('clear_credential', { namespace, provider })
}

export async function migrateLegacyCredentials(): Promise<void> {
  return invoke('migrate_legacy_credentials')
}

export async function setCapsuleAutoHide(enabled: boolean): Promise<void> {
  return invoke('set_capsule_auto_hide', { enabled })
}

export async function retryTranscription(): Promise<string> {
  return invoke('retry_transcription')
}

export interface MicrophoneInfo {
  name: string
  isDefault: boolean
  connection: 'builtIn' | 'usb' | 'bluetooth' | 'virtual' | 'other'
}

export async function listMicrophones(): Promise<MicrophoneInfo[]> {
  return invoke('list_microphones')
}

export async function startMicrophonePreview(device: string): Promise<number> {
  return invoke('start_microphone_preview', { device })
}

export async function getMicrophonePreviewLevel(session: number): Promise<number> {
  return invoke('get_microphone_preview_level', { session })
}

export async function stopMicrophonePreview(session: number): Promise<void> {
  return invoke('stop_microphone_preview', { session })
}

export async function getPlatformCapabilities(): Promise<PlatformCapabilities> {
  return invoke('get_platform_capabilities')
}

export async function getHotkeyRegistrationError(): Promise<string | null> {
  return invoke('get_hotkey_registration_error')
}

export interface HotkeyBindingStatus {
  value: string
  valid: boolean
}

export type HotkeyAdapter = 'tauriGlobalShortcut' | 'nativeHook' | 'unavailable'
export type HotkeyInstallState = 'starting' | 'installed' | 'failed' | 'disabled'
export type HotkeyRole =
  | 'dictation'
  | 'ask'
  | 'translate'
  | 'editSelection'
  | 'switchScene'
  | 'openApp'

export interface HotkeyStatusError {
  code: string
  message: string
}

export interface HotkeyRoleStatus {
  role: HotkeyRole | string
  index: number
  display: string
  backend: HotkeyAdapter
  valid: boolean
  conflictWith: { role: HotkeyRole | string; index: number } | null
  adapter: HotkeyAdapter
  state: HotkeyInstallState
  message: string | null
  lastError: HotkeyStatusError | null
}

export interface HotkeyCapability {
  platform: 'macos' | 'windows' | 'linux' | 'unknown' | string
  sessionType: 'wayland' | 'x11' | 'unknown' | string
  supportsGlobalHotkey: boolean
  supportsHoldMode: boolean
  supportsReleasedEdge: boolean
  supportsSideSpecificModifiers: boolean
  requiresAccessibilityPermission: boolean
  statusHint: string | null
}

export interface HotkeyStatus {
  dictation: HotkeyBindingStatus
  ask: HotkeyBindingStatus
  conflict: boolean
  registration_error: string | null
  roles: HotkeyRoleStatus[]
  capability: HotkeyCapability
}

export async function getHotkeyStatus(): Promise<HotkeyStatus> {
  return invoke('get_hotkey_status')
}

export type DiagnosticStatus = 'ok' | 'warning' | 'error' | 'notApplicable' | 'checking'

export interface DiagnosticRow {
  id: 'microphone' | 'accessibility' | 'hotkey' | 'clipboard' | 'insertion' | 'platform' | string
  status: DiagnosticStatus
  message: string
  action: string | null
  lastCheckedAt: string
}

export interface SystemDiagnosticsReport {
  checkedAt: string
  rows: DiagnosticRow[]
}

export async function getSystemDiagnostics(): Promise<SystemDiagnosticsReport> {
  return invoke('get_system_diagnostics')
}

export interface SttProviderDiagnosticIssue {
  code: string
  message: string
}

export interface SttProviderDiagnostics {
  provider: string
  kind: 'localCompatible' | 'builtinLocal' | 'byokRemote' | 'cloudManaged' | 'unknown'
  endpoint: string | null
  model: string | null
  requiresApiKey: boolean
  apiKeyConfigured: boolean
  ready: boolean
  issues: SttProviderDiagnosticIssue[]
}

export async function getSttProviderDiagnostics(
  apiKey: string,
  provider: string,
  customBaseUrl?: string,
  customModel?: string,
  providerRegion?: string,
): Promise<SttProviderDiagnostics> {
  return invoke('get_stt_provider_diagnostics', {
    apiKey,
    provider,
    customBaseUrl,
    customModel,
    providerRegion,
  })
}

// Connection test
export async function testSttConnection(
  apiKey: string,
  provider: string,
  customBaseUrl?: string,
  customModel?: string,
  volcengineResourceId?: string,
  providerRegion?: string,
): Promise<boolean> {
  return invoke('test_stt_connection', {
    apiKey,
    provider,
    customBaseUrl,
    customModel,
    volcengineResourceId,
    providerRegion,
  })
}

export async function testLlmConnection(
  apiKey: string,
  provider: string,
  baseUrl: string,
  model: string,
): Promise<boolean> {
  return invoke('test_llm_connection', { apiKey, provider, baseUrl, model })
}

// Latency benchmark — returns round-trip time in milliseconds
export async function benchSttConnection(
  apiKey: string,
  provider: string,
  customBaseUrl?: string,
  customModel?: string,
  volcengineResourceId?: string,
  providerRegion?: string,
): Promise<number> {
  return invoke('bench_stt_connection', {
    apiKey,
    provider,
    customBaseUrl,
    customModel,
    volcengineResourceId,
    providerRegion,
  })
}

export async function benchLlmConnection(
  apiKey: string,
  provider: string,
  baseUrl: string,
  model: string,
): Promise<number> {
  return invoke('bench_llm_connection', { apiKey, provider, baseUrl, model })
}

// LLM models
export async function fetchLlmModels(
  apiKey: string,
  providerOrBaseUrl: string,
  explicitBaseUrl?: string,
): Promise<string[]> {
  const provider = explicitBaseUrl === undefined ? 'openai' : providerOrBaseUrl
  const baseUrl = explicitBaseUrl === undefined ? providerOrBaseUrl : explicitBaseUrl
  return invoke('fetch_llm_models', { apiKey, provider, baseUrl })
}

// Hotkey
export async function updateHotkey(hotkey: string): Promise<void> {
  return invoke('update_hotkey', { hotkey })
}

export async function updateAskHotkey(hotkey: string): Promise<void> {
  return invoke('update_ask_hotkey', { hotkey })
}

export async function pauseHotkey(captureId?: string): Promise<void> {
  return invoke('pause_hotkey', { captureId })
}

export async function resumeHotkey(captureId?: string): Promise<void> {
  return invoke('resume_hotkey', { captureId })
}

// Ask Anything
export async function askAnything(question: string): Promise<string> {
  return invoke('ask_anything', { question: question.trim() })
}

export async function showAskWindow(): Promise<void> {
  return invoke('show_ask_window')
}

export async function startAskFlow(): Promise<void> {
  return invoke('start_ask_flow')
}

export type VoiceIntentKind =
  | 'dictate_insert'
  | 'draft_insert'
  | 'rewrite_selection'
  | 'translate_insert'
  | 'translate_selection'
  | 'ask_selection'
  | 'open_question'
  | 'search'

export type VoiceOutputPlacement =
  | 'insert_at_cursor'
  | 'replace_selection'
  | 'popup_answer'
  | 'open_url'

export type VoiceExecutionFallbackReason =
  | 'feature_disabled'
  | 'empty_output'
  | 'target_changed'
  | 'selection_lost'
  | 'focus_restore_failed'
  | 'output_failed'

export type AskResultOutput = 'popupAnswer' | 'openedSearch' | 'insertedText' | 'copiedFallback'

export interface AskDictationResult {
  question: string
  answer: string
  intent: VoiceIntentKind
  output: AskResultOutput
  usedSelectedText: boolean
  selectedTextTruncated: boolean
  searchProvider: string | null
  requestedPlacement: VoiceOutputPlacement
  actualPlacement: VoiceOutputPlacement | null
  fallbackReason: VoiceExecutionFallbackReason | null
  /** The selection the question was about, when Ask used selected text. */
  selectedText?: string | null
}

export interface AskDictationStartResult {
  usedSelectedText: boolean
  selectedTextTruncated: boolean
}

export type PendingAskMessage =
  | { kind: 'result'; payload: AskDictationResult }
  | { kind: 'recordingStarted'; payload: AskDictationStartResult }
  | { kind: 'error'; payload: string }

export async function startAskDictation(): Promise<AskDictationStartResult> {
  return invoke('start_ask_dictation')
}

export async function stopAskDictation(): Promise<AskDictationResult> {
  return invoke('stop_ask_dictation')
}

export async function stopAskFlow(): Promise<void> {
  return invoke('stop_ask_flow')
}

export async function abortAskDictation(): Promise<void> {
  return invoke('abort_ask_dictation')
}

export async function takePendingAskMessage(): Promise<PendingAskMessage | null> {
  return invoke('take_pending_ask_message')
}

/** Lets the answer card size its transparent window to the rendered content height. */
export async function resizeAskWindow(height: number): Promise<void> {
  return invoke('resize_ask_window', { height })
}

// History
export async function getHistory(limit: number, offset: number): Promise<HistoryEntry[]> {
  return invoke('get_history', { limit, offset })
}

export async function clearHistory(): Promise<void> {
  return invoke('clear_history')
}

export async function pruneHistoryRetention(
  enabled: boolean,
  retentionDays: number,
): Promise<HistoryEntry[]> {
  return invoke('prune_history_retention', { enabled, retentionDays })
}

export async function deleteHistoryEntry(id: number): Promise<boolean> {
  return invoke('delete_history_entry', { id })
}

export async function exportHistoryAudio(id: number): Promise<string> {
  return invoke('export_history_audio', { id })
}

export type TrayUpdateStatus = 'idle' | 'checking' | 'downloading' | 'downloaded'

export async function setTrayUpdateStatus(status: TrayUpdateStatus): Promise<void> {
  return invoke('set_tray_update_status', { status })
}

export async function isDevelopmentBuild(): Promise<boolean> {
  return invoke('is_development_build')
}

export async function copyPngToClipboard(png: Uint8Array): Promise<void> {
  return invoke('copy_png_to_clipboard', png)
}

/** Rejects with `cancelled` when the user dismisses the save panel. */
export async function savePngWithDialog(png: Uint8Array, fileName: string): Promise<string> {
  return invoke('save_png_with_dialog', png, { headers: { 'x-file-name': fileName } })
}

export async function retryHistoryTranscription(id: number): Promise<string> {
  while (historyRetryCancellation) {
    await historyRetryCancellation
  }
  return invoke('retry_history_transcription', { id })
}

/** Typeless Undo (cancelled take) and Retry (failed take): re-run it and write it into the app. */
export async function recoverUnfinishedTake(id: number): Promise<void> {
  while (historyRetryCancellation) {
    await historyRetryCancellation
  }
  return invoke('recover_unfinished_take', { id })
}

let historyRetryCancellation: Promise<void> | null = null

export function cancelTranscriptionRetry(): Promise<void> {
  if (historyRetryCancellation) return historyRetryCancellation
  const cancellation = invoke<void>('cancel_transcription_retry')
  historyRetryCancellation = cancellation
  const clear = () => {
    if (historyRetryCancellation === cancellation) historyRetryCancellation = null
  }
  void cancellation.then(clear, clear)
  return cancellation
}

export interface DailyActivity {
  date: string
  words: number
  sessions: number
}

export interface UsageStats {
  totalWords: number
  totalSessions: number
  totalAudioSeconds: number
  averageWpm: number | null
  minutesSaved: number
  activeDays: number
  currentStreak: number
  longestStreak: number
  daily: DailyActivity[]
}

export async function getUsageStats(): Promise<UsageStats> {
  return invoke('get_usage_stats')
}

export async function showSavedAskAnswer(question: string, answer: string): Promise<void> {
  return invoke('show_saved_ask_answer', { question, answer })
}

export interface RestoreBackupResult {
  history: HistoryEntry[]
  dictionary: DictionaryEntry[]
  correctionRules: CorrectionRule[]
}

export async function validateBackupData(
  history: unknown | null,
  dictionary: unknown | null,
): Promise<void> {
  return invoke('validate_backup_data', { history, dictionary })
}

export async function restoreBackupData(
  history: unknown | null,
  dictionary: unknown | null,
): Promise<RestoreBackupResult> {
  return invoke('restore_backup_data', { history, dictionary })
}

// Dictionary
export async function getDictionary(): Promise<DictionaryEntry[]> {
  return invoke('get_dictionary')
}

export async function addDictionaryEntry(
  word: string,
  pronunciation: string | null,
): Promise<void> {
  return invoke('add_dictionary_entry', { word, pronunciation })
}

export interface WordAutoAddedPayload {
  term: string
  id: number
}

export async function removeDictionaryEntry(id: number): Promise<void> {
  return invoke('remove_dictionary_entry', { id })
}

export async function removeDictionaryEntries(ids: number[]): Promise<number> {
  return invoke('remove_dictionary_entries', { ids })
}

export async function updateDictionaryEntry(
  id: number,
  word: string,
  pronunciation: string | null,
): Promise<void> {
  return invoke('update_dictionary_entry', { id, word, pronunciation })
}

export async function getCorrectionRules(): Promise<CorrectionRule[]> {
  return invoke('get_correction_rules')
}

export async function addCorrectionRule(pattern: string, replacement: string): Promise<void> {
  return invoke('add_correction_rule', { pattern, replacement })
}

export async function removeCorrectionRule(id: number): Promise<void> {
  return invoke('remove_correction_rule', { id })
}

export async function setCorrectionRuleEnabled(id: number, enabled: boolean): Promise<void> {
  return invoke('set_correction_rule_enabled', { id, enabled })
}

export async function updateCorrectionRule(
  id: number,
  pattern: string,
  replacement: string,
  enabled: boolean,
): Promise<void> {
  return invoke('update_correction_rule', { id, pattern, replacement, enabled })
}

export type DictionaryImportFormat = 'txt' | 'csv' | 'json'

export interface DictionaryImportRowError {
  row: number
  code: string
}

export interface DictionaryImportReport {
  accepted: number
  skippedDuplicates: number
  skippedInvalid: number
  errors: DictionaryImportRowError[]
  acceptedWords: string[]
}

export async function previewDictionaryImport(
  bytes: number[],
  format: DictionaryImportFormat,
): Promise<DictionaryImportReport> {
  return invoke('preview_dictionary_import', { bytes, format })
}

export async function commitDictionaryImport(
  bytes: number[],
  format: DictionaryImportFormat,
): Promise<DictionaryImportReport> {
  return invoke('commit_dictionary_import', { bytes, format })
}

export async function exportDictionaryJson(): Promise<string> {
  return invoke('export_dictionary_json')
}

export async function exportDictionaryCsv(): Promise<string> {
  return invoke('export_dictionary_csv')
}

// Auto-start
export async function setAutoStart(enabled: boolean): Promise<void> {
  return invoke('set_auto_start', { enabled })
}

// macOS Accessibility permission
export async function checkAccessibilityPermission(): Promise<boolean> {
  return invoke('check_accessibility_permission')
}

export async function requestAccessibilityPermission(): Promise<boolean> {
  return invoke('request_accessibility_permission')
}

export async function waitForAccessibilityPermission({
  timeoutMs = 60_000,
  intervalMs = 1_000,
}: {
  timeoutMs?: number
  intervalMs?: number
} = {}): Promise<boolean> {
  const deadline = Date.now() + timeoutMs

  while (true) {
    const trusted = await checkAccessibilityPermission()
    if (trusted || Date.now() >= deadline) return trusted
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }
}

export async function loadOnboardingCompleted(): Promise<boolean> {
  return invoke('load_onboarding_completed')
}

export async function saveOnboardingCompleted(): Promise<void> {
  await invoke('save_onboarding_completed')
  // Typeless greets a newly onboarded user from the floating bar with the dictation key.
  try {
    const { emit } = await import('@tauri-apps/api/event')
    await emit('capsule:alert', { kind: 'ready' })
  } catch {
    // The capsule window is optional (browser preview, tests).
  }
}
