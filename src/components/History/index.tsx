import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Check,
  CircleArrowDown,
  Copy,
  Flag,
  HardDrive,
  Lock,
  MoreHorizontal,
  Replace,
  RotateCw,
  Search,
  Sparkles,
  Trash2,
} from 'lucide-react'
import { useAppStore, type HistoryEntry } from '../../stores/appStore'
import {
  addCorrectionRule,
  clearHistory,
  deleteHistoryEntry,
  exportHistoryAudio,
  retryHistoryTranscription,
  getCorrectionRules,
  pruneHistoryRetention,
  showSavedAskAnswer,
} from '../../lib/tauri'
import { toast } from '../toast-service'
import { ConfirmDeleteDialog } from '../shared/ConfirmDeleteDialog'
import { Modal } from '../shared/Modal'
import { Tooltip } from '../shared/Tooltip'
import { PageContainer, PageHeader } from '../shared/Page'
import { Select } from '../shared/Select'
import { AppContextMeta } from './AppContextMeta'
import { CreateCorrectionDialog } from './CreateCorrectionDialog'
import { HistoryItemText } from './HistoryItemText'
import { historyItemState, historyTranscript } from './historyItemState'
import { HistoryFeedbackDialog } from './HistoryFeedbackDialog'
import { HistoryRetryDialog } from './HistoryRetryDialog'
import { enqueueConfigSave, useAutoSaveConfig } from '../Settings/shared/useAutoSaveConfig'

type HistoryTab = 'all' | 'dictate' | 'ask'

// Typeless "Keep history" choices (REA: `yf`), expressed in days for our retention policy.
const RETENTION_OPTIONS = [
  { value: 'forever', days: 0, enabled: true },
  { value: 'year', days: 365, enabled: true },
  { value: 'month', days: 30, enabled: true },
  { value: 'week', days: 7, enabled: true },
  { value: 'day', days: 1, enabled: true },
  { value: 'never', days: 0, enabled: false },
] as const

function entryMode(entry: HistoryEntry) {
  return entry.mode ?? 'dictate'
}

function localDateKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function History() {
  useAutoSaveConfig()
  const [retryEntry, setRetryEntry] = useState<HistoryEntry | null>(null)
  const [feedbackEntry, setFeedbackEntry] = useState<HistoryEntry | null>(null)
  const history = useAppStore((s) => s.history)
  const setHistory = useAppStore((s) => s.setHistory)
  const setCorrectionRules = useAppStore((s) => s.setCorrectionRules)
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const { t, i18n } = useTranslation()
  const [tab, setTab] = useState<HistoryTab>('all')
  const [search, setSearch] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [pageMenuOpen, setPageMenuOpen] = useState(false)
  const [copiedId, setCopiedId] = useState<number | null>(null)
  const [menuEntryId, setMenuEntryId] = useState<number | null>(null)
  const [correctionEntry, setCorrectionEntry] = useState<HistoryEntry | null>(null)
  const [confirmingClear, setConfirmingClear] = useState(false)
  const [pendingRetention, setPendingRetention] = useState<string | null>(null)
  const [retentionBusy, setRetentionBusy] = useState(false)
  const retentionInFlight = useRef(false)
  const mounted = useRef(true)
  const answerOpenRequest = useRef(0)
  const [confirmingDelete, setConfirmingDelete] = useState<HistoryEntry | null>(null)
  const menuTriggerEntryId = useRef<number | null>(null)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const closeEntryMenu = useCallback(() => {
    setMenuEntryId(null)
    const entryId = menuTriggerEntryId.current
    window.setTimeout(() => {
      if (entryId === null) return
      document.querySelector<HTMLButtonElement>(`[data-history-menu-trigger="${entryId}"]`)?.focus()
    }, 0)
  }, [])

  useEffect(() => {
    if (menuEntryId === null) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      closeEntryMenu()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [closeEntryMenu, menuEntryId])

  const retentionValue = !config.history_enabled
    ? 'never'
    : (RETENTION_OPTIONS.find(
        (option) => option.enabled && option.days === config.history_retention_days,
      )?.value ?? 'forever')

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase()
    return history.filter((entry) => {
      const mode = entryMode(entry)
      if (tab === 'ask' && mode !== 'ask') return false
      if (tab === 'dictate' && mode === 'ask') return false
      if (!query) return true
      return (
        entry.polished_text.toLowerCase().includes(query) ||
        entry.raw_text.toLowerCase().includes(query) ||
        entry.context_label.toLowerCase().includes(query) ||
        (entry.answer_text ?? '').toLowerCase().includes(query)
      )
    })
  }, [history, search, tab])

  const handleCopy = (id: number, text: string) => {
    navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopiedId(id)
        setTimeout(() => setCopiedId(null), 1500)
      })
      .catch(() => {
        toast.error(t('history.failedToCopy'))
      })
  }

  const handleClear = async () => {
    try {
      await clearHistory()
      setHistory([])
      setConfirmingClear(false)
    } catch (e) {
      console.error('Failed to clear history:', e)
      toast.error(t('history.failedToClear'))
    }
  }

  const handleDelete = async (entry: HistoryEntry) => {
    try {
      const deleted = await deleteHistoryEntry(entry.id)
      setHistory(useAppStore.getState().history.filter((item) => item.id !== entry.id))
      setConfirmingDelete(null)
      if (deleted) toast.success(t('history.deleted'))
    } catch (error) {
      console.error('Failed to delete history entry:', error)
      toast.error(t('history.failedToDelete'))
    }
  }

  const handleViewAnswer = (entry: HistoryEntry) => {
    if (!entry.answer_text?.trim()) return
    const request = ++answerOpenRequest.current
    showSavedAskAnswer(historyTranscript(entry), entry.answer_text).catch(() => {
      if (!mounted.current || request !== answerOpenRequest.current) return
      console.error('Failed to open saved answer')
      toast.error(t('history.failedToOpenAnswer'))
    })
  }

  const handleRetentionChange = (value: string) => {
    if (retentionInFlight.current) return
    const option = RETENTION_OPTIONS.find((item) => item.value === value)
    if (!option) return
    const shortened =
      config.history_enabled &&
      (!option.enabled ||
        (option.days > 0 &&
          (config.history_retention_days === 0 || option.days < config.history_retention_days)))
    if (shortened) {
      setPendingRetention(value)
      return
    }
    updateConfig({ history_enabled: option.enabled, history_retention_days: option.days })
  }

  const confirmRetention = async () => {
    if (retentionInFlight.current) return
    const option = RETENTION_OPTIONS.find((item) => item.value === pendingRetention)
    if (!option) return
    retentionInFlight.current = true
    setRetentionBusy(true)
    try {
      updateConfig({
        history_enabled: option.enabled,
        history_retention_days: option.days,
      })
      if (!(await enqueueConfigSave())) return
      if (!mounted.current) return
      const { config: current, savedConfig: latest } = useAppStore.getState()
      if (
        latest?.history_enabled !== option.enabled ||
        latest.history_retention_days !== option.days ||
        current.history_enabled !== option.enabled ||
        current.history_retention_days !== option.days
      ) {
        return
      }
      const historyBeforeCleanup = useAppStore.getState().history
      const refreshed = await pruneHistoryRetention(option.enabled, option.days)
      if (!mounted.current) return
      if (useAppStore.getState().history === historyBeforeCleanup) setHistory(refreshed)
      setPendingRetention(null)
    } catch {
      if (mounted.current) toast.error(t('history.failedToClear'))
    } finally {
      retentionInFlight.current = false
      if (mounted.current) setRetentionBusy(false)
    }
  }

  const handleCreateCorrection = async (pattern: string, replacement: string) => {
    try {
      await addCorrectionRule(pattern, replacement)
      setCorrectionRules(await getCorrectionRules())
      setCorrectionEntry(null)
      toast.success(t('history.correctionCreated'))
    } catch (error) {
      console.error('Failed to create correction from history:', error)
      toast.error(t('history.failedToCreateCorrection'))
      throw error
    }
  }

  const outputStatusLabel = (status: string | null) => {
    switch (status) {
      case 'partial':
        return t('history.outputStatus.partial')
      case 'fallback':
        return t('history.outputStatus.fallback')
      case 'clipboard_fallback':
        return t('history.outputStatus.clipboardFallback')
      default:
        return null
    }
  }

  const grouped = useMemo(() => {
    const today = localDateKey(new Date())
    const yesterday = localDateKey(new Date(Date.now() - 86_400_000))
    const dateFormat = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' })
    const map = new Map<string, HistoryEntry[]>()
    for (const entry of filtered) {
      const date = entry.created_at.slice(0, 10)
      const label =
        date === today
          ? t('history.today')
          : date === yesterday
            ? t('history.yesterday')
            : dateFormat.format(new Date(`${date}T00:00:00`))
      if (!map.has(label)) map.set(label, [])
      map.get(label)!.push(entry)
    }
    return map
  }, [filtered, i18n.language, t])

  const timeFormat = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { hour: '2-digit', minute: '2-digit' }),
    [i18n.language],
  )

  const tabs: { id: HistoryTab; label: string }[] = [
    { id: 'all', label: t('history.tabs.all') },
    { id: 'dictate', label: t('history.tabs.dictate') },
    { id: 'ask', label: t('history.tabs.ask') },
  ]

  return (
    <PageContainer>
      <PageHeader
        title={t('history.title')}
        actions={
          <div className="relative flex items-center gap-1">
            <button
              type="button"
              aria-label={t('history.search')}
              onClick={() => setSearchOpen((open) => !open)}
              className="btn-ghost grid h-9 w-9 cursor-pointer place-items-center"
            >
              <Search size={17} />
            </button>
            <button
              type="button"
              aria-label={t('history.moreActions')}
              aria-haspopup="menu"
              aria-expanded={pageMenuOpen}
              onClick={() => setPageMenuOpen((open) => !open)}
              className="btn-ghost grid h-9 w-9 cursor-pointer place-items-center"
            >
              <MoreHorizontal size={18} />
            </button>
            {pageMenuOpen && (
              <>
                <div className="fixed inset-0 z-30" onClick={() => setPageMenuOpen(false)} />
                <div
                  role="menu"
                  className="surface-popover absolute right-0 top-10 z-40 min-w-44 py-1"
                >
                  <button
                    type="button"
                    role="menuitem"
                    disabled={history.length === 0}
                    onClick={() => {
                      setPageMenuOpen(false)
                      setConfirmingClear(true)
                    }}
                    className="flex h-9 w-full items-center gap-2 border-none bg-transparent px-3 text-left text-[13px] text-error cursor-pointer hover:bg-fill-hover disabled:opacity-40"
                  >
                    <Trash2 size={14} />
                    {t('history.clearAll')}
                  </button>
                </div>
              </>
            )}
          </div>
        }
      />

      <section className="surface-muted mb-6 divide-y divide-border-thin px-5">
        <div className="flex items-center justify-between gap-6 py-4">
          <div>
            <div className="flex items-center gap-2 text-[14px] font-medium text-text-primary">
              <HardDrive size={15} />
              {t('history.keepHistory')}
            </div>
            <p className="mt-1 text-[14px] text-text-tertiary">{t('history.keepHistoryDesc')}</p>
          </div>
          <Select
            aria-label={t('history.keepHistory')}
            value={retentionValue}
            onChange={handleRetentionChange}
            className="w-[200px] shrink-0"
            options={RETENTION_OPTIONS.map((option) => ({
              value: option.value,
              label: t(`history.retention.${option.value}`),
            }))}
          />
        </div>
        <div className="py-4">
          <div className="flex items-center gap-2 text-[14px] font-medium text-text-primary">
            <Lock size={15} />
            {t('history.privacyTitle')}
          </div>
          <p className="mt-1 text-[14px] text-text-tertiary">{t('history.privacyDesc')}</p>
        </div>
      </section>

      {confirmingClear && (
        <div className="mb-4 rounded-[12px] border border-error/20 bg-error/10 px-4 py-3">
          <p className="text-[13px] leading-relaxed text-text-secondary">
            {t('history.clearConfirm')}
          </p>
          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setConfirmingClear(false)}
              className="btn-outline cursor-pointer px-3 py-1 text-[12px]"
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              onClick={handleClear}
              className="cursor-pointer rounded-full border border-error/30 bg-error/15 px-3 py-1 text-[12px] font-medium text-error hover:bg-error/20"
            >
              {t('history.confirmClear')}
            </button>
          </div>
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div role="tablist" className="inline-flex rounded-full bg-fill-tough p-1">
          {tabs.map((item) => (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={tab === item.id}
              onClick={() => setTab(item.id)}
              className={`cursor-pointer rounded-full border-none px-4 py-1.5 text-[14px] transition-colors ${
                tab === item.id
                  ? 'bg-bg-elevated text-text-primary shadow-sm'
                  : 'bg-transparent text-text-secondary hover:text-text-primary'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
        {searchOpen && (
          <input
            autoFocus
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('history.searchPlaceholder')}
            className="h-9 w-64 rounded-full border border-border bg-bg-elevated px-4 text-[13px] text-text-primary outline-none focus:border-accent"
          />
        )}
      </div>

      {filtered.length === 0 ? (
        <div className="py-16 text-center">
          <p className="text-[20px] font-semibold text-text-primary">
            {search ? t('history.noResults') : t('history.noHistory')}
          </p>
          {!search && (
            <p className="mx-auto mt-2 max-w-[360px] text-[14px] text-text-tertiary">
              {config.history_enabled ? t('history.noHistoryHint') : t('history.neverKeepHint')}
            </p>
          )}
        </div>
      ) : (
        Array.from(grouped.entries()).map(([label, entries]) => (
          <section key={label} className="mb-6">
            <h3 className="mb-2 text-[13px] text-text-tertiary">{label}</h3>
            <div className="overflow-hidden rounded-[16px] border border-border-thin">
              {entries.map((entry, index) => {
                const isAsk = entryMode(entry) === 'ask'
                const state = historyItemState(entry)
                const hasTranscript = state === 'transcript'
                const menuItem =
                  'flex h-8 w-full cursor-pointer items-center gap-2 rounded-[6px] border-none bg-transparent px-2 text-left text-[14px] hover:bg-fill-hover disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent'
                const revealOnHover =
                  'opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 focus-visible:opacity-100'
                return (
                  <div
                    key={entry.id}
                    data-history-state={state}
                    className={`group relative flex items-start gap-4 px-5 py-4 transition-colors hover:bg-fill-thin ${
                      index > 0 ? 'border-t border-border-thin' : ''
                    }`}
                  >
                    <span className="w-[72px] shrink-0 pt-px text-[12px] leading-5 tabular-nums text-text-tertiary">
                      {timeFormat.format(new Date(entry.created_at))}
                    </span>
                    <div className="min-w-0 flex-1">
                      <HistoryItemText entry={entry} />
                      <AppContextMeta
                        iconKey={entry.context_icon_key}
                        family={entry.context_family}
                        label={entry.context_label}
                        time=""
                        providerKind={entry.provider_kind}
                        browserAccessStatus={entry.browser_access_status}
                      />
                      {entry.output_status && outputStatusLabel(entry.output_status) && (
                        <p className="mt-1 break-words text-[11px] leading-snug text-warning">
                          {outputStatusLabel(entry.output_status)}
                          {entry.output_error ? ` · ${entry.output_error}` : ''}
                        </p>
                      )}
                    </div>
                    <div className="relative flex shrink-0 items-center gap-1">
                      {isAsk && hasTranscript && entry.answer_text?.trim() && (
                        <button
                          type="button"
                          onClick={() => handleViewAnswer(entry)}
                          className={`${revealOnHover} flex h-8 cursor-pointer items-center gap-1.5 rounded-[6px] border-none bg-fill-tough px-3 text-[14px] text-text-primary hover:bg-fill-selected`}
                        >
                          <Sparkles size={14} />
                          {t('history.viewAnswer')}
                        </button>
                      )}
                      {!hasTranscript && (
                        <Tooltip
                          label={t('history.item.noSavedAudio')}
                          wide
                          disabled={entry.has_audio === true}
                        >
                          <button
                            type="button"
                            disabled={!entry.has_audio}
                            onClick={() => setRetryEntry(entry)}
                            className="mr-1 flex h-8 cursor-pointer items-center gap-1.5 rounded-[6px] border-none bg-fill-tough px-3 text-[14px] leading-4 text-text-primary hover:bg-fill-selected disabled:cursor-default disabled:opacity-40 disabled:hover:bg-fill-tough"
                          >
                            <RotateCw size={14} />
                            {t('history.item.retry')}
                          </button>
                        </Tooltip>
                      )}
                      {!isAsk && hasTranscript && (
                        <Tooltip label={t('history.item.copyTranscript')}>
                          <button
                            type="button"
                            onClick={() => handleCopy(entry.id, historyTranscript(entry))}
                            aria-label={t(
                              copiedId === entry.id
                                ? 'history.copied'
                                : 'history.item.copyTranscript',
                            )}
                            className={`${revealOnHover} btn-ghost grid h-8 w-8 cursor-pointer place-items-center text-text-secondary`}
                          >
                            {copiedId === entry.id ? (
                              <Check size={14} className="text-success" />
                            ) : (
                              <Copy size={14} />
                            )}
                          </button>
                        </Tooltip>
                      )}
                      <Tooltip label={t('history.feedback.title')}>
                        <button
                          type="button"
                          data-history-feedback-trigger={entry.id}
                          aria-label={t('history.feedback.title')}
                          onClick={() => setFeedbackEntry(entry)}
                          className={`${revealOnHover} btn-ghost grid h-8 w-8 cursor-pointer place-items-center text-text-secondary`}
                        >
                          <Flag size={14} />
                        </button>
                      </Tooltip>
                      <div className="relative">
                        <button
                          type="button"
                          onClick={() => {
                            menuTriggerEntryId.current = entry.id
                            setMenuEntryId((current) => (current === entry.id ? null : entry.id))
                          }}
                          data-history-menu-trigger={entry.id}
                          aria-label={t('history.moreActions')}
                          aria-haspopup="menu"
                          aria-expanded={menuEntryId === entry.id}
                          className={`${
                            hasTranscript && menuEntryId !== entry.id ? revealOnHover : ''
                          } btn-ghost grid h-8 w-8 cursor-pointer place-items-center text-text-secondary`}
                        >
                          <MoreHorizontal size={14} />
                        </button>
                        {menuEntryId === entry.id && (
                          <>
                            <div className="fixed inset-0 z-30" onClick={closeEntryMenu} />
                            <div
                              role="menu"
                              className="surface-popover absolute right-0 top-full z-40 mt-2 min-w-44 p-1"
                            >
                              {!isAsk && hasTranscript && (
                                <button
                                  type="button"
                                  role="menuitem"
                                  onClick={() => {
                                    setMenuEntryId(null)
                                    setCorrectionEntry(entry)
                                  }}
                                  className={`${menuItem} text-text-primary`}
                                >
                                  <Replace size={14} className="text-text-secondary" />
                                  {t('history.createCorrection')}
                                </button>
                              )}
                              {hasTranscript && (
                                <button
                                  type="button"
                                  role="menuitem"
                                  disabled={!entry.has_audio}
                                  onClick={() => {
                                    setMenuEntryId(null)
                                    setRetryEntry(entry)
                                  }}
                                  className={`${menuItem} text-text-primary`}
                                >
                                  <RotateCw size={14} className="text-text-secondary" />
                                  {t('history.item.retry')}
                                </button>
                              )}
                              <button
                                type="button"
                                role="menuitem"
                                disabled={!entry.has_audio}
                                aria-describedby={
                                  entry.has_audio ? undefined : `history-no-audio-${entry.id}`
                                }
                                onClick={() => {
                                  setMenuEntryId(null)
                                  void exportHistoryAudio(entry.id)
                                    .then(() => toast.success(t('history.audioExported')))
                                    .catch(() => toast.error(t('history.audioExportFailed')))
                                }}
                                className={`${menuItem} text-text-primary`}
                              >
                                <CircleArrowDown size={14} className="text-text-secondary" />
                                {t('history.item.downloadAudio')}
                              </button>
                              {!entry.has_audio && (
                                <p
                                  id={`history-no-audio-${entry.id}`}
                                  className="m-0 max-w-[240px] px-2 pb-1 text-[12px] leading-4 text-text-tertiary"
                                >
                                  {t('history.item.noSavedAudio')}
                                </p>
                              )}
                              <hr className="my-1 border-0 border-t border-border-thin" />
                              <button
                                type="button"
                                role="menuitem"
                                onClick={() => {
                                  setMenuEntryId(null)
                                  setConfirmingDelete(entry)
                                }}
                                className={`${menuItem} text-error`}
                              >
                                <Trash2 size={14} />
                                {t('history.item.deleteTranscript')}
                              </button>
                            </div>
                          </>
                        )}
                      </div>
                      {isAsk && hasTranscript && menuEntryId !== entry.id && (
                        <span
                          aria-hidden
                          className="pointer-events-none absolute right-0 top-1/2 grid h-8 w-8 -translate-y-1/2 place-items-center text-text-tertiary/50 group-focus-within:hidden group-hover:hidden"
                        >
                          <Sparkles size={14} />
                        </span>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          </section>
        ))
      )}

      {pendingRetention && (
        <Modal
          title={t('history.retentionConfirmTitle')}
          role="alertdialog"
          bare
          width={440}
          dismissible={!retentionBusy}
          closeOnBackdrop={false}
          onClose={() => {
            if (!retentionInFlight.current) setPendingRetention(null)
          }}
        >
          <div aria-busy={retentionBusy} className="p-6">
            <h2 id="retention-confirm-title" className="m-0 text-[20px] font-medium">
              {t('history.retentionConfirmTitle')}
            </h2>
            <p
              id="retention-confirm-description"
              className="text-[14px] leading-6 text-text-secondary"
            >
              {t('history.retentionConfirmDescription')}
            </p>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                data-autofocus
                aria-disabled={retentionBusy}
                onClick={() => {
                  if (!retentionInFlight.current) setPendingRetention(null)
                }}
                className="btn-outline h-9 px-4 text-[13px]"
              >
                {t('common.cancel')}
              </button>
              <button
                type="button"
                disabled={retentionBusy}
                onClick={() => void confirmRetention()}
                className="h-9 rounded-full border-none bg-error px-4 text-[13px] text-white"
              >
                {t('history.confirmRetention')}
              </button>
            </div>
          </div>
        </Modal>
      )}
      {correctionEntry && (
        <CreateCorrectionDialog
          entry={correctionEntry}
          onCancel={() => {
            setCorrectionEntry(null)
            const entryId = menuTriggerEntryId.current
            window.setTimeout(() => {
              if (entryId === null) return
              document
                .querySelector<HTMLButtonElement>(`[data-history-menu-trigger="${entryId}"]`)
                ?.focus()
            }, 0)
          }}
          onSave={handleCreateCorrection}
        />
      )}
      {confirmingDelete && (
        <ConfirmDeleteDialog
          title={t('history.item.deleteTitle')}
          description={t('history.item.deleteDescription')}
          confirmLabel={t('history.item.delete')}
          onClose={() => setConfirmingDelete(null)}
          onConfirm={() => handleDelete(confirmingDelete)}
        />
      )}
      {feedbackEntry && (
        <HistoryFeedbackDialog entry={feedbackEntry} onClose={() => setFeedbackEntry(null)} />
      )}
      {retryEntry && (
        <HistoryRetryDialog
          entry={retryEntry}
          onClose={() => setRetryEntry(null)}
          onRetry={() => retryHistoryTranscription(retryEntry.id)}
        />
      )}
    </PageContainer>
  )
}
