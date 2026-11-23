import type { HistoryEntry } from '../../stores/appStore'

export type HistoryItemState = 'transcript' | 'dismissed' | 'silent' | 'interrupted'

export function historyTranscript(entry: HistoryEntry): string {
  return entry.polished_text.trim() ? entry.polished_text : entry.raw_text
}

// Typeless `CP`: a dismissed or interrupted take wins over text; anything else without text is empty.
export function historyItemState(entry: HistoryEntry): HistoryItemState {
  if (entry.output_status === 'dismissed') return 'dismissed'
  if (entry.output_status === 'recognition_failed') return 'interrupted'
  if (entry.output_status === 'silent' || !historyTranscript(entry).trim()) return 'silent'
  return 'transcript'
}
