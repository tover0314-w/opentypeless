import { describe, expect, it } from 'vitest'
import type { HistoryEntry } from '../../../stores/appStore'
import { MAX_FEEDBACK_URL_LENGTH, buildFeedbackIssueUrl } from '../feedbackIssue'

const entry: HistoryEntry = {
  id: 7,
  created_at: '2026-11-03T08:00:00.000Z',
  context_profile_id: 'chat.slack',
  context_label: 'Slack',
  context_icon_key: 'slack',
  context_family: 'work_chat',
  browser_access_status: 'not_applicable',
  provider_kind: 'byok',
  raw_text: 'open type less',
  polished_text: 'OpenTypeless is great.\nSecond line.',
  language: 'en',
  duration_ms: 2300,
  active_scene_id: null,
  active_scene_source: null,
  active_scene_name: 'Email',
  active_scene_prompt_chars: null,
  active_scene_prompt_truncated: false,
  output_status: null,
  output_error: null,
}

const config = {
  stt_provider: 'groq-whisper' as const,
  llm_provider: 'openrouter' as const,
  llm_model: 'google/gemini-2.5-flash',
  polish_enabled: true,
}

function params(url: string) {
  const parsed = new URL(url)
  expect(`${parsed.origin}${parsed.pathname}`).toBe(
    'https://github.com/tover0314-w/opentypeless/issues/new',
  )
  return parsed.searchParams
}

describe('buildFeedbackIssueUrl', () => {
  it('bounds oversized diagnostic metadata as well as transcript and feedback', () => {
    const url = buildFeedbackIssueUrl({
      entry: {
        ...entry,
        context_label: '应用'.repeat(3000),
        active_scene_name: '场景'.repeat(3000),
        output_error: '错误'.repeat(3000),
      },
      feedback: 'A short report',
      version: '版本'.repeat(3000),
      platform: 'macOS',
      config: { ...config, llm_model: '模型'.repeat(3000) },
    })
    expect(url.length).toBeLessThanOrEqual(MAX_FEEDBACK_URL_LENGTH)
    expect(params(url).get('summary')).toBe('A short report')
  })

  it('quotes the raw transcript when polished text is whitespace', () => {
    const query = params(
      buildFeedbackIssueUrl({
        entry: { ...entry, raw_text: 'Raw words', polished_text: ' \n ' },
        feedback: 'Formatting issue',
        version: '1.1.64',
        platform: 'macOS',
        config,
      }),
    )
    expect(query.get('actual')).toBe('Transcript:\n> Raw words')
  })

  it('prefills the bug-report form fields by their ids', () => {
    const query = params(
      buildFeedbackIssueUrl({
        entry,
        feedback: '  The brand name was split.  ',
        version: '1.1.64',
        platform: 'macOS',
        config,
      }),
    )
    expect(query.get('template')).toBe('bug_report.yml')
    expect(query.get('title')).toBe('[Bug]: The brand name was split.')
    expect(query.get('summary')).toBe('The brand name was split.')
    expect(query.get('actual')).toBe('Transcript:\n> OpenTypeless is great.\n> Second line.')
    expect(query.get('version')).toBe('1.1.64')
    expect(query.get('app')).toBe('Slack')
    const logs = query.get('logs') ?? ''
    expect(logs).toContain('Platform: macOS')
    expect(logs).toContain('STT provider: groq-whisper')
    expect(logs).toContain('LLM provider: openrouter (google/gemini-2.5-flash)')
    expect(logs).toContain('Scene: Email')
    expect(logs).not.toContain('Output error')
  })

  it('does not serialize dedicated credential fields from the config object', () => {
    const url = buildFeedbackIssueUrl({
      entry,
      feedback: 'x',
      version: '1.1.64',
      platform: 'Linux',
      config: { ...config, stt_api_key: 'sk-secret', llm_api_key: 'sk-other' } as typeof config,
    })
    expect(url).not.toContain('sk-secret')
    expect(url).not.toContain('sk-other')
  })

  it('excludes raw history errors while preserving the output status', () => {
    const syntheticToken = 'synthetic-feedback-token-not-a-real-secret'
    const query = params(
      buildFeedbackIssueUrl({
        entry: {
          ...entry,
          output_status: 'recognition_failed',
          output_error: `Request failed at https://example.invalid/transcribe?api_key=${syntheticToken}`,
        },
        feedback: 'Synthetic feedback',
        version: '1.1.64',
        platform: 'macOS',
        config,
      }),
    )
    expect(query.get('logs')).not.toContain(syntheticToken)
    expect(query.get('logs')).not.toContain('Output error')
    expect(query.get('logs')).toContain('Output status: recognition_failed')
  })

  it('labels an Ask entry as a question and reports polishing off', () => {
    const query = params(
      buildFeedbackIssueUrl({
        entry: { ...entry, mode: 'ask', polished_text: 'What is REA?' },
        feedback: 'Wrong answer',
        version: '1.1.64',
        platform: 'Windows',
        config: { ...config, polish_enabled: false },
      }),
    )
    expect(query.get('actual')).toBe('Question:\n> What is REA?')
    expect(query.get('logs')).toContain('LLM provider: off')
    expect(query.get('logs')).toContain('Mode: ask')
  })

  it('describes a take without text by its state instead of quoting nothing', () => {
    const query = params(
      buildFeedbackIssueUrl({
        entry: { ...entry, raw_text: '', polished_text: '', output_status: 'dismissed' },
        feedback: 'I cancelled by accident',
        version: '1.1.64',
        platform: 'macOS',
        config,
      }),
    )
    expect(query.get('actual')).toBe('(dismissed)')
  })

  it('shortens the transcript first so long CJK text stays under the URL limit', () => {
    const feedback = '品牌名被拆开了'
    const url = buildFeedbackIssueUrl({
      entry: { ...entry, polished_text: '你好世界'.repeat(1000) },
      feedback,
      version: '1.1.64',
      platform: 'macOS',
      config,
    })
    expect(url.length).toBeLessThanOrEqual(MAX_FEEDBACK_URL_LENGTH)
    const query = params(url)
    expect(query.get('summary')).toBe(feedback)
    expect(query.get('actual')).toMatch(/^Transcript:\n> 你好世界.*…$/)
  })

  it('also shortens oversized feedback and keeps the title to one short line', () => {
    const feedback = `第一行\n${'很长的反馈'.repeat(2000)}`
    const url = buildFeedbackIssueUrl({
      entry,
      feedback,
      version: '1.1.64',
      platform: 'macOS',
      config,
    })
    expect(url.length).toBeLessThanOrEqual(MAX_FEEDBACK_URL_LENGTH)
    const query = params(url)
    expect(query.get('summary')?.endsWith('…')).toBe(true)
    expect(Array.from(query.get('title') ?? '').length).toBeLessThanOrEqual('[Bug]: '.length + 61)
    expect(query.get('title')).not.toContain('\n')
  })
})
