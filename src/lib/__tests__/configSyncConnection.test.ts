import { describe, expect, it, vi } from 'vitest'
import { connectConfigSync } from '../configSyncConnection'
import { useAppStore } from '../../stores/appStore'
import type { ConfigCommitDisposition } from '../configCommit'

function deferred<Value>() {
  let resolve!: (value: Value) => void
  const promise = new Promise<Value>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}

function harness() {
  let receive!: (payload: unknown) => void
  const subscription = deferred<() => void>()
  const reading = deferred<unknown>()
  const initialize = vi.fn(() => true)
  const accept = vi.fn((): ConfigCommitDisposition => 'accepted')
  const changed = vi.fn()
  const failed = vi.fn()
  const read = vi.fn(() => reading.promise)
  const connection = connectConfigSync({
    subscribe: vi.fn((handler) => {
      receive = handler
      return subscription.promise
    }),
    read,
    initialize,
    accept,
    changed,
    failed,
  })
  return {
    receive: (payload: unknown) => receive(payload),
    subscription,
    reading,
    initialize,
    accept,
    changed,
    failed,
    read,
    connection,
  }
}

describe('configuration sync connection', () => {
  it('waits for listener registration before reading and replays commits delivered during read', async () => {
    const runtime = harness()
    expect(runtime.read).not.toHaveBeenCalled()
    runtime.receive({ revision: 12 })
    runtime.subscription.resolve(vi.fn())
    await Promise.resolve()
    expect(runtime.read).toHaveBeenCalledOnce()
    runtime.receive({ revision: 13 })
    runtime.reading.resolve({ revision: 11 })
    expect(await runtime.connection.ready).toBe(true)
    expect(runtime.initialize).toHaveBeenCalledExactlyOnceWith({ revision: 11 })
    expect(runtime.accept.mock.calls).toEqual([[{ revision: 12 }], [{ revision: 13 }]])
    expect(runtime.changed).toHaveBeenCalledTimes(3)
  })

  it('notifies only for accepted events after initialization', async () => {
    const runtime = harness()
    runtime.subscription.resolve(vi.fn())
    runtime.reading.resolve({ revision: 5 })
    await runtime.connection.ready
    runtime.changed.mockClear()
    runtime.accept.mockReturnValueOnce('stale')
    runtime.receive({ revision: 4 })
    runtime.accept.mockReturnValueOnce('duplicate')
    runtime.receive({ revision: 5 })
    runtime.receive({ revision: 6 })
    expect(runtime.changed).toHaveBeenCalledOnce()
  })

  it('releases a listener that finishes registration after disposal without reading', async () => {
    const runtime = harness()
    const unlisten = vi.fn()
    runtime.connection.dispose()
    runtime.connection.dispose()
    runtime.subscription.resolve(unlisten)
    expect(await runtime.connection.ready).toBe(false)
    expect(unlisten).toHaveBeenCalledOnce()
    expect(runtime.read).not.toHaveBeenCalled()
  })

  it('ignores a late initialization result and later events after disposal', async () => {
    const runtime = harness()
    const unlisten = vi.fn()
    runtime.subscription.resolve(unlisten)
    await Promise.resolve()
    runtime.connection.dispose()
    runtime.reading.resolve({ revision: 5 })
    expect(await runtime.connection.ready).toBe(false)
    runtime.receive({ revision: 6 })
    expect(runtime.initialize).not.toHaveBeenCalled()
    expect(runtime.accept).not.toHaveBeenCalled()
    expect(unlisten).toHaveBeenCalledOnce()
  })

  it('fails closed and removes the subscription when initialization is rejected', async () => {
    const runtime = harness()
    const unlisten = vi.fn()
    runtime.initialize.mockReturnValueOnce(false)
    runtime.subscription.resolve(unlisten)
    runtime.reading.resolve({ revision: 'invalid' })
    expect(await runtime.connection.ready).toBe(false)
    runtime.receive({ revision: 6 })
    expect(runtime.accept).not.toHaveBeenCalled()
    expect(unlisten).toHaveBeenCalledOnce()
    expect(runtime.failed).toHaveBeenCalledExactlyOnceWith('initialization')
  })

  it('cleans up when initialization throws instead of rejecting the ready promise', async () => {
    const runtime = harness()
    const unlisten = vi.fn()
    runtime.initialize.mockImplementationOnce(() => {
      throw new Error('synthetic-private-validation-detail')
    })
    runtime.subscription.resolve(unlisten)
    runtime.reading.resolve({ revision: 5 })
    expect(await runtime.connection.ready).toBe(false)
    expect(unlisten).toHaveBeenCalledOnce()
    expect(runtime.failed).toHaveBeenCalledExactlyOnceWith('initialization')
    runtime.receive({ revision: 6 })
    expect(runtime.accept).not.toHaveBeenCalled()
  })

  it('cleans up an event consumer exception without throwing into the native listener', async () => {
    const runtime = harness()
    const unlisten = vi.fn()
    runtime.subscription.resolve(unlisten)
    runtime.reading.resolve({ revision: 5 })
    await runtime.connection.ready
    runtime.accept.mockImplementationOnce(() => {
      throw new Error('synthetic-private-event-detail')
    })
    expect(() => runtime.receive({ revision: 6 })).not.toThrow()
    expect(unlisten).toHaveBeenCalledOnce()
    expect(runtime.failed).toHaveBeenCalledExactlyOnceWith('event')
    runtime.receive({ revision: 7 })
    expect(runtime.accept).toHaveBeenCalledOnce()
  })

  it('reports fixed read errors without forwarding private rejection details', async () => {
    const failed = vi.fn()
    const unlisten = vi.fn()
    const connection = connectConfigSync({
      subscribe: async () => unlisten,
      read: async () => {
        throw new Error('synthetic-private-credential')
      },
      initialize: vi.fn(() => true),
      accept: vi.fn((): ConfigCommitDisposition => 'accepted'),
      changed: vi.fn(),
      failed,
    })
    expect(await connection.ready).toBe(false)
    expect(failed).toHaveBeenCalledExactlyOnceWith('read')
    expect(unlisten).toHaveBeenCalledOnce()
  })

  it('reports subscription failures without reading or exposing rejection details', async () => {
    const failed = vi.fn()
    const read = vi.fn()
    const connection = connectConfigSync({
      subscribe: async () => {
        throw new Error('synthetic-private-detail')
      },
      read,
      initialize: vi.fn(() => true),
      accept: vi.fn((): ConfigCommitDisposition => 'accepted'),
      changed: vi.fn(),
      failed,
    })
    expect(await connection.ready).toBe(false)
    expect(read).not.toHaveBeenCalled()
    expect(failed).toHaveBeenCalledExactlyOnceWith('subscription')
  })

  it('bounds pre-initialization buffering and releases a late listener after overflow', async () => {
    const runtime = harness()
    for (let revision = 0; revision < 65; revision += 1) runtime.receive({ revision })
    const unlisten = vi.fn()
    runtime.subscription.resolve(unlisten)
    expect(await runtime.connection.ready).toBe(false)
    expect(runtime.failed).toHaveBeenCalledExactlyOnceWith('buffer')
    expect(runtime.read).not.toHaveBeenCalled()
    expect(unlisten).toHaveBeenCalledOnce()
  })

  it('replays real store commits out of order without losing a newer local draft', async () => {
    useAppStore.setState(useAppStore.getInitialState())
    const config = {
      ...structuredClone(useAppStore.getState().config),
      managed_stt_capability_state: null,
    }
    const configEpoch = '11111111-1111-4111-8111-111111111111'
    const reading = deferred<unknown>()
    let receive!: (payload: unknown) => void
    const connection = connectConfigSync({
      subscribe: async (handler) => {
        receive = handler
        return vi.fn<() => void>()
      },
      read: () => reading.promise,
      initialize: (payload) => useAppStore.getState().initializeConfigCommit(payload),
      accept: (payload) => useAppStore.getState().applyConfigCommit(payload),
      changed: vi.fn(),
      failed: vi.fn(),
    })
    receive({
      configEpoch,
      revision: 12,
      saveId: null,
      config: { ...config, microphone_device: 'latest-device' },
    })
    receive({
      configEpoch,
      revision: 11,
      saveId: null,
      config: { ...config, microphone_device: 'older-device' },
    })
    reading.resolve({ configEpoch, revision: 10, saveId: null, config })
    try {
      expect(await connection.ready).toBe(true)
      expect(useAppStore.getState().configCommit?.revision).toBe(12)
      expect(useAppStore.getState().config.microphone_device).toBe('latest-device')
      useAppStore.getState().updateConfig({ theme: 'dark' })
      receive({ configEpoch, revision: 13, saveId: null, config })
      expect(useAppStore.getState().config.theme).toBe('dark')
      expect(useAppStore.getState().savedConfig?.theme).toBe('system')
    } finally {
      connection.dispose()
      useAppStore.setState(useAppStore.getInitialState())
    }
  })
})
