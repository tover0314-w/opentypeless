import { useEffect, useRef, useState } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'

export function useFnShortcutCapture(enabled: boolean, onEdge: (pressed: boolean) => void) {
  const [status, setStatus] = useState<'pending' | 'ready' | 'error'>('pending')
  const [previousEnabled, setPreviousEnabled] = useState(enabled)
  if (previousEnabled !== enabled) {
    setPreviousEnabled(enabled)
    setStatus('pending')
  }
  const callback = useRef(onEdge)
  callback.current = onEdge

  useEffect(() => {
    if (!enabled) return
    setStatus('pending')
    const captureId = crypto.randomUUID()
    let active = true
    let ready = false
    let unlisten: (() => void) | null = null
    const stop = () => {
      void invoke('stop_shortcut_capture', { captureId }).catch(() => {
        console.error('Failed to stop local shortcut capture')
      })
    }
    const revoke = () => {
      if (!active) return
      active = false
      unlisten?.()
      unlisten = null
      stop()
    }
    const onBlur = () => {
      if (!active) return
      setStatus('pending')
      revoke()
    }
    window.addEventListener('blur', onBlur)
    void listen<unknown>('shortcut-capture:fn', ({ payload }) => {
      if (
        active &&
        ready &&
        payload !== null &&
        typeof payload === 'object' &&
        'captureId' in payload &&
        payload.captureId === captureId &&
        'pressed' in payload &&
        typeof payload.pressed === 'boolean'
      ) {
        callback.current(payload.pressed)
      }
    })
      .then(async (release) => {
        if (!active) {
          release()
          return
        }
        unlisten = release
        await invoke('start_shortcut_capture', { captureId })
        if (!active) stop()
        else {
          ready = true
          setStatus('ready')
        }
      })
      .catch(() => {
        if (active) {
          console.error('Failed to start local shortcut capture')
          setStatus('error')
          revoke()
        }
      })
    return () => {
      window.removeEventListener('blur', onBlur)
      revoke()
    }
  }, [enabled])
  return enabled && previousEnabled === enabled ? status : 'pending'
}
