export type PipelineErrorPayload =
  | string
  | {
      code: string
      details?: string
      retry_count?: number
    }

export type CapsuleErrorKey =
  | 'authentication_required'
  | 'microphone_permission_required'
  | 'microphone_unavailable'
  | 'speech_permission_required'
  | 'speech_permission_restricted'
  | 'speech_language_unavailable'
  | 'no_network'
  | 'stt_timeout'
  | 'stt_invalid_key'
  | 'stt_failed'
  | 'stt_quota_exceeded'
  | 'stt_no_speech_detected'
  | 'output_fallback_clipboard'
  | 'output_wayland_unsupported'
  | 'output_wayland_clipboard_copy_only'
  | 'llm_failed'
  | 'llm_answer_incomplete'
  | 'ask_web_search_unavailable'
  | 'ask_web_search_quote_changed'
  | 'ask_web_search_no_sources'
  | 'llm_quota_exceeded'
  | 'accessibility_required'
  | 'stt_not_configured'
  | 'stt_connection_failed'
  | 'audio_failed'
  | 'output_failed'
  | 'selection_read_failed'
  | 'unknown'

const structuredCapsuleErrorKeys = new Set<CapsuleErrorKey>([
  'authentication_required',
  'microphone_permission_required',
  'microphone_unavailable',
  'speech_permission_required',
  'speech_permission_restricted',
  'speech_language_unavailable',
  'no_network',
  'stt_timeout',
  'stt_invalid_key',
  'stt_failed',
  'stt_quota_exceeded',
  'stt_no_speech_detected',
  'output_fallback_clipboard',
  'output_wayland_unsupported',
  'output_wayland_clipboard_copy_only',
  'llm_failed',
  'llm_answer_incomplete',
  'ask_web_search_unavailable',
  'ask_web_search_quote_changed',
  'ask_web_search_no_sources',
  'llm_quota_exceeded',
  'accessibility_required',
  'stt_not_configured',
  'stt_connection_failed',
  'audio_failed',
  'output_failed',
  'selection_read_failed',
])

function isStructuredCapsuleErrorKey(code: string): code is CapsuleErrorKey {
  return structuredCapsuleErrorKeys.has(code as CapsuleErrorKey)
}

export function capsuleErrorKeyFromPayload(payload: PipelineErrorPayload): CapsuleErrorKey {
  if (typeof payload !== 'string') {
    if ((payload.code === 'stt_failed' || payload.code === 'audio_failed') && payload.details) {
      const specific = specificInputError(payload.details.toLowerCase())
      if (specific) return specific
    }
    return isStructuredCapsuleErrorKey(payload.code) ? payload.code : 'unknown'
  }

  const normalized = payload.trim().toLowerCase()
  if (isStructuredCapsuleErrorKey(normalized) && normalized.startsWith('ask_web_search_')) {
    return normalized
  }
  if (
    normalized === 'llm response was truncated' ||
    normalized === 'anthropic llm response was truncated' ||
    normalized === 'cloud llm answer was truncated'
  ) {
    return 'llm_answer_incomplete'
  }
  if (normalized.includes('selection could not be read safely')) return 'selection_read_failed'
  const inputError = specificInputError(normalized)
  if (inputError) return inputError

  if (
    normalized.includes('session token is missing') ||
    normalized.includes('sign in first') ||
    normalized.includes('authentication_required')
  ) {
    return 'authentication_required'
  }
  if (
    normalized.includes('microphone') &&
    (normalized.includes('permission denied') || normalized.includes('permission required'))
  ) {
    return 'microphone_permission_required'
  }
  if (
    normalized.includes('network is unreachable') ||
    normalized.includes('no internet connection') ||
    normalized.includes('offline')
  ) {
    return 'no_network'
  }

  if (payload === 'ACCESSIBILITY_REQUIRED' || normalized.includes('accessibility_required')) {
    return 'accessibility_required'
  }

  if (
    normalized.includes('api key is not configured') ||
    normalized.includes('not configured') ||
    normalized.includes('missing base url') ||
    normalized.includes('missing base url or model') ||
    normalized.includes('configuration failed')
  ) {
    return 'stt_not_configured'
  }

  if (normalized.includes('quota')) {
    return normalized.includes('llm') || normalized.includes('ai')
      ? 'llm_quota_exceeded'
      : 'stt_quota_exceeded'
  }

  if (
    normalized.includes('auth error') ||
    normalized.includes('invalid key') ||
    normalized.includes('401') ||
    normalized.includes('403')
  ) {
    return 'stt_invalid_key'
  }

  if (normalized.includes('no speech')) {
    return 'stt_no_speech_detected'
  }

  if (normalized.includes('timeout') || normalized.includes('timed out')) {
    return 'stt_timeout'
  }

  if (normalized.includes('audio capture failed')) {
    return 'audio_failed'
  }

  if (normalized.includes('output failed')) {
    return 'output_failed'
  }

  if (normalized.includes('stt connection failed') || normalized.includes('connection failed')) {
    return 'stt_connection_failed'
  }

  if (normalized.includes('llm') || normalized.includes('polish')) {
    return 'llm_failed'
  }

  if (normalized.includes('stt') || normalized.includes('speech')) {
    return 'stt_failed'
  }

  return 'unknown'
}

function specificInputError(message: string): CapsuleErrorKey | null {
  if (message.includes('speech recognition is restricted')) return 'speech_permission_restricted'
  if (
    message.includes('speech recognition') &&
    (message.includes('permission') ||
      message.includes('not authorized') ||
      message.includes('waiting for') ||
      message.includes('authorization status'))
  ) {
    return 'speech_permission_required'
  }
  if (
    message.includes('apple speech recognizer could not be created for this language') ||
    message.includes('apple speech is not currently available for this language')
  ) {
    return 'speech_language_unavailable'
  }
  if (message.includes('no input device available')) return 'microphone_unavailable'
  return null
}
