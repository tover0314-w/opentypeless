export type PipelineErrorPayload =
  | string
  | {
      code: string
      details?: string
      retry_count?: number
    }

export type CapsuleErrorKey =
  | 'stt_timeout'
  | 'stt_invalid_key'
  | 'stt_failed'
  | 'stt_quota_exceeded'
  | 'stt_no_speech_detected'
  | 'output_fallback_clipboard'
  | 'output_wayland_unsupported'
  | 'output_wayland_clipboard_copy_only'
  | 'llm_failed'
  | 'llm_thinking_unavailable'
  | 'llm_quota_exceeded'
  | 'accessibility_required'
  | 'stt_not_configured'
  | 'stt_connection_failed'
  | 'audio_failed'
  | 'output_failed'
  | 'unknown'

const structuredCapsuleErrorKeys = new Set<CapsuleErrorKey>([
  'stt_timeout',
  'stt_invalid_key',
  'stt_failed',
  'stt_quota_exceeded',
  'stt_no_speech_detected',
  'output_fallback_clipboard',
  'output_wayland_unsupported',
  'output_wayland_clipboard_copy_only',
  'llm_failed',
  'llm_thinking_unavailable',
  'llm_quota_exceeded',
  'accessibility_required',
  'stt_not_configured',
  'stt_connection_failed',
  'audio_failed',
  'output_failed',
])

function isStructuredCapsuleErrorKey(code: string): code is CapsuleErrorKey {
  return structuredCapsuleErrorKeys.has(code as CapsuleErrorKey)
}

export function capsuleErrorKeyFromPayload(payload: PipelineErrorPayload): CapsuleErrorKey {
  if (typeof payload !== 'string') {
    return isStructuredCapsuleErrorKey(payload.code) ? payload.code : 'unknown'
  }

  const normalized = payload.trim().toLowerCase()

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
