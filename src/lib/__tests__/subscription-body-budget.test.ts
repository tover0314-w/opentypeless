import { afterEach, expect, it, vi } from 'vitest'
import { getSubscriptionStatus } from '../api'

vi.mock('../cloud-session', () => ({
  captureCloudSessionOwner: () => () => true,
  loadSessionToken: vi.fn().mockResolvedValue(null),
  invalidateCloudSessionOnce: vi.fn(),
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it.each([401, 503])('preserves the size-limit failure for HTTP %s responses', async (status) => {
  const cancel = vi.fn()
  const response = new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array(1024 * 1024 + 1).fill(32))
      },
      cancel,
    }),
    { status, statusText: 'Synthetic service failure' },
  )
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  await expect(getSubscriptionStatus()).rejects.toThrow('Cloud response exceeds size limit')
  expect(cancel).toHaveBeenCalledOnce()
})

it('keeps the ordinary HTTP error fallback for a small malformed error body', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response('not-json', { status: 503, statusText: 'Unavailable' })),
  )
  await expect(getSubscriptionStatus()).rejects.toMatchObject({
    status: 503,
    message: 'Unavailable',
  })
})

it.each([undefined, '12'])(
  'rejects actual oversized bytes with declared length %s',
  async (length) => {
    const cancel = vi.fn()
    const response = new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(new Uint8Array(1024 * 1024 + 1).fill(32))
          controller.enqueue(new TextEncoder().encode('{"plan":"free"}'))
        },
        cancel,
      }),
      { headers: length ? { 'Content-Length': length } : {} },
    )
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    await expect(getSubscriptionStatus()).rejects.toThrow('Cloud response exceeds size limit')
    expect(cancel).toHaveBeenCalledOnce()
  },
)

it('reads valid JSON split across multibyte UTF-8 boundaries', async () => {
  const bytes = new TextEncoder().encode('{"plan":"free","displayName":"中文"}')
  const response = new Response(
    new ReadableStream({
      start(controller) {
        for (const byte of bytes) controller.enqueue(new Uint8Array([byte]))
        controller.close()
      },
    }),
  )
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  expect((await getSubscriptionStatus()).displayName).toBe('中文')
})

it('accepts a response exactly at the byte budget', async () => {
  const json = '{"plan":"free"}'
  const response = new Response(json + ' '.repeat(1024 * 1024 - json.length))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  expect((await getSubscriptionStatus()).plan).toBe('free')
})

it('counts all chunks before accepting a response', async () => {
  const cancel = vi.fn()
  const response = new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array(512 * 1024).fill(32))
        controller.enqueue(new Uint8Array(512 * 1024 + 1).fill(32))
      },
      cancel,
    }),
  )
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
  await expect(getSubscriptionStatus()).rejects.toThrow('Cloud response exceeds size limit')
  expect(cancel).toHaveBeenCalledOnce()
})

it('rejects malformed UTF-8 rather than silently replacing bytes', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Uint8Array([0xff]))))
  await expect(getSubscriptionStatus()).rejects.toThrow()
})

it('cancels a stalled body at the existing request deadline', async () => {
  vi.useFakeTimers()
  const cancel = vi.fn()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new ReadableStream({ cancel }))))
  const outcome = getSubscriptionStatus().catch((error: unknown) => error)
  await vi.advanceTimersByTimeAsync(30_001)
  expect(await outcome).toMatchObject({ name: 'AbortError' })
  expect(cancel).toHaveBeenCalledOnce()
  expect(vi.getTimerCount()).toBe(0)
})
