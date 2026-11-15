import { beforeEach, describe, expect, it, vi } from 'vitest'
import { onOpenUrl } from '@tauri-apps/plugin-deep-link'
import { generateOAuthState, initDeepLinkListener, clearOAuthState } from '../deep-link'

const mocks = vi.hoisted(() => ({
  refreshSubscription: vi.fn(),
  user: { id: 'synthetic-user' },
}))

vi.mock('@tauri-apps/plugin-deep-link', () => ({ onOpenUrl: vi.fn() }))
vi.mock('../../stores/authStore', () => ({
  useAuthStore: {
    getState: () => ({ refreshSubscription: mocks.refreshSubscription, user: mocks.user }),
  },
}))

beforeEach(() => {
  vi.mocked(onOpenUrl).mockReset()
  mocks.refreshSubscription.mockReset().mockResolvedValue(undefined)
  window.location.hash = ''
})

describe('desktop deep-link listener ownership', () => {
  it('contains an asynchronous native cleanup rejection', async () => {
    const unlisten = vi.fn(async () => {
      throw new Error('Synthetic async cleanup failure')
    })
    vi.mocked(onOpenUrl).mockResolvedValue(unlisten)
    const stop = initDeepLinkListener()
    await Promise.resolve()
    const handler = vi.mocked(onOpenUrl).mock.calls[0][0]
    stop()
    await new Promise((resolve) => setTimeout(resolve, 0))
    stop()
    await handler(['opentypeless://checkout/success'])
    expect(unlisten).toHaveBeenCalledOnce()
    expect(mocks.refreshSubscription).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('contains cleanup failure when registration arrives after its owner stops', async () => {
    let finishRegistration: ((stop: () => void) => void) | undefined
    const unlisten = vi.fn(() => {
      throw new Error('Synthetic late cleanup failure')
    })
    vi.mocked(onOpenUrl).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishRegistration = resolve
        }),
    )
    const stop = initDeepLinkListener()
    const handler = vi.mocked(onOpenUrl).mock.calls[0][0]
    stop()
    finishRegistration?.(unlisten)
    await new Promise((resolve) => setTimeout(resolve, 0))
    stop()
    await handler(['opentypeless://checkout/success'])
    expect(unlisten).toHaveBeenCalledOnce()
    expect(mocks.refreshSubscription).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('keeps stopped callbacks inactive when native cleanup throws', async () => {
    const unlisten = vi.fn(() => {
      throw new Error('Synthetic native cleanup failure')
    })
    vi.mocked(onOpenUrl).mockResolvedValue(unlisten)
    const stop = initDeepLinkListener()
    await Promise.resolve()
    const handler = vi.mocked(onOpenUrl).mock.calls[0][0]
    expect(() => stop()).not.toThrow()
    expect(() => stop()).not.toThrow()
    await handler(['opentypeless://checkout/success'])
    expect(unlisten).toHaveBeenCalledOnce()
    expect(mocks.refreshSubscription).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('does not navigate when a checkout refresh finishes after its listener stops', async () => {
    let finishRefresh: (() => void) | undefined
    mocks.refreshSubscription.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishRefresh = resolve
        }),
    )
    vi.mocked(onOpenUrl).mockResolvedValue(vi.fn())
    const stop = initDeepLinkListener()
    await Promise.resolve()
    const handler = vi.mocked(onOpenUrl).mock.calls[0][0]
    const pending = handler(['opentypeless://checkout/success'])
    stop()
    finishRefresh?.()
    await pending
    expect(window.location.hash).toBe('')
  })

  it('releases the native listener when its owner stops', async () => {
    const unlisten = vi.fn()
    vi.mocked(onOpenUrl).mockResolvedValue(unlisten)
    const stop = await initDeepLinkListener()
    expect(stop).toBeTypeOf('function')
    stop()
    stop()
    expect(unlisten).toHaveBeenCalledOnce()
  })

  it('releases a registration that completes after its owner stops', async () => {
    const unlisten = vi.fn()
    let finishRegistration: ((stop: () => void) => void) | undefined
    vi.mocked(onOpenUrl).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishRegistration = resolve
        }),
    )
    const stop = initDeepLinkListener()
    expect(stop).toBeTypeOf('function')
    stop()
    finishRegistration?.(unlisten)
    await Promise.resolve()
    expect(unlisten).toHaveBeenCalledOnce()
  })

  it('ignores callbacks from a stopped listener', async () => {
    const unlisten = vi.fn()
    vi.mocked(onOpenUrl).mockResolvedValue(unlisten)
    const stop = initDeepLinkListener()
    await Promise.resolve()
    const handler = vi.mocked(onOpenUrl).mock.calls[0][0]
    const state = generateOAuthState()
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    try {
      stop()
      await handler([`opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`])
      expect(fetchSpy).not.toHaveBeenCalled()
    } finally {
      fetchSpy.mockRestore()
      clearOAuthState()
    }
  })

  it('provides safe cleanup when native registration is unavailable', async () => {
    vi.mocked(onOpenUrl).mockRejectedValue(new Error('Synthetic plugin unavailable'))
    const stop = initDeepLinkListener()
    await Promise.resolve()
    await Promise.resolve()
    expect(() => stop()).not.toThrow()
  })

  it('contains a failed checkout refresh and continues the remaining callback batch', async () => {
    vi.mocked(onOpenUrl).mockResolvedValue(vi.fn())
    mocks.refreshSubscription.mockRejectedValueOnce(
      new Error('Synthetic refresh failure: sensitive-response-detail'),
    )
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const stop = initDeepLinkListener()
    await Promise.resolve()
    const handler = vi.mocked(onOpenUrl).mock.calls[0][0]
    try {
      await expect(
        Promise.resolve(
          handler(['opentypeless://checkout/success', 'opentypeless://checkout/success/']),
        ),
      ).resolves.toBeUndefined()
      expect(mocks.refreshSubscription).toHaveBeenCalledTimes(2)
      expect(window.location.hash).toBe('#/upgrade')
      expect(errorSpy).toHaveBeenCalledExactlyOnceWith('[deep-link] callback processing failed')
    } finally {
      stop()
      errorSpy.mockRestore()
    }
  })
})
