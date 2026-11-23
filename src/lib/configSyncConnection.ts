import type { ConfigCommitDisposition } from './configCommit'

export interface ConfigSyncOptions {
  subscribe: (receive: (payload: unknown) => void) => Promise<() => void>
  read: () => Promise<unknown>
  initialize: (payload: unknown) => boolean
  accept: (payload: unknown) => ConfigCommitDisposition
  changed: () => void
  failed: (stage: 'subscription' | 'read' | 'initialization' | 'buffer' | 'event') => void
}

export function connectConfigSync(options: ConfigSyncOptions): {
  ready: Promise<boolean>
  dispose: () => void
} {
  let active = true
  let initialized = false
  let unlisten: (() => void) | null = null
  const pending: unknown[] = []

  const release = (callback: () => void) => {
    try {
      Promise.resolve(callback()).catch(() => {})
    } catch {
      return
    }
  }

  const dispose = () => {
    active = false
    pending.length = 0
    if (unlisten) {
      const callback = unlisten
      unlisten = null
      release(callback)
    }
  }

  const fail = (stage: Parameters<ConfigSyncOptions['failed']>[0]) => {
    if (!active) return
    dispose()
    options.failed(stage)
  }

  const receive = (payload: unknown) => {
    if (!active) return
    if (!initialized) {
      if (pending.length >= 64) {
        fail('buffer')
        return
      }
      pending.push(payload)
      return
    }
    try {
      if (options.accept(payload) === 'accepted') options.changed()
    } catch {
      fail('event')
    }
  }

  const ready = (async () => {
    try {
      const callback = await options.subscribe(receive)
      if (!active) {
        release(callback)
        return false
      }
      unlisten = callback
    } catch {
      fail('subscription')
      return false
    }
    let snapshot: unknown
    try {
      snapshot = await options.read()
    } catch {
      fail('read')
      return false
    }
    if (!active) return false
    try {
      if (!options.initialize(snapshot)) {
        fail('initialization')
        return false
      }
      initialized = true
      options.changed()
    } catch {
      fail('initialization')
      return false
    }
    const buffered = pending.splice(0)
    for (const payload of buffered) receive(payload)
    return active
  })()

  return { ready, dispose }
}
