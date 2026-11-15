import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { GeneralPane } from './GeneralPane'
import { ScenesPane } from './ScenesPane'
import { AboutPane } from './AboutPane'
import { HelpPane } from './HelpPane'
import { AccountPage } from '../AccountPage'
import { useAutoSaveConfig } from './shared/useAutoSaveConfig'
import {
  SETTINGS_TABS,
  paneFromHash,
  updateCheckFromHash,
  type SettingsTabId,
} from './settingsTabs'

/** Onboarding step where the hands-on practice ("Try it") begins. */
const EXPLORE_START_STEP = 6

interface Props {
  initialTab?: SettingsTabId
  onClose?: () => void
}

export function Settings({ initialTab, onClose }: Props) {
  const [activeTab, setActiveTab] = useState<SettingsTabId>(
    () => paneFromHash() ?? initialTab ?? 'general',
  )
  const [updateCheck, setUpdateCheck] = useState(updateCheckFromHash)
  const contentRef = useRef<HTMLDivElement | null>(null)
  const { t } = useTranslation()
  useAutoSaveConfig()

  useEffect(() => {
    const syncRequestedPane = () => {
      const requested = paneFromHash()
      if (requested) setActiveTab(requested)
      setUpdateCheck(updateCheckFromHash())
    }
    window.addEventListener('hashchange', syncRequestedPane)
    return () => window.removeEventListener('hashchange', syncRequestedPane)
  }, [])

  useEffect(() => {
    contentRef.current?.scrollTo?.({ top: 0 })
  }, [activeTab])

  const activeLabel = t(SETTINGS_TABS.find((tab) => tab.id === activeTab)!.labelKey)
  const ActiveIcon = SETTINGS_TABS.find((tab) => tab.id === activeTab)!.icon

  return (
    <div className="relative flex h-full min-h-0 w-full flex-col overflow-hidden bg-bg-elevated text-text-primary">
      <header className="flex shrink-0 items-center justify-between border-b border-border-thin px-6 py-5">
        <h2 className="m-0 text-[16px] font-semibold">{t('settingsModal.title')}</h2>
        {onClose && (
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="grid h-7 w-7 place-items-center rounded-[8px] border-none bg-transparent text-text-tertiary hover:bg-fill-hover hover:text-text-primary"
          >
            <X size={18} />
          </button>
        )}
      </header>
      <div className="flex min-h-0 flex-1">
        <nav
          aria-label={t('settingsModal.title')}
          className="flex w-[160px] shrink-0 flex-col gap-0.5 border-r border-border-thin bg-fill-thin px-3 py-4"
        >
          {SETTINGS_TABS.map((tab) => {
            const Icon = tab.icon
            const isActive = activeTab === tab.id
            return (
              <button
                key={tab.id}
                type="button"
                aria-current={isActive ? 'page' : undefined}
                onClick={() => setActiveTab(tab.id)}
                className={`flex w-full items-center gap-2.5 rounded-[10px] border-none px-3 py-2.5 text-left text-[14px] transition-colors cursor-pointer ${
                  isActive
                    ? 'bg-fill-selected text-text-primary'
                    : 'bg-transparent text-text-secondary hover:bg-fill-hover hover:text-text-primary'
                }`}
              >
                <Icon size={16} strokeWidth={1.8} className="shrink-0" aria-hidden="true" />
                <span>{t(tab.labelKey)}</span>
              </button>
            )
          })}
        </nav>

        <div
          ref={contentRef}
          className="settings-dialog-content min-h-0 min-w-0 flex-1 overflow-y-auto overflow-x-hidden px-6 py-5"
        >
          <h2 className="mb-5 flex items-center gap-2 text-[20px] font-semibold leading-7 text-text-primary">
            {activeTab === 'personalization' && (
              <span className="text-accent">
                <ActiveIcon size={20} className="shrink-0" aria-hidden="true" />
              </span>
            )}
            {activeLabel}
          </h2>
          <div key={activeTab} className="w-full">
            {activeTab === 'account' && <AccountPage embedded />}
            {activeTab === 'general' && <GeneralPane />}
            {activeTab === 'personalization' && <ScenesPane />}
            {activeTab === 'about' && <AboutPane autoCheck={updateCheck} />}
            {activeTab === 'help' && (
              <HelpPane
                onExploreFeatures={() => {
                  // Replays the practice steps without touching saved providers or shortcuts.
                  const { setOnboardingStep, setOnboardingCompleted } = useAppStore.getState()
                  setOnboardingStep(EXPLORE_START_STEP)
                  setOnboardingCompleted(false)
                  onClose?.()
                }}
              />
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/** Settings rendered as a modal above the current page. */
export function SettingsModal({ onClose }: { onClose: () => void }) {
  return (
    <ModalFrame onClose={onClose}>
      <Settings onClose={onClose} />
    </ModalFrame>
  )
}

function modalElementVisible(element: HTMLElement) {
  if (element.closest('[hidden], [inert]')) return false
  let ancestor: HTMLElement | null = element
  while (ancestor) {
    const style = window.getComputedStyle(ancestor)
    if (style.display === 'none' || style.visibility === 'hidden') return false
    ancestor = ancestor.parentElement
  }
  return true
}

function otherVisibleModalPresent(panel: HTMLElement) {
  return Array.from(
    document.querySelectorAll<HTMLElement>('[role="dialog"], [role="alertdialog"]'),
  ).some((element) => element !== panel.parentElement && modalElementVisible(element))
}

export function ModalFrame({
  children,
  onClose,
  title,
  maxWidth = 940,
  maxHeight = 652,
}: {
  children: React.ReactNode
  onClose: () => void
  title?: string
  maxWidth?: number
  maxHeight?: number
}) {
  const { t } = useTranslation()
  const panelRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    const panel = panelRef.current
    if (!panel) return
    const previousFocus = document.activeElement
    const otherModalPresent = () => otherVisibleModalPresent(panel)
    const controls = () =>
      Array.from(
        panel.querySelectorAll<HTMLElement>(
          'button, input:not([type="hidden"]), textarea, select, [href], [tabindex]',
        ),
      ).filter(
        (element) =>
          element.tabIndex >= 0 && !element.matches(':disabled') && modalElementVisible(element),
      )
    if (!otherModalPresent() && !panel.contains(document.activeElement)) {
      ;(controls()[0] ?? panel).focus()
    }
    const keydown = (event: KeyboardEvent) => {
      if (
        event.defaultPrevented ||
        event.isComposing ||
        event.keyCode === 229 ||
        otherModalPresent()
      )
        return
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopImmediatePropagation()
        closeRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = controls()
      const first = items[0]
      const last = items[items.length - 1]
      if (!first) {
        event.preventDefault()
        panel.focus()
      } else if (!items.some((element) => element === document.activeElement)) {
        event.preventDefault()
        ;(event.shiftKey ? last : first).focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', keydown)
    return () => {
      window.removeEventListener('keydown', keydown)
      if (
        previousFocus instanceof HTMLElement &&
        previousFocus.isConnected &&
        !previousFocus.matches(':disabled') &&
        modalElementVisible(previousFocus) &&
        !otherModalPresent()
      )
        previousFocus.focus()
    }
  }, [])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title ?? t('settingsModal.title')}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/25 p-4 backdrop-blur-[3px]"
      onMouseDown={(event) => {
        if (
          event.target === event.currentTarget &&
          panelRef.current &&
          !otherVisibleModalPresent(panelRef.current)
        )
          onClose()
      }}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        className="dialog-surface h-[calc(100dvh-32px)] w-full overflow-hidden rounded-[20px]"
        style={{ maxWidth, maxHeight }}
      >
        {children}
      </div>
    </div>
  )
}
