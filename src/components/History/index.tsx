import { useState, useMemo } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { Search, Copy, Trash2 } from 'lucide-react'
import { spring } from '../../lib/animations'
import { useAppStore } from '../../stores/appStore'
import { clearHistory } from '../../lib/tauri'
import { toast } from '../Toast'

export function History() {
  const history = useAppStore((s) => s.history)
  const setHistory = useAppStore((s) => s.setHistory)
  const { t } = useTranslation()
  const [search, setSearch] = useState('')
  const [copiedId, setCopiedId] = useState<number | null>(null)

  // Track expanded state for each entry
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set())

  const filtered = useMemo(
    () =>
      search
        ? history.filter(
            (h) =>
              h.polished_text.includes(search) ||
              h.raw_text.includes(search) ||
              h.app_name.includes(search),
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

  const toggleExpand = (id: number) => {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  const handleClear = async () => {
    if (!window.confirm(t('history.clearConfirm'))) return
    try {
      await clearHistory()
      setHistory([])
    } catch (e) {
      console.error('Failed to clear history:', e)
      toast.error(t('history.failedToClear'))
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
            className="w-full pl-8 pr-3 py-2.5 bg-bg-secondary border border-border rounded-[14px] text-[13px] text-text-primary outline-none focus:ring-2 focus:ring-jelly-primary focus:border-jelly-primary transition-all jelly-btn"
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
                  {entries.map((entry) => {
                    const isExpanded = expandedIds.has(entry.id)

                    return (
                      <motion.div
                        key={entry.id}
                        whileHover={{ scale: 1.01 }}
                        transition={spring.jellyGentle}
                        className="group px-3 py-2.5 rounded-[10px] hover:bg-bg-secondary transition-colors"
                      >
                        {/* Row 1: Polished text + Toggle + Copy Polished */}
                        <div className="flex items-start gap-1">
                          <div className="flex-1 min-w-0">
                            <p className="text-[13px] text-text-primary leading-relaxed">
                              {entry.polished_text}
                            </p>
                          </div>
                          {/* Toggle button */}
                          <motion.button
                            onClick={() => toggleExpand(entry.id)}
                            whileTap={{ scaleX: 1.1, scaleY: 0.9 }}
                            transition={spring.jelly}
                            className="opacity-0 scale-95 group-hover:opacity-100 group-hover:scale-100 px-2 py-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all duration-200 bg-transparent border-none cursor-pointer text-[11px] text-text-tertiary hover:text-accent whitespace-nowrap flex-shrink-0"
                          >
                            {isExpanded ? 'Hide Original' : 'View Original'}
                          </motion.button>
                          {/* Copy Polished button */}
                          <motion.button
                            onClick={() => handleCopy(entry.id, entry.polished_text)}
                            whileTap={{ scaleX: 1.1, scaleY: 0.9 }}
                            transition={spring.jelly}
                            className="opacity-0 scale-95 group-hover:opacity-100 group-hover:scale-100 p-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all duration-200 bg-transparent border-none cursor-pointer text-text-tertiary hover:text-accent flex-shrink-0"
                            aria-label={`Copy polished: ${entry.polished_text.slice(0, 30)}`}
                            title="Copy polished text"
                          >
                            <Copy size={13} />
                          </motion.button>
                        </div>

                        {/* Row 2: Raw text + Copy Original (when expanded) */}
                        <AnimatePresence>
                          {isExpanded && (
                            <motion.div
                              initial={{ height: 0, opacity: 0 }}
                              animate={{ height: 'auto', opacity: 1 }}
                              exit={{ height: 0, opacity: 0 }}
                              transition={{ duration: 0.2 }}
                              className="overflow-hidden"
                            >
                              <div className="mt-2 pt-2 border-t border-border/50 flex items-start gap-1">
                                <p className="flex-1 text-[12px] text-text-tertiary leading-relaxed min-w-0">
                                  {entry.raw_text}
                                </p>
                                {/* Spacer for alignment with Toggle column */}
                                <div className="px-2 py-1.5 text-[11px] whitespace-nowrap opacity-0 flex-shrink-0">
                                  Hide Original
                                </div>
                                {/* Copy Original button - aligned with Copy Polished */}
                                <motion.button
                                  onClick={() => handleCopy(entry.id, entry.raw_text)}
                                  whileTap={{ scaleX: 1.1, scaleY: 0.9 }}
                                  transition={spring.jelly}
                                  className="p-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all duration-200 bg-transparent border-none cursor-pointer text-text-tertiary hover:text-accent flex-shrink-0"
                                  aria-label={`Copy original: ${entry.raw_text.slice(0, 30)}`}
                                  title="Copy original text"
                                >
                                  <Copy size={13} />
                                </motion.button>
                              </div>
                            </motion.div>
                          )}
                        </AnimatePresence>

                        {/* Row 3: Metadata */}
                        <div className="flex items-center gap-1 mt-1">
                          <p className="text-[11px] text-text-tertiary">
                            {entry.created_at.split('T')[1]?.slice(0, 5) || ''} · {entry.app_name}
                          </p>
                          {/* Copied indicator */}
                          {copiedId === entry.id && (
                            <span className="text-[11px] text-success">{t('history.copied')}</span>
                          )}
                        </div>
                      </motion.div>
                    )
                  })}
                </div>
              </div>
            ))}
          </AnimatePresence>
        )}
      </div>

      {/* Clear button — jelly */}
      {history.length > 0 && (
        <div className="px-5 py-3 border-t border-border">
          <motion.button
            onClick={handleClear}
            whileHover={{ scale: 1.04 }}
            whileTap={{ scaleX: 1.06, scaleY: 0.94 }}
            transition={spring.jellyGentle}
            className="flex items-center justify-center gap-1.5 w-full py-2 text-[12px] text-text-tertiary hover:text-error rounded-[10px] cursor-pointer transition-colors jelly-btn"
          >
            <Trash2 size={12} />
            {t('history.clearAll')}
          </motion.button>
        </div>
      )}
    </div>
  )
}
