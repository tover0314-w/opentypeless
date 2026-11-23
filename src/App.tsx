import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import i18n from './i18n'
import { useTauriEvents } from './hooks/useTauriEvents'
import { useConfigSync } from './hooks/useConfigSync'
import { useHistorySyncScheduler } from './hooks/useHistorySyncScheduler'
import { useMainUpdateEvents } from './hooks/useUpdateEvents'
import { useInteractionSounds } from './hooks/useInteractionSounds'
import { useTheme } from './hooks/useTheme'
import { useAppStore } from './stores/appStore'
import { useAuthStore } from './stores/authStore'
import { useRoute } from './lib/router'
import {
  loadOnboardingCompleted,
  getHistory,
  getDictionary,
  getCorrectionRules,
  checkAccessibilityPermission,
  getPlatformCapabilities,
  getHotkeyRegistrationError,
} from './lib/tauri'
import { initDeepLinkListener } from './lib/deep-link'
import { readPendingDesktopCheckout } from './lib/desktop-checkout-intent'
import { refreshDesktopCheckout } from './lib/desktop-checkout-order'
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

function CapsuleApp() {
  useConfigSync()
  useTauriEvents({ legacyConfigEvents: false })
  useTheme()
  useInteractionSounds()

  // Window show is handled by useCapsuleResize (setSize → setPosition → show),
  // which works on both Windows and macOS. The previous rAF-based show approach
  // failed on macOS because WKWebView pauses requestAnimationFrame in hidden windows.
  return <Capsule />
}

function AskApp() {
  useConfigSync()
  useTheme()

  return (
    <>
      <AskPanel />
      <ToastContainer />
    </>
  )
}

function MainApp() {
  const { t } = useTranslation()
  const configSyncStatus = useConfigSync()
  useHistorySyncScheduler()
  useTauriEvents({ legacyConfigEvents: false })
  useMainUpdateEvents()
  useTheme()

  const onboardingCompleted = useAppStore((s) => s.onboardingCompleted)
  const setOnboardingCompleted = useAppStore((s) => s.setOnboardingCompleted)
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
        if (done) {
          try {
            const [
              history,
              dictionary,
              correctionRules,
              platformCapabilities,
              hotkeyRegistrationError,
            ] = await Promise.all([
              getHistory(200, 0),
              getDictionary(),
              getCorrectionRules(),
              getPlatformCapabilities(),
              getHotkeyRegistrationError(),
            ])
            if (!active) return
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
    setHistory,
    setDictionary,
    setCorrectionRules,
    setAccessibilityTrusted,
    setPlatformCapabilities,
    setHotkeyRegistrationError,
  ])

  const user = useAuthStore((s) => s.user)
  const authLoading = useAuthStore((s) => s.loading)
  const hasPendingCheckout = useAuthStore((s) => s.checkoutPending)

  useEffect(() => {
    if (!loaded || authLoading || !user || route !== 'account') return
    if (readPendingDesktopCheckout(localStorage)) navigate('upgrade')
  }, [authLoading, loaded, navigate, route, user])

  useEffect(() => {
    if (!loaded || !user) return

    let refreshInFlight = false
    const refreshPendingCheckout = () => {
      const { checkoutPending } = useAuthStore.getState()
      if (refreshInFlight || document.visibilityState === 'hidden') return
      const tracked = refreshDesktopCheckout(user.id)
      if (!tracked && !shouldRefreshSubscriptionOnFocus(checkoutPending)) return
      refreshInFlight = true
      void (tracked ?? useAuthStore.getState().refreshSubscription()).finally(() => {
        refreshInFlight = false
      })
    }

    refreshPendingCheckout()
    const poll = hasPendingCheckout ? window.setInterval(refreshPendingCheckout, 15_000) : undefined
    window.addEventListener('focus', refreshPendingCheckout)
    document.addEventListener('visibilitychange', refreshPendingCheckout)
    return () => {
      window.clearInterval(poll)
      window.removeEventListener('focus', refreshPendingCheckout)
      document.removeEventListener('visibilitychange', refreshPendingCheckout)
    }
  }, [hasPendingCheckout, loaded, route, user])

  if (!loaded || configSyncStatus === 'loading')
    return (
      <div className="flex items-center justify-center h-screen">
        <span className="text-text-tertiary text-[13px]">{i18n.t('common.loading')}</span>
      </div>
    )
  if (loadError || configSyncStatus === 'error')
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
