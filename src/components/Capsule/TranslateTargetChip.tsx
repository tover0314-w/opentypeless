import { Check, ChevronsUpDown } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { TARGET_LANGUAGES } from '../../lib/constants'
import { setActiveTranslationTarget } from '../../lib/tauri'
import { useAppStore } from '../../stores/appStore'
import { openCapsuleSettings } from '../../lib/capsuleNavigation'
import { valuesEqual } from '../../lib/configValuesEqual'

const CHIP_ID = 'translate-target-chip'
const MENU_ID = 'translate-target-menu'

function focusTranslationTarget() {
  const menu = document.getElementById(MENU_ID)
  const selected = menu?.querySelector<HTMLButtonElement>(
    'button[aria-checked="true"]:not(:disabled)',
  )
  const first = menu?.querySelector<HTMLButtonElement>('button:not(:disabled)')
  ;(selected ?? first)?.focus()
}

function languageLabel(code: string) {
  return TARGET_LANGUAGES.find((language) => language.value === code)?.label ?? code.toUpperCase()
}

function restoreChipFocus() {
  const trigger = document.getElementById(CHIP_ID)
  const previousFocus = document.activeElement
  window.setTimeout(() => {
    if (!trigger?.isConnected) return
    if (document.activeElement !== previousFocus && document.activeElement !== document.body) return
    trigger.focus()
  }, 0)
}

export function TranslateTargetChip() {
  const { t } = useTranslation()
  const pipelineState = useAppStore((state) => state.pipelineState)
  const activeVoiceMode = useAppStore((state) => state.activeVoiceMode)
  const recordingSessionId = useAppStore((state) => state.recordingDeadline?.sessionId)
  const recordingKind = useAppStore((state) => state.recordingDeadline?.recordingKind)
  const configEpoch = useAppStore((state) => state.configCommit?.configEpoch)
  const activeTarget = useAppStore((state) => state.config.translation.active_target)
  const menuOpen = useAppStore((state) => state.translationTargetMenuOpen)
  const setMenuOpen = useAppStore((state) => state.setTranslationTargetMenuOpen)
  const root = useRef<HTMLDivElement>(null)
  const leave = (event: React.MouseEvent) => {
    if (event.relatedTarget instanceof Node && root.current?.contains(event.relatedTarget)) return
    setMenuOpen(false)
  }
  useEffect(() => () => setMenuOpen(false), [setMenuOpen])

  if (pipelineState !== 'recording' || activeVoiceMode !== 'translate') return null

  return (
    <div
      ref={root}
      onMouseLeave={leave}
      onBlur={(event) => {
        if (event.relatedTarget instanceof Node && root.current?.contains(event.relatedTarget))
          return
        setMenuOpen(false)
      }}
      className="relative max-w-full"
    >
      <TranslateTargetMenu
        key={`${configEpoch ?? 'legacy'}:${recordingKind ?? 'unknown'}:${recordingSessionId ?? 'unknown'}`}
        onMouseLeave={leave}
      />
      <div className="capsule-alert flex max-w-full items-center gap-2 rounded-[8px] py-1 pl-2 pr-1 text-[14px] leading-4">
        <span className="min-w-0">{t('capsule.alerts.translationLabel')}</span>
        <button
          id={CHIP_ID}
          type="button"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          aria-label={`${t('capsule.translationTarget')} ${activeTarget}`}
          title={languageLabel(activeTarget)}
          onPointerDown={(event) => event.stopPropagation()}
          onPointerUp={(event) => event.stopPropagation()}
          onMouseEnter={() => setMenuOpen(true)}
          onKeyDown={(event) => {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault()
              const trigger = event.currentTarget
              setMenuOpen(true)
              window.setTimeout(() => {
                if (trigger.isConnected && document.activeElement === trigger)
                  focusTranslationTarget()
              }, 0)
            }
          }}
          onClick={(event) => {
            event.stopPropagation()
            setMenuOpen(true)
          }}
          className="flex min-w-0 shrink-0 items-center gap-2 rounded-[6px] border border-white/20 bg-white/10 px-2 py-1.5 text-[14px] text-white/90 hover:bg-white/18 hover:text-white"
        >
          <span>{languageLabel(activeTarget)}</span>
          <ChevronsUpDown size={14} />
        </button>
      </div>
    </div>
  )
}

export function TranslateTargetMenu({
  onMouseLeave,
}: {
  onMouseLeave: (event: React.MouseEvent) => void
}) {
  const { t } = useTranslation()
  const targets = useAppStore((state) => state.config.translation.targets)
  const currentTranslation = useAppStore((state) => state.config.translation)
  const recordingDeadline = useAppStore((state) => state.recordingDeadline)
  const configEpoch = useAppStore((state) => state.configCommit?.configEpoch)
  const activeTarget = useAppStore((state) => state.config.translation.active_target)
  const menuOpen = useAppStore((state) => state.translationTargetMenuOpen)
  const setMenuOpen = useAppStore((state) => state.setTranslationTargetMenuOpen)
  const applyPersistedConfigPatch = useAppStore((state) => state.applyPersistedConfigPatch)
  const [pendingTarget, setPendingTarget] = useState<string | null>(null)
  const [failedConfiguration, setFailedConfiguration] = useState<{
    translation: typeof currentTranslation
    epoch: string | undefined
    sessionId: number | undefined
    recordingKind: 'dictation' | 'ask' | undefined
  } | null>(null)
  const inFlight = useRef(false)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

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
    if (inFlight.current) return
    if (code === activeTarget) {
      close()
      return
    }
    setPendingTarget(code)
    setFailedConfiguration(null)
    inFlight.current = true
    const expected = useAppStore.getState().config.translation
    const expectedEpoch = useAppStore.getState().configCommit?.configEpoch
    const expectedDeadline = useAppStore.getState().recordingDeadline
    const expectedMenu = document.getElementById(MENU_ID)
    const isCurrent = () => {
      const current = useAppStore.getState()
      return (
        mounted.current &&
        current.config.translation === expected &&
        current.configCommit?.configEpoch === expectedEpoch &&
        current.recordingDeadline?.sessionId === expectedDeadline?.sessionId &&
        current.recordingDeadline?.recordingKind === expectedDeadline?.recordingKind &&
        current.pipelineState === 'recording' &&
        current.activeVoiceMode === 'translate'
      )
    }
    try {
      const translation = expectedDeadline
        ? await setActiveTranslationTarget(code, expectedDeadline.sessionId, expectedEpoch)
        : expectedEpoch !== undefined
          ? await setActiveTranslationTarget(code, undefined, expectedEpoch)
          : await setActiveTranslationTarget(code)
      if (expectedEpoch !== undefined) {
        const current = useAppStore.getState()
        if (
          !mounted.current ||
          current.configCommit?.configEpoch !== expectedEpoch ||
          current.recordingDeadline?.sessionId !== expectedDeadline?.sessionId ||
          current.recordingDeadline?.recordingKind !== expectedDeadline?.recordingKind ||
          current.pipelineState !== 'recording' ||
          current.activeVoiceMode !== 'translate' ||
          !valuesEqual(current.savedConfig?.translation, translation) ||
          !valuesEqual(current.config.translation, translation)
        ) {
          return
        }
        if (expectedMenu?.isConnected && document.getElementById(MENU_ID) === expectedMenu) close()
        return
      }
      if (!isCurrent()) return
      applyPersistedConfigPatch({ target_lang: translation.active_target, translation })
      if (expectedMenu?.isConnected && document.getElementById(MENU_ID) === expectedMenu) close()
    } catch {
      if (!isCurrent()) return
      console.error('Failed to switch translation target')
      setFailedConfiguration({
        translation: expected,
        epoch: expectedEpoch,
        sessionId: expectedDeadline?.sessionId,
        recordingKind: expectedDeadline?.recordingKind,
      })
    } finally {
      inFlight.current = false
      if (mounted.current) setPendingTarget(null)
    }
  }

  return (
    <>
      <div
        id={MENU_ID}
        role="menu"
        aria-label={t('capsule.translationTargets')}
        onMouseLeave={onMouseLeave}
        onPointerDown={(event) => event.stopPropagation()}
        onPointerUp={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
          event.preventDefault()
          event.stopPropagation()
          const items = [
            ...event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'),
          ]
          if (!items.length) return
          const current = items.indexOf(document.activeElement as HTMLButtonElement)
          const next =
            event.key === 'Home'
              ? 0
              : event.key === 'End'
                ? items.length - 1
                : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
          items[next]?.focus()
        }}
        className="absolute bottom-full left-0 z-50 w-full min-w-[180px] pb-1"
      >
        <div className="rounded-[12px] border border-white/20 bg-[#272525] p-1 text-[#f2f1f0] shadow-lg">
          {targets.length < 5 && (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                close()
                void openCapsuleSettings('#/models?section=translation').catch(() =>
                  console.error('Failed to open language settings'),
                )
              }}
              className="w-full rounded-[8px] border-none bg-transparent px-2 py-1.5 text-left text-[14px] text-inherit hover:bg-white/15"
            >
              {t('translationTargets.addLanguage')}
            </button>
          )}
          {[...targets].reverse().map((code) => (
            <button
              key={code}
              type="button"
              role="menuitemradio"
              aria-checked={activeTarget === code}
              disabled={pendingTarget !== null}
              onClick={() => void selectTarget(code)}
              className="flex min-h-8 w-full items-center gap-2 rounded-[8px] border-none bg-transparent px-2 text-left text-[14px] text-inherit hover:bg-white/15 disabled:opacity-60"
            >
              <span className="flex h-4 w-4 items-center justify-center">
                {activeTarget === code && <Check size={13} />}
              </span>
              <span className="min-w-0 flex-1 truncate">{languageLabel(code)}</span>
            </button>
          ))}
          {failedConfiguration !== null &&
            failedConfiguration.translation === currentTranslation &&
            failedConfiguration.epoch === configEpoch &&
            failedConfiguration.sessionId === recordingDeadline?.sessionId &&
            failedConfiguration.recordingKind === recordingDeadline?.recordingKind && (
              <p role="alert" className="m-0 px-2 py-1 text-[12px] text-[#ff9e6c]">
                {t('capsule.errors.unknown')}
              </p>
            )}
        </div>
      </div>
    </>
  )
}
