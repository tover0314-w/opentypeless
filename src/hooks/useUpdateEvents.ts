import { useEffect, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { useAppStore } from '../stores/appStore'
import { useCapsuleAlertStore } from '../stores/capsuleAlertStore'
import { installAndRestart } from '../lib/appUpdater'
import {
  UPDATE_INSTALL_EVENT,
  UPDATE_NOTICE_IDLE_DELAY_MS,
  UPDATE_READY_EVENT,
} from '../lib/appUpdaterEvents'

function useTauriEvent<T>(event: string, handler: (payload: T) => void) {
  useEffect(() => {
    let disposed = false
    let unlisten: (() => void) | null = null
    const release = (stop: () => void) => {
      try {
        Promise.resolve(stop()).catch(() => {})
      } catch {
        return
      }
    }
    listen<T>(event, (e) => {
      if (!disposed) handler(e.payload)
    })
      .then((fn) => {
        if (disposed) release(fn)
        else unlisten = fn
      })
      .catch((error) => console.error(`Failed to listen for ${event}:`, error))
    return () => {
      disposed = true
      if (unlisten) release(unlisten)
    }
  }, [event]) // eslint-disable-line react-hooks/exhaustive-deps
}

/** Main window: the capsule's Restart button and the tray's "Check for updates...". */
export function useMainUpdateEvents() {
  useTauriEvent<void>(UPDATE_INSTALL_EVENT, () => {
    installAndRestart().catch((error) => console.error('Failed to install update:', error))
  })
  useTauriEvent<void>('tray:check-updates', () => {
    window.location.hash = `#/settings?pane=about&check=${Date.now()}`
  })
}

/** Capsule: Typeless waits until the bar has been idle for 8s, once per downloaded version. */
export function useCapsuleUpdateNotice() {
  const [version, setVersion] = useState<string | null>(null)
  const [shown, setShown] = useState<ReadonlySet<string>>(() => new Set())
  const idle = useAppStore((s) => s.pipelineState === 'idle' && s.pipelineError === null)

  useTauriEvent<unknown>(UPDATE_READY_EVENT, (payload) => {
    if (typeof payload === 'string' && payload.trim()) setVersion(payload)
  })

  useEffect(() => {
    if (!version || !idle || shown.has(version)) return
    const timer = window.setTimeout(() => {
      setShown((previous) => new Set([...previous, version]))
      useCapsuleAlertStore.getState().show('update_available')
    }, UPDATE_NOTICE_IDLE_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [version, idle, shown])
}
