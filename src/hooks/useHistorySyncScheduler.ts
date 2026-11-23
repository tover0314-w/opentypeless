import { useEffect, useRef } from 'react'
import { useAuthStore } from '../stores/authStore'
import {
  getHistorySyncConsentView,
  getHistorySyncConflictCount,
  historySyncOnceBlocked,
  syncHistoryOnce,
} from '../lib/historySyncConsent'
import {
  nextHistorySyncDelay,
  setAutomaticHistorySync,
  useAutomaticHistorySync,
} from '../lib/historySyncSchedule'

export function useHistorySyncScheduler() {
  const owner = useAuthStore((state) => state.user)
  const enabled = useAutomaticHistorySync(owner?.id)
  const nextAllowedSync = useRef(0)
  useEffect(() => {
    nextAllowedSync.current = 0
    if (!owner) return
    return () => setAutomaticHistorySync(owner.id, false)
  }, [owner])
  useEffect(() => {
    if (!owner || !enabled) return
    let active = true
    let running = false
    let failures = 0
    let timer: ReturnType<typeof setTimeout> | undefined
    const current = () => active && useAuthStore.getState().user === owner
    const schedule = (delay: number) => {
      if (!current()) return
      if (timer !== undefined) clearTimeout(timer)
      timer = setTimeout(() => void run(), Math.max(delay, nextAllowedSync.current - Date.now()))
    }
    const run = async () => {
      if (!current() || running) return
      running = true
      let delay = 30000
      try {
        if (historySyncOnceBlocked() || !navigator.onLine) return
        const view = await getHistorySyncConsentView()
        if (!current()) return
        if (!view.active) {
          setAutomaticHistorySync(owner.id, false)
          return
        }
        const conflicts = await getHistorySyncConflictCount(view)
        if (!current()) return
        if (conflicts > 0) {
          setAutomaticHistorySync(owner.id, false)
          return
        }
        let result
        nextAllowedSync.current = Date.now() + 7000
        try {
          result = await syncHistoryOnce(view)
        } catch (error) {
          if (current()) setAutomaticHistorySync(owner.id, false)
          throw error
        }
        if (!current()) return
        failures = 0
        delay = nextHistorySyncDelay(result, 0)
      } catch {
        if (!current()) return
        failures += 1
        delay = nextHistorySyncDelay(null, failures)
        nextAllowedSync.current = Math.max(nextAllowedSync.current, Date.now() + delay)
      } finally {
        running = false
        schedule(delay)
      }
    }
    const wake = () => {
      if (!running) schedule(1000)
    }
    window.addEventListener('online', wake)
    window.addEventListener('focus', wake)
    schedule(1000)
    return () => {
      active = false
      if (timer !== undefined) clearTimeout(timer)
      window.removeEventListener('online', wake)
      window.removeEventListener('focus', wake)
    }
  }, [owner, enabled])
}
