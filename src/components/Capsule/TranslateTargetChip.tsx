import { Check } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TARGET_LANGUAGES } from '../../lib/constants'
import { setActiveTranslationTarget } from '../../lib/tauri'
import { useAppStore } from '../../stores/appStore'

const CHIP_ID = 'translate-target-chip'

function languageLabel(code: string) {
  return TARGET_LANGUAGES.find((language) => language.value === code)?.label ?? code.toUpperCase()
}

function restoreChipFocus() {
  window.setTimeout(() => document.getElementById(CHIP_ID)?.focus(), 0)
}

export function TranslateTargetChip() {
  const { t } = useTranslation()
  const pipelineState = useAppStore((state) => state.pipelineState)
  const activeVoiceMode = useAppStore((state) => state.activeVoiceMode)
  const activeTarget = useAppStore((state) => state.config.translation.active_target)
  const menuOpen = useAppStore((state) => state.translationTargetMenuOpen)
  const setMenuOpen = useAppStore((state) => state.setTranslationTargetMenuOpen)
  const setContextMenuOpen = useAppStore((state) => state.setContextMenuOpen)

  if (pipelineState !== 'recording' || activeVoiceMode !== 'translate') return null

  return (
    <button
      id={CHIP_ID}
      type="button"
      aria-haspopup="menu"
      aria-expanded={menuOpen}
      aria-label={`${t('capsule.translationTarget')} ${activeTarget}`}
      title={languageLabel(activeTarget)}
      onPointerDown={(event) => event.stopPropagation()}
      onPointerUp={(event) => event.stopPropagation()}
      onClick={(event) => {
        event.stopPropagation()
        setContextMenuOpen(false)
        setMenuOpen(!menuOpen)
      }}
      className="flex h-[22px] min-w-7 flex-shrink-0 items-center justify-center rounded-[6px] border border-white/20 bg-white/10 px-1.5 text-[10px] font-semibold uppercase text-white/90 transition-colors hover:bg-white/18 hover:text-white"
    >
      {activeTarget}
    </button>
  )
}

export function TranslateTargetMenu() {
  const { t } = useTranslation()
  const targets = useAppStore((state) => state.config.translation.targets)
  const activeTarget = useAppStore((state) => state.config.translation.active_target)
  const menuOpen = useAppStore((state) => state.translationTargetMenuOpen)
  const setMenuOpen = useAppStore((state) => state.setTranslationTargetMenuOpen)
  const applyPersistedConfigPatch = useAppStore((state) => state.applyPersistedConfigPatch)
  const [pendingTarget, setPendingTarget] = useState<string | null>(null)

  const close = useCallback(() => {
    setMenuOpen(false)
    restoreChipFocus()
  }, [setMenuOpen])

  useEffect(() => {
    if (!menuOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      close()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [close, menuOpen])

  if (!menuOpen) return null

  const selectTarget = async (code: string) => {
    if (code === activeTarget) {
      close()
      return
    }
    setPendingTarget(code)
    try {
      const translation = await setActiveTranslationTarget(code)
      applyPersistedConfigPatch({ target_lang: translation.active_target, translation })
      close()
    } catch (error) {
      console.error('Failed to switch translation target:', error)
    } finally {
      setPendingTarget(null)
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-40" onClick={close} />
      <div
        role="menu"
        aria-label={t('capsule.translationTargets')}
        className="absolute left-[216px] top-1/2 z-50 w-[148px] -translate-y-1/2 py-1 rounded-[14px] surface-popover"
      >
        {targets.map((code) => (
          <button
            key={code}
            type="button"
            role="menuitemradio"
            aria-checked={activeTarget === code}
            disabled={pendingTarget !== null}
            onClick={() => void selectTarget(code)}
            className="flex h-8 w-full items-center gap-2 bg-transparent px-3 text-left text-[12px] text-text-primary transition-colors hover:bg-bg-tertiary disabled:opacity-60"
          >
            <span className="flex h-4 w-4 items-center justify-center">
              {activeTarget === code && <Check size={13} />}
            </span>
            <span className="min-w-0 flex-1 truncate">{languageLabel(code)}</span>
          </button>
        ))}
      </div>
    </>
  )
}
