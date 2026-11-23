import { useEffect, useState } from 'react'
import { pauseHotkey, resumeHotkey } from '../lib/tauri'

let captureOwners = 0
type NativeCaptureState =
  | { phase: 'idle' }
  | { phase: 'pending' | 'capturing' | 'uncertain'; captureId: string | undefined }
let nativeCapture: NativeCaptureState = { phase: 'idle' }
let operationQueue: Promise<void> = Promise.resolve()
type CaptureStatus = 'pending' | 'ready' | 'error'
const statusListeners = new Set<(status: CaptureStatus) => void>()
const heldKeys = new Set<string>()
let releaseTracking = false
let resumeInFlight = false
let interruptPause: Promise<boolean> | null = null
let captureId: string | undefined

function trackCapturedKeys(event: KeyboardEvent) {
  const namedKey = event.key.toLowerCase()
  const key = ['control', 'shift', 'alt', 'meta'].includes(namedKey)
    ? namedKey
    : event.code || namedKey
  if (event.type === 'keydown') heldKeys.add(key)
  else heldKeys.delete(key)
  for (const [modifier, pressed] of [
    ['control', event.ctrlKey],
    ['shift', event.shiftKey],
    ['alt', event.altKey],
    ['meta', event.metaKey],
  ] as const) {
    if (pressed) heldKeys.add(modifier)
    else heldKeys.delete(modifier)
  }
  if (event.type === 'keyup' && heldKeys.size === 0 && captureOwners === 0) {
    void reconcileCapture(false)
  }
}

function startReleaseTracking() {
  if (releaseTracking) return
  releaseTracking = true
  window.addEventListener('keydown', trackCapturedKeys, true)
  window.addEventListener('keyup', trackCapturedKeys, true)
  window.addEventListener('focus', recoverPausedCapture)
}

function stopReleaseTracking() {
  window.removeEventListener('keydown', trackCapturedKeys, true)
  window.removeEventListener('keyup', trackCapturedKeys, true)
  window.removeEventListener('focus', recoverPausedCapture)
  releaseTracking = false
  heldKeys.clear()
}

function recoverPausedCapture() {
  if (captureOwners === 0 && nativeCapture.phase === 'uncertain' && heldKeys.size === 0) {
    void reconcileCapture(false)
  }
}

function publishCaptureStatus(status: CaptureStatus) {
  for (const listener of statusListeners) listener(status)
}

function captureIsReady(identity = captureId, active = captureOwners > 0): boolean {
  return (
    active &&
    Boolean(identity) &&
    identity === captureId &&
    nativeCapture.phase === 'capturing' &&
    nativeCapture.captureId === identity
  )
}

export function queueShortcutCaptureSave<Result>(save: () => Promise<Result>): Promise<Result> {
  const requestedCaptureId = captureId
  const requestedWhileActive = captureOwners > 0
  const pending = operationQueue.then(async () => {
    if (!captureIsReady(requestedCaptureId, requestedWhileActive)) {
      throw new Error('Shortcut capture is not ready')
    }
    return save()
  })
  operationQueue = pending.then(
    () => {},
    () => {
      console.error('Failed to save captured shortcut')
    },
  )
  return pending
}

function reconcileCapture(pauseRequested: boolean) {
  operationQueue = operationQueue
    .then(async () => {
      if (
        pauseRequested &&
        (nativeCapture.phase !== 'capturing' || nativeCapture.captureId !== captureId)
      ) {
        publishCaptureStatus('pending')
        const requestedCaptureId = captureId
        nativeCapture = { phase: 'pending', captureId: requestedCaptureId }
        await pauseHotkey(requestedCaptureId)
        nativeCapture = { phase: 'capturing', captureId: requestedCaptureId }
        if (requestedCaptureId === captureId) publishCaptureStatus('ready')
      } else if (!pauseRequested && captureOwners === 0 && nativeCapture.phase !== 'idle') {
        if (heldKeys.size > 0) return
        publishCaptureStatus('pending')
        resumeInFlight = true
        try {
          await resumeHotkey(captureId)
        } catch (error) {
          if (!interruptPause) throw error
        } finally {
          resumeInFlight = false
        }
        if (interruptPause) {
          const paused = await interruptPause
          interruptPause = null
          nativeCapture = {
            phase: paused ? 'capturing' : 'uncertain',
            captureId: nativeCapture.captureId,
          }
          return
        }
        nativeCapture = { phase: 'idle' }
        if (captureOwners === 0 && heldKeys.size > 0) {
          nativeCapture = { phase: 'pending', captureId }
          await pauseHotkey(captureId)
          nativeCapture = { phase: 'capturing', captureId }
          return
        }
        if (captureOwners === 0) stopReleaseTracking()
      }
    })
    .catch(() => {
      nativeCapture = {
        phase: 'uncertain',
        captureId: nativeCapture.phase === 'idle' ? captureId : nativeCapture.captureId,
      }
      publishCaptureStatus('error')
      console.error('Failed to synchronize shortcut capture')
    })
  return operationQueue
}

export function useHotkeyCapture(enabled: boolean) {
  const [status, setStatus] = useState<CaptureStatus>('pending')
  useEffect(() => {
    if (!enabled) {
      setStatus('pending')
      return
    }
    let active = true
    setStatus('pending')
    statusListeners.add(setStatus)
    if (captureOwners === 0) captureId = crypto.randomUUID()
    captureOwners += 1
    if (resumeInFlight && !interruptPause) {
      const interruptCaptureId = captureId
      interruptPause = pauseHotkey(interruptCaptureId).then(
        () => {
          nativeCapture = { phase: 'capturing', captureId: interruptCaptureId }
          return true
        },
        () => false,
      )
    }
    startReleaseTracking()
    void reconcileCapture(true).then(() => {
      if (active) setStatus(captureIsReady() ? 'ready' : 'error')
    })
    return () => {
      active = false
      statusListeners.delete(setStatus)
      captureOwners -= 1
      reconcileCapture(false)
    }
  }, [enabled])
  return enabled ? status : 'pending'
}
