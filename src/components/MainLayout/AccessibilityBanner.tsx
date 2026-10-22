import { useState, useEffect, useCallback, useRef } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Globe2, Loader2, ShieldAlert, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { needsMacAccessibility } from '../../lib/accessibility'
import {
  checkAccessibilityPermission,
  requestAccessibilityPermission,
  requestBrowserAccess,
  resumeHotkey,
  waitForAccessibilityPermission,
} from '../../lib/tauri'

export function AccessibilityBanner() {
  const { t } = useTranslation()
  const accessibilityTrusted = useAppStore((s) => s.accessibilityTrusted)
  const setAccessibilityTrusted = useAppStore((s) => s.setAccessibilityTrusted)
  const config = useAppStore((s) => s.config)
  const lastContext = useAppStore((s) => s.lastContext)
  const setLastContext = useAppStore((s) => s.setLastContext)
  const [accessibilityDismissed, setAccessibilityDismissed] = useState(false)
  const [browserDismissedTarget, setBrowserDismissedTarget] = useState<string | null>(null)
  const [requestingBrowserAccess, setRequestingBrowserAccess] = useState(false)
  const accessibilityRecoveryStarted = useRef(false)
  const accessibilityRecovery = useRef<Promise<void> | null>(null)
  const macAccessibilityNeeded = needsMacAccessibility(config)

  const showAccessibility =
    macAccessibilityNeeded && !accessibilityTrusted && !accessibilityDismissed
  const browserTarget = lastContext?.browserTarget ?? null
  const showBrowserAccess = Boolean(
    !showAccessibility &&
    config.polish_enabled &&
    config.context_adaptation_enabled &&
    lastContext?.profileId === 'general.browser' &&
    lastContext.browserAccessStatus === 'needs_permission' &&
    browserTarget &&
    browserDismissedTarget !== browserTarget,
  )
  const show = showAccessibility || showBrowserAccess

  // Re-show banner when accessibility error fires (user just hit the issue)
  useEffect(() => {
    if (!accessibilityTrusted) {
      setAccessibilityDismissed(false)
      accessibilityRecoveryStarted.current = false
    }
  }, [accessibilityTrusted])

  const applyAccessibilityPermission = useCallback(
    async (trusted: boolean) => {
      if (trusted && !accessibilityRecoveryStarted.current) {
        // Permission can be granted directly in System Settings. Rebuild the
        // native monitor too: HID may have been unavailable at first startup.
        accessibilityRecoveryStarted.current = true
        accessibilityRecovery.current = resumeHotkey().catch((error) => {
          accessibilityRecoveryStarted.current = false
          console.error('Failed to re-register hotkeys after Accessibility grant:', error)
        })
      }
      if (trusted) await accessibilityRecovery.current
      setAccessibilityTrusted(trusted)
    },
    [setAccessibilityTrusted],
  )

  useEffect(() => {
    if (lastContext?.browserAccessStatus !== 'needs_permission') {
      setBrowserDismissedTarget(null)
    }
  }, [lastContext?.browserAccessStatus])

  useEffect(() => {
    if (macAccessibilityNeeded && !accessibilityTrusted) {
      let cancelled = false
      let checking = false
      const checkPermission = async () => {
        if (checking) return
        checking = true
        try {
          const trusted = await checkAccessibilityPermission()
          if (!cancelled) await applyAccessibilityPermission(trusted)
        } catch (error) {
          console.error('Failed to check Accessibility permission:', error)
        } finally {
          checking = false
        }
      }
      window.addEventListener('focus', checkPermission)
      // The user may return directly to their editor after granting access.
      const timer = window.setInterval(checkPermission, 1_000)
      return () => {
        cancelled = true
        window.removeEventListener('focus', checkPermission)
        window.clearInterval(timer)
      }
    }
  }, [macAccessibilityNeeded, accessibilityTrusted, applyAccessibilityPermission])

  const handleGrant = useCallback(async () => {
    await requestAccessibilityPermission()
    const trusted = await waitForAccessibilityPermission()
    await applyAccessibilityPermission(trusted)
  }, [applyAccessibilityPermission])

  const handleBrowserGrant = useCallback(async () => {
    if (!browserTarget || !lastContext) return
    setRequestingBrowserAccess(true)
    try {
      const status = await requestBrowserAccess(browserTarget)
      setLastContext({ ...lastContext, browserAccessStatus: status })
    } catch (error) {
      console.error('Failed to request Browser Access:', error)
    } finally {
      setRequestingBrowserAccess(false)
    }
  }, [browserTarget, lastContext, setLastContext])

  return (
    <AnimatePresence>
      {show && (
        <motion.div
          initial={{ height: 0, opacity: 0 }}
          animate={{ height: 'auto', opacity: 1 }}
          exit={{ height: 0, opacity: 0 }}
          transition={{ duration: 0.2 }}
          className="overflow-hidden"
        >
          <div className="flex items-center gap-2 px-4 py-2 bg-amber-500/10 border-b border-amber-500/20">
            {showAccessibility ? (
              <ShieldAlert size={14} className="text-amber-500 shrink-0" />
            ) : (
              <Globe2 size={14} className="text-amber-500 shrink-0" />
            )}
            <span className="text-[12px] text-text-primary flex-1">
              {showAccessibility
                ? `${t('settings.accessibilityRequired')} - ${t('settings.accessibilityPermission')}`
                : t('settings.browserAccessHint')}
            </span>
            <button
              onClick={showAccessibility ? handleGrant : handleBrowserGrant}
              disabled={requestingBrowserAccess}
              className="px-3 py-1 text-[11px] font-medium text-white bg-accent rounded-full border-none cursor-pointer hover:bg-accent-hover transition-colors shrink-0"
            >
              {requestingBrowserAccess && !showAccessibility && (
                <Loader2 size={11} className="mr-1 inline animate-spin" />
              )}
              {showAccessibility ? t('settings.grantPermission') : t('settings.allowBrowserAccess')}
            </button>
            <button
              type="button"
              onClick={() => {
                if (showAccessibility) setAccessibilityDismissed(true)
                else setBrowserDismissedTarget(browserTarget)
              }}
              aria-label={t('common.close')}
              title={t('common.close')}
              className="grid h-6 w-6 shrink-0 place-items-center rounded-[6px] border-none bg-transparent text-text-tertiary hover:bg-bg-hover hover:text-text-secondary"
            >
              <X size={13} />
            </button>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}
