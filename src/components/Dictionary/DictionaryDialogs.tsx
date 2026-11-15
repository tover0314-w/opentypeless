import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CircleAlert, FileText, Upload } from 'lucide-react'
import type { DictionaryImportReport } from '../../lib/tauri'
import { FOOTER_BUTTON } from '../shared/ConfirmDeleteDialog'
import { Modal } from '../shared/Modal'
import { Tooltip } from '../shared/Tooltip'
import { toast } from '../toast-service'
import { pickImportFile, termErrorKey, type ImportPick } from './dictionaryFiles'

export interface WordDraft {
  word: string
  pronunciation: string
}

function ImportCsvButton({
  disabled,
  onPicked,
}: {
  disabled?: boolean
  onPicked: (pick: ImportPick) => void
}) {
  const { t } = useTranslation()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const pickRequestId = useRef(0)

  useEffect(
    () => () => {
      pickRequestId.current += 1
    },
    [],
  )

  return (
    <Tooltip label={t('dictionary.importCsvTooltip')} align="start" wide>
      <button
        type="button"
        disabled={disabled || busy}
        onClick={() => input.current?.click()}
        className="btn-ghost inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-full px-3 text-[14px] leading-4 text-text-primary disabled:opacity-40"
      >
        <Upload size={14} />
        {t('dictionary.importCsv')}
      </button>
      <input
        ref={input}
        type="file"
        accept=".csv,.txt,.json,text/csv,text/plain,application/json"
        aria-label={t('dictionary.importFile')}
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (!file) return
          const requestId = ++pickRequestId.current
          setBusy(true)
          void pickImportFile(file)
            .then((pick) => {
              if (requestId === pickRequestId.current) onPicked(pick)
            })
            .finally(() => {
              if (requestId === pickRequestId.current) setBusy(false)
            })
        }}
      />
    </Tooltip>
  )
}

function WordFields({
  draft,
  onChange,
  onSubmit,
}: {
  draft: WordDraft
  onChange: (draft: WordDraft) => void
  onSubmit: () => void
}) {
  const { t } = useTranslation()
  const submitOnEnter = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
      event.preventDefault()
      onSubmit()
    }
  }
  return (
    <div className="flex flex-col gap-2 pb-6">
      <input
        data-autofocus
        value={draft.word}
        onChange={(event) => onChange({ ...draft, word: event.target.value })}
        onKeyDown={submitOnEnter}
        placeholder={t('dictionary.addPlaceholder')}
        aria-label={t('dictionary.word')}
        className="field-control"
      />
      <input
        value={draft.pronunciation}
        onChange={(event) => onChange({ ...draft, pronunciation: event.target.value })}
        onKeyDown={submitOnEnter}
        placeholder={t('dictionary.pronunciationOptional')}
        aria-label={t('dictionary.pronunciation')}
        maxLength={100}
        className="field-control"
      />
    </div>
  )
}

export function WordDialog({
  mode,
  initial,
  onClose,
  onSave,
  onImportPicked,
}: {
  mode: 'add' | 'edit'
  initial?: WordDraft
  onClose: () => void
  onSave: (draft: WordDraft) => Promise<boolean>
  onImportPicked?: (pick: ImportPick) => void
}) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState<WordDraft>(initial ?? { word: '', pronunciation: '' })
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)

  const submit = async () => {
    if (savingRef.current) return
    const word = draft.word.trim()
    const errorKey = termErrorKey(word)
    if (errorKey) {
      toast.error(t(errorKey))
      return
    }
    savingRef.current = true
    setSaving(true)
    try {
      if (await onSave({ word, pronunciation: draft.pronunciation.trim() })) onClose()
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  return (
    <Modal
      title={t(mode === 'add' ? 'dictionary.addTitle' : 'dictionary.editTitle')}
      onClose={onClose}
      dismissible={!saving}
    >
      <WordFields draft={draft} onChange={setDraft} onSubmit={() => void submit()} />
      <div className="flex items-center justify-between gap-6">
        {mode === 'add' && onImportPicked ? (
          <ImportCsvButton disabled={saving} onPicked={onImportPicked} />
        ) : (
          <span />
        )}
        <div className="flex items-center gap-2">
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
            onClick={() => void submit()}
            disabled={saving}
            className={`btn-primary ${FOOTER_BUTTON}`}
          >
            {t(mode === 'add' ? 'dictionary.addWord' : 'common.save')}
          </button>
        </div>
      </div>
    </Modal>
  )
}

function ImportPreview({ fileName, report }: { fileName: string; report: DictionaryImportReport }) {
  const { t } = useTranslation()
  const corrections = report.accepted - report.acceptedWords.length
  const skipped = report.skippedDuplicates + report.skippedInvalid
  return (
    <div className="flex max-h-[440px] min-h-0 flex-col">
      <p className="m-0 text-[14px] leading-4 text-text-secondary [overflow-wrap:anywhere]">
        {t('dictionary.importWordCount', { count: report.acceptedWords.length, fileName })}
      </p>
      {(skipped > 0 || corrections > 0) && (
        <p className="m-0 mt-2 text-[12px] leading-4 text-text-tertiary">
          {[
            skipped > 0 &&
              t('dictionary.importSkipped', {
                duplicates: report.skippedDuplicates,
                invalid: report.skippedInvalid,
              }),
            corrections > 0 && t('dictionary.importCorrections', { count: corrections }),
          ]
            .filter(Boolean)
            .join(' ')}
        </p>
      )}
      <hr className="-mx-6 my-4 border-0 border-t border-border-thin" />
      {report.acceptedWords.length > 0 && (
        <ul
          aria-label={t('dictionary.tabAll')}
          className="m-0 -mx-6 grid min-h-0 list-none grid-cols-3 gap-1.5 overflow-y-auto px-6 pb-6"
        >
          {report.acceptedWords.map((word, index) => (
            <li
              key={`${index}-${word}`}
              title={word}
              className="truncate rounded-[10px] border border-border px-2 py-2.5 text-[14px] leading-4 text-text-primary"
            >
              {word}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function ImportFailure({ fileName, errorKey }: { fileName: string; errorKey: string }) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-1.5 pb-6">
      <div className="flex min-w-0 items-center gap-2 rounded-[10px] border border-border px-2 py-[7px]">
        <FileText size={14} className="shrink-0 text-text-secondary" />
        <span className="truncate text-[14px] leading-[22px] text-text-primary" title={fileName}>
          {fileName}
        </span>
      </div>
      <p
        role="alert"
        className="m-0 flex items-start gap-2 text-[14px] leading-4 text-error [overflow-wrap:anywhere]"
      >
        <CircleAlert size={14} className="mt-0.5 shrink-0" />
        {t(errorKey, { fileName })}
      </p>
    </div>
  )
}

export function ImportDialog({
  pick,
  onPick,
  onClose,
  onConfirm,
}: {
  pick: ImportPick
  onPick: (pick: ImportPick) => void
  onClose: () => void
  onConfirm: (pick: Extract<ImportPick, { ok: true }>) => Promise<boolean>
}) {
  const { t } = useTranslation()
  const [committing, setCommitting] = useState(false)
  const total = pick.ok ? pick.report.accepted : 0

  return (
    <Modal title={t('dictionary.addTitle')} onClose={onClose} dismissible={!committing}>
      {pick.ok ? (
        <ImportPreview fileName={pick.fileName} report={pick.report} />
      ) : (
        <ImportFailure fileName={pick.fileName} errorKey={pick.errorKey} />
      )}
      <div className={`flex items-center gap-6 ${pick.ok ? 'justify-end' : 'justify-between'}`}>
        {!pick.ok && <ImportCsvButton onPicked={onPick} />}
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={committing}
            className={`btn-outline ${FOOTER_BUTTON}`}
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            data-autofocus
            disabled={!pick.ok || total === 0 || committing}
            onClick={() => {
              if (!pick.ok) return
              setCommitting(true)
              void onConfirm(pick).then((done) => {
                if (!done) setCommitting(false)
              })
            }}
            className={`btn-primary ${FOOTER_BUTTON}`}
          >
            {t(total === 1 ? 'dictionary.addWord' : 'dictionary.addWords')}
          </button>
        </div>
      </div>
    </Modal>
  )
}
