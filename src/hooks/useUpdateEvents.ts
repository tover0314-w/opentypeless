import { useEffect, useState } from 'react'
import { listen } from '@tauri-apps/api/event'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../stores/appStore'
import { useCapsuleAlertStore } from '../stores/capsuleAlertStore'
import { installAndRestart } from '../lib/appUpdater'
import {
  UPDATE_INSTALL_EVENT,
  UPDATE_NOTICE_IDLE_DELAY_MS,
  UPDATE_READY_EVENT,
} from '../lib/appUpdaterEvents'

function useTauriEvent<T>(event: string, handler: (payload: T, active: () => boolean) => void) {
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
      if (!disposed) handler(e.payload, () => !disposed)
    })
      .then((fn) => {
        if (disposed) release(fn)
        else unlisten = fn
      })
      .catch(() => {
        if (!disposed) console.error(`Failed to listen for ${event}`)
      })
    return () => {
      disposed = true
      if (unlisten) release(unlisten)
    }
  }, [event]) // eslint-disable-line react-hooks/exhaustive-deps
}

/** Main window: the capsule's Restart button and the tray's "Check for updates...". */
export function useMainUpdateEvents() {
  useTauriEvent<unknown>(UPDATE_INSTALL_EVENT, (payload, active) => {
    if (typeof payload !== 'string' || !payload.trim()) return
    invoke<boolean>('consume_update_install_request', { requestId: payload })
      .then((authorized) => {
        if (authorized === true && active()) return installAndRestart()
      })
      .catch(() => console.error('Failed to install update'))
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
