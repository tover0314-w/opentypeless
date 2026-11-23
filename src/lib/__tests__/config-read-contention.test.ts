import { afterEach, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { getConfigCommit } from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
afterEach(() => {
  vi.mocked(invoke).mockReset()
  vi.useRealTimers()
})

it('retries only explicit configuration contention and returns the later owned snapshot', async () => {
  vi.useFakeTimers()
  const snapshot = { configEpoch: 'synthetic', revision: 2, saveId: null, config: {} }
  vi.mocked(invoke)
    .mockRejectedValueOnce('Application settings are being updated')
    .mockResolvedValueOnce(snapshot)
  const reading = getConfigCommit()
  void reading.catch(() => {})
  await vi.advanceTimersByTimeAsync(20)
  expect(await reading).toEqual(snapshot)
  expect(invoke).toHaveBeenCalledTimes(2)
})

it('bounds persistent contention instead of waiting indefinitely', async () => {
  vi.useFakeTimers()
  vi.mocked(invoke).mockRejectedValue('Application settings are being updated')
  const result = expect(getConfigCommit()).rejects.toBe('Application settings are being updated')
  await vi.advanceTimersByTimeAsync(100)
  await result
  expect(invoke).toHaveBeenCalledTimes(3)
})

it('does not retry unreadable settings or other private diagnostic errors', async () => {
  vi.mocked(invoke).mockRejectedValueOnce('Application settings are invalid')
  await expect(getConfigCommit()).rejects.toBe('Application settings are invalid')
  expect(invoke).toHaveBeenCalledOnce()
})

it('does not invoke configuration reads when cancellation already occurred', async () => {
  const controller = new AbortController()
  controller.abort()
  const read = getConfigCommit as (signal?: AbortSignal) => ReturnType<typeof getConfigCommit>
  await expect(read(controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  expect(invoke).not.toHaveBeenCalled()
})

it('cancels the retry delay without issuing another native request', async () => {
  vi.useFakeTimers()
  const controller = new AbortController()
  vi.mocked(invoke).mockRejectedValue('Application settings are being updated')
  const read = getConfigCommit as (signal?: AbortSignal) => ReturnType<typeof getConfigCommit>
  const pending = read(controller.signal)
  const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  void rejected.catch(() => {})
  await Promise.resolve()
  controller.abort()
  await vi.advanceTimersByTimeAsync(100)
  await rejected
  expect(invoke).toHaveBeenCalledOnce()
})
