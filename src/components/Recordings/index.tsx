import { useState, useEffect, useMemo, useCallback, useRef } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { Search, RefreshCw, Trash2, Loader2 } from 'lucide-react'
import { spring } from '../../lib/animations'
import type { RecordingEntry } from '../../stores/appStore'
import {
  getRecordings,
  readRecordingBytes,
  retranscribeRecording,
  deleteRecording,
} from '../../lib/tauri'
import { toast } from '../Toast'
import { RecordingPlayer } from './RecordingPlayer'

const PAGE_SIZE = 200

/** Map a saved-file format to the MIME type for blob playback. */
function mimeForFormat(format: string | null | undefined): string {
  switch ((format || '').toLowerCase()) {
    case 'wav':
      return 'audio/wav'
    case 'flac':
      return 'audio/flac'
    default:
      return 'audio/mpeg' // mp3
  }
}

export function Recordings() {
  const { t } = useTranslation()
  const [recordings, setRecordings] = useState<RecordingEntry[]>([])
  const [audioSrc, setAudioSrc] = useState<Record<number, string>>({})
  const [retranscribingId, setRetranscribingId] = useState<number | null>(null)
  const [search, setSearch] = useState('')

  // Track every blob URL we create so we can revoke them on unmount/delete.
  const blobUrls = useRef<Map<number, string>>(new Map())

  // Load recordings on mount (Recordings are fetched here, not preloaded in App)
  useEffect(() => {
    getRecordings(PAGE_SIZE, 0)
      .then(setRecordings)
      .catch((e) => {
        console.error('Failed to load recordings:', e)
        toast.error(t('recordings.failedToLoad'))
      })
  }, [t])

  // Resolve audio lazily per row. WebKitGTK rejects the custom asset:// scheme
  // as a media source, so we fetch the bytes over IPC and play from a blob URL.
  const loadAudio = useCallback(
    (entry: RecordingEntry) => {
      if (blobUrls.current.has(entry.id)) return
      readRecordingBytes(entry.id)
        .then((buf) => {
          if (blobUrls.current.has(entry.id)) return
          const blob = new Blob([buf], { type: mimeForFormat(entry.format) })
          const url = URL.createObjectURL(blob)
          blobUrls.current.set(entry.id, url)
          setAudioSrc((prev) => ({ ...prev, [entry.id]: url }))
        })
        .catch((e) => {
          console.error('Failed to load recording audio:', e)
          toast.error(t('recordings.failedToLoadAudio'))
        })
    },
    [t],
  )

  // Audio is loaded lazily, on first play of a row (see RecordingPlayer's
  // onRequestLoad). Eagerly loading every recording mounted ~100 simultaneous
  // WebKitGTK media pipelines, most of which never became playable — the cause
  // of play buttons silently doing nothing.

  // Revoke all blob URLs when the screen unmounts.
  useEffect(() => {
    const urls = blobUrls.current
    return () => {
      for (const url of urls.values()) URL.revokeObjectURL(url)
      urls.clear()
    }
  }, [])

  const filtered = useMemo(
    () =>
      search
        ? recordings.filter((r) => r.polished_text.includes(search) || r.raw_text.includes(search))
        : recordings,
    [recordings, search],
  )

  // Group by date — mirror History
  const grouped = useMemo(() => {
    const map = new Map<string, typeof filtered>()
    for (const entry of filtered) {
      const date = entry.created_at.split('T')[0] || entry.created_at.split(' ')[0]
      const today = new Date().toISOString().split('T')[0]
      const yesterday = new Date(Date.now() - 86400000).toISOString().split('T')[0]
      const label =
        date === today
          ? t('recordings.today')
          : date === yesterday
            ? t('recordings.yesterday')
            : date
      if (!map.has(label)) map.set(label, [])
      map.get(label)!.push(entry)
    }
    return map
  }, [filtered, t])

  const handleRetranscribe = async (id: number) => {
    if (retranscribingId !== null) return
    setRetranscribingId(id)
    try {
      const newText = await retranscribeRecording(id)
      setRecordings((prev) =>
        prev.map((r) => (r.id === id ? { ...r, raw_text: newText, polished_text: newText } : r)),
      )
    } catch (e) {
      console.error('Failed to re-transcribe recording:', e)
      toast.error(t('recordings.failedToRetranscribe'))
    } finally {
      setRetranscribingId(null)
    }
  }

  const handleDelete = async (id: number) => {
    if (!window.confirm(t('recordings.deleteConfirm'))) return
    try {
      await deleteRecording(id)
      const url = blobUrls.current.get(id)
      if (url) {
        URL.revokeObjectURL(url)
        blobUrls.current.delete(id)
      }
      setAudioSrc((prev) => {
        const next = { ...prev }
        delete next[id]
        return next
      })
      setRecordings((prev) => prev.filter((r) => r.id !== id))
    } catch (e) {
      console.error('Failed to delete recording:', e)
      toast.error(t('recordings.failedToDelete'))
    }
  }

  return (
    <div className="w-full h-full bg-bg-primary text-text-primary flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-5 pt-4 pb-3 border-b border-border">
        <h2 className="text-[15px] font-medium">{t('recordings.title')}</h2>
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
            placeholder={t('recordings.searchPlaceholder')}
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
              t('recordings.noResults')
            ) : (
              <>
                {t('recordings.noRecordings')}
                <br />
                <span className="text-[12px]">{t('recordings.noRecordingsHint')}</span>
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
                <div className="space-y-1.5">
                  {entries.map((entry) => (
                    <motion.div
                      key={entry.id}
                      initial={{ opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={{ opacity: 0, y: -8 }}
                      transition={spring.jellyGentle}
                      className="group px-3 py-2.5 rounded-[10px] hover:bg-bg-secondary transition-colors"
                    >
                      <div className="flex items-start gap-3">
                        <div className="flex-1 min-w-0">
                          {entry.raw_text.trim() ? (
                            <p className="text-[13px] text-text-primary leading-relaxed">
                              {entry.polished_text || entry.raw_text}
                            </p>
                          ) : (
                            <p className="text-[13px] text-error leading-relaxed">
                              {t('recordings.failedNeedsRetranscribe')}
                            </p>
                          )}
                          <p className="text-[11px] text-text-tertiary mt-1">
                            <span className="tabular-nums">#{entry.id}</span> ·{' '}
                            {entry.created_at.split('T')[1]?.slice(0, 5) || ''} ·{' '}
                            {entry.format.toUpperCase()}
                          </p>
                        </div>
                        <div className="flex items-center gap-1 flex-shrink-0">
                          <motion.button
                            onClick={() => handleRetranscribe(entry.id)}
                            disabled={retranscribingId !== null}
                            whileTap={{ scaleX: 1.1, scaleY: 0.9 }}
                            transition={spring.jelly}
                            className="p-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all duration-200 bg-transparent border-none cursor-pointer text-text-tertiary hover:text-accent disabled:opacity-50 disabled:cursor-default"
                            aria-label={t('recordings.retranscribe')}
                            title={t('recordings.retranscribe')}
                          >
                            {retranscribingId === entry.id ? (
                              <motion.span
                                className="block"
                                animate={{ rotate: 360 }}
                                transition={{ repeat: Infinity, duration: 0.8, ease: 'linear' }}
                              >
                                <Loader2 size={13} />
                              </motion.span>
                            ) : (
                              <RefreshCw size={13} />
                            )}
                          </motion.button>
                          <motion.button
                            onClick={() => handleDelete(entry.id)}
                            whileTap={{ scaleX: 1.1, scaleY: 0.9 }}
                            transition={spring.jelly}
                            className="p-1.5 rounded-[6px] hover:bg-bg-tertiary transition-all duration-200 bg-transparent border-none cursor-pointer text-text-tertiary hover:text-error"
                            aria-label={t('recordings.delete')}
                            title={t('recordings.delete')}
                          >
                            <Trash2 size={13} />
                          </motion.button>
                        </div>
                      </div>
                      {retranscribingId === entry.id && (
                        <p className="text-[11px] text-text-tertiary mt-1">
                          {t('recordings.retranscribing')}
                        </p>
                      )}
                      <RecordingPlayer
                        src={audioSrc[entry.id]}
                        durationMs={entry.duration_ms}
                        onRequestLoad={() => loadAudio(entry)}
                      />
                    </motion.div>
                  ))}
                </div>
              </div>
            ))}
          </AnimatePresence>
        )}
      </div>
    </div>
  )
}
