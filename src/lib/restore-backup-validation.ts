import { validBackupHistory } from './backup-history-validation'
import { validBackupDictionary } from './backup-dictionary-validation'

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function allowedFields(value: Record<string, unknown>, fields: readonly string[]): boolean {
  return Object.keys(value).every((field) => fields.includes(field))
}

const HISTORY_FIELDS = [
  'id',
  'created_at',
  'context_profile_id',
  'context_label',
  'context_icon_key',
  'context_family',
  'browser_access_status',
  'provider_kind',
  'raw_text',
  'polished_text',
  'language',
  'duration_ms',
  'active_scene_id',
  'active_scene_source',
  'active_scene_name',
  'active_scene_prompt_chars',
  'active_scene_prompt_truncated',
  'output_status',
  'output_error',
  'mode',
  'answer_text',
  'ask_intent',
  'citations',
  'citation_supports',
  'has_audio',
]

function uniqueIds(entries: unknown[]): boolean {
  const seen = new Set<number>()
  return entries.every((entry) => {
    if (!record(entry) || !Number.isSafeInteger(entry.id) || Number(entry.id) <= 0) return false
    const id = Number(entry.id)
    if (seen.has(id)) return false
    seen.add(id)
    return true
  })
}

function validNullableHistoryFields(entry: Record<string, unknown>): boolean {
  const textFields = [
    'language',
    'active_scene_id',
    'active_scene_source',
    'active_scene_name',
    'output_status',
    'output_error',
    'answer_text',
  ]
  return (
    textFields.every((field) => entry[field] === null || typeof entry[field] === 'string') &&
    ['duration_ms', 'active_scene_prompt_chars'].every(
      (field) =>
        entry[field] === null ||
        (typeof entry[field] === 'number' &&
          Number.isSafeInteger(entry[field]) &&
          Number(entry[field]) >= 0),
    )
  )
}

export function validRestoreBackupResponse(value: unknown): boolean {
  if (
    !record(value) ||
    Object.keys(value).length !== 3 ||
    !Array.isArray(value.history) ||
    !Array.isArray(value.dictionary) ||
    !Array.isArray(value.correctionRules)
  ) {
    return false
  }
  if (
    !validBackupHistory(value.history) ||
    !validBackupDictionary({
      entries: value.dictionary,
      correction_rules: value.correctionRules,
    }) ||
    ![value.history, value.dictionary, value.correctionRules].every(uniqueIds)
  ) {
    return false
  }
  return (
    value.history.every(
      (entry) =>
        record(entry) &&
        allowedFields(entry, HISTORY_FIELDS) &&
        (entry.citations === undefined ||
          (Array.isArray(entry.citations) &&
            entry.citations.every(
              (source: unknown) => record(source) && allowedFields(source, ['url', 'title']),
            ))) &&
        [
          'created_at',
          'context_profile_id',
          'context_label',
          'context_icon_key',
          'context_family',
          'browser_access_status',
          'provider_kind',
          'raw_text',
          'polished_text',
          'mode',
        ].every((field) => typeof entry[field] === 'string') &&
        ['dictate', 'translate', 'ask'].includes(String(entry.mode)) &&
        validNullableHistoryFields(entry) &&
        typeof entry.active_scene_prompt_truncated === 'boolean' &&
        typeof entry.has_audio === 'boolean',
    ) &&
    value.dictionary.every(
      (entry) =>
        record(entry) &&
        allowedFields(entry, ['id', 'word', 'pronunciation', 'auto']) &&
        (entry.pronunciation === null || typeof entry.pronunciation === 'string') &&
        typeof entry.auto === 'boolean',
    ) &&
    value.correctionRules.every(
      (entry) =>
        record(entry) &&
        allowedFields(entry, ['id', 'pattern', 'replacement', 'enabled']) &&
        typeof entry.enabled === 'boolean',
    )
  )
}
