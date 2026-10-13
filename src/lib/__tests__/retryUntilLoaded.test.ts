import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { retryUntilLoaded } from '../retryUntilLoaded'

describe('retryUntilLoaded', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('retries until the loader succeeds, then delivers the value once', async () => {
    const load = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error('state not managed'))
      .mockRejectedValueOnce(new Error('state not managed'))
      .mockResolvedValue('config')
    const onLoaded = vi.fn()
    const onError = vi.fn()

    retryUntilLoaded(load, onLoaded, onError)
    await vi.advanceTimersByTimeAsync(200) // first retry after 200ms
    await vi.advanceTimersByTimeAsync(400) // second retry after 400ms

    expect(load).toHaveBeenCalledTimes(3)
    expect(onError).toHaveBeenCalledTimes(2)
    expect(onLoaded).toHaveBeenCalledTimes(1)
    expect(onLoaded).toHaveBeenCalledWith('config')
  })

  it('caps the backoff delay', async () => {
    const load = vi.fn<() => Promise<string>>().mockRejectedValue(new Error('down'))

    retryUntilLoaded(load, vi.fn(), vi.fn(), { initialDelayMs: 1000, maxDelayMs: 1500 })
    await vi.advanceTimersByTimeAsync(1000 + 1500 + 1500)

    expect(load).toHaveBeenCalledTimes(4)
  })

  it('treats a hung attempt as failed and retries', async () => {
    const load = vi
      .fn<() => Promise<string>>()
      .mockReturnValueOnce(new Promise<string>(() => {})) // reply lost
      .mockResolvedValue('config')
    const onLoaded = vi.fn()
    const onError = vi.fn()

    retryUntilLoaded(load, onLoaded, onError, { attemptTimeoutMs: 3000 })
    await vi.advanceTimersByTimeAsync(3000 + 200)

    expect(onError).toHaveBeenCalledTimes(1)
    expect(load).toHaveBeenCalledTimes(2)
    expect(onLoaded).toHaveBeenCalledWith('config')
  })

  it('delivers only the first result when a slow attempt resolves late', async () => {
    let resolveSlow: (value: string) => void = () => {}
    const load = vi
      .fn<() => Promise<string>>()
      .mockReturnValueOnce(new Promise<string>((resolve) => (resolveSlow = resolve)))
      .mockResolvedValue('fresh')
    const onLoaded = vi.fn()

    retryUntilLoaded(load, onLoaded, vi.fn(), { attemptTimeoutMs: 1000 })
    await vi.advanceTimersByTimeAsync(1000 + 200)
    resolveSlow('stale')
    await vi.advanceTimersByTimeAsync(0)

    expect(onLoaded).toHaveBeenCalledTimes(1)
    expect(onLoaded).toHaveBeenCalledWith('fresh')
  })

  it('stops retrying and drops late results after cancel', async () => {
    const load = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error('state not managed'))
      .mockResolvedValue('config')
    const onLoaded = vi.fn()

    const cancel = retryUntilLoaded(load, onLoaded)
    await vi.advanceTimersByTimeAsync(0)
    cancel()
    await vi.advanceTimersByTimeAsync(5000)

    expect(load).toHaveBeenCalledTimes(1)
    expect(onLoaded).not.toHaveBeenCalled()
  })
})
