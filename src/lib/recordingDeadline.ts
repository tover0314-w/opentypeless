import type { RecordingDeadlineSnapshot } from '../stores/appStore'

export function isRecordingDeadline(value: unknown): value is RecordingDeadlineSnapshot {
  if (!value || typeof value !== 'object') return false
  const deadline = value as Record<string, unknown>
  const integer = (input: unknown) =>
    typeof input === 'number' && Number.isSafeInteger(input) && input >= 0
  return (
    integer(deadline.sessionId) &&
    (deadline.sessionId as number) > 0 &&
    (deadline.recordingKind === 'dictation' || deadline.recordingKind === 'ask') &&
    integer(deadline.startedAtUnixMs) &&
    integer(deadline.deadlineAtUnixMs) &&
    (deadline.deadlineAtUnixMs as number) >= (deadline.startedAtUnixMs as number) &&
    integer(deadline.effectiveMaxSeconds) &&
    (deadline.effectiveMaxSeconds as number) > 0
  )
}
