import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { LANGUAGE_VARIANTS, type VariantLanguage } from '../../lib/languageVariants'
import { Select } from '../shared/Select'

const LANGUAGE_NAMES: Record<VariantLanguage, string> = {
  en: 'English',
  zh: '中文',
  es: 'Español',
  fr: 'Français',
  pt: 'Português',
}

export function LanguageVariants() {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const button = useRef<HTMLButtonElement>(null)

  const close = () => {
    setOpen(false)
    button.current?.focus()
  }

  return (
    <>
      <button
        ref={button}
        type="button"
        aria-haspopup="dialog"
        onClick={() => setOpen(true)}
        className="btn-outline h-9 self-end px-3 text-[14px]"
      >
        {t('languageVariants.select')}
      </button>
      {open && createPortal(<VariantsDialog onClose={close} />, document.body)}
    </>
  )
}

function VariantsDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const variants = useAppStore((state) => state.config.language_variants)
  const polishEnabled = useAppStore((state) => state.config.polish_enabled)
  const updateConfig = useAppStore((state) => state.updateConfig)
  const dialog = useRef<HTMLDivElement>(null)

  useEffect(() => {
    dialog.current?.focus()
    const keydown = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
      }
      if (event.key === 'Tab') {
        const controls = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled)')
        if (!controls?.length) return
        const first = controls[0]
        const last = controls[controls.length - 1]
        if (
          event.shiftKey &&
          (document.activeElement === first || document.activeElement === dialog.current)
        ) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', keydown)
    return () => document.removeEventListener('keydown', keydown)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-[80] flex items-center justify-center bg-black/40 p-6"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        ref={dialog}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={t('languageVariants.title')}
        className="max-h-[90vh] w-full max-w-[448px] overflow-y-auto rounded-[12px] bg-bg-elevated text-text-primary shadow-float outline-none"
      >
        <header className="relative border-b border-border-thin p-6 pb-4">
          <h2 className="m-0 pr-6 text-[18px] font-semibold leading-7">
            {t('languageVariants.title')}
          </h2>
          <p className="mb-0 mt-2 text-[13px] leading-5 text-text-tertiary">
            {t('languageVariants.description')}
          </p>
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="btn-ghost absolute right-2 top-2 grid h-7 w-7 place-items-center"
          >
            <X size={16} />
          </button>
        </header>
        <div className="space-y-3 p-6 pt-4">
          {(Object.keys(LANGUAGE_VARIANTS) as VariantLanguage[]).map((base) => (
            <div
              key={base}
              className="flex items-center justify-between gap-3 rounded-[8px] border border-border-thin px-4 py-3"
            >
              <span className="text-[14px] text-text-tertiary">{LANGUAGE_NAMES[base]}</span>
              <Select
                aria-label={LANGUAGE_NAMES[base]}
                className="w-[230px] max-w-[70%]"
                value={variants?.[base] ?? ''}
                options={[
                  { value: '', label: t('languageVariants.automatic') },
                  ...LANGUAGE_VARIANTS[base],
                ]}
                onChange={(code) => {
                  const next = { ...variants }
                  if (code) next[base] = code
                  else delete next[base]
                  updateConfig({ language_variants: next })
                }}
              />
            </div>
          ))}
          {!polishEnabled && (
            <p role="status" className="text-[12px] leading-5 text-warning">
              {t('languageVariants.requiresPolish')}
            </p>
          )}
          <p className="mb-0 text-[12px] leading-5 text-text-tertiary">
            {t('languageVariants.otherLanguages')}
          </p>
        </div>
      </div>
    </div>
  )
}
