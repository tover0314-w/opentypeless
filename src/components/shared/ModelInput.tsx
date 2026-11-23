import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check } from 'lucide-react'
import { isAnchorClipped } from '../../lib/anchorVisibility'
import { useModalPopoverLayer } from './modalLayer'

interface Props {
  label: string
  value: string
  models: string[]
  onChange: (value: string) => void
  placeholder?: string
}

export function ModelInput({ label, value, models, onChange, placeholder }: Props) {
  const menuLayer = useModalPopoverLayer()
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState<{ model: string | null; value: string } | null>(null)
  const [previousValue, setPreviousValue] = useState(value)
  if (previousValue !== value) {
    setPreviousValue(value)
    setHighlight(null)
  }
  const [position, setPosition] = useState({ left: 0, top: 0, width: 0, height: 240 })
  const inputRef = useRef<HTMLInputElement>(null)
  const menuRef = useRef<HTMLUListElement>(null)
  const listId = useId()
  const query = value.trim().toLowerCase()
  const uniqueModels = [...new Set(models)]
  const knownModel = uniqueModels.includes(value)
  const candidates = uniqueModels.filter(
    (model) => knownModel || model.toLowerCase().includes(query),
  )
  const visible = open && candidates.length > 0
  const highlightedIndex =
    highlight?.value === value && highlight.model !== null
      ? candidates.indexOf(highlight.model)
      : -1
  const activeIndex =
    highlightedIndex >= 0 ? highlightedIndex : Math.max(0, candidates.indexOf(value))
  const setActiveModel = (model: string | null) => setHighlight({ model, value })

  useLayoutEffect(() => {
    if (!visible) return
    const place = () => {
      const bounds = inputRef.current?.getBoundingClientRect()
      if (!bounds) return
      if (inputRef.current && isAnchorClipped(inputRef.current)) {
        setOpen(false)
        return
      }
      const below = window.innerHeight - bounds.bottom - 12
      const above = bounds.top - 12
      const desired = Math.min(240, candidates.length * 34 + 8)
      const up = below < desired && above > below
      const height = Math.max(0, Math.min(desired, up ? above : below))
      const width = Math.min(bounds.width, Math.max(0, window.innerWidth - 16))
      setPosition({
        left: Math.max(8, Math.min(bounds.left, window.innerWidth - width - 8)),
        top: up ? bounds.top - height - 4 : bounds.bottom + 4,
        width,
        height,
      })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [visible, candidates.length])

  useEffect(() => {
    if (!visible) return
    const dismiss = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return
      if (inputRef.current?.contains(event.target) || menuRef.current?.contains(event.target))
        return
      setOpen(false)
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [visible])

  useEffect(() => {
    if (!visible) return
    menuRef.current?.children[activeIndex]?.scrollIntoView?.({ block: 'nearest' })
  }, [activeIndex, visible])

  const choose = (model: string) => {
    if (model !== value) onChange(model)
    inputRef.current?.focus()
    setOpen(false)
  }

  return (
    <>
      <input
        ref={inputRef}
        role="combobox"
        aria-label={label}
        aria-autocomplete="list"
        aria-expanded={visible}
        aria-controls={visible ? listId : undefined}
        aria-activedescendant={visible ? `${listId}-${activeIndex}` : undefined}
        value={value}
        placeholder={placeholder}
        className="field-control min-w-0 flex-1"
        onFocus={() => {
          setActiveModel(candidates[Math.max(0, candidates.indexOf(value))] ?? null)
          setOpen(true)
        }}
        onClick={() => {
          if (!visible) setActiveModel(candidates[Math.max(0, candidates.indexOf(value))] ?? null)
          setOpen(true)
        }}
        onBlur={() => setOpen(false)}
        onChange={(event) => {
          onChange(event.target.value)
          setActiveModel(null)
          setOpen(true)
        }}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return
            if (candidates.length === 0) return
            event.preventDefault()
            const delta = event.key === 'ArrowDown' ? 1 : -1
            const nextIndex = visible
              ? (activeIndex + delta + candidates.length) % candidates.length
              : Math.max(0, candidates.indexOf(value))
            setActiveModel(candidates[nextIndex] ?? null)
            setOpen(true)
          } else if (event.key === 'Enter' && visible) {
            event.preventDefault()
            choose(candidates[activeIndex])
          } else if (event.key === 'Escape' && visible) {
            event.preventDefault()
            event.stopPropagation()
            setOpen(false)
          } else if (event.key === 'Tab') {
            setOpen(false)
          }
        }}
      />
      {visible &&
        createPortal(
          <ul
            ref={menuRef}
            id={listId}
            role="listbox"
            aria-label={label}
            data-select-menu=""
            className="surface-popover fixed z-[91] m-0 list-none overflow-y-auto p-1"
            style={{
              left: position.left,
              zIndex: menuLayer,
              top: position.top,
              width: position.width,
              maxHeight: position.height,
            }}
          >
            {candidates.map((model, index) => (
              <li
                key={model}
                id={`${listId}-${index}`}
                role="option"
                aria-selected={model === value}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => setActiveModel(model)}
                onClick={() => choose(model)}
                className={`flex cursor-pointer items-center gap-2 rounded-[4px] px-2 py-1.5 text-[14px] leading-5 ${
                  model === value
                    ? 'bg-fill-selected text-text-primary'
                    : index === activeIndex
                      ? 'bg-fill-hover text-text-primary'
                      : 'text-text-secondary'
                }`}
              >
                <span className="min-w-0 flex-1 truncate">{model}</span>
                {model === value && <Check size={14} className="shrink-0" />}
              </li>
            ))}
          </ul>,
          document.body,
        )}
    </>
  )
}
