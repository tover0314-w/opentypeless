import { useEffect, useRef, useState } from 'react'
import { useAuthStore, type AuthUser } from '../../stores/authStore'
import {
  cancelHistorySyncProposal,
  getHistorySyncSessionEpoch,
  prepareHistorySyncProposal,
  type HistorySyncProposal,
} from '../../lib/historySyncConsent'

export function useHistorySyncPreparation(
  owner: AuthUser,
  onPrepared: (proposal: HistorySyncProposal) => void,
) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const mounted = useRef(false)
  const operation = useRef<AbortController | null>(null)

  useEffect(() => {
    mounted.current = true
    setBusy(false)
    setError(false)
    return () => {
      mounted.current = false
      const pending = operation.current
      operation.current = null
      pending?.abort()
    }
  }, [owner])

  const cancel = () => {
    const pending = operation.current
    operation.current = null
    pending?.abort()
    if (mounted.current && useAuthStore.getState().user === owner) setBusy(false)
  }

  const start = async (epoch?: string) => {
    if (!mounted.current || operation.current || useAuthStore.getState().user !== owner) return
    const pending = new AbortController()
    operation.current = pending
    const current = () =>
      mounted.current &&
      operation.current === pending &&
      !pending.signal.aborted &&
      useAuthStore.getState().user === owner
    setBusy(true)
    setError(false)
    const deadline = setTimeout(() => {
      if (!current()) return
      operation.current = null
      setBusy(false)
      setError(true)
      pending.abort()
    }, 30000)
    const clearDeadline = () => clearTimeout(deadline)
    pending.signal.addEventListener('abort', clearDeadline, { once: true })
    try {
      const expectedEpoch = epoch ?? (await getHistorySyncSessionEpoch())
      if (!current()) return
      const proposal = await prepareHistorySyncProposal(expectedEpoch, pending.signal)
      if (!current()) {
        await cancelHistorySyncProposal(proposal.requestId).catch(() => undefined)
        return
      }
      try {
        onPrepared(proposal)
      } catch (failure) {
        await cancelHistorySyncProposal(proposal.requestId).catch(() => undefined)
        throw failure
      }
    } catch {
      if (current()) setError(true)
    } finally {
      clearDeadline()
      pending.signal.removeEventListener('abort', clearDeadline)
      if (operation.current === pending) {
        operation.current = null
        if (mounted.current && useAuthStore.getState().user === owner) setBusy(false)
      }
    }
  }

  return { busy, error, start, cancel }
}
