import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Modal } from './Modal'

export const FOOTER_BUTTON = 'h-8 cursor-pointer px-3 text-[14px] leading-4 disabled:opacity-40'
export const DANGER_BUTTON = `${FOOTER_BUTTON} rounded-full border-none bg-error text-white hover:opacity-90`

export function ConfirmDeleteDialog({
  title,
  description,
  confirmLabel,
  onClose,
  onConfirm,
}: {
  title: string
  description: string
  confirmLabel?: string
  onClose: () => void
  onConfirm: () => Promise<void>
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  return (
    <Modal title={title} onClose={onClose} dismissible={!busy} role="alertdialog">
      <p className="m-0 pb-6 text-[14px] leading-[22px] text-text-secondary">{description}</p>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          disabled={busy}
          className={`btn-outline ${FOOTER_BUTTON}`}
        >
          {t('common.cancel')}
        </button>
        <button
          type="button"
          data-autofocus
          disabled={busy}
          onClick={() => {
            setBusy(true)
            void onConfirm().finally(() => setBusy(false))
          }}
          className={DANGER_BUTTON}
        >
          {confirmLabel ?? t('dictionary.delete')}
        </button>
      </div>
    </Modal>
  )
}
