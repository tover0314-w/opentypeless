import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuthStore, type AuthUser } from '../../stores/authStore'
import {
  getHistorySyncConsentView,
  revokeHistorySyncConsentView,
  syncHistoryOnce,
  HistorySyncResultUnknownError,
  historySyncOnceBlocked,
  subscribeHistorySyncOnceResult,
  getHistorySyncAttempt,
  getHistorySyncConflictCount,
  listHistorySyncConflicts,
  type HistorySyncConflictSummary,
  previewHistorySyncRemoteRecord,
  type HistorySyncRemoteRecord,
  previewHistorySyncLocalRecord,
  type HistorySyncLocalRecord,
  keepLocalHistorySyncConflict,
  keepRemoteHistorySyncConflict,
  acknowledgeHistorySyncAttempt,
  type HistorySyncAttempt,
  type HistorySyncOnceResult,
  type HistorySyncConsentView,
} from '../../lib/historySyncConsent'
import { HistorySyncAuthorizationFlow } from './HistorySyncAuthorizationFlow'
import { Modal } from '../shared/Modal'
import { setAutomaticHistorySync, useAutomaticHistorySync } from '../../lib/historySyncSchedule'

export function HistorySyncAuthorization({ owner }: { owner: AuthUser }) {
  const { t } = useTranslation()
  const automatic = useAutomaticHistorySync(owner.id)
  const [view, setView] = useState<HistorySyncConsentView | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState(false)
  const [checked, setChecked] = useState(false)
  const [syncBusy, setSyncBusy] = useState(false)
  const [syncError, setSyncError] = useState(false)
  const [syncUnknown, setSyncUnknown] = useState(historySyncOnceBlocked)
  const [syncResult, setSyncResult] = useState<HistorySyncOnceResult | null>(null)
  const [readingResult, setReadingResult] = useState(false)
  const [resultReadError, setResultReadError] = useState(false)
  const [lastAttempt, setLastAttempt] = useState<HistorySyncAttempt | null>(null)
  const [conflicts, setConflicts] = useState<number | null>(null)
  const [conflictRefresh, setConflictRefresh] = useState(0)
  const [conflictList, setConflictList] = useState<HistorySyncConflictSummary[] | null>(null)
  const [conflictListBusy, setConflictListBusy] = useState(false)
  const [conflictListError, setConflictListError] = useState(false)
  const [moreConflicts, setMoreConflicts] = useState(false)
  const [remotePreview, setRemotePreview] = useState<{
    recordId: string
    record: HistorySyncRemoteRecord | null
  } | null>(null)
  const [previewBusy, setPreviewBusy] = useState(false)
  const [previewError, setPreviewError] = useState(false)
  const [localPreview, setLocalPreview] = useState<HistorySyncLocalRecord | null>(null)
  const [localPreviewBusy, setLocalPreviewBusy] = useState(false)
  const [localPreviewError, setLocalPreviewError] = useState(false)
  const [localChoice, setLocalChoice] = useState<{
    summary: HistorySyncConflictSummary
    view: HistorySyncConsentView
    remoteRevision: number
    remote?: boolean
  } | null>(null)
  const [resolutionBusy, setResolutionBusy] = useState(false)
  const [resolutionError, setResolutionError] = useState(false)
  const [resolutionQueued, setResolutionQueued] = useState(false)
  const [remoteApplied, setRemoteApplied] = useState(false)
  const [reviewUnknown, setReviewUnknown] = useState(false)
  const [acceptRisk, setAcceptRisk] = useState(false)
  const [ackBusy, setAckBusy] = useState(false)
  const [ackError, setAckError] = useState(false)
  const [reload, setReload] = useState(0)
  const mounted = useRef(false)
  const request = useRef(0)
  const inFlight = useRef<symbol | null>(null)
  const currentView = useRef(view)
  currentView.current = view
  const previewRevision = useRef(0)
  const localPreviewRevision = useRef(0)
  const resolutionRunning = useRef(false)

  const confirmLocalChoice = async () => {
    if (
      !localChoice ||
      resolutionError ||
      resolutionRunning.current ||
      currentView.current !== localChoice.view ||
      useAuthStore.getState().user !== owner
    )
      return
    const choice = localChoice
    resolutionRunning.current = true
    setResolutionBusy(true)
    setResolutionError(false)
    setAutomaticHistorySync(owner.id, false)
    try {
      await (choice.remote ? keepRemoteHistorySyncConflict : keepLocalHistorySyncConflict)(
        choice.view,
        choice.summary,
        choice.remoteRevision,
      )
      if (
        mounted.current &&
        currentView.current === choice.view &&
        useAuthStore.getState().user === owner
      ) {
        setLocalChoice(null)
        setResolutionQueued(!choice.remote)
        setRemoteApplied(Boolean(choice.remote))
        setConflictRefresh((value) => value + 1)
      }
    } catch {
      if (
        mounted.current &&
        currentView.current === choice.view &&
        useAuthStore.getState().user === owner
      ) {
        setLocalChoice(null)
        setResolutionError(true)
        setConflictRefresh((value) => value + 1)
      }
    } finally {
      resolutionRunning.current = false
      if (mounted.current && useAuthStore.getState().user === owner) setResolutionBusy(false)
    }
  }

  const previewLocal = async (summary: HistorySyncConflictSummary) => {
    if (!view?.active || localPreviewBusy || useAuthStore.getState().user !== owner) return
    setLocalChoice(null)
    const revision = ++localPreviewRevision.current
    const previewView = view
    setLocalPreview(null)
    setLocalPreviewBusy(true)
    setLocalPreviewError(false)
    const isCurrent = () =>
      mounted.current &&
      localPreviewRevision.current === revision &&
      currentView.current === previewView &&
      useAuthStore.getState().user === owner
    try {
      const record = await previewHistorySyncLocalRecord(view, summary)
      if (isCurrent()) setLocalPreview(record)
    } catch {
      if (isCurrent()) setLocalPreviewError(true)
    } finally {
      if (isCurrent()) setLocalPreviewBusy(false)
    }
  }

  const previewRemote = async (summary: HistorySyncConflictSummary) => {
    if (!view?.active || previewBusy || useAuthStore.getState().user !== owner) return
    setLocalChoice(null)
    const revision = ++previewRevision.current
    const previewView = view
    setPreviewBusy(true)
    setPreviewError(false)
    setRemotePreview(null)
    const isCurrent = () =>
      mounted.current &&
      previewRevision.current === revision &&
      currentView.current === previewView &&
      useAuthStore.getState().user === owner
    try {
      const record = await previewHistorySyncRemoteRecord(view, summary)
      if (isCurrent()) setRemotePreview({ recordId: summary.recordId, record })
    } catch {
      if (isCurrent()) setPreviewError(true)
    } finally {
      if (isCurrent()) setPreviewBusy(false)
    }
  }

  useEffect(() => {
    setResolutionQueued(false)
    setRemoteApplied(false)
    setResolutionError(false)
    setLocalChoice(null)
    setConflictList(null)
    setConflictListBusy(false)
    setConflictListError(false)
    setConflicts(null)
    setSyncBusy(false)
    setSyncError(false)
    setSyncResult(null)
    setSyncUnknown(historySyncOnceBlocked())
    setReadingResult(false)
    setResultReadError(false)
    setLastAttempt(null)
    setReviewUnknown(false)
    setAcceptRisk(false)
    setAckBusy(false)
    setAckError(false)
  }, [owner])

  const reviewConflicts = async (append = false) => {
    if (!view?.active || conflictListBusy || useAuthStore.getState().user !== owner) return
    if (!append) {
      setLocalChoice(null)
      previewRevision.current += 1
      localPreviewRevision.current += 1
      setRemotePreview(null)
      setLocalPreview(null)
      setPreviewBusy(false)
      setLocalPreviewBusy(false)
    }
    const currentRequest = request.current
    const reviewView = view
    setConflictListBusy(true)
    setConflictListError(false)
    try {
      const cursor =
        append && conflictList?.length ? conflictList[conflictList.length - 1].recordId : undefined
      const summaries = await listHistorySyncConflicts(view, cursor)
      if (
        mounted.current &&
        request.current === currentRequest &&
        currentView.current === reviewView &&
        useAuthStore.getState().user === owner
      ) {
        setConflictList((previous) => (append ? [...(previous ?? []), ...summaries] : summaries))
        setMoreConflicts(summaries.length === 200)
      }
    } catch {
      if (
        mounted.current &&
        request.current === currentRequest &&
        currentView.current === reviewView
      )
        setConflictListError(true)
    } finally {
      if (mounted.current && request.current === currentRequest) setConflictListBusy(false)
    }
  }

  useEffect(() => {
    let active = true
    setLocalChoice(null)
    previewRevision.current += 1
    localPreviewRevision.current += 1
    setLocalPreview(null)
    setLocalPreviewBusy(false)
    setLocalPreviewError(false)
    setRemotePreview(null)
    setPreviewBusy(false)
    setPreviewError(false)
    setConflictList(null)
    setMoreConflicts(false)
    setConflictListBusy(false)
    setConflictListError(false)
    setConflicts(null)
    if (!view?.active) return
    void getHistorySyncConflictCount(view)
      .then((count) => {
        if (!active || useAuthStore.getState().user !== owner) return
        setConflicts(count)
        if (count > 0) setAutomaticHistorySync(owner.id, false)
      })
      .catch(() => {
        if (active && useAuthStore.getState().user === owner) setConflicts(null)
      })
    return () => {
      active = false
    }
  }, [owner, view, syncError, syncResult, conflictRefresh])

  useEffect(() => {
    if (!view?.active || useAuthStore.getState().user !== owner) return
    return subscribeHistorySyncOnceResult(view, (result) => {
      if (useAuthStore.getState().user !== owner) return
      setSyncResult(result)
      setSyncUnknown(false)
      setSyncError(false)
      setSyncBusy(false)
      setLastAttempt(null)
      setReviewUnknown(false)
    })
  }, [owner, view])

  useEffect(() => {
    mounted.current = true
    const currentRequest = ++request.current
    setView(null)
    if (reload === 0) {
      setError(false)
      setChecked(false)
    }
    inFlight.current = null
    setBusy(reload > 0)
    void getHistorySyncConsentView()
      .then((result) => {
        if (
          mounted.current &&
          request.current === currentRequest &&
          useAuthStore.getState().user === owner
        ) {
          setView(result)
          setChecked(true)
          setError(false)
        }
      })
      .catch(() => {
        if (
          mounted.current &&
          request.current === currentRequest &&
          useAuthStore.getState().user === owner
        ) {
          setError(true)
          setChecked(true)
        }
      })
      .finally(() => {
        if (mounted.current && request.current === currentRequest) setBusy(false)
      })
    return () => {
      mounted.current = false
      request.current += 1
    }
  }, [owner, reload])

  const disable = async () => {
    if (!view?.active || inFlight.current || useAuthStore.getState().user !== owner) return
    setAutomaticHistorySync(owner.id, false)
    const operation = Symbol()
    inFlight.current = operation
    setBusy(true)
    setError(false)
    const currentRequest = request.current
    let revoked = false
    try {
      await revokeHistorySyncConsentView(view)
      revoked = true
      if (
        mounted.current &&
        request.current === currentRequest &&
        useAuthStore.getState().user === owner
      ) {
        setView(null)
        const refreshed = await getHistorySyncConsentView()
        if (
          mounted.current &&
          request.current === currentRequest &&
          useAuthStore.getState().user === owner
        ) {
          setView(refreshed)
        }
      }
    } catch {
      if (
        mounted.current &&
        request.current === currentRequest &&
        useAuthStore.getState().user === owner
      ) {
        setError(true)
        if (!revoked) {
          setView(null)
          try {
            const refreshed = await getHistorySyncConsentView()
            if (
              mounted.current &&
              request.current === currentRequest &&
              useAuthStore.getState().user === owner
            ) {
              setView(refreshed)
            }
          } catch {
            if (
              mounted.current &&
              request.current === currentRequest &&
              useAuthStore.getState().user === owner
            ) {
              setView(null)
            }
          }
        }
      }
    } finally {
      if (inFlight.current === operation) {
        inFlight.current = null
        if (mounted.current && request.current === currentRequest) setBusy(false)
      }
    }
  }

  const syncNow = async () => {
    if (
      !view?.active ||
      resolutionError ||
      resolutionBusy ||
      resolutionRunning.current ||
      conflicts !== 0 ||
      syncBusy ||
      syncUnknown ||
      busy ||
      useAuthStore.getState().user !== owner
    )
      return
    setSyncBusy(true)
    setSyncError(false)
    setSyncResult(null)
    const currentRequest = request.current
    try {
      const result = await syncHistoryOnce(view)
      if (
        mounted.current &&
        request.current === currentRequest &&
        useAuthStore.getState().user === owner
      ) {
        setSyncResult(result)
      }
    } catch (failure) {
      if (
        mounted.current &&
        request.current === currentRequest &&
        useAuthStore.getState().user === owner
      ) {
        setSyncError(true)
        setSyncUnknown(failure instanceof HistorySyncResultUnknownError || historySyncOnceBlocked())
      }
    } finally {
      if (mounted.current && request.current === currentRequest) setSyncBusy(false)
    }
  }

  const checkLastResult = async () => {
    if (
      !view?.active ||
      readingResult ||
      ackBusy ||
      reviewUnknown ||
      useAuthStore.getState().user !== owner
    )
      return
    const currentRequest = request.current
    setReadingResult(true)
    setResultReadError(false)
    try {
      const attempt = await getHistorySyncAttempt(view)
      if (
        !mounted.current ||
        request.current !== currentRequest ||
        useAuthStore.getState().user !== owner
      )
        return
      const conflictCount = await getHistorySyncConflictCount(view)
      if (
        !mounted.current ||
        request.current !== currentRequest ||
        useAuthStore.getState().user !== owner
      )
        return
      setConflicts(conflictCount)
      setResolutionError(false)
      setLastAttempt(attempt)
      if (attempt?.status === 'confirmed' && attempt.result) {
        setSyncResult(attempt.result)
        setSyncError(false)
        setSyncUnknown(historySyncOnceBlocked())
      } else if (attempt) {
        setSyncUnknown(attempt.acknowledged !== true || historySyncOnceBlocked())
        setSyncResult(null)
      }
    } catch {
      if (
        mounted.current &&
        request.current === currentRequest &&
        useAuthStore.getState().user === owner
      )
        setResultReadError(true)
    } finally {
      if (mounted.current && request.current === currentRequest) setReadingResult(false)
    }
  }

  const acknowledgeUnknown = async () => {
    if (
      !acceptRisk ||
      ackBusy ||
      readingResult ||
      syncBusy ||
      busy ||
      !view?.active ||
      !lastAttempt ||
      lastAttempt.acknowledged === true ||
      !['running', 'unknown'].includes(lastAttempt.status) ||
      useAuthStore.getState().user !== owner
    )
      return
    const currentRequest = request.current
    setAckBusy(true)
    setAckError(false)
    try {
      await acknowledgeHistorySyncAttempt(view, lastAttempt)
      if (
        !mounted.current ||
        currentRequest !== request.current ||
        useAuthStore.getState().user !== owner
      )
        return
      setReviewUnknown(false)
      setAcceptRisk(false)
      setLastAttempt(null)
      setSyncUnknown(historySyncOnceBlocked())
      setSyncError(false)
    } catch {
      if (
        mounted.current &&
        currentRequest === request.current &&
        useAuthStore.getState().user === owner
      )
        setAckError(true)
    } finally {
      if (mounted.current && currentRequest === request.current) setAckBusy(false)
    }
  }

  if (useAuthStore.getState().user !== owner) return null
  if (checked && !view?.active && !error && !busy) {
    return (
      <section className="account-section py-4">
        <HistorySyncAuthorizationFlow
          owner={owner}
          onAuthorizationChanged={() => {
            if (useAuthStore.getState().user !== owner) return
            setBusy(true)
            setReload((value) => value + 1)
          }}
        />
      </section>
    )
  }
  if (!checked && !view?.active && !error) return null
  return (
    <section className="account-section py-4">
      {localChoice && currentView.current === localChoice.view && (
        <Modal
          title={t(
            localChoice.remote
              ? 'historySyncAuthorization.keepRemote'
              : 'historySyncAuthorization.keepLocal',
          )}
          role="alertdialog"
          closeOnBackdrop={false}
          onClose={() => {
            if (!resolutionBusy) setLocalChoice(null)
          }}
        >
          <p className="text-[13px] leading-5 text-text-secondary">
            {t(
              localChoice.remote
                ? 'historySyncAuthorization.confirmRemoteDescription'
                : 'historySyncAuthorization.confirmLocalDescription',
            )}
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              data-autofocus="true"
              disabled={resolutionBusy}
              onClick={() => setLocalChoice(null)}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              disabled={resolutionBusy}
              onClick={() => void confirmLocalChoice()}
            >
              {t(
                localChoice.remote
                  ? 'historySyncAuthorization.confirmRemote'
                  : 'historySyncAuthorization.confirmLocal',
              )}
            </button>
          </div>
        </Modal>
      )}
      {resolutionQueued && (
        <p role="status" className="text-[12px] text-text-secondary">
          {t('historySyncAuthorization.resolutionQueued')}
        </p>
      )}
      {remoteApplied && (
        <p role="status" className="text-[12px] text-text-secondary">
          {t('historySyncAuthorization.remoteApplied')}
        </p>
      )}
      {resolutionError && (
        <p role="alert" className="text-[12px] text-error">
          {t('historySyncAuthorization.resolutionUnknown')}
        </p>
      )}
      {reviewUnknown && view?.active && lastAttempt && (
        <Modal
          title={t('historySyncAuthorization.reviewUnknown')}
          onClose={() => {
            if (!ackBusy) setReviewUnknown(false)
          }}
          dismissible={!ackBusy}
          closeOnBackdrop={false}
        >
          <p className="text-[13px] leading-5 text-text-secondary">
            {t('historySyncAuthorization.ackWarning')}
          </p>
          <label className="mt-3 flex gap-2 text-[13px] text-text-secondary">
            <input
              type="checkbox"
              checked={acceptRisk}
              disabled={ackBusy}
              onChange={(event) => setAcceptRisk(event.target.checked)}
            />
            {t('historySyncAuthorization.ackAccept')}
          </label>
          {ackError && <p role="alert">{t('historySyncAuthorization.readError')}</p>}
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              data-autofocus
              disabled={ackBusy}
              onClick={() => setReviewUnknown(false)}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              disabled={!acceptRisk || ackBusy}
              onClick={() => void acknowledgeUnknown()}
            >
              {t(ackBusy ? 'common.loading' : 'historySyncAuthorization.acknowledge')}
            </button>
          </div>
        </Modal>
      )}
      <div className="flex items-center justify-between gap-3">
        <span className="text-[13px] font-medium text-text-primary">
          {t('historySyncAuthorization.title')}
        </span>
        <div className="flex items-center gap-2">
          {view?.active && (
            <button
              type="button"
              disabled={
                resolutionError ||
                resolutionBusy ||
                conflicts !== 0 ||
                busy ||
                syncBusy ||
                syncUnknown
              }
              onClick={() => void syncNow()}
              className="rounded-[6px] border border-border px-2.5 py-1 text-[12px] text-text-secondary disabled:opacity-50"
            >
              {t(syncBusy ? 'common.loading' : 'historySyncAuthorization.syncNow')}
            </button>
          )}
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              if (busy) return
              if (view?.active) {
                void disable()
              } else {
                setBusy(true)
                setReload((value) => value + 1)
              }
            }}
            className="rounded-[6px] border border-border px-2.5 py-1 text-[12px] text-text-secondary disabled:opacity-50"
          >
            {t(
              busy
                ? 'common.loading'
                : view?.active
                  ? 'historySyncAuthorization.disable'
                  : 'common.retry',
            )}
          </button>
        </div>
      </div>
      {view?.active && (
        <label className="mt-2 flex items-start gap-2 text-[12px] leading-5 text-text-secondary">
          <input
            type="checkbox"
            checked={automatic}
            disabled={
              !automatic &&
              (resolutionError ||
                resolutionBusy ||
                conflicts !== 0 ||
                busy ||
                syncBusy ||
                syncUnknown)
            }
            onChange={(event) => setAutomaticHistorySync(owner.id, event.target.checked)}
          />
          {t('historySyncAuthorization.automatic')}
        </label>
      )}
      {view?.active && (
        <p className="mt-2 text-[12px] leading-5 text-text-tertiary">
          {t('historySyncAuthorization.warning')}
        </p>
      )}
      {view?.active && conflicts !== null && conflicts > 0 && (
        <div className="mt-2 space-y-2">
          <p role="alert" className="text-[12px] leading-5 text-error">
            {t('historySyncAuthorization.conflictWarning', { count: conflicts })}
          </p>
          <button
            type="button"
            disabled={conflictListBusy}
            onClick={() => void reviewConflicts()}
            className="rounded-[6px] border border-border px-2.5 py-1 text-[12px] text-text-secondary disabled:opacity-50"
          >
            {t(conflictListBusy ? 'common.loading' : 'historySyncAuthorization.reviewConflicts')}
          </button>
          {conflictListError && (
            <p role="alert" className="text-[12px] text-error">
              {t('historySyncAuthorization.readError')}
            </p>
          )}
          {conflictList?.map((summary) => (
            <div key={summary.recordId} className="rounded-[6px] border border-border p-2">
              <code className="break-all text-[11px]">{summary.recordId}</code>
              <p className="text-[12px] text-text-secondary">
                {t('historySyncAuthorization.conflictRevisions', {
                  local: summary.clientRevision,
                  remote: summary.serverRevision,
                })}
              </p>
              <button
                type="button"
                disabled={previewBusy}
                onClick={() => void previewRemote(summary)}
                className="mt-1 rounded-[6px] border border-border px-2 py-1 text-[12px]"
              >
                {t(previewBusy ? 'common.loading' : 'historySyncAuthorization.previewRemote')}
              </button>
              <button
                type="button"
                disabled={localPreviewBusy}
                onClick={() => void previewLocal(summary)}
                className="ml-2 mt-1 rounded-[6px] border border-border px-2 py-1 text-[12px]"
              >
                {t(localPreviewBusy ? 'common.loading' : 'historySyncAuthorization.previewLocal')}
              </button>
              {localPreview?.recordId === summary.recordId && (
                <div className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words text-[12px]">
                  <p className="font-medium">{t('historySyncAuthorization.previewLocal')}</p>
                  {localPreview.deleted
                    ? t('historySyncAuthorization.localDeleted')
                    : localPreview.payload.answerText ||
                      localPreview.payload.polishedText ||
                      localPreview.payload.rawText}
                </div>
              )}
              {remotePreview?.recordId === summary.recordId && (
                <div className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words text-[12px]">
                  <p className="font-medium">{t('historySyncAuthorization.previewRemote')}</p>
                  {remotePreview.record === null
                    ? t('historySyncAuthorization.remoteMissing')
                    : remotePreview.record.deleted
                      ? t('historySyncAuthorization.remoteDeleted')
                      : remotePreview.record.payload.answerText ||
                        remotePreview.record.payload.polishedText ||
                        remotePreview.record.payload.rawText}
                </div>
              )}
              {view?.active &&
                summary.result === 'conflict' &&
                localPreview?.recordId === summary.recordId &&
                localPreview.clientRevision === summary.clientRevision &&
                remotePreview?.recordId === summary.recordId &&
                (localPreview.deleted || remotePreview.record?.deleted !== true) && (
                  <button
                    type="button"
                    disabled={resolutionError || resolutionBusy || previewBusy || localPreviewBusy}
                    onClick={() => {
                      setResolutionQueued(false)
                      setResolutionError(false)
                      setLocalChoice({
                        summary,
                        view,
                        remoteRevision: remotePreview.record?.serverRevision ?? 0,
                      })
                    }}
                    className="mt-2 rounded-[6px] border border-border px-2 py-1 text-[12px]"
                  >
                    {t('historySyncAuthorization.keepLocal')}
                  </button>
                )}
              {summary.result === 'conflict' &&
                localPreview?.recordId === summary.recordId &&
                remotePreview?.recordId === summary.recordId &&
                remotePreview.record !== null && (
                  <button
                    type="button"
                    disabled={resolutionBusy || previewBusy || localPreviewBusy}
                    className="rounded-[6px] border border-border px-2.5 py-1 text-[12px]"
                    onClick={() => {
                      if (!view || !remotePreview.record) return
                      setLocalChoice({
                        summary,
                        view,
                        remoteRevision: remotePreview.record.serverRevision,
                        remote: true,
                      })
                    }}
                  >
                    {t('historySyncAuthorization.keepRemote')}
                  </button>
                )}
            </div>
          ))}
          {moreConflicts && (
            <button
              type="button"
              disabled={conflictListBusy}
              onClick={() => void reviewConflicts(true)}
              className="rounded-[6px] border border-border px-2.5 py-1 text-[12px]"
            >
              {t('history.loadMore', 'Load more')}
            </button>
          )}
          {previewError && (
            <p role="alert" className="text-[12px] text-error">
              {t('historySyncAuthorization.readError')}
            </p>
          )}
          {localPreviewError && (
            <p role="alert" className="text-[12px] text-error">
              {t('historySyncAuthorization.readError')}
            </p>
          )}
        </div>
      )}
      {view?.active && (
        <button
          type="button"
          disabled={readingResult || ackBusy || reviewUnknown}
          onClick={() => void checkLastResult()}
          className="mt-2 rounded-[6px] border border-border px-2.5 py-1 text-[12px] text-text-secondary disabled:opacity-50"
        >
          {t(readingResult ? 'common.loading' : 'historySyncAuthorization.checkLastResult')}
        </button>
      )}
      {resultReadError && (
        <p role="alert" className="mt-2 text-[12px] text-error">
          {t('historySyncAuthorization.readError')}
        </p>
      )}
      {view?.active &&
        syncUnknown &&
        lastAttempt &&
        lastAttempt.acknowledged !== true &&
        ['running', 'unknown'].includes(lastAttempt.status) && (
          <button
            type="button"
            disabled={readingResult || ackBusy || syncBusy}
            onClick={() => {
              setAcceptRisk(false)
              setAckError(false)
              setReviewUnknown(true)
            }}
          >
            {t('historySyncAuthorization.reviewUnknown')}
          </button>
        )}
      {error && (
        <p role="alert" className="mt-2 text-[12px] text-error">
          {t('historySyncAuthorization.error')}
        </p>
      )}
      {syncResult && (
        <p role="status" className="mt-2 text-[12px] text-text-tertiary">
          {t('historySyncAuthorization.syncResult', {
            pushed: syncResult.pushed,
            pulled: syncResult.pulled,
            pending: syncResult.pending,
          })}
          {syncResult.hasMore ? ` ${t('historySyncAuthorization.morePending')}` : ''}
        </p>
      )}
      {(syncError || syncUnknown) && (
        <p role="alert" className="mt-2 text-[12px] text-error">
          {t(
            syncUnknown
              ? 'historySyncAuthorization.syncUnknown'
              : 'historySyncAuthorization.syncError',
          )}
        </p>
      )}
    </section>
  )
}
