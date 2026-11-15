import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import {
  addCorrectionRule,
  removeCorrectionRule,
  setCorrectionRuleEnabled,
  updateCorrectionRule,
} from '../../lib/tauri'
import type { CorrectionRule } from '../../stores/appStore'
import { Toggle } from '../Settings/shared/Toggle'
import { Modal } from '../shared/Modal'
import { toast } from '../toast-service'
import { FOOTER_BUTTON } from '../shared/ConfirmDeleteDialog'

function CorrectionDialog({
  rule,
  onClose,
  onSaved,
}: {
  rule: CorrectionRule
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const { t } = useTranslation()
  const [pattern, setPattern] = useState(rule.pattern)
  const [replacement, setReplacement] = useState(rule.replacement)
  const [saving, setSaving] = useState(false)
  const valid = pattern.trim() && replacement.trim()

  const save = async () => {
    if (!valid || saving) return
    setSaving(true)
    try {
      await updateCorrectionRule(rule.id, pattern.trim(), replacement.trim(), rule.enabled)
      await onSaved()
      onClose()
    } catch (error) {
      console.error('Failed to update correction rule:', error)
      toast.error(t('dictionary.failedToUpdateCorrection'))
      setSaving(false)
    }
  }

  return (
    <Modal title={t('dictionary.editCorrection')} onClose={onClose} dismissible={!saving}>
      <div className="flex flex-col gap-2 pb-6">
        <input
          data-autofocus
          aria-label={t('dictionary.wrongPhrase')}
          placeholder={t('dictionary.wrongPhrase')}
          value={pattern}
          onChange={(event) => setPattern(event.target.value)}
          className="field-control"
        />
        <input
          aria-label={t('dictionary.correctPhrase')}
          placeholder={t('dictionary.correctPhrase')}
          value={replacement}
          onChange={(event) => setReplacement(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) void save()
          }}
          className="field-control"
        />
      </div>
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          disabled={saving}
          className={`btn-outline ${FOOTER_BUTTON}`}
        >
          {t('common.cancel')}
        </button>
        <button
          type="button"
          onClick={() => void save()}
          disabled={!valid || saving}
          className={`btn-primary ${FOOTER_BUTTON}`}
        >
          {t('common.save')}
        </button>
      </div>
    </Modal>
  )
}

export function CorrectionsTab({
  rules,
  query,
  onChanged,
}: {
  rules: CorrectionRule[]
  query: string
  onChanged: () => Promise<void>
}) {
  const { t } = useTranslation()
  const [pattern, setPattern] = useState('')
  const [replacement, setReplacement] = useState('')
  const [editing, setEditing] = useState<CorrectionRule | null>(null)
  const needle = query.trim().toLocaleLowerCase()
  const visible = needle
    ? rules.filter((rule) =>
        `${rule.pattern}\n${rule.replacement}`.toLocaleLowerCase().includes(needle),
      )
    : rules

  const run = async (action: () => Promise<void>, failureKey: string) => {
    try {
      await action()
      await onChanged()
    } catch (error) {
      console.error(failureKey, error)
      toast.error(t(failureKey))
    }
  }

  const add = () => {
    const nextPattern = pattern.trim()
    const nextReplacement = replacement.trim()
    if (!nextPattern || !nextReplacement) return
    void run(async () => {
      await addCorrectionRule(nextPattern, nextReplacement)
      setPattern('')
      setReplacement('')
    }, 'dictionary.failedToAddCorrection')
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-1 gap-2 min-[760px]:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
        <input
          value={pattern}
          onChange={(event) => setPattern(event.target.value)}
          placeholder={t('dictionary.wrongPhrase')}
          aria-label={t('dictionary.wrongPhrase')}
          className="field-control"
        />
        <input
          value={replacement}
          onChange={(event) => setReplacement(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) add()
          }}
          placeholder={t('dictionary.correctPhrase')}
          aria-label={t('dictionary.correctPhrase')}
          className="field-control"
        />
        <button
          type="button"
          onClick={add}
          disabled={!pattern.trim() || !replacement.trim()}
          aria-label={t('dictionary.addCorrection')}
          className="btn-primary inline-flex h-10 cursor-pointer items-center justify-center gap-1.5 px-4 text-[14px] disabled:opacity-40"
        >
          <Plus size={14} />
          {t('dictionary.add')}
        </button>
      </div>

      {visible.length === 0 ? (
        <p className="m-0 pt-16 text-center text-[14px] leading-4 text-text-tertiary">
          {needle ? t('dictionary.noResults') : t('dictionary.noCorrections')}
        </p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
          {visible.map((rule) => (
            <li
              key={rule.id}
              className="group flex min-h-12 items-center gap-3 rounded-[10px] border border-border px-3 py-2 hover:bg-fill-hover"
            >
              <Toggle
                checked={rule.enabled}
                ariaLabel={`${t('dictionary.toggleCorrection')} ${rule.pattern}`}
                onChange={(enabled) =>
                  void run(
                    () => setCorrectionRuleEnabled(rule.id, enabled),
                    'dictionary.failedToUpdateCorrection',
                  )
                }
              />
              <div className="flex min-w-0 flex-1 items-center gap-2 text-[14px] leading-4">
                <span className="truncate text-text-secondary line-through decoration-text-disabled">
                  {rule.pattern}
                </span>
                <span className="shrink-0 text-text-tertiary">→</span>
                <span className="truncate text-text-primary">{rule.replacement}</span>
              </div>
              <div className="flex shrink-0 items-center opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100">
                <button
                  type="button"
                  onClick={() => setEditing(rule)}
                  aria-label={`${t('dictionary.editCorrection')} ${rule.pattern}`}
                  title={t('dictionary.editCorrection')}
                  className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center"
                >
                  <Pencil size={14} />
                </button>
                <button
                  type="button"
                  onClick={() =>
                    void run(
                      () => removeCorrectionRule(rule.id),
                      'dictionary.failedToRemoveCorrection',
                    )
                  }
                  aria-label={`${t('dictionary.removeCorrection')} ${rule.pattern}`}
                  title={t('dictionary.removeCorrection')}
                  className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center hover:!text-error"
                >
                  <Trash2 size={14} />
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <CorrectionDialog rule={editing} onClose={() => setEditing(null)} onSaved={onChanged} />
      )}
    </div>
  )
}
