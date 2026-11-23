import { useEffect, useRef, useState } from 'react'
import { useAuthStore, type AuthUser } from '../../stores/authStore'
import {
  getHistorySyncConsentView,
  type HistorySyncConsentView,
} from '../../lib/historySyncConsent'

export function useHistorySyncConsentRecovery(owner: AuthUser, expectedEpoch: string) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const [view, setView] = useState<HistorySyncConsentView | null>(null)
  const mounted = useRef(false)
  const operation = useRef<symbol | null>(null)
  const generation = useRef(0)

  useEffect(() => {
    mounted.current = true
    generation.current += 1
    operation.current = null
    setBusy(false)
    setError(false)
    setView(null)
    return () => {
      mounted.current = false
      generation.current += 1
      operation.current = null
    }
  }, [owner, expectedEpoch])

  const read = async () => {
    if (!mounted.current || operation.current || useAuthStore.getState().user !== owner) return
    const pending = Symbol()
    const currentGeneration = generation.current
    operation.current = pending
    const current = () =>
      mounted.current &&
      operation.current === pending &&
      generation.current === currentGeneration &&
      useAuthStore.getState().user === owner
    setBusy(true)
    setError(false)
    setView(null)
    try {
      const snapshot = await getHistorySyncConsentView()
      if (!current()) return
      if (snapshot.sessionEpoch !== expectedEpoch) throw new Error('Recovery session changed')
      setView(snapshot)
    } catch {
      if (current()) setError(true)
    } finally {
      if (current()) {
        operation.current = null
        setBusy(false)
      }
    }
  }

  return { busy, error, view, read }
}
