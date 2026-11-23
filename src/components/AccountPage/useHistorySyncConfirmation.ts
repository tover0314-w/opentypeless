import { useEffect, useRef, useState } from 'react'
import { useAuthStore, type AuthUser } from '../../stores/authStore'
import {
  cancelHistorySyncProposal,
  confirmAndReadHistorySyncConsent,
  HistorySyncConfirmationUnknownError,
  type HistorySyncProposal,
} from '../../lib/historySyncConsent'

export function useHistorySyncConfirmation(owner: AuthUser, proposal: HistorySyncProposal) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const [resultUnknown, setResultUnknown] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [cancelled, setCancelled] = useState(false)
  const mounted = useRef(false)
  const generation = useRef(0)
  const attempted = useRef(false)
  const proposalIdentity = JSON.stringify([
    proposal.requestId,
    proposal.description.accountId,
    proposal.description.serviceUrl,
    proposal.description.sessionEpoch,
  ])

  useEffect(() => {
    mounted.current = true
    generation.current += 1
    attempted.current = false
    setBusy(false)
    setError(false)
    setResultUnknown(false)
    setConfirmed(false)
    setCancelled(false)
    return () => {
      mounted.current = false
      generation.current += 1
    }
  }, [owner, proposalIdentity])

  const confirm = async () => {
    if (!mounted.current || attempted.current || useAuthStore.getState().user !== owner) return
    attempted.current = true
    const currentGeneration = generation.current
    const current = () =>
      mounted.current &&
      generation.current === currentGeneration &&
      useAuthStore.getState().user === owner
    setBusy(true)
    setError(false)
    try {
      await confirmAndReadHistorySyncConsent(proposal)
      if (current()) setConfirmed(true)
    } catch (failure) {
      if (current()) {
        setError(true)
        setResultUnknown(failure instanceof HistorySyncConfirmationUnknownError)
      }
    } finally {
      if (current()) setBusy(false)
    }
  }

  const cancel = async () => {
    if (!mounted.current || attempted.current || useAuthStore.getState().user !== owner) return
    attempted.current = true
    const currentGeneration = generation.current
    const current = () =>
      mounted.current &&
      generation.current === currentGeneration &&
      useAuthStore.getState().user === owner
    setBusy(true)
    setError(false)
    try {
      const removed = await cancelHistorySyncProposal(proposal.requestId)
      if (current()) {
        setCancelled(removed)
        setResultUnknown(!removed)
        setError(!removed)
      }
    } catch {
      if (current()) {
        setError(true)
        setResultUnknown(true)
      }
    } finally {
      if (current()) setBusy(false)
    }
  }

  return {
    busy,
    error,
    resultUnknown,
    confirmed,
    confirm,
    cancelled,
    cancel,
  }
}
