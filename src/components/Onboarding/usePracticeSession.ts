import { useEffect, useRef, useState } from 'react'
import {
  cancelPractice,
  getPractice,
  startPractice,
  stopPractice,
  type PracticeSnapshot,
  type PracticeOptions,
} from '../../lib/tauri'

function isBoundedPracticeText(value: unknown, optional = false): boolean {
  if (optional && value === undefined) return true
  return (
    typeof value === 'string' &&
    value.length <= 32_768 &&
    new TextEncoder().encode(value).byteLength <= 32_768
  )
}

function isValidPracticeError(value: unknown): boolean {
  if (value === null || value === undefined) return true
  if (typeof value !== 'object' || Array.isArray(value)) return false
  const error = value as Record<string, unknown>
  return (
    typeof error.code === 'string' &&
    error.code.trim().length > 0 &&
    error.code.length <= 128 &&
    (error.details === null || isBoundedPracticeText(error.details, true)) &&
    Number.isInteger(error.retry_count) &&
    (error.retry_count as number) >= 0 &&
    (error.retry_count as number) <= 4_294_967_295
  )
}

export function usePracticeSession(options?: PracticeOptions) {
  const [snapshot, setSnapshot] = useState<PracticeSnapshot | null>(null)
  const [starting, setStarting] = useState(false)
  const [stopping, setStopping] = useState(false)
  const [failed, setFailed] = useState(false)
  const mounted = useRef(true)
  const identity = useRef<object | null>(null)
  const session = useRef<string | null>(null)
  const pendingStop = useRef<object | null>(null)
  const startupStop = useRef<object | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const clearTimer = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
  }
  const release = (value: string) => {
    void cancelPractice(value).catch(() => {})
  }

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      identity.current = null
      pendingStop.current = null
      startupStop.current = null
      clearTimer()
      if (session.current) release(session.current)
      session.current = null
    }
  }, [])

  const start = async () => {
    if (identity.current || !mounted.current) return
    const owner = {}
    identity.current = owner
    startupStop.current = null
    pendingStop.current = null
    setStopping(false)
    setStarting(true)
    setFailed(false)
    setSnapshot(null)
    const current = () => mounted.current && identity.current === owner
    try {
      if (session.current) release(session.current)
      session.current = null
      const value = await startPractice(options)
      if (typeof value !== 'string' || !value.trim()) {
        throw new Error('Invalid practice session identity')
      }
      if (!current()) {
        release(value)
        return
      }
      session.current = value
      setStarting(false)
      if (startupStop.current === owner) {
        startupStop.current = null
        await stop()
        if (!current()) return
      }
      const poll = async () => {
        try {
          const next = await getPractice(value)
          if (!current()) return
          if (next.session !== value) throw new Error('Practice session mismatch')
          if (
            ![
              'preparing',
              'recording',
              'transcribing',
              'polishing',
              'complete',
              'error',
              'cancelled',
            ].includes(next.phase) ||
            !Number.isFinite(next.level) ||
            next.level < 0 ||
            next.level > 1
          ) {
            throw new Error('Invalid practice progress')
          }
          if (
            !isBoundedPracticeText(next.rawText) ||
            !isBoundedPracticeText(next.result) ||
            !isBoundedPracticeText(next.polishPreview, true)
          ) {
            throw new Error('Invalid practice text')
          }
          if (next.phase === 'complete' && !next.result.trim()) {
            throw new Error('Empty practice result')
          }
          if (
            !isValidPracticeError(next.error) ||
            (next.phase === 'complete' && next.error != null)
          ) {
            throw new Error('Invalid practice error')
          }
          setSnapshot(next)
          if (next.phase !== 'preparing' && next.phase !== 'recording') {
            pendingStop.current = null
            setStopping(false)
          }
          if (['complete', 'error', 'cancelled'].includes(next.phase)) {
            identity.current = null
            pendingStop.current = null
            setStopping(false)
            return
          }
          timer.current = setTimeout(() => void poll(), 150)
        } catch {
          if (!current()) return
          release(value)
          session.current = null
          identity.current = null
          pendingStop.current = null
          setStopping(false)
          setSnapshot(null)
          setFailed(true)
        }
      }
      void poll()
    } catch {
      if (current()) {
        identity.current = null
        setStarting(false)
        setFailed(true)
      }
    }
  }

  const stop = async () => {
    const value = session.current
    const owner = identity.current
    if (owner && !value) {
      startupStop.current = owner
      return
    }
    if (!value || !owner || pendingStop.current) return
    const request = {}
    pendingStop.current = request
    setStopping(true)
    let accepted = false
    try {
      await stopPractice(value)
      accepted = true
    } catch {
      if (
        mounted.current &&
        session.current === value &&
        identity.current === owner &&
        pendingStop.current === request
      ) {
        identity.current = null
        clearTimer()
        release(value)
        session.current = null
        setSnapshot(null)
        setFailed(true)
      }
    } finally {
      if (!accepted && pendingStop.current === request) {
        pendingStop.current = null
        if (mounted.current) setStopping(false)
      }
    }
  }

  const cancel = async () => {
    startupStop.current = null
    identity.current = null
    pendingStop.current = null
    clearTimer()
    const value = session.current
    session.current = null
    if (mounted.current) {
      setStarting(false)
      setStopping(false)
      setSnapshot(null)
      setFailed(false)
    }
    if (value) release(value)
  }

  return { snapshot, starting, stopping, failed, start, stop, cancel }
}
