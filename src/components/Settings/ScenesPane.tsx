import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, Crown, Copy, Check, BookOpen } from 'lucide-react'
import { useAuthStore } from '../../stores/authStore'
import { useAppStore } from '../../stores/appStore'
import { getScenes, type ScenePack } from '../../lib/api'
import { addDictionaryEntry, getDictionary } from '../../lib/tauri'

type Status = 'idle' | 'loading' | 'error'

export function ScenesPane() {
  const { user, plan } = useAuthStore()
  const { t } = useTranslation()
  const [scenes, setScenes] = useState<ScenePack[]>([])
  const [status, setStatus] = useState<Status>('idle')
  const [activeCategory, setActiveCategory] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [mergeMsg, setMergeMsg] = useState<string | null>(null)
  const [mergeOk, setMergeOk] = useState(false)

  const setDictionary = useAppStore((s) => s.setDictionary)

  useEffect(() => {
    if (!user) return
    setStatus('loading')
    getScenes()
      .then((data) => {
        setScenes(data)
        setStatus('idle')
      })
      .catch(() => setStatus('error'))
  }, [user])

  if (!user) {
    return (
      <div className="text-center py-12 text-text-secondary text-[13px]">
        {t('scenes.signInToBrowse')}
      </div>
    )
  }

  if (status === 'loading') {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 size={20} className="animate-spin text-text-tertiary" />
      </div>
    )
  }

  if (status === 'error') {
    return (
      <div className="text-center py-12 text-[13px]">
        <p className="text-red-500">{t('scenes.failedToLoad')}</p>
        <button
          onClick={() => {
            setStatus('loading')
            getScenes()
              .then((data) => {
                setScenes(data)
                setStatus('idle')
              })
              .catch(() => setStatus('error'))
          }}
          className="mt-3 px-4 py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] cursor-pointer hover:bg-bg-secondary transition-colors"
        >
          {t('scenes.retry')}
        </button>
      </div>
    )
  }

  if (scenes.length === 0) {
    return (
      <div className="text-center py-12 text-text-secondary text-[13px]">
        {t('scenes.noScenes')}
      </div>
    )
  }

  const categories = [...new Set(scenes.map((s) => s.category))]
  const filtered = activeCategory ? scenes.filter((s) => s.category === activeCategory) : scenes

  const handleCopyPrompt = async (scene: ScenePack) => {
    try {
      await navigator.clipboard.writeText(scene.promptTemplate)
      setCopiedId(scene.id)
      setTimeout(() => setCopiedId(null), 2000)
    } catch {
      // Clipboard write failed silently
    }
  }

  const handleMergeDictionary = async (scene: ScenePack) => {
    setMergeMsg(null)
    setMergeOk(false)
    try {
      for (const term of scene.dictionaryTerms) {
        await addDictionaryEntry(term.word, term.pronunciation ?? null)
      }
      const updated = await getDictionary()
      setDictionary(updated)
      setMergeOk(true)
      setMergeMsg(t('scenes.addedTerms', { count: scene.dictionaryTerms.length }))
      setTimeout(() => {
        setMergeMsg(null)
        setMergeOk(false)
      }, 3000)
    } catch {
      setMergeOk(false)
      setMergeMsg(t('scenes.failedToMerge'))
      setTimeout(() => {
        setMergeMsg(null)
        setMergeOk(false)
      }, 3000)
    }
  }

  const isLocked = (scene: ScenePack) => scene.isPro && plan !== 'pro'

  return (
    <div className="space-y-4">
      {/* Category filter */}
      {categories.length > 1 && (
        <div className="flex gap-1.5 flex-wrap">
          <FilterChip
            label={t('scenes.all')}
            active={activeCategory === null}
            onClick={() => setActiveCategory(null)}
          />
          {categories.map((cat) => (
            <FilterChip
              key={cat}
              label={cat}
              active={activeCategory === cat}
              onClick={() => setActiveCategory(cat)}
            />
          ))}
        </div>
      )}

      {/* Scene list */}
      <div className="space-y-2">
        {filtered.map((scene) => {
          const locked = isLocked(scene)
          const expanded = expandedId === scene.id

          return (
            <div key={scene.id} className="border border-border rounded-[10px] overflow-hidden">
              {/* Header */}
              <button
                onClick={() => setExpandedId(expanded ? null : scene.id)}
                className="w-full flex items-center justify-between px-3 py-2.5 bg-transparent border-none cursor-pointer text-left hover:bg-bg-secondary/50 transition-colors"
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-[13px] text-text-primary font-medium truncate">
                    {scene.name}
                  </span>
                  {scene.isPro && (
                    <span className="shrink-0 flex items-center gap-0.5 text-[10px] font-semibold text-accent bg-accent/10 px-1.5 py-0.5 rounded-full">
                      <Crown size={10} /> {t('scenes.pro')}
                    </span>
                  )}
                </div>
                <span className="text-[11px] text-text-tertiary shrink-0 ml-2 capitalize">
                  {scene.category}
                </span>
              </button>

              {/* Expanded detail */}
              {expanded && (
                <div className="border-t border-border px-3 py-3 space-y-3">
                  <p className="text-[12px] text-text-secondary leading-relaxed">
                    {scene.description}
                  </p>

                  {locked ? (
                    <p className="text-[12px] text-text-tertiary italic">
                      {t('scenes.upgradeToUse')}
                    </p>
                  ) : (
                    <>
                      {/* Prompt template */}
                      {scene.promptTemplate && (
                        <div className="space-y-1.5">
                          <span className="text-[11px] font-medium text-text-secondary uppercase tracking-wide">
                            {t('scenes.promptTemplate')}
                          </span>
                          <pre className="text-[12px] text-text-primary bg-bg-secondary rounded-[8px] px-3 py-2 whitespace-pre-wrap max-h-[120px] overflow-y-auto leading-relaxed">
                            {scene.promptTemplate}
                          </pre>
                          <button
                            onClick={() => handleCopyPrompt(scene)}
                            aria-label={`Copy prompt for ${scene.name}`}
                            className="flex items-center gap-1 text-[12px] text-accent bg-transparent border-none cursor-pointer hover:opacity-80 transition-opacity"
                          >
                            {copiedId === scene.id ? <Check size={12} /> : <Copy size={12} />}
                            {copiedId === scene.id ? t('scenes.copied') : t('scenes.copyPrompt')}
                          </button>
                        </div>
                      )}

                      {/* Dictionary terms */}
                      {scene.dictionaryTerms.length > 0 && (
                        <div className="space-y-1.5">
                          <span className="text-[11px] font-medium text-text-secondary uppercase tracking-wide">
                            {t('scenes.dictionaryTerms', { count: scene.dictionaryTerms.length })}
                          </span>
                          <div className="flex flex-wrap gap-1.5">
                            {scene.dictionaryTerms.slice(0, 12).map((term, i) => (
                              <span
                                key={i}
                                className="text-[11px] px-2 py-0.5 rounded-full bg-bg-secondary text-text-primary border border-border"
                              >
                                {term.word}
                              </span>
                            ))}
                            {scene.dictionaryTerms.length > 12 && (
                              <span className="text-[11px] px-2 py-0.5 text-text-tertiary">
                                {t('scenes.moreTerms', {
                                  count: scene.dictionaryTerms.length - 12,
                                })}
                              </span>
                            )}
                          </div>
                          <button
                            onClick={() => handleMergeDictionary(scene)}
                            className="flex items-center gap-1 text-[12px] text-accent bg-transparent border-none cursor-pointer hover:opacity-80 transition-opacity"
                          >
                            <BookOpen size={12} />
                            {t('scenes.mergeIntoDictionary')}
                          </button>
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* Merge feedback */}
      {mergeMsg && (
        <p className={`text-[12px] ${!mergeOk ? 'text-red-500' : 'text-green-500'}`}>{mergeMsg}</p>
      )}
    </div>
  )
}

function FilterChip({
  label,
  active,
  onClick,
}: {
  label: string
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1.5 rounded-full text-[12px] border cursor-pointer transition-colors capitalize ${
        active
          ? 'bg-accent text-white border-accent'
          : 'bg-transparent text-text-secondary border-border hover:border-border-focus'
      }`}
    >
      {label}
    </button>
  )
}
