import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readSavedAskAnswer } from '../savedAskAnswer'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
beforeEach(() => {
  vi.mocked(invoke).mockReset()
})
afterEach(() => vi.useRealTimers())

const snapshot = {
  historyId: 1,
  question: 'Synthetic question',
  answer: 'Synthetic answer',
  citations: [{ url: 'https://example.invalid/source', title: 'Source' }],
}

it('preserves strictly verified stored citation supports', async () => {
  const answer = 'Read https://example.invalid/source'
  const response = {
    ...snapshot,
    answer,
    citationSupports: [{ start: 5, end: answer.length, sourceIndex: 0 }],
  }
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(readSavedAskAnswer(1, snapshot.question, answer)).resolves.toEqual(response)
})

it.each([
  [{ start: 0, end: 4, sourceIndex: 0 }],
  [{ start: 5, end: 34, sourceIndex: 99 }],
  [{ start: 5, end: 34, sourceIndex: 0, content: 'Private extra' }],
  null,
])('rejects corrupt saved supports %j', async (citationSupports) => {
  vi.mocked(invoke).mockResolvedValue({ ...snapshot, citationSupports })
  await expect(readSavedAskAnswer(1, snapshot.question, snapshot.answer)).rejects.toThrow(
    'Invalid saved answer response',
  )
})

it.each(['open_question', 'ask_selection', null])(
  'preserves optional stored intent %s',
  async (askIntent) => {
    const response = { ...snapshot, askIntent }
    vi.mocked(invoke).mockResolvedValue(response)
    await expect(readSavedAskAnswer(1, snapshot.question, snapshot.answer)).resolves.toEqual(
      response,
    )
  },
)

it.each(['search', ['ask_selection'], 42])(
  'rejects invalid stored intent %j',
  async (askIntent) => {
    vi.mocked(invoke).mockResolvedValue({ ...snapshot, askIntent })
    await expect(readSavedAskAnswer(1, snapshot.question, snapshot.answer)).rejects.toThrow()
  },
)

it.each([snapshot, null])('returns a matching snapshot or absent record %j', async (response) => {
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(readSavedAskAnswer(1, snapshot.question, snapshot.answer)).resolves.toEqual(response)
  expect(invoke).toHaveBeenCalledExactlyOnceWith('read_saved_ask_answer', {
    historyId: 1,
    question: snapshot.question,
    answer: snapshot.answer,
  })
})

it.each([
  { ...snapshot, historyId: 2 },
  { ...snapshot, question: 'Other question' },
  { ...snapshot, answer: 'Other answer' },
  { ...snapshot, token: 'private' },
  { ...snapshot, citations: [{ url: 'javascript:bad', title: 'Source' }] },
  { ...snapshot, citations: [{ url: 'https://user:secret@example.invalid/', title: 'Source' }] },
  {
    ...snapshot,
    citations: [{ url: 'https://example.invalid/', title: 'Source', content: 'private' }],
  },
  { ...snapshot, citations: [snapshot.citations[0], snapshot.citations[0]] },
  { ...snapshot, citations: Array(21).fill(snapshot.citations[0]) },
  undefined,
  {},
])('rejects mismatched or untrusted snapshot %j', async (response) => {
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(readSavedAskAnswer(1, snapshot.question, snapshot.answer)).rejects.toThrow()
})

it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])(
  'rejects invalid history ID %s before IPC',
  async (id) => {
    await expect(readSavedAskAnswer(id, snapshot.question, snapshot.answer)).rejects.toThrow()
    expect(invoke).not.toHaveBeenCalled()
  },
)

it('bounds waiting and ignores a late result without retrying', async () => {
  vi.useFakeTimers()
  let finish!: (value: typeof snapshot) => void
  vi.mocked(invoke).mockImplementation(() => new Promise((resolve) => (finish = resolve)))
  let settled = false
  const outcome = readSavedAskAnswer(1, snapshot.question, snapshot.answer).catch(
    (error: unknown) => {
      settled = true
      return error
    },
  )
  await vi.advanceTimersByTimeAsync(5001)
  expect(settled).toBe(true)
  expect(await outcome).toMatchObject({ message: 'Saved answer read timed out' })
  expect(vi.getTimerCount()).toBe(0)
  expect(invoke).toHaveBeenCalledTimes(1)
  finish(snapshot)
  await vi.advanceTimersByTimeAsync(0)
})

it('rejects oversized UTF8 input before invoking and escaped output after reading', async () => {
  await expect(readSavedAskAnswer(1, '', '😀'.repeat(1024 * 1024 + 1))).rejects.toThrow()
  expect(invoke).not.toHaveBeenCalled()
  const answer = '\u0001'.repeat(Math.floor((4 * 1024 * 1024) / 6) + 1)
  vi.mocked(invoke).mockResolvedValue({ ...snapshot, answer })
  await expect(readSavedAskAnswer(1, snapshot.question, answer)).rejects.toThrow()
  expect(invoke).toHaveBeenCalledTimes(1)
})

it('preserves native errors and clears its timer after a successful response', async () => {
  vi.useFakeTimers()
  vi.mocked(invoke).mockResolvedValue(snapshot)
  await readSavedAskAnswer(1, snapshot.question, snapshot.answer)
  expect(vi.getTimerCount()).toBe(0)
  const failure = new Error('Synthetic native rejection')
  vi.mocked(invoke).mockRejectedValue(failure)
  await expect(readSavedAskAnswer(1, snapshot.question, snapshot.answer)).rejects.toBe(failure)
  expect(vi.getTimerCount()).toBe(0)
})
