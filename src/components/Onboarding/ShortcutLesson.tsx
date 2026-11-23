import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { PracticeOptions } from '../../lib/tauri'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'
import { usePracticeShortcut } from './usePracticeShortcut'
import { Keycaps } from '../shared/Keycaps'
import { ShortcutSurface } from './ShortcutSurface'

export function ShortcutLesson({
  mode,
  onContinue,
  onConfigure,
  onPressedChange,
}: {
  mode: PracticeOptions['mode']
  onContinue: () => void
  onConfigure: () => void
  onPressedChange?: (pressed: boolean) => void
}) {
  const { t } = useTranslation()
  const shortcuts = useVoiceHotkeys()
  const shortcut = shortcuts[mode]
  const [pressedShortcut, setPressedShortcut] = useState<string | null>(null)
  const [hitShortcut, setHitShortcut] = useState<string | null>(null)
  const pressed = Boolean(shortcut && pressedShortcut === shortcut)
  const hit = Boolean(shortcut && hitShortcut === shortcut)
  useEffect(() => {
    onPressedChange?.(pressed)
    return () => onPressedChange?.(false)
  }, [pressed, onPressedChange])
  useEffect(() => {
    setPressedShortcut(null)
    setHitShortcut(null)
  }, [shortcut])
  usePracticeShortcut(
    mode,
    (edge) => {
      setPressedShortcut(edge ? shortcut : null)
      if (edge && shortcut) setHitShortcut(shortcut)
    },
    () => setPressedShortcut(null),
    shortcut,
  )
  return (
    <div className="flex min-h-[400px] flex-col pt-10">
      <h2 className="m-0 text-[30px] font-semibold leading-[34px] text-text-primary">
        {t(`home.modes.${mode}.title`)}
      </h2>
      <div className="mt-4 flex flex-wrap items-center gap-2 text-[18px] leading-[22px] text-text-secondary">
        <span>{t('onboarding.test.press')}</span>
        {shortcut && <Keycaps hotkey={shortcut} />}
      </div>
      {!onPressedChange && (
        <div className="mt-[72px]" aria-label={t('settings.hotkey')}>
          <ShortcutSurface shortcut={shortcut ?? ''} pressed={pressed} />
        </div>
      )}
      <div className="mt-auto flex justify-end gap-3 pb-8 pt-6">
        <button
          type="button"
          onClick={onConfigure}
          className="btn-secondary rounded-full px-4 py-2 text-[16px] leading-6"
        >
          {t('settings.hotkey')}
        </button>
        <button
          type="button"
          disabled={!hit}
          onClick={onContinue}
          className="btn-primary rounded-full px-4 py-2 text-[16px] leading-6 disabled:opacity-40"
        >
          {t('onboarding.layout.next')}
        </button>
      </div>
    </div>
  )
}
