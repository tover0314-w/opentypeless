import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { HistoryEntry } from '../../stores/appStore'
import { Modal } from '../shared/Modal'

interface CreateCorrectionDialogProps {
  entry: HistoryEntry
  onCancel: () => void
  onSave: (pattern: string, replacement: string) => Promise<void>
}

function canPrefill(value: string) {
  const length = Array.from(value.trim()).length
  return length > 0 && length <= 120
}

function clampCorrectionText(value: string) {
  return Array.from(value).slice(0, 120).join('')
}

export function CreateCorrectionDialog({ entry, onCancel, onSave }: CreateCorrectionDialogProps) {
  const { t } = useTranslation()
  const canUseSource = canPrefill(entry.raw_text) && canPrefill(entry.polished_text)
  const [pattern, setPattern] = useState(canUseSource ? entry.raw_text.trim() : '')
  const [replacement, setReplacement] = useState(canUseSource ? entry.polished_text.trim() : '')
  const [saving, setSaving] = useState(false)

  const save = async () => {
    const nextPattern = pattern.trim()
    const nextReplacement = replacement.trim()
    if (!nextPattern || !nextReplacement) return
    setSaving(true)
    try {
      await onSave(nextPattern, nextReplacement)
    } catch {
      return
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title={t('history.createCorrection')}
      onClose={onCancel}
      dismissible={!saving}
      width={420}
      bare
    >
      <div aria-busy={saving}>
        <div className="border-b border-border px-4 py-3">
          <h3 className="text-[14px] font-medium text-text-primary">
            {t('history.createCorrection')}
          </h3>
        </div>
        <div className="space-y-3 px-4 py-3">
          <label className="block text-[11px] text-text-secondary">
            {t('dictionary.wrongPhrase')}
            <input
              data-autofocus
              value={pattern}
              disabled={saving}
              onChange={(event) => setPattern(clampCorrectionText(event.target.value))}
              className="mt-1 w-full rounded-[8px] border border-border bg-bg-secondary px-3 py-2 text-[13px] text-text-primary outline-none focus:border-border-focus"
            />
          </label>
          <label className="block text-[11px] text-text-secondary">
            {t('dictionary.correctPhrase')}
            <input
              value={replacement}
              disabled={saving}
              onChange={(event) => setReplacement(clampCorrectionText(event.target.value))}
              className="mt-1 w-full rounded-[8px] border border-border bg-bg-secondary px-3 py-2 text-[13px] text-text-primary outline-none focus:border-border-focus"
            />
          </label>
        </div>
        <div className="flex justify-end gap-2 border-t border-border px-4 py-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={saving}
            className="rounded-[8px] border border-border bg-transparent px-3 py-1.5 text-[12px] text-text-secondary hover:text-text-primary disabled:opacity-50"
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={!pattern.trim() || !replacement.trim() || saving}
            className="rounded-[8px] border-none bg-accent px-3 py-1.5 text-[12px] text-white hover:bg-accent-hover disabled:opacity-40"
          >
            {t('history.saveCorrection')}
          </button>
        </div>
      </div>
    </Modal>
  )
}
