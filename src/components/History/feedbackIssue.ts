import { APP_REPO_URL } from '../../lib/constants'
import type { AppConfig, HistoryEntry } from '../../stores/appStore'
import { historyItemState, historyTranscript } from './historyItemState'

// GitHub rejects very long /issues/new URLs; CJK text expands ~9x when percent-encoded.
export const MAX_FEEDBACK_URL_LENGTH = 7000
const TITLE_CHARS = 60

export type FeedbackPlatform = 'macOS' | 'Windows' | 'Linux'

export interface FeedbackIssueInput {
  entry: HistoryEntry
  feedback: string
  version: string
  platform: FeedbackPlatform
  config: Pick<AppConfig, 'stt_provider' | 'llm_provider' | 'llm_model' | 'polish_enabled'>
}

function clip(text: string, limit: number) {
  const chars = Array.from(text)
  return chars.length <= limit ? text : `${chars.slice(0, limit).join('')}…`
}

function quote(text: string) {
  return text
    .split('\n')
    .map((line) => `> ${line}`)
    .join('\n')
}

function details({ entry, platform, config }: FeedbackIssueInput) {
  const rows: [string, string | number | null | undefined][] = [
    ['Platform', platform],
    ['Mode', entry.mode ?? 'dictate'],
    ['History state', historyItemState(entry)],
    ['Output status', entry.output_status],
    ['Provider kind', entry.provider_kind],
    ['STT provider', config.stt_provider],
    [
      'LLM provider',
      config.polish_enabled ? `${config.llm_provider} (${config.llm_model})` : 'off',
    ],
    ['Language', entry.language],
    ['Duration (ms)', entry.duration_ms],
    ['Scene', entry.active_scene_name],
    ['Recorded at', entry.created_at],
  ]
  return rows
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([label, value]) => `${label}: ${value}`)
    .join('\n')
}

function build(input: FeedbackIssueInput, feedback: string, transcript: string | null) {
  const { entry, version } = input
  const params = new URLSearchParams({
    template: 'bug_report.yml',
    title: `[Bug]: ${clip(input.feedback.trim().replace(/\s+/g, ' '), TITLE_CHARS)}`,
    summary: feedback,
    actual:
      transcript === null
        ? `(${historyItemState(entry)})`
        : `${entry.mode === 'ask' ? 'Question' : 'Transcript'}:\n${quote(transcript)}`,
    version: clip(version, 64),
    app: clip(entry.context_label, 100),
    logs: clip(details(input), 400),
  })
  return `${APP_REPO_URL}/issues/new?${params.toString()}`
}

/** Prefills the repo's bug-report issue form; nothing is submitted until the user does it on GitHub. */
export function buildFeedbackIssueUrl(input: FeedbackIssueInput): string {
  const feedback = input.feedback.trim()
  const transcript =
    historyItemState(input.entry) === 'transcript' ? historyTranscript(input.entry) : null
  let transcriptLimit = transcript === null ? 0 : Array.from(transcript).length
  let feedbackLimit = Array.from(feedback).length
  for (;;) {
    const url = build(
      input,
      clip(feedback, feedbackLimit),
      transcript === null ? null : clip(transcript, transcriptLimit),
    )
    if (url.length <= MAX_FEEDBACK_URL_LENGTH || (transcriptLimit === 0 && feedbackLimit === 0)) {
      return url
    }
    if (transcriptLimit > 0) transcriptLimit = Math.floor(transcriptLimit / 2)
    else feedbackLimit = Math.floor(feedbackLimit / 2)
  }
}
