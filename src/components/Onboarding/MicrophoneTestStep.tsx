import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import {
  getMicrophonePreviewLevel,
  startMicrophonePreview,
  stopMicrophonePreview,
} from '../../lib/tauri'

export function MicrophoneTestStep({
  onContinue,
  onLevelChange,
}: {
  onContinue: () => void
  onLevelChange?: (level: number) => void
}) {
  const { t } = useTranslation()
  const device = useAppStore((state) => state.config.microphone_device)
  const [running, setRunning] = useState(false)
  const [passed, setPassed] = useState(false)
  const [failed, setFailed] = useState(false)
  const [level, setLevel] = useState(0)
  useEffect(() => {
    onLevelChange?.(level)
    return () => onLevelChange?.(0)
  }, [level, onLevelChange])
  const generation = useRef(0)
  const session = useRef<number | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const observations = useRef(0)
  const pendingContinue = useRef(false)
  const [continuing, setContinuing] = useState(false)
  const currentDevice = useRef(device)
  const stopping = useRef<Promise<void> | null>(null)
  const stop = useCallback(async () => {
    if (stopping.current) return stopping.current
    generation.current += 1
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
    const identity = session.current
    const owner = generation.current
    setRunning(false)
    setLevel(0)
    if (identity !== null) {
      const request = stopMicrophonePreview(identity)
      stopping.current = request
      try {
        await request
        if (session.current === identity) session.current = null
      } catch (error) {
        if (generation.current === owner) setRunning(true)
        throw error
      } finally {
        if (stopping.current === request) stopping.current = null
      }
    }
  }, [])
  useEffect(() => {
    if (currentDevice.current === device) return
    currentDevice.current = device
    if (stopping.current) generation.current += 1
    setPassed(false)
    setContinuing(false)
    void stop().catch(() => setFailed(true))
  }, [device, stop])
  useEffect(() => {
    const cancel = () => {
      if (stopping.current) generation.current += 1
      void stop().catch(() => setFailed(true))
      setPassed(false)
      setContinuing(false)
    }
    window.addEventListener('blur', cancel)
    return () => {
      window.removeEventListener('blur', cancel)
      generation.current += 1
      if (timer.current) clearTimeout(timer.current)
      if (session.current !== null) void stopMicrophonePreview(session.current).catch(() => {})
      session.current = null
    }
  }, [stop])
  const start = async () => {
    if (running || session.current !== null) return
    const owner = ++generation.current
    observations.current = 0
    setRunning(true)
    setPassed(false)
    setFailed(false)
    const deadline = Date.now() + 20_000
    try {
      const identity = await startMicrophonePreview(device)
      if (!Number.isSafeInteger(identity) || identity < 0)
        throw new Error('Invalid preview identity')
      if (owner !== generation.current) {
        await stopMicrophonePreview(identity)
        return
      }
      session.current = identity
      const poll = async () => {
        try {
          const volume = await getMicrophonePreviewLevel(identity)
          if (owner !== generation.current) return
          if (!Number.isFinite(volume) || volume < 0 || volume > 1) {
            throw new Error('Invalid preview level')
          }
          setLevel(volume)
          if (volume > 0.02) observations.current += 1
          if (observations.current >= 3) setPassed(true)
          if (Date.now() >= deadline) {
            await stop()
            return
          }
          timer.current = setTimeout(poll, 100)
        } catch {
          if (owner !== generation.current) return
          setPassed(false)
          setFailed(true)
          await stop().catch(() => {})
        }
      }
      void poll()
    } catch {
      if (owner !== generation.current) return
      setRunning(false)
      setFailed(true)
    }
  }
  return (
    <div className="space-y-6 pt-10">
      <h2 className="text-[30px] font-semibold leading-[34px]">{t('settings.microphone')}</h2>
      <p className="text-[16px] leading-6 text-text-secondary">{t('microphoneDialog.hint')}</p>
      <p className="text-[13px] text-text-tertiary">
        {device || t('microphoneDialog.systemDefault')}
      </p>
      {!onLevelChange && (
        <meter
          min={0}
          max={1}
          value={level}
          aria-label={t('microphoneDialog.level')}
          className="h-3 w-full accent-accent"
        />
      )}
      {failed && (
        <p role="alert" className="text-error">
          {t('microphoneDialog.previewError')}
        </p>
      )}
      <div className="flex justify-end gap-3">
        <button
          className="btn-secondary px-4 py-2"
          disabled={running || continuing}
          onClick={() => void start()}
        >
          {t('practice.start')}
        </button>
        {running && (
          <button
            className="btn-secondary px-4 py-2"
            onClick={() => void stop().catch(() => setFailed(true))}
          >
            {t('practice.stop')}
          </button>
        )}
        <button
          className="btn-primary px-4 py-2 disabled:opacity-40"
          disabled={!passed || continuing}
          onClick={() => {
            if (pendingContinue.current) return
            pendingContinue.current = true
            setContinuing(true)
            const owner = generation.current + (stopping.current ? 0 : 1)
            void stop()
              .then(() => {
                if (generation.current === owner) onContinue()
              })
              .catch(() => {
                if (generation.current !== owner) return
                setPassed(false)
                setFailed(true)
              })
              .finally(() => {
                pendingContinue.current = false
                if (generation.current === owner) setContinuing(false)
              })
          }}
        >
          {t('onboarding.layout.next')}
        </button>
      </div>
    </div>
  )
}
