import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SavedAnswerOpenUnknownError, showSavedAskAnswer } from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
beforeEach(() => {
  vi.mocked(invoke).mockReset()
})
afterEach(() => vi.useRealTimers())

it.each([undefined, true, false, {}, 'opened'])(
  'does not interpret malformed native success %j as a verified opening',
  async (response) => {
    vi.useFakeTimers()
    vi.mocked(invoke).mockResolvedValue(response)
    await expect(
      showSavedAskAnswer('Synthetic question', 'Synthetic answer', 1),
    ).rejects.toBeInstanceOf(SavedAnswerOpenUnknownError)
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  },
)

it.each([undefined, 1])(
  'accepts native unit response and preserves optional history ID %s',
  async (id) => {
    vi.useFakeTimers()
    vi.mocked(invoke).mockResolvedValue(null)
    await expect(
      showSavedAskAnswer('Synthetic question', 'Synthetic answer', id),
    ).resolves.toBeUndefined()
    expect(invoke).toHaveBeenCalledExactlyOnceWith('show_saved_ask_answer', {
      question: 'Synthetic question',
      answer: 'Synthetic answer',
      ...(id === undefined ? {} : { historyId: id }),
    })
    expect(vi.getTimerCount()).toBe(0)
  },
)

it('preserves explicit native rejection and clears the waiting timer', async () => {
  vi.useFakeTimers()
  const rejection = new Error('Synthetic native rejection')
  vi.mocked(invoke).mockRejectedValue(rejection)
  await expect(showSavedAskAnswer('Synthetic question', 'Synthetic answer')).rejects.toBe(rejection)
  expect(vi.getTimerCount()).toBe(0)
})

it('bounds saved answer waiting without retrying or accepting late success', async () => {
  vi.useFakeTimers()
  let finish!: (value: null) => void
  vi.mocked(invoke).mockImplementation(() => new Promise((resolve) => (finish = resolve)))
  let settled = false
  const outcome = showSavedAskAnswer('Synthetic question', 'Synthetic answer', 1).catch(
    (error: unknown) => {
      settled = true
      return error
    },
  )
  await vi.advanceTimersByTimeAsync(5001)
  expect(settled).toBe(true)
  expect(await outcome).toMatchObject({ name: 'SavedAnswerOpenUnknownError' })
  expect(invoke).toHaveBeenCalledTimes(1)
  expect(vi.getTimerCount()).toBe(0)
  finish(null)
  await vi.advanceTimersByTimeAsync(0)
})
