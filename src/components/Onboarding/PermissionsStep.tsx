import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Mic, MousePointer2, Globe2, Speech, AlertCircle } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { isMacPlatform, needsMacAccessibility } from '../../lib/accessibility'
import {
  checkAccessibilityPermission,
  requestAccessibilityPermission,
  resumeHotkey,
  waitForAccessibilityPermission,
} from '../../lib/tauri'

type PermissionState = 'ready' | 'needed' | 'later'
type PermissionRowModel = {
  id: string
  icon: React.ComponentType<{ size?: number; className?: string }>
  title: string
  desc: string
  state: PermissionState | null
  action?: 'accessibility' | null
}

export function PermissionsStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const [accessibilityTrusted, setAccessibilityTrusted] = useState<boolean | null>(null)
  const [requestFailed, setRequestFailed] = useState(false)
  const [requesting, setRequesting] = useState(false)
  const pendingRequest = useRef(false)
  const requestGeneration = useRef(0)
  const textOutputNeedsPermission = needsMacAccessibility(config)

  useEffect(() => {
    if (!textOutputNeedsPermission) {
      setAccessibilityTrusted(true)
      return
    }
    let active = true
    setAccessibilityTrusted(null)
    checkAccessibilityPermission()
      .then((trusted) => {
        if (active) setAccessibilityTrusted(trusted === true)
      })
      .catch(() => {
        if (active) setAccessibilityTrusted(false)
      })
    return () => {
      active = false
    }
  }, [textOutputNeedsPermission])

  const rows = useMemo(() => {
    const items: PermissionRowModel[] = [
      {
        id: 'microphone',
        icon: Mic,
        title: t('onboarding.permissions.microphone'),
        desc: t('onboarding.permissions.microphoneDesc'),
        state: 'later' as PermissionState,
      },
      {
        id: 'textOutput',
        icon: MousePointer2,
        title: t('onboarding.permissions.textOutput'),
        desc: t('onboarding.permissions.textOutputDesc'),
        state: !textOutputNeedsPermission
          ? 'ready'
          : accessibilityTrusted === null
            ? null
            : accessibilityTrusted
              ? 'ready'
              : 'needed',
        action:
          textOutputNeedsPermission && (accessibilityTrusted === false || requestFailed)
            ? 'accessibility'
            : null,
      },
      {
        id: 'browserApps',
        icon: Globe2,
        title: t('onboarding.permissions.browserApps'),
        desc: t('onboarding.permissions.browserAppsDesc'),
        state: isMacPlatform() ? ('later' as PermissionState) : ('ready' as PermissionState),
      },
    ]
    if (config.stt_provider === 'apple-speech') {
      items.push({
        id: 'appleSpeech',
        icon: Speech,
        title: t('onboarding.permissions.appleSpeech'),
        desc: t('onboarding.permissions.appleSpeechDesc'),
        state: 'later' as PermissionState,
        action: null,
      })
    }
    return items
  }, [accessibilityTrusted, config.stt_provider, requestFailed, t, textOutputNeedsPermission])

  const handleAccessibility = async () => {
    if (pendingRequest.current) return
    const generation = requestGeneration.current
    pendingRequest.current = true
    setRequesting(true)
    setRequestFailed(false)
    try {
      const trusted =
        accessibilityTrusted === true
          ? await checkAccessibilityPermission()
          : await requestAccessibilityPermission().then(() => waitForAccessibilityPermission())
      if (generation !== requestGeneration.current) return
      if (typeof trusted !== 'boolean') throw new Error('Invalid permission response')
      setAccessibilityTrusted(trusted)
      if (trusted) await resumeHotkey()
    } catch {
      if (generation === requestGeneration.current) setRequestFailed(true)
    } finally {
      if (generation === requestGeneration.current) {
        pendingRequest.current = false
        setRequesting(false)
      }
    }
  }
  useEffect(() => {
    setRequesting(false)
    return () => {
      requestGeneration.current += 1
      pendingRequest.current = false
    }
  }, [textOutputNeedsPermission])

  return (
    <div className="space-y-3">
      <p className="text-[13px] leading-relaxed text-text-secondary">
        {t('onboarding.permissions.subtitle')}
      </p>
      {requestFailed && (
        <p role="alert" className="text-[13px] text-error">
          {t('common.saveFailed')}
        </p>
      )}
      <div className="space-y-2">
        {rows.map((row) => (
          <PermissionRow
            key={row.id}
            icon={row.icon}
            title={row.title}
            desc={row.desc}
            state={row.state}
            action={row.action}
            onAccessibility={handleAccessibility}
            requesting={requesting}
          />
        ))}
      </div>
    </div>
  )
}

function PermissionRow({
  icon: Icon,
  title,
  desc,
  state,
  action,
  onAccessibility,
  requesting,
}: {
  icon: React.ComponentType<{ size?: number; className?: string }>
  title: string
  desc: string
  state: PermissionState | null
  action?: string | null
  onAccessibility: () => void
  requesting: boolean
}) {
  const { t } = useTranslation()
  const stateClass =
    state === 'ready'
      ? 'bg-green-500/10 text-green-600'
      : state === 'needed'
        ? 'bg-amber-500/10 text-amber-600'
        : 'bg-bg-tertiary text-text-tertiary'
  const StateIcon = state === 'ready' ? Check : state === 'needed' ? AlertCircle : null

  return (
    <div
      aria-busy={state === null}
      className="flex items-start gap-4 rounded-[12px] bg-fill-thin p-6"
    >
      <div className="grid h-7 w-7 shrink-0 place-items-center rounded-[8px] bg-bg-tertiary text-text-tertiary">
        <Icon size={14} />
      </div>
      <div className="min-w-0 flex-1">
        <p className="text-[16px] leading-5 font-medium text-text-primary">{title}</p>
        <p className="mt-4 text-[14px] leading-[22px] text-text-tertiary">{desc}</p>
      </div>
      {action === 'accessibility' ? (
        <button
          type="button"
          onClick={onAccessibility}
          disabled={requesting}
          className="shrink-0 rounded-[18px] border border-border bg-bg-primary px-3 py-1.5 text-[14px] leading-5 font-normal text-text-secondary hover:text-text-primary disabled:opacity-40"
        >
          {t('onboarding.permissions.fix')}
        </button>
      ) : state !== null ? (
        <span
          className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-1 text-[10px] font-medium ${stateClass}`}
        >
          {StateIcon && <StateIcon size={10} />}
          {t(`onboarding.permissions.status.${state}`)}
        </span>
      ) : null}
    </div>
  )
}
