import { capsuleErrorKeyFromPayload } from './capsuleError'

/** i18n key for a history re-recognition failure; history-only causes get their own copy. */
export function historyRetryErrorKey(failure: unknown): string {
  const message = String(failure).toLowerCase()
  if (message.includes('voice operation is busy') || message.includes('is busy')) {
    return 'history.retryBusy'
  }
  if (message.includes('file-based whisper provider')) return 'history.retryUnsupported'
  if (message.includes('no saved recording')) return 'history.retryMissingAudio'
  if (message.includes('recording format') || message.includes('invalid recording size')) {
    return 'history.retryUnreadableAudio'
  }
  return `capsule.errors.${capsuleErrorKeyFromPayload(String(failure))}`
}
