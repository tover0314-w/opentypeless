import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import i18n from './i18n'
import { useTauriEvents } from './hooks/useTauriEvents'
import { useMainUpdateEvents } from './hooks/useUpdateEvents'
import { useInteractionSounds } from './hooks/useInteractionSounds'
import { useTheme } from './hooks/useTheme'
import { useAppStore } from './stores/appStore'
import { useAuthStore } from './stores/authStore'
import { useRoute } from './lib/router'
import {
  loadOnboardingCompleted,
  getConfig,
  getHistory,
  getDictionary,
  getCorrectionRules,
  checkAccessibilityPermission,
  getPlatformCapabilities,
  getHotkeyRegistrationError,
} from './lib/tauri'
import { initDeepLinkListener } from './lib/deep-link'
import { readPendingDesktopCheckout } from './lib/desktop-checkout-intent'
import { shouldRefreshSubscriptionOnFocus } from './lib/subscription-refresh-policy'
import { Capsule } from './components/Capsule'
import { SettingsModal, ModalFrame } from './components/Settings'
import { PageContent } from './components/PageContent'
import { Onboarding } from './components/Onboarding'
import { MainLayout } from './components/MainLayout'
import { UpgradePage } from './components/UpgradePage'
import { AccountPage } from './components/AccountPage'
import { AskPanel } from './components/AskPanel'
import { ToastContainer } from './components/Toast'
import { UpdatePrompt } from './components/UpdatePrompt'
import { valuesEqual } from './lib/configValuesEqual'
import { listen } from '@tauri-apps/api/event'
import type { AppConfig } from './stores/appStore'

function loadAuxiliaryConfig(failureMessage: string) {
  let active = true
  const changedKeys = new Set<string>()
  const unsubscribe = useAppStore.subscribe((state, previous) => {
    for (const key of Object.keys(state.config) as (keyof typeof state.config)[]) {
      if (!valuesEqual(state.config[key], previous.config[key])) changedKeys.add(key)
    }
  })
  getConfig()
    .then((config) => {
      if (!active) return
      unsubscribe()
      const current = useAppStore.getState().config
      const preserved = Object.fromEntries(
        Object.entries(current).filter(([key]) => changedKeys.has(key)),
      )
      useAppStore.getState().setConfig({ ...config, ...preserved })
      const language = useAppStore.getState().config.ui_language
      if (language && language !== i18n.language) {
        i18n.changeLanguage(language)
        localStorage.setItem('ui_language', language)
      }
    })
    .catch(() => {
      unsubscribe()
      if (active) console.error(failureMessage)
    })
  return () => {
    active = false
    unsubscribe()
  }
}

function CapsuleApp() {
  useTauriEvents()
  useTheme()
  useInteractionSounds()

  useEffect(() => loadAuxiliaryConfig('Failed to load config in capsule'), [])

  // Window show is handled by useCapsuleResize (setSize → setPosition → show),
  // which works on both Windows and macOS. The previous rAF-based show approach
  // failed on macOS because WKWebView pauses requestAnimationFrame in hidden windows.
  return <Capsule />
}

function AskApp() {
  useTheme()
  useEffect(() => loadAuxiliaryConfig('Failed to load config in Ask app'), [])
  useEffect(() => {
    let active = true
    let unlisten: (() => void) | undefined
    const release = (callback: () => void) => {
      try {
        Promise.resolve(callback()).catch(() => {})
      } catch {
        return
      }
    }
    listen<Partial<AppConfig>>('config:patch', ({ payload }) => {
      if (!active) return
      useAppStore.getState().applyPersistedConfigPatch(payload)
      if (payload.ui_language) {
        i18n.changeLanguage(payload.ui_language)
        localStorage.setItem('ui_language', payload.ui_language)
      }
    })
      .then((callback) => {
        if (active) unlisten = callback
        else release(callback)
      })
      .catch(() => {
        if (active) console.error('Failed to listen for Ask configuration changes')
      })
    return () => {
      active = false
      if (unlisten) release(unlisten)
    }
  }, [])

  return (
    <>
      <AskPanel />
      <ToastContainer />
    </>
  )
}

function MainApp() {
  const { t } = useTranslation()
  useTauriEvents()
  useMainUpdateEvents()
  useTheme()

  const onboardingCompleted = useAppStore((s) => s.onboardingCompleted)
  const setOnboardingCompleted = useAppStore((s) => s.setOnboardingCompleted)
  const setConfig = useAppStore((s) => s.setConfig)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const setHistory = useAppStore((s) => s.setHistory)
  const setDictionary = useAppStore((s) => s.setDictionary)
  const setCorrectionRules = useAppStore((s) => s.setCorrectionRules)
  const setAccessibilityTrusted = useAppStore((s) => s.setAccessibilityTrusted)
  const setPlatformCapabilities = useAppStore((s) => s.setPlatformCapabilities)
  const setHotkeyRegistrationError = useAppStore((s) => s.setHotkeyRegistrationError)
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState(false)
  const { route, page, modal, navigate, closeModal } = useRoute()

  useEffect(() => {
    let active = true
    loadOnboardingCompleted()
      .then(async (done) => {
        if (!active) return
        setOnboardingCompleted(done)
        if (!done) {
          try {
            const config = await getConfig()
            if (!active) return
            setConfig(config)
            setSavedConfig(config)
            if (config.ui_language && config.ui_language !== i18n.language) {
              await i18n.changeLanguage(config.ui_language)
              if (!active) return
              localStorage.setItem('ui_language', config.ui_language)
            }
          } catch {
            if (active) setLoadError(true)
          }
        }
        if (done) {
          try {
            const [
              config,
              history,
              dictionary,
              correctionRules,
              platformCapabilities,
              hotkeyRegistrationError,
            ] = await Promise.all([
              getConfig(),
              getHistory(200, 0),
              getDictionary(),
              getCorrectionRules(),
              getPlatformCapabilities(),
              getHotkeyRegistrationError(),
            ])
            if (!active) return
            setConfig(config)
            setSavedConfig(config)
            setHistory(history)
            setDictionary(dictionary)
            setCorrectionRules(correctionRules)
            setPlatformCapabilities(platformCapabilities)
            setHotkeyRegistrationError(hotkeyRegistrationError)
            // Check macOS Accessibility permission
            if (navigator.platform.toUpperCase().indexOf('MAC') >= 0) {
              checkAccessibilityPermission().then((trusted) => {
                if (active) setAccessibilityTrusted(trusted)
              })
            }
            // Restore UI language from config
            if (config.ui_language && config.ui_language !== i18n.language) {
              i18n.changeLanguage(config.ui_language)
              localStorage.setItem('ui_language', config.ui_language)
            }
          } catch (e) {
            if (!active) return
            console.error('Failed to load initial data:', e)
            setLoadError(true)
          }
        }
        if (active) setLoaded(true)
      })
      .catch(() => {
        if (!active) return
        setLoadError(true)
        setLoaded(true)
      })

    // Initialize auth session (non-blocking)
    useAuthStore.getState().initialize()

    // Initialize deep-link listener
    const stopDeepLinkListener = initDeepLinkListener()
    return () => {
      active = false
      stopDeepLinkListener()
    }
  }, [
    setOnboardingCompleted,
    setConfig,
    setSavedConfig,
    setHistory,
    setDictionary,
    setCorrectionRules,
    setAccessibilityTrusted,
    setPlatformCapabilities,
    setHotkeyRegistrationError,
  ])

  const user = useAuthStore((s) => s.user)
  const authLoading = useAuthStore((s) => s.loading)

  useEffect(() => {
    if (!loaded || authLoading || !user || route !== 'account') return
    if (readPendingDesktopCheckout(localStorage)) navigate('upgrade')
  }, [authLoading, loaded, navigate, route, user])

  // Subscription changes are event-driven. Focus refresh is reserved for a pending checkout.
  useEffect(() => {
    if (!loaded || !user) return

    let refreshInFlight = false
    const refreshPendingCheckout = () => {
      const { checkoutPending } = useAuthStore.getState()
      if (!shouldRefreshSubscriptionOnFocus(checkoutPending) || refreshInFlight) return
      refreshInFlight = true
      void useAuthStore
        .getState()
        .refreshSubscription()
        .finally(() => {
          refreshInFlight = false
        })
    }

    window.addEventListener('focus', refreshPendingCheckout)

    return () => {
      window.removeEventListener('focus', refreshPendingCheckout)
    }
  }, [loaded, user])

  if (!loaded)
    return (
      <div className="flex items-center justify-center h-screen">
        <span className="text-text-tertiary text-[13px]">{i18n.t('common.loading')}</span>
      </div>
    )
  if (loadError)
    return (
      <div className="flex flex-col items-center justify-center h-screen gap-3">
        <span className="text-error text-[13px]">{i18n.t('common.loadFailed')}</span>
        <button
          onClick={() => window.location.reload()}
          className="btn-accent px-4 py-2 text-[13px] cursor-pointer"
        >
          {i18n.t('common.retry')}
        </button>
      </div>
    )
  if (!onboardingCompleted) return <Onboarding />

  return (
    <MainLayout>
      <PageContent page={page} />
      {modal === 'settings' && <SettingsModal onClose={closeModal} />}
      {modal === 'account' && (
        <ModalFrame onClose={closeModal} maxWidth={560} title={t('account.title')}>
          <div className="h-full overflow-y-auto bg-bg-elevated">
            <AccountPage />
          </div>
        </ModalFrame>
      )}
      {modal === 'upgrade' && (
        <ModalFrame onClose={closeModal} maxWidth={720} title={t('upgrade.title')}>
          <div className="h-full overflow-y-auto bg-bg-elevated">
            <UpgradePage />
          </div>
        </ModalFrame>
      )}
      <UpdatePrompt />
      <ToastContainer />
    </MainLayout>
  )
}

function App() {
  // Capsule window loads with #capsule hash — detect synchronously, no race condition
  if (window.location.hash === '#capsule') return <CapsuleApp />
  if (window.location.hash === '#ask') return <AskApp />
  return <MainApp />
}

export default App
