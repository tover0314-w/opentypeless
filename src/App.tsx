import { useEffect, useState } from 'react'
import i18n from './i18n'
import { useTauriEvents } from './hooks/useTauriEvents'
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
import { Settings } from './components/Settings'
import { History } from './components/History'
import { Onboarding } from './components/Onboarding'
import { MainLayout } from './components/MainLayout'
import { HomePage } from './components/HomePage'
import { UpgradePage } from './components/UpgradePage'
import { AccountPage } from './components/AccountPage'
import { AskPanel } from './components/AskPanel'
import { ToastContainer } from './components/Toast'
import { UpdatePrompt } from './components/UpdatePrompt'

function CapsuleApp() {
  useTauriEvents()
  useTheme()

  const setConfig = useAppStore((s) => s.setConfig)

  useEffect(() => {
    // Load config so DurationTimer gets the correct max_recording_seconds
    getConfig()
      .then((config) => {
        setConfig(config)
        // Restore UI language from config
        if (config.ui_language && config.ui_language !== i18n.language) {
          i18n.changeLanguage(config.ui_language)
          localStorage.setItem('ui_language', config.ui_language)
        }
      })
      .catch((e) => {
        console.error('Failed to load config in capsule:', e)
      })
  }, [setConfig])

  // Window show is handled by useCapsuleResize (setSize → setPosition → show),
  // which works on both Windows and macOS. The previous rAF-based show approach
  // failed on macOS because WKWebView pauses requestAnimationFrame in hidden windows.
  return <Capsule />
}

function AskApp() {
  useTheme()
  const setConfig = useAppStore((s) => s.setConfig)

  useEffect(() => {
    useAuthStore.getState().initialize()
    getConfig()
      .then((config) => {
        setConfig(config)
        if (config.ui_language && config.ui_language !== i18n.language) {
          i18n.changeLanguage(config.ui_language)
          localStorage.setItem('ui_language', config.ui_language)
        }
      })
      .catch((e) => {
        console.error('Failed to load config in Ask app:', e)
      })
  }, [setConfig])

  return (
    <>
      <AskPanel />
      <ToastContainer />
    </>
  )
}

function MainApp() {
  useTauriEvents()
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
  const { route, navigate } = useRoute()

  useEffect(() => {
    loadOnboardingCompleted().then(async (done) => {
      setOnboardingCompleted(done)
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
              setAccessibilityTrusted(trusted)
            })
          }
          // Restore UI language from config
          if (config.ui_language && config.ui_language !== i18n.language) {
            i18n.changeLanguage(config.ui_language)
            localStorage.setItem('ui_language', config.ui_language)
          }
        } catch (e) {
          console.error('Failed to load initial data:', e)
          setLoadError(true)
        }
      }
      setLoaded(true)
    })

    // Initialize auth session (non-blocking)
    useAuthStore.getState().initialize()

    // Initialize deep-link listener
    initDeepLinkListener()
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
        <span className="text-text-tertiary text-[13px]">Loading...</span>
      </div>
    )
  if (loadError)
    return (
      <div className="flex flex-col items-center justify-center h-screen gap-3">
        <span className="text-error text-[13px]">Failed to load application data.</span>
        <button
          onClick={() => window.location.reload()}
          className="px-4 py-2 bg-accent text-white rounded-[10px] text-[13px] border-none cursor-pointer hover:bg-accent-hover transition-colors"
        >
          Retry
        </button>
      </div>
    )
  if (!onboardingCompleted) return <Onboarding />

  return (
    <MainLayout>
      {route === 'home' && <HomePage />}
      {route === 'settings' && <Settings />}
      {route === 'history' && <History />}
      {route === 'upgrade' && <UpgradePage />}
      {route === 'account' && <AccountPage />}
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
