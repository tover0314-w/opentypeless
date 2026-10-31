import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Copy, HardDrive, Lock, MoreHorizontal, Search, Sparkles, Trash2 } from 'lucide-react'
import { useAppStore, type HistoryEntry } from '../../stores/appStore'
import {
  addCorrectionRule,
  clearHistory,
  deleteHistoryEntry,
  getCorrectionRules,
  showSavedAskAnswer,
} from '../../lib/tauri'
import { toast } from '../toast-service'
import { PageContainer, PageHeader } from '../shared/Page'
import { AppContextMeta } from './AppContextMeta'
import { CreateCorrectionDialog } from './CreateCorrectionDialog'

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
  const [confirmingDelete, setConfirmingDelete] = useState<HistoryEntry | null>(null)
  const menuTriggerEntryId = useRef<number | null>(null)

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
      await deleteHistoryEntry(entry.id)
      setHistory(useAppStore.getState().history.filter((item) => item.id !== entry.id))
      setConfirmingDelete(null)
      toast.success(t('history.deleted'))
    } catch (error) {
      console.error('Failed to delete history entry:', error)
      toast.error(t('history.failedToDelete'))
    }
  }

  const handleViewAnswer = (entry: HistoryEntry) => {
    if (!entry.answer_text) return
    showSavedAskAnswer(entry.polished_text || entry.raw_text, entry.answer_text).catch((error) => {
      console.error('Failed to open saved answer:', error)
      toast.error(t('history.failedToOpenAnswer'))
    })
  }

  const handleRetentionChange = (value: string) => {
    const option = RETENTION_OPTIONS.find((item) => item.value === value)
    if (!option) return
    updateConfig({ history_enabled: option.enabled, history_retention_days: option.days })
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
          <select
            aria-label={t('history.keepHistory')}
            value={retentionValue}
            onChange={(event) => handleRetentionChange(event.target.value)}
            className="h-10 min-w-[200px] rounded-[8px] border border-border bg-bg-elevated px-3 text-[14px] text-text-primary"
          >
            {RETENTION_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {t(`history.retention.${option.value}`)}
              </option>
            ))}
          </select>
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
                return (
                  <div
                    key={entry.id}
                    className={`group relative flex items-start gap-4 px-5 py-4 transition-colors hover:bg-fill-thin ${
                      index > 0 ? 'border-t border-border-thin' : ''
                    }`}
                  >
                    <span className="w-[72px] shrink-0 pt-0.5 text-[13px] tabular-nums text-text-tertiary">
                      {timeFormat.format(new Date(entry.created_at))}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="text-[15px] leading-6 text-text-primary">
                        {entry.polished_text || entry.raw_text}
                      </p>
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
                    <div className="flex shrink-0 items-center gap-1">
                      {isAsk && entry.answer_text && (
                        <button
                          type="button"
                          onClick={() => handleViewAnswer(entry)}
                          className="flex cursor-pointer items-center gap-1.5 rounded-[6px] border-none bg-fill-tough px-3 py-1.5 text-[13px] text-text-primary opacity-0 transition-opacity hover:bg-fill-selected group-hover:opacity-100 focus-visible:opacity-100"
                        >
                          <Sparkles size={13} />
                          {t('history.viewAnswer')}
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => handleCopy(entry.id, entry.polished_text || entry.raw_text)}
                        aria-label={t('history.copy')}
                        className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center opacity-0 group-hover:opacity-100 focus-visible:opacity-100"
                      >
                        <Copy size={14} />
                      </button>
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
                          className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center"
                        >
                          <MoreHorizontal size={14} />
                        </button>
                        {menuEntryId === entry.id && (
                          <>
                            <div className="fixed inset-0 z-30" onClick={closeEntryMenu} />
                            <div
                              role="menu"
                              className="surface-popover absolute right-0 top-9 z-40 min-w-44 py-1"
                            >
                              {!isAsk && (
                                <button
                                  type="button"
                                  role="menuitem"
                                  onClick={() => {
                                    setMenuEntryId(null)
                                    setCorrectionEntry(entry)
                                  }}
                                  className="h-9 w-full border-none bg-transparent px-3 text-left text-[13px] text-text-primary cursor-pointer hover:bg-fill-hover"
                                >
                                  {t('history.createCorrection')}
                                </button>
                              )}
                              <button
                                type="button"
                                role="menuitem"
                                onClick={() => {
                                  setMenuEntryId(null)
                                  setConfirmingDelete(entry)
                                }}
                                className="h-9 w-full border-none bg-transparent px-3 text-left text-[13px] text-error cursor-pointer hover:bg-fill-hover"
                              >
                                {t('history.deleteEntry')}
                              </button>
                            </div>
                          </>
                        )}
                      </div>
                    </div>
                    {copiedId === entry.id && (
                      <span className="absolute right-5 top-1 text-[11px] text-success">
                        {t('history.copied')}
                      </span>
                    )}
                    {confirmingDelete?.id === entry.id && (
                      <div className="absolute inset-0 z-20 flex items-center justify-end gap-2 bg-bg-elevated/95 px-5">
                        <span className="mr-auto text-[13px] text-text-secondary">
                          {t('history.deleteConfirm')}
                        </span>
                        <button
                          type="button"
                          onClick={() => setConfirmingDelete(null)}
                          className="btn-outline cursor-pointer px-3 py-1 text-[12px]"
                        >
                          {t('common.cancel')}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(entry)}
                          className="cursor-pointer rounded-full border border-error/30 bg-error/15 px-3 py-1 text-[12px] font-medium text-error hover:bg-error/20"
                        >
                          {t('history.deleteEntry')}
                        </button>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </section>
        ))
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
    </PageContainer>
  )
}
