import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { capsuleErrorKeyFromPayload } from '../../lib/capsuleError'
import { retryTranscription } from '../../lib/tauri'
import { useAppStore } from '../../stores/appStore'
import { useCapsuleAlertStore } from '../../stores/capsuleAlertStore'

export function useRetryAndCopy() {
  const { t } = useTranslation()
  const pipelineError = useAppStore((state) => state.pipelineError)
  const errorKey = useAppStore((state) => state.pipelineErrorKey)
  const failedTakeId = useAppStore((state) => state.failedTakeId)
  const setPipelineError = useAppStore((state) => state.setPipelineError)
  const [retrying, setRetrying] = useState(false)
  const retryPending = useRef(false)
  const retryWatchCleanup = useRef<(() => void) | null>(null)

  useEffect(() => () => retryWatchCleanup.current?.(), [])

  const retryAndCopy = async () => {
    if (retryPending.current) return
    const current = useAppStore.getState()
    if (
      current.pipelineState !== 'idle' ||
      !current.retryAvailable ||
      current.pipelineError !== pipelineError ||
      current.pipelineErrorKey !== errorKey ||
      current.failedTakeId !== failedTakeId
    )
      return
    retryPending.current = true
    setRetrying(true)
    let superseded = false
    let unsubscribe: (() => void) | null = null
    const invalidate = () => {
      superseded = true
      unsubscribe?.()
      unsubscribe = null
    }
    unsubscribe = useAppStore.subscribe((state) => {
      if (
        state.pipelineState === 'preparing' ||
        state.pipelineState === 'recording' ||
        state.pipelineState === 'ask_recording' ||
        state.pipelineError !== pipelineError ||
        state.pipelineErrorKey !== errorKey ||
        state.failedTakeId !== failedTakeId
      )
        invalidate()
    })
    retryWatchCleanup.current = invalidate
    const ownsError = () => {
      const state = useAppStore.getState()
      return (
        !superseded &&
        state.pipelineState === 'idle' &&
        state.pipelineError === pipelineError &&
        state.pipelineErrorKey === errorKey &&
        state.failedTakeId === failedTakeId
      )
    }
    try {
      await retryTranscription()
      if (!ownsError()) return
      useAppStore.getState().setRetryAvailable(false)
      setPipelineError(null)
      useCapsuleAlertStore.getState().show('retry_copied')
    } catch (error) {
      if (!ownsError()) return
      const key = capsuleErrorKeyFromPayload(String(error))
      useAppStore.getState().setPipelineErrorKey(key)
      const messageKey = `capsule.errors.${key}`
      setPipelineError(t(messageKey), messageKey)
    } finally {
      unsubscribe?.()
      if (retryWatchCleanup.current === invalidate) retryWatchCleanup.current = null
      retryPending.current = false
      setRetrying(false)
    }
  }

  return { retrying, retryAndCopy }
}
