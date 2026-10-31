import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { Search, Copy, Trash2, MoreHorizontal } from 'lucide-react'
import { spring } from '../../lib/animations'
import { useAppStore, type HistoryEntry } from '../../stores/appStore'
import { addCorrectionRule, clearHistory, getCorrectionRules } from '../../lib/tauri'
import { toast } from '../toast-service'
import { AppContextMeta } from './AppContextMeta'
import { CreateCorrectionDialog } from './CreateCorrectionDialog'

export function History() {
  const history = useAppStore((s) => s.history)
  const setHistory = useAppStore((s) => s.setHistory)
  const setCorrectionRules = useAppStore((s) => s.setCorrectionRules)
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [copiedId, setCopiedId] = useState<number | null>(null)
  const [menuEntryId, setMenuEntryId] = useState<number | null>(null)
  const [correctionEntry, setCorrectionEntry] = useState<HistoryEntry | null>(null)
  const [confirmingClear, setConfirmingClear] = useState(false)
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

  const filtered = useMemo(
    () =>
      search
        ? history.filter(
            (h) =>
              h.polished_text.includes(search) ||
              h.raw_text.includes(search) ||
              h.context_label.includes(search),
          )
        : history,
    [history, search],
  )

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

  // Group by date
  const grouped = useMemo(() => {
    const map = new Map<string, typeof filtered>()
    for (const entry of filtered) {
      const date = entry.created_at.split('T')[0] || entry.created_at.split(' ')[0]
      const today = new Date().toISOString().split('T')[0]
      const yesterday = new Date(Date.now() - 86400000).toISOString().split('T')[0]
      const label =
        date === today ? t('history.today') : date === yesterday ? t('history.yesterday') : date
      if (!map.has(label)) map.set(label, [])
      map.get(label)!.push(entry)
    }
    return map
  }, [filtered, t])

  return (
    <div className="w-full h-full bg-bg-primary text-text-primary flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-5 pt-4 pb-3 border-b border-border">
        <h2 className="text-[15px] font-medium">{t('history.title')}</h2>
      </div>

      {/* Search — jelly focus */}
      <div className="px-5 py-3">
        <div className="relative">
          <Search
            size={14}
            className="absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
          />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('history.searchPlaceholder')}
            className="w-full pl-8 pr-3 py-2.5 bg-bg-secondary border border-border rounded-[14px] text-[13px] text-text-primary outline-none focus:ring-2 focus:ring-accent/30 focus:border-accent transition-all"
            style={{ transform: 'none' }}
          />
        </div>
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto px-5 pb-4">
        {filtered.length === 0 ? (
          <p className="text-center text-text-tertiary text-[13px] py-12">
            {search ? (
              t('history.noResults')
            ) : (
              <>
                {t('history.noHistory')}
                <br />
                <span className="text-[12px]">{t('history.noHistoryHint')}</span>
              </>
            )}
          </p>
        ) : (
          <AnimatePresence>
            {Array.from(grouped.entries()).map(([label, entries]) => (
              <div key={label} className="mb-4">
                <h3 className="text-[11px] font-medium text-text-tertiary uppercase tracking-wider mb-2 px-1 pb-1 border-b border-border">
                  {label}
                </h3>
                <div className="space-y-0.5">
                  {entries.map((entry) => (
                    <motion.div
                      key={entry.id}
                      whileHover={{ scale: 1.01 }}
                      transition={spring.jellyGentle}
                      className="group flex items-start gap-3 px-3 py-2.5 rounded-[10px] hover:bg-bg-secondary transition-colors"
                    >
                      <div className="flex-1 min-w-0">
                        <p className="text-[13px] text-text-primary leading-relaxed">
                          {entry.polished_text}
                        </p>
                        <AppContextMeta
                          iconKey={entry.context_icon_key}
                          family={entry.context_family}
                          label={entry.context_label}
                          time={entry.created_at.split('T')[1]?.slice(0, 5) || ''}
                          providerKind={entry.provider_kind}
                          browserAccessStatus={entry.browser_access_status}
                        />
                        {entry.output_status && outputStatusLabel(entry.output_status) && (
                          <p className="text-[11px] text-warning mt-1 leading-snug break-words">
                            {outputStatusLabel(entry.output_status)}
                            {entry.output_error ? ` · ${entry.output_error}` : ''}
                          </p>
                        )}
                      </div>
                      <div className="flex flex-shrink-0 items-center">
                        <motion.button
                          onClick={() => handleCopy(entry.id, entry.polished_text)}
                          whileTap={{ scaleX: 1.1, scaleY: 0.9 }}
                          transition={spring.jelly}
                          className="opacity-0 scale-95 group-hover:opacity-100 group-hover:scale-100 p-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all duration-200 bg-transparent border-none cursor-pointer text-text-tertiary hover:text-accent flex-shrink-0"
                          aria-label={`Copy text: ${entry.polished_text.slice(0, 30)}`}
                        >
                          <Copy size={13} />
                        </motion.button>
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
                            className="p-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all bg-transparent border-none cursor-pointer text-text-tertiary hover:text-text-primary flex-shrink-0"
                          >
                            <MoreHorizontal size={13} />
                          </button>
                          {menuEntryId === entry.id && (
                            <>
                              <div className="fixed inset-0 z-30" onClick={closeEntryMenu} />
                              <div
                                role="menu"
                                className="absolute right-0 top-7 z-40 min-w-40 rounded-[8px] border border-border bg-bg-primary py-1 shadow-float"
                              >
                                <button
                                  type="button"
                                  role="menuitem"
                                  onClick={() => {
                                    setMenuEntryId(null)
                                    setCorrectionEntry(entry)
                                  }}
                                  className="h-8 w-full bg-transparent px-3 text-left text-[12px] text-text-primary hover:bg-bg-secondary"
                                >
                                  {t('history.createCorrection')}
                                </button>
                              </div>
                            </>
                          )}
                        </div>
                      </div>
                      {copiedId === entry.id && (
                        <span className="text-[11px] text-success flex-shrink-0 self-center">
                          {t('history.copied')}
                        </span>
                      )}
                    </motion.div>
                  ))}
                </div>
              </div>
            ))}
          </AnimatePresence>
        )}
      </div>

      {/* Clear button — jelly */}
      {history.length > 0 && (
        <div className="space-y-2 border-t border-border px-5 py-3">
          {confirmingClear && (
            <div className="rounded-[10px] border border-error/20 bg-error/10 px-3 py-2">
              <p className="text-[12px] leading-relaxed text-text-secondary">
                {t('history.clearConfirm')}
              </p>
              <div className="mt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setConfirmingClear(false)}
                  className="rounded-[7px] border border-border bg-transparent px-2.5 py-1 text-[11px] text-text-secondary hover:text-text-primary"
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="button"
                  onClick={handleClear}
                  className="rounded-[7px] border border-error/30 bg-error/15 px-2.5 py-1 text-[11px] font-medium text-error hover:bg-error/20"
                >
                  {t('history.confirmClear')}
                </button>
              </div>
            </div>
          )}
          <motion.button
            onClick={() => setConfirmingClear(true)}
            whileHover={{ scale: 1.04 }}
            whileTap={{ scaleX: 1.06, scaleY: 0.94 }}
            transition={spring.jellyGentle}
            className="flex w-full cursor-pointer items-center justify-center gap-1.5 rounded-[10px] py-2 text-[12px] text-text-tertiary transition-colors hover:text-error hover:bg-fill-hover"
          >
            <Trash2 size={12} />
            {t('history.clearAll')}
          </motion.button>
        </div>
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
    </div>
  )
}
