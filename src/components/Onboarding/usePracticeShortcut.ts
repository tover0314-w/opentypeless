import { useEffect, useRef } from 'react'
import { Channel, invoke } from '@tauri-apps/api/core'
import type { PracticeOptions } from '../../lib/tauri'

export function usePracticeShortcut(
  mode: PracticeOptions['mode'],
  onEdge: (pressed: boolean) => void,
  onBlur?: () => void,
  shortcutIdentity?: string | null,
) {
  const callback = useRef(onEdge)
  callback.current = onEdge
  const blurCallback = useRef(onBlur)
  blurCallback.current = onBlur

  useEffect(() => {
    let leaseId = ''
    let active = true
    let ready = false
    let generation = 0
    const unregister = (identity = leaseId) => {
      if (!identity) return
      void invoke('unregister_practice_shortcut', { leaseId: identity }).catch(() => {})
    }
    let channel: Channel<unknown>
    try {
      channel = new Channel<unknown>()
    } catch {
      return
    }
    channel.onmessage = (payload) => {
      if (
        active &&
        ready &&
        payload !== null &&
        typeof payload === 'object' &&
        'leaseId' in payload &&
        payload.leaseId === leaseId &&
        'pressed' in payload &&
        typeof payload.pressed === 'boolean'
      ) {
        callback.current(payload.pressed)
      }
    }
    const register = () => {
      if (!active) return
      const registration = ++generation
      const identity = crypto.randomUUID()
      leaseId = identity
      ready = false
      void invoke('register_practice_shortcut', { leaseId: identity, mode, channel })
        .then(() => {
          if (active && generation === registration) ready = true
          else unregister(identity)
        })
        .catch(() => {})
    }
    const blur = () => {
      generation += 1
      ready = false
      unregister()
      blurCallback.current?.()
    }
    window.addEventListener('focus', register)
    window.addEventListener('blur', blur)
    register()
    return () => {
      active = false
      ready = false
      window.removeEventListener('focus', register)
      window.removeEventListener('blur', blur)
      unregister()
    }
  }, [mode, shortcutIdentity])
}
