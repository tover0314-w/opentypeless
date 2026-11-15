import { useCallback, useEffect, useRef, useState } from 'react'
import { Check, Copy, Loader2, Mic, Square } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { openUrl } from '@tauri-apps/plugin-opener'
import {
  abortAskDictation,
  resizeAskWindow,
  startAskDictation,
  stopAskDictation,
  takePendingAskMessage,
} from '../../lib/tauri'
import type { AskDictationResult, AskDictationStartResult } from '../../lib/tauri'
import { AskAnswerCard } from './AskAnswerCard'
import { capsuleErrorKeyFromPayload } from '../../lib/capsuleError'

interface AskPanelProps {
  embedded?: boolean
  showHeader?: boolean
  title?: string
}

type AskResultPayload = AskDictationResult

function currentNativeWindow() {
  try {
    return getCurrentWindow()
  } catch {
    return null
  }
}

function safeUnlisten(unlisten: () => void) {
  try {
    Promise.resolve(unlisten()).catch(() => {})
  } catch {
    // Tauri can reject stale listener cleanup during dev reloads.
  }
}

export function AskPanel({ embedded = false, showHeader = true, title = 'Ask' }: AskPanelProps) {
  const { t } = useTranslation()
  const [result, setResult] = useState<AskResultPayload | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const [recordingContext, setRecordingContext] = useState<AskDictationStartResult | null>(null)
  const [dictationState, setDictationState] = useState<'idle' | 'recording' | 'processing'>('idle')
  const loadingRef = useRef(loading)
  const dictationStateRef = useRef(dictationState)
  const ownsDictationRef = useRef(false)
  const ignoreNextLocalResultRef = useRef(false)
  const copiedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const copyRevision = useRef(0)
  const messageRevision = useRef(0)
  const startupOwner = useRef<object | null>(null)
  const startupRequest = useRef<Promise<AskDictationStartResult> | null>(null)

  useEffect(() => {
    loadingRef.current = loading
    dictationStateRef.current = dictationState
  }, [dictationState, loading])

  const setBusy = useCallback((next: boolean) => {
    loadingRef.current = next
    setLoading(next)
  }, [])

  const setAskDictationState = useCallback((next: 'idle' | 'recording' | 'processing') => {
    dictationStateRef.current = next
    setDictationState(next)
  }, [])

  const applyResult = useCallback(
    (payload: AskResultPayload) => {
      ignoreNextLocalResultRef.current = false
      setResult(payload)
      setError('')
      setCopied(false)
      setRecordingContext(null)
      setAskDictationState('idle')
      setBusy(false)
    },
    [setAskDictationState, setBusy],
  )

  const applyError = useCallback(
    (message: string) => {
      ignoreNextLocalResultRef.current = false
      setError(message)
      setResult(null)
      setCopied(false)
      setRecordingContext(null)
      setAskDictationState('idle')
      setBusy(false)
    },
    [setAskDictationState, setBusy],
  )

  const applyRecordingStarted = useCallback(
    (payload: AskDictationStartResult) => {
      ignoreNextLocalResultRef.current = false
      setResult(null)
      setError('')
      setCopied(false)
      setRecordingContext(payload)
      ownsDictationRef.current = true
      setAskDictationState('recording')
      setBusy(false)
    },
    [setAskDictationState, setBusy],
  )

  const beginDictation = useCallback(async () => {
    if (loadingRef.current || dictationStateRef.current !== 'idle') return
    const owner = {}
    startupOwner.current = owner

    ignoreNextLocalResultRef.current = false
    setResult(null)
    setError('')
    setCopied(false)
    setRecordingContext(null)
    setAskDictationState('recording')
    ownsDictationRef.current = true
    try {
      const request = startAskDictation()
      startupRequest.current = request
      const started = await request
      if (startupOwner.current !== owner) return
      if (loadingRef.current) return
      startupOwner.current = null
      startupRequest.current = null
      applyRecordingStarted(started)
    } catch (e) {
      if (startupOwner.current !== owner) return
      startupOwner.current = null
      startupRequest.current = null
      ownsDictationRef.current = false
      setBusy(false)
      const message = e instanceof Error ? e.message : String(e)
      setError(message === 'Ask cancelled' ? '' : message)
      setRecordingContext(null)
      setAskDictationState('idle')
    }
  }, [applyRecordingStarted, setAskDictationState, setBusy])

  const finishDictation = useCallback(async () => {
    if (loadingRef.current || dictationStateRef.current !== 'recording') return
    const owner = startupOwner.current
    const pendingStartup = startupRequest.current
    setAskDictationState('processing')
    setBusy(true)
    setError('')
    setRecordingContext(null)
    if (owner && pendingStartup) {
      try {
        await pendingStartup
      } catch {
        return
      }
      if (startupOwner.current !== owner) return
    }
    startupOwner.current = null
    startupRequest.current = null
    ownsDictationRef.current = false
    try {
      const result = await stopAskDictation()
      if (ignoreNextLocalResultRef.current) {
        ignoreNextLocalResultRef.current = false
      } else {
        applyResult(result)
      }
    } catch (e) {
      if (ignoreNextLocalResultRef.current) {
        ignoreNextLocalResultRef.current = false
      } else {
        const message = e instanceof Error ? e.message : String(e)
        if (message !== 'Ask cancelled') applyError(message)
      }
    } finally {
      ownsDictationRef.current = false
      setBusy(false)
      setAskDictationState('idle')
    }
  }, [applyError, applyResult, setAskDictationState, setBusy])

  const hideStandaloneWindow = useCallback(async () => {
    if (embedded) return
    const window = currentNativeWindow()
    if (!window) return
    try {
      await window.hide()
    } catch {
      // Tests and browser preview do not always provide a native Tauri window.
    }
  }, [embedded])

  const dismissStandalone = useCallback(
    (ignorePendingResult = true) => {
      if (embedded) return
      startupOwner.current = null
      messageRevision.current += 1
      if (
        ignorePendingResult &&
        (loadingRef.current || dictationStateRef.current === 'processing')
      ) {
        ignoreNextLocalResultRef.current = true
      }
      if (ownsDictationRef.current && dictationStateRef.current === 'recording') {
        ownsDictationRef.current = false
        void abortAskDictation().catch(() => {})
      }
      setResult(null)
      setError('')
      setCopied(false)
      setRecordingContext(null)
      setBusy(false)
      setAskDictationState('idle')
      void hideStandaloneWindow()
    },
    [embedded, hideStandaloneWindow, setAskDictationState, setBusy],
  )

  useEffect(() => {
    if (embedded) return

    let cancelled = false
    const startupRevision = messageRevision.current
    const unlisteners: Array<() => void> = []
    const releaseListeners = () => {
      unlisteners.splice(0).forEach(safeUnlisten)
    }
    const trackListener = (registration: Promise<() => void>) =>
      registration.then((unlisten) => {
        if (cancelled) safeUnlisten(unlisten)
        else unlisteners.push(unlisten)
      })
    const applyPendingMessage = async () => {
      const revision = messageRevision.current
      const pending = await takePendingAskMessage()
      if (cancelled || revision !== messageRevision.current || !pending) return
      if (pending.kind === 'result') {
        applyResult(pending.payload)
      } else if (pending.kind === 'error') {
        applyError(pending.payload)
      }
    }

    import('@tauri-apps/api/event')
      .then(({ listen }) =>
        Promise.all([
          trackListener(
            listen<AskResultPayload>('ask:result', (event) => {
              if (!cancelled) {
                messageRevision.current += 1
                applyResult(event.payload)
                void takePendingAskMessage().catch(() => {})
              }
            }),
          ),
          trackListener(
            listen<string>('ask:error', (event) => {
              if (!cancelled) {
                messageRevision.current += 1
                applyError(event.payload)
                void takePendingAskMessage().catch(() => {})
              }
            }),
          ),
        ]),
      )
      .then(() => {
        if (!cancelled && startupRevision === messageRevision.current) {
          void applyPendingMessage().catch(() => {})
        }
      })
      .catch(() => {
        cancelled = true
        releaseListeners()
      })

    return () => {
      cancelled = true
      releaseListeners()
    }
  }, [applyError, applyResult, embedded])

  useEffect(() => {
    if (embedded) return

    // Typeless only closes the answer card from × or Esc; losing focus keeps it on screen.
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      dismissStandalone(true)
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [dismissStandalone, embedded])

  useEffect(() => {
    return () => {
      const starting = startupOwner.current !== null
      startupOwner.current = null
      startupRequest.current = null
      if (!ownsDictationRef.current) return
      if (!starting && dictationStateRef.current !== 'recording') return
      ownsDictationRef.current = false
      Promise.resolve(abortAskDictation()).catch(() => {})
    }
  }, [])

  const toggleDictation = useCallback(() => {
    if (dictationState === 'recording') {
      void finishDictation()
      return
    }

    void beginDictation()
  }, [beginDictation, dictationState, finishDictation])

  const answer = result?.answer ?? ''

  const copyAnswer = useCallback(() => {
    if (!answer) return
    const revision = ++copyRevision.current
    navigator.clipboard
      .writeText(answer)
      .then(() => {
        if (revision !== copyRevision.current) return
        setCopied(true)
        if (copiedTimerRef.current) clearTimeout(copiedTimerRef.current)
        copiedTimerRef.current = setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {})
  }, [answer])

  useEffect(() => {
    copyRevision.current += 1
    return () => {
      copyRevision.current += 1
      if (copiedTimerRef.current) clearTimeout(copiedTimerRef.current)
    }
  }, [result, error])

  const capsuleLabel =
    dictationState === 'recording'
      ? t('ask.listening')
      : dictationState === 'processing'
        ? t('ask.thinking')
        : t('ask.ready')
  const capsuleActive = dictationState === 'recording' || dictationState === 'processing'
  const displayTitle = title === 'Ask' ? t('ask.title') : title
  const errorKey = capsuleErrorKeyFromPayload(error)
  const localizedError = !error
    ? ''
    : error === 'Ask is already busy' || error.toLowerCase().includes('voice operation is busy')
      ? t('history.retryBusy')
      : errorKey !== 'unknown'
        ? t(`capsule.errors.${errorKey}`)
        : error
  const resultText = localizedError || answer
  const canCopyAnswer = Boolean(answer && !error && result?.output !== 'openedSearch')
  const recordingContextLabel = recordingContext?.usedSelectedText
    ? recordingContext.selectedTextTruncated
      ? t('ask.usingSelectedTextTruncated')
      : t('ask.usingSelectedText')
    : null
  const contextLabel =
    result?.fallbackReason === 'feature_disabled'
      ? t('ask.routeDisabled')
      : result?.output === 'copiedFallback'
        ? result.fallbackReason === 'target_changed' ||
          result.fallbackReason === 'focus_restore_failed'
          ? t('ask.targetChanged')
          : t('ask.copiedInstead')
        : result?.usedSelectedText
          ? result.selectedTextTruncated
            ? t('ask.usingSelectedTextTruncated')
            : t('ask.usingSelectedText')
          : result?.output === 'openedSearch' && result.searchProvider
            ? t('ask.searchOpened', { provider: result.searchProvider })
            : t('ask.questionLabel')
  const copyAction = canCopyAnswer ? (
    <div className="flex shrink-0 items-center gap-2">
      {copied && <span className="text-[11px] text-success">{t('ask.copied')}</span>}
      <button
        type="button"
        aria-label={t('ask.copyAnswer')}
        title={t('ask.copyAnswer')}
        onClick={copyAnswer}
        className="flex h-7 w-7 items-center justify-center rounded-[6px] border border-border bg-bg-secondary text-text-tertiary transition-colors hover:border-border-focus hover:text-accent cursor-pointer"
      >
        {copied ? <Check size={13} /> : <Copy size={13} />}
      </button>
    </div>
  ) : null

  const voiceButton = (
    <button
      type="button"
      aria-label={dictationState === 'recording' ? t('ask.stopAndAsk') : t('ask.recordQuestion')}
      onClick={toggleDictation}
      disabled={loading && dictationState !== 'recording'}
      className={`h-11 rounded-full border px-4 text-[13px] font-medium cursor-pointer disabled:cursor-not-allowed disabled:opacity-50 flex items-center gap-2 transition-colors ${
        capsuleActive
          ? 'bg-accent text-white border-accent shadow-sm'
          : 'bg-bg-secondary text-text-primary border-border hover:border-border-focus'
      }`}
    >
      {dictationState === 'processing' ? (
        <Loader2 size={14} className="animate-spin" />
      ) : dictationState === 'recording' ? (
        <span className="h-2 w-2 rounded-full bg-white animate-pulse" />
      ) : (
        <Mic size={14} />
      )}
      <span className="flex-1 text-left">
        <span className="block text-[13px]">{t('ask.voiceQuestion')}</span>
        <span
          className={`block text-[11px] font-normal ${
            capsuleActive ? 'text-white/70' : 'text-text-tertiary'
          }`}
        >
          {capsuleLabel}
        </span>
      </span>
      {dictationState === 'recording' && <Square size={13} />}
    </button>
  )

  const openExternalLink = useCallback((url: string) => {
    void openUrl(url).catch(() => console.error('Failed to open link'))
  }, [])

  const reportCardHeight = useCallback((height: number) => {
    // Card height plus the 24px top / 16px bottom padding around it in the transparent window.
    void resizeAskWindow(height + 40).catch(() => {})
  }, [])

  if (!embedded) {
    return (
      <div className="flex min-h-screen w-screen flex-col items-center justify-end bg-transparent px-2 pb-4 pt-6 text-text-primary">
        <AskAnswerCard
          prompt={result && result.output !== 'openedSearch' ? result.question : null}
          selectedText={result?.selectedText}
          answer={resultText ? answer || null : null}
          error={localizedError || null}
          context={result && contextLabel !== t('ask.questionLabel') ? contextLabel : null}
          canCopyAnswer={canCopyAnswer}
          onClose={() => dismissStandalone(true)}
          onOpenLink={openExternalLink}
          onHeightChange={reportCardHeight}
        />
      </div>
    )
  }

  return (
    <div
      className={`${embedded ? 'w-full' : 'h-screen w-screen'} bg-bg-primary text-text-primary flex flex-col`}
    >
      {showHeader && (
        <div className="flex items-center justify-between border-b border-border px-3 py-2">
          <span className="text-[13px] font-medium">{displayTitle}</span>
        </div>
      )}

      <div className={`${embedded ? 'p-3' : 'flex-1 min-h-0 p-3'} flex flex-col gap-3`}>
        {voiceButton}

        <p className="text-[11px] text-text-tertiary -mt-1">
          {recordingContextLabel ?? t('ask.voiceQuestionDesc')}
        </p>

        {resultText && (
          <div className="min-h-0 flex-1 overflow-y-auto rounded-[8px] border border-border bg-bg-secondary px-3 py-2">
            {canCopyAnswer && (
              <div className="mb-2 flex items-center justify-between gap-2">
                {result && (
                  <span className="truncate text-[11px] text-text-tertiary">{contextLabel}</span>
                )}
                {copyAction}
              </div>
            )}
            {result && !error && result.output !== 'openedSearch' && (
              <p className="mb-2 text-[12px] leading-5 text-text-secondary">{result.question}</p>
            )}
            <p
              className={`text-[13px] leading-5 whitespace-pre-wrap ${
                error ? 'text-error' : 'text-text-primary'
              }`}
            >
              {resultText}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
