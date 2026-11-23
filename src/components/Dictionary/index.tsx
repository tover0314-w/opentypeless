import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Download,
  FileJson,
  FileSpreadsheet,
  Search,
  SquareCheck,
  SquareMinus,
  Trash2,
  X,
} from 'lucide-react'
import { useAppStore, type DictionaryEntry } from '../../stores/appStore'
import {
  addDictionaryEntry,
  commitDictionaryImport,
  exportDictionaryCsv,
  exportDictionaryJson,
  getCorrectionRules,
  getDictionary,
  removeDictionaryEntries,
  removeDictionaryEntry,
  updateDictionaryEntry,
} from '../../lib/tauri'
import { PageContainer, PageHeader } from '../shared/Page'
import { Tooltip } from '../shared/Tooltip'
import { toast } from '../toast-service'
import { CorrectionsTab } from './CorrectionsTab'
import { ConfirmDeleteDialog } from '../shared/ConfirmDeleteDialog'
import { ImportDialog, WordDialog, type WordDraft } from './DictionaryDialogs'
import { downloadText, importErrorKey, isDuplicateError, type ImportPick } from './dictionaryFiles'
import { SourceIcon, WordChip } from './WordChip'

type Tab = 'all' | 'auto' | 'manual' | 'corrections'

type Dialog =
  | { kind: 'add' }
  | { kind: 'edit'; entry: DictionaryEntry }
  | { kind: 'delete'; entry: DictionaryEntry }
  | { kind: 'batchDelete' }
  | { kind: 'import'; pick: ImportPick }
  | null

const EMPTY_DESCRIPTION: Record<Exclude<Tab, 'corrections'>, string> = {
  all: 'dictionary.emptyAll',
  auto: 'dictionary.emptyAuto',
  manual: 'dictionary.emptyManual',
}

function DictionaryTabs({ tab, onChange }: { tab: Tab; onChange: (tab: Tab) => void }) {
  const { t } = useTranslation()
  const tabs: { id: Tab; label: string; icon?: React.ReactNode }[] = [
    { id: 'all', label: t('dictionary.tabAll') },
    { id: 'auto', label: t('dictionary.tabAuto'), icon: <SourceIcon auto size={14} /> },
    { id: 'manual', label: t('dictionary.tabManual'), icon: <SourceIcon auto={false} size={14} /> },
    { id: 'corrections', label: t('dictionary.tabCorrections') },
  ]
  return (
    <div role="tablist" className="inline-flex gap-1 rounded-full bg-fill-tough p-1">
      {tabs.map((item) => (
        <button
          key={item.id}
          type="button"
          role="tab"
          aria-selected={tab === item.id}
          onClick={() => onChange(item.id)}
          className={`inline-flex min-w-10 cursor-pointer items-center justify-center gap-1 whitespace-nowrap rounded-full border-none p-2 text-[14px] leading-4 transition-colors ${
            tab === item.id
              ? 'bg-bg-elevated text-text-primary shadow-sm'
              : 'bg-transparent text-text-secondary hover:bg-fill-hover hover:text-text-primary'
          }`}
        >
          {item.icon}
          {item.label}
        </button>
      ))}
    </div>
  )
}

function ExpandingSearch({
  value,
  onChange,
}: {
  value: string
  onChange: (value: string) => void
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const expanded = open || Boolean(value)

  useEffect(() => {
    if (open) input.current?.focus()
  }, [open])

  return (
    <div
      className={`flex h-8 items-center overflow-hidden rounded-full transition-all duration-200 ${
        expanded
          ? 'border border-border-focus bg-bg-elevated py-0.5 pl-1.5 shadow-[0_0_0_2px_var(--color-accent-light)]'
          : 'bg-fill-tough hover:bg-fill-selected'
      }`}
    >
      <Tooltip label={t('dictionary.search')} disabled={expanded}>
        <button
          type="button"
          aria-label={t('dictionary.search')}
          onClick={() => setOpen(true)}
          tabIndex={expanded ? -1 : 0}
          className={`grid cursor-pointer place-items-center rounded-full border-none bg-transparent text-text-secondary ${
            expanded ? 'pointer-events-none p-1.5' : 'p-[9px]'
          }`}
        >
          <Search size={14} />
        </button>
      </Tooltip>
      <input
        ref={input}
        type="search"
        value={value}
        maxLength={60}
        tabIndex={expanded ? 0 : -1}
        aria-label={t('dictionary.search')}
        placeholder={t('dictionary.search')}
        onChange={(event) => onChange(event.target.value)}
        onBlur={() => {
          if (!value) setOpen(false)
        }}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return
          if (event.key === 'Escape' && value) {
            event.stopPropagation()
            onChange('')
          }
        }}
        className={`min-w-0 border-none bg-transparent text-[14px] leading-4 text-text-primary outline-none transition-all duration-100 placeholder:text-text-tertiary [&::-webkit-search-cancel-button]:hidden ${
          expanded ? 'w-[180px] pr-1 opacity-100' : 'pointer-events-none w-0 opacity-0'
        }`}
      />
      {value && (
        <button
          type="button"
          aria-label={t('dictionary.clearSearch')}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => {
            onChange('')
            input.current?.focus()
          }}
          className="btn-ghost mr-0.5 grid h-6 w-6 shrink-0 cursor-pointer place-items-center rounded-full"
        >
          <X size={14} />
        </button>
      )}
    </div>
  )
}

function SelectionBar({
  count,
  allSelected,
  onClear,
  onSelectAll,
  onDelete,
}: {
  count: number
  allSelected: boolean
  onClear: () => void
  onSelectAll: () => void
  onDelete: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="flex h-10 items-center justify-between gap-3">
      <div className="flex min-w-0 items-center gap-3">
        <div className="flex min-w-0 items-center">
          <Tooltip label={t('dictionary.clearSelection')}>
            <button
              type="button"
              aria-label={t('dictionary.clearSelection')}
              aria-checked={allSelected ? true : 'mixed'}
              role="checkbox"
              onClick={onClear}
              className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center"
            >
              {allSelected ? (
                <SquareCheck size={16} />
              ) : (
                <SquareMinus size={16} className="text-text-secondary" />
              )}
            </button>
          </Tooltip>
          <span className="truncate text-[14px] leading-4 text-text-primary">
            {t('dictionary.selectedCount', { count })}
          </span>
        </div>
        {!allSelected && (
          <button
            type="button"
            onClick={onSelectAll}
            className="btn-ghost h-8 cursor-pointer rounded-full px-3 text-[14px] leading-4"
          >
            {t('dictionary.selectAll')}
          </button>
        )}
      </div>
      <button
        type="button"
        onClick={onDelete}
        className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-full border border-border bg-transparent px-3 text-[14px] leading-4 text-error hover:bg-fill-hover"
      >
        <Trash2 size={14} />
        {t('dictionary.delete')}
      </button>
    </div>
  )
}

function ExportMenu() {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)

  const close = useCallback(() => {
    setOpen(false)
    window.setTimeout(() => trigger.current?.focus(), 0)
  }, [])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [close, open])

  const exportAs = async (format: 'json' | 'csv') => {
    try {
      downloadText(
        format === 'json' ? await exportDictionaryJson() : await exportDictionaryCsv(),
        format,
      )
      close()
    } catch (error) {
      console.error('Failed to export dictionary:', error)
      toast.error(t('dictionary.exportFailed'))
    }
  }

  const item =
    'flex h-8 w-full cursor-pointer items-center gap-2 rounded-[6px] border-none bg-transparent px-2 text-left text-[14px] text-text-primary hover:bg-fill-hover'
  return (
    <div className="relative">
      <Tooltip label={t('dictionary.export')} disabled={open}>
        <button
          ref={trigger}
          type="button"
          onClick={() => setOpen((value) => !value)}
          aria-label={t('dictionary.export')}
          aria-haspopup="menu"
          aria-expanded={open}
          className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center rounded-full"
        >
          <Download size={16} />
        </button>
      </Tooltip>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={close} />
          <div role="menu" className="surface-popover absolute right-0 top-10 z-40 min-w-36 p-1">
            <button
              type="button"
              role="menuitem"
              onClick={() => void exportAs('json')}
              className={item}
            >
              <FileJson size={14} className="text-text-secondary" />
              {t('dictionary.exportJson')}
            </button>
            <button
              type="button"
              role="menuitem"
              onClick={() => void exportAs('csv')}
              className={item}
            >
              <FileSpreadsheet size={14} className="text-text-secondary" />
              {t('dictionary.exportCsv')}
            </button>
          </div>
        </>
      )}
    </div>
  )
}

export function DictionaryPage() {
  const { t } = useTranslation()
  const dictionary = useAppStore((state) => state.dictionary)
  const setDictionary = useAppStore((state) => state.setDictionary)
  const correctionRules = useAppStore((state) => state.correctionRules)
  const setCorrectionRules = useAppStore((state) => state.setCorrectionRules)
  const [tab, setTab] = useState<Tab>('all')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<Set<number>>(() => new Set())
  const [dialog, setDialog] = useState<Dialog>(null)

  const refresh = useCallback(async () => {
    const [words, rules] = await Promise.all([getDictionary(), getCorrectionRules()])
    setDictionary(words)
    setCorrectionRules(rules)
  }, [setCorrectionRules, setDictionary])

  const query = search.trim().toLocaleLowerCase()
  const visibleWords = useMemo(() => {
    const byTab =
      tab === 'auto'
        ? dictionary.filter((entry) => entry.auto)
        : tab === 'manual'
          ? dictionary.filter((entry) => !entry.auto)
          : dictionary
    if (!query) return byTab
    return byTab.filter((entry) =>
      `${entry.word}\n${entry.pronunciation ?? ''}`.toLocaleLowerCase().includes(query),
    )
  }, [dictionary, query, tab])

  const selectedIds = useMemo(() => {
    const live = new Set(dictionary.map((entry) => entry.id))
    return [...selected].filter((id) => live.has(id))
  }, [dictionary, selected])
  const multiSelect = selectedIds.length > 0
  const allSelected =
    multiSelect && visibleWords.length > 0 && visibleWords.every((entry) => selected.has(entry.id))

  const toggle = useCallback((id: number) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])
  const clearSelection = useCallback(() => setSelected(new Set()), [])
  const openEdit = useCallback((entry: DictionaryEntry) => setDialog({ kind: 'edit', entry }), [])
  const openDelete = useCallback(
    (entry: DictionaryEntry) => setDialog({ kind: 'delete', entry }),
    [],
  )

  const saveWord = async (draft: WordDraft, entry?: DictionaryEntry) => {
    try {
      if (entry) {
        await updateDictionaryEntry(entry.id, draft.word, draft.pronunciation || null)
      } else {
        await addDictionaryEntry(draft.word, draft.pronunciation || null)
      }
      await refresh()
      toast.success(
        t(entry ? 'dictionary.editSuccess' : 'dictionary.addSuccess', { term: draft.word }),
      )
      return true
    } catch (error) {
      console.error('Failed to save dictionary word:', error)
      toast.error(
        t(
          isDuplicateError(error)
            ? 'dictionary.duplicateWord'
            : entry
              ? 'dictionary.failedToUpdate'
              : 'dictionary.failedToAdd',
        ),
      )
      return false
    }
  }

  const deleteOne = async (entry: DictionaryEntry) => {
    try {
      await removeDictionaryEntry(entry.id)
      await refresh()
      setDialog(null)
      toast.success(t('dictionary.deleteSuccess', { term: entry.word }))
    } catch (error) {
      console.error('Failed to remove dictionary word:', error)
      toast.error(t('dictionary.failedToRemove'))
    }
  }

  const deleteSelected = async () => {
    try {
      const count = await removeDictionaryEntries(selectedIds)
      await refresh()
      setDialog(null)
      clearSelection()
      toast.success(t('dictionary.batchDeleteSuccess', { count }))
    } catch (error) {
      console.error('Failed to remove dictionary words:', error)
      toast.error(t('dictionary.failedToRemove'))
    }
  }

  const commitImport = async (pick: Extract<ImportPick, { ok: true }>) => {
    try {
      const report = await commitDictionaryImport(pick.bytes, pick.format)
      await refresh()
      setDialog(null)
      toast.success(t('dictionary.importSuccess', { count: report.accepted }))
      return true
    } catch (error) {
      console.error('Failed to import dictionary:', error)
      toast.error(t(importErrorKey(error)))
      return false
    }
  }

  const changeTab = (next: Tab) => {
    setTab(next)
    clearSelection()
  }

  const wordsTab = tab !== 'corrections'
  return (
    <PageContainer>
      <PageHeader
        title={t('nav.dictionary')}
        actions={
          <>
            <ExportMenu />
            <button
              type="button"
              onClick={() => setDialog({ kind: 'add' })}
              className="btn-primary h-8 cursor-pointer px-3 text-[14px] leading-4"
            >
              {t('dictionary.newWord')}
            </button>
          </>
        }
      />

      <div className="flex flex-col gap-4">
        {multiSelect && wordsTab ? (
          <SelectionBar
            count={selectedIds.length}
            allSelected={allSelected}
            onClear={clearSelection}
            onSelectAll={() =>
              setSelected(
                (current) => new Set([...current, ...visibleWords.map((entry) => entry.id)]),
              )
            }
            onDelete={() => setDialog({ kind: 'batchDelete' })}
          />
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <DictionaryTabs tab={tab} onChange={changeTab} />
            <ExpandingSearch value={search} onChange={setSearch} />
          </div>
        )}

        {!wordsTab ? (
          <CorrectionsTab rules={correctionRules} query={search} onChanged={refresh} />
        ) : visibleWords.length === 0 ? (
          <div className="flex flex-col items-center gap-2 pt-24 text-center">
            {!query && (
              <h2 className="m-0 text-[20px] font-bold leading-6 text-text-primary">
                {t('dictionary.emptyTitle')}
              </h2>
            )}
            <p className="m-0 max-w-[470px] text-[14px] leading-4 text-text-tertiary">
              {query ? t('dictionary.noResults') : t(EMPTY_DESCRIPTION[tab])}
            </p>
          </div>
        ) : (
          <ul
            aria-label={t('nav.dictionary')}
            className="m-0 grid list-none grid-cols-1 gap-1.5 p-0 min-[560px]:grid-cols-2 min-[760px]:grid-cols-3"
          >
            {visibleWords.map((entry) => (
              <WordChip
                key={entry.id}
                entry={entry}
                selected={selected.has(entry.id)}
                multiSelect={multiSelect}
                onToggle={toggle}
                onEdit={openEdit}
                onDelete={openDelete}
              />
            ))}
          </ul>
        )}
      </div>

      {dialog?.kind === 'add' && (
        <WordDialog
          mode="add"
          onClose={() => setDialog(null)}
          onSave={(draft) => saveWord(draft)}
          onImportPicked={(pick) => setDialog({ kind: 'import', pick })}
        />
      )}
      {dialog?.kind === 'edit' && (
        <WordDialog
          mode="edit"
          initial={{
            word: dialog.entry.word,
            pronunciation: dialog.entry.pronunciation ?? '',
          }}
          onClose={() => setDialog(null)}
          onSave={(draft) => saveWord(draft, dialog.entry)}
        />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDeleteDialog
          title={t('dictionary.deleteTitle', { term: dialog.entry.word })}
          description={t('dictionary.deleteDescription', { term: dialog.entry.word })}
          onClose={() => setDialog(null)}
          onConfirm={() => deleteOne(dialog.entry)}
        />
      )}
      {dialog?.kind === 'batchDelete' && (
        <ConfirmDeleteDialog
          title={t('dictionary.batchDeleteTitle', { count: selectedIds.length })}
          description={t('dictionary.batchDeleteDescription', { count: selectedIds.length })}
          onClose={() => setDialog(null)}
          onConfirm={deleteSelected}
        />
      )}
      {dialog?.kind === 'import' && (
        <ImportDialog
          key={dialog.pick.fileName + String(dialog.pick.ok)}
          pick={dialog.pick}
          onPick={(pick) => setDialog({ kind: 'import', pick })}
          onClose={() => setDialog(null)}
          onConfirm={commitImport}
        />
      )}
    </PageContainer>
  )
}
