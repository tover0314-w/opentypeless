import { validCitationSupports } from './citation-support-validation'

const MAX_HISTORY_ENTRIES = 5000
const RECORDING_ONLY_STATUSES = new Set(['dismissed', 'silent', 'recognition_failed'])
const HISTORY_ENUMS: Record<string, ReadonlySet<string>> = {
  context_family: new Set([
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
  ]),
  browser_access_status: new Set(['available', 'needs_permission', 'not_applicable', 'unknown']),
  provider_kind: new Set(['managed_cloud', 'byok', 'local']),
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function optionalText(value: unknown, limit: number): boolean {
  return (
    value == null ||
    (typeof value === 'string' && Array.from(value.replace(/\0/g, '').trim()).length <= limit)
  )
}

function validEntry(entry: unknown): boolean {
  if (!record(entry)) return false
  if (
    'citationSupports' in entry ||
    !validCitationSupports(entry.citation_supports, entry.answer_text, entry.citations) ||
    (entry.citation_supports !== undefined && entry.mode !== 'ask')
  )
    return false
  if (
    entry.ask_intent != null &&
    (typeof entry.ask_intent !== 'string' ||
      !['open_question', 'ask_selection'].includes(entry.ask_intent) ||
      entry.mode !== 'ask' ||
      typeof entry.answer_text !== 'string' ||
      !entry.answer_text.trim())
  ) {
    return false
  }
  if (entry.citations != null) {
    if (!Array.isArray(entry.citations) || entry.citations.length > 20) return false
    if (
      entry.citations.length &&
      (entry.mode !== 'ask' || typeof entry.answer_text !== 'string' || !entry.answer_text.trim())
    ) {
      return false
    }
    for (const citation of entry.citations) {
      if (
        !record(citation) ||
        typeof citation.url !== 'string' ||
        citation.url.length > 2048 ||
        new TextEncoder().encode(citation.url).length > 2048 ||
        typeof citation.title !== 'string' ||
        !citation.title.trim() ||
        citation.title.length > 500 ||
        Array.from(citation.url).some(
          (character) => character <= ' ' || character === '\u007f' || character === '\\',
        )
      ) {
        return false
      }
      try {
        const url = new URL(citation.url)
        if (
          !/^https?:\/\//iu.test(citation.url) ||
          !['http:', 'https:'].includes(url.protocol) ||
          !url.hostname ||
          url.username ||
          url.password ||
          new TextEncoder().encode(url.href).length > 2048
        ) {
          return false
        }
      } catch {
        return false
      }
    }
  }
  if (
    !Object.entries(HISTORY_ENUMS).every(
      ([field, allowed]) =>
        entry[field] == null || (typeof entry[field] === 'string' && allowed.has(entry[field])),
    )
  ) {
    return false
  }
  if ('raw_text' in entry && 'text' in entry) return false
  const raw = entry.raw_text ?? entry.text ?? ''
  const polished = entry.polished_text ?? raw
  if (!optionalText(raw, 1_000_000) || !optionalText(polished, 1_000_000)) return false
  if (
    !String(raw).replace(/\0/g, '').trim() &&
    !String(polished).replace(/\0/g, '').trim() &&
    !RECORDING_ONLY_STATUSES.has(String(entry.output_status))
  ) {
    return false
  }
  const textFields: Record<string, number> = {
    created_at: 100,
    context_profile_id: 200,
    context_label: 200,
    app_name: 200,
    context_icon_key: 100,
    language: 100,
    active_scene_id: 200,
    active_scene_source: 100,
    active_scene_name: 200,
    output_status: 100,
    output_error: 2000,
    mode: 100,
    answer_text: 1_000_000,
  }
  if (!Object.entries(textFields).every(([field, limit]) => optionalText(entry[field], limit))) {
    return false
  }
  for (const field of ['duration_ms', 'active_scene_prompt_chars']) {
    const value = entry[field]
    if (value != null && (typeof value !== 'number' || !Number.isSafeInteger(value))) return false
  }
  return (
    entry.active_scene_prompt_truncated === undefined ||
    typeof entry.active_scene_prompt_truncated === 'boolean'
  )
}

export function validBackupHistory(value: unknown): boolean {
  return (
    value == null ||
    (Array.isArray(value) && value.length <= MAX_HISTORY_ENTRIES && value.every(validEntry))
  )
}
