import { memo, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Feather, MoreHorizontal, Pencil, Sparkle, Square, SquareCheck, Trash2 } from 'lucide-react'
import type { DictionaryEntry } from '../../stores/appStore'
import { Tooltip } from '../shared/Tooltip'

export function SourceIcon({ auto, size = 12 }: { auto: boolean; size?: number }) {
  return auto ? (
    <Sparkle size={size} className="shrink-0 fill-current text-success" aria-hidden />
  ) : (
    <Feather size={size} className="shrink-0 text-text-disabled" aria-hidden />
  )
}

function WordChipView({
  entry,
  selected,
  multiSelect,
  onToggle,
  onEdit,
  onDelete,
}: {
  entry: DictionaryEntry
  selected: boolean
  multiSelect: boolean
  onToggle: (id: number) => void
  onEdit: (entry: DictionaryEntry) => void
  onDelete: (entry: DictionaryEntry) => void
}) {
  const { t } = useTranslation()
  const [menuOpen, setMenuOpen] = useState(false)
  const pinned = multiSelect || menuOpen
  const auto = Boolean(entry.auto)

  useEffect(() => {
    if (!menuOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [menuOpen])

  const reveal = pinned
    ? 'visible opacity-100'
    : 'invisible opacity-0 group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100'
  const contentPadding = pinned
    ? multiSelect
      ? 'pr-9'
      : 'pr-[68px]'
    : 'group-hover:pr-[68px] group-focus-within:pr-[68px]'

  return (
    <li
      data-testid="dictionary-word"
      className={`group relative flex min-h-9 items-center gap-1 rounded-[10px] border p-2 transition-colors ${
        selected ? 'border-border-focus bg-fill-selected' : 'border-border hover:bg-fill-hover'
      }`}
    >
      <Tooltip label={t(auto ? 'dictionary.tooltipAuto' : 'dictionary.tooltipManual')}>
        <span className="grid place-items-center rounded-[8px] p-1">
          <SourceIcon auto={auto} />
        </span>
      </Tooltip>
      <span
        title={entry.pronunciation ? `${entry.word} · ${entry.pronunciation}` : entry.word}
        className={`min-w-0 flex-1 truncate py-0.5 text-[14px] leading-4 text-text-primary transition-[padding] duration-150 ${contentPadding}`}
      >
        {entry.word}
      </span>
      <div
        className={`absolute right-px top-1/2 flex -translate-y-1/2 items-center rounded-[6px] pl-1 transition-opacity duration-150 ${reveal}`}
      >
        {!multiSelect && (
          <button
            type="button"
            aria-label={`${t('dictionary.moreActions')} ${entry.word}`}
            title={t('dictionary.moreActions')}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
            className={`btn-ghost grid h-8 w-8 cursor-pointer place-items-center ${menuOpen ? 'bg-fill-hover' : ''}`}
          >
            <MoreHorizontal size={16} />
          </button>
        )}
        <button
          type="button"
          aria-label={`${t('dictionary.selectWord')} ${entry.word}`}
          aria-pressed={selected}
          onClick={(event) => {
            event.stopPropagation()
            onToggle(entry.id)
          }}
          className="btn-ghost grid h-8 w-8 cursor-pointer place-items-center"
        >
          {selected ? <SquareCheck size={16} /> : <Square size={16} />}
        </button>
      </div>
      {menuOpen && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setMenuOpen(false)} />
          <div
            role="menu"
            className="surface-popover absolute right-0 top-full z-40 mt-1.5 min-w-28 max-w-[280px] p-1"
          >
            <button
              type="button"
              role="menuitem"
              autoFocus
              onClick={() => {
                setMenuOpen(false)
                onEdit(entry)
              }}
              className="flex h-8 w-full cursor-pointer items-center gap-2 rounded-[6px] border-none bg-transparent px-2 text-left text-[14px] text-text-primary hover:bg-fill-hover"
            >
              <Pencil size={14} className="text-text-secondary" />
              {t('dictionary.edit')}
            </button>
            <hr className="my-1 border-0 border-t border-border-thin" />
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setMenuOpen(false)
                onDelete(entry)
              }}
              className="flex h-8 w-full cursor-pointer items-center gap-2 rounded-[6px] border-none bg-transparent px-2 text-left text-[14px] text-error hover:bg-fill-hover"
            >
              <Trash2 size={14} />
              {t('dictionary.delete')}
            </button>
          </div>
        </>
      )}
    </li>
  )
}

export const WordChip = memo(WordChipView)
