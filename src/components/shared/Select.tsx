import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronDown } from 'lucide-react'
import { isAnchorClipped } from '../../lib/anchorVisibility'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
  leading?: React.ReactNode
}

interface Props {
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  'aria-label'?: string
  disabled?: boolean
  className?: string
}

const MENU_MAX_HEIGHT = 320
const MENU_GAP = 4
const VIEWPORT_MARGIN = 8

/**
 * Typeless select (REA `O6`/`$x1`/`Zv`): a 40px outlined trigger with a chevron that flips when
 * open, and a floating menu with an 8px radius, 4px inset and a grey fill on the selected item.
 */
export function Select({
  value,
  options,
  onChange,
  'aria-label': ariaLabel,
  disabled = false,
  className = '',
}: Props) {
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState<{ option: string | null; value: string } | null>(null)
  const [previousValue, setPreviousValue] = useState(value)
  if (previousValue !== value) {
    setPreviousValue(value)
    setHighlight(null)
  }
  const [rect, setRect] = useState<{
    left: number
    top: number
    width: number
    up: boolean
    maxHeight: number
  }>()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const listId = useId()
  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.value === value),
  )
  const selected = options.find((option) => option.value === value)
  const highlightedIndex = options.findIndex(
    (option) => highlight?.value === value && option.value === highlight.option && !option.disabled,
  )
  const currentEnabledIndex = options.findIndex(
    (option) => option.value === value && !option.disabled,
  )
  const activeIndex =
    highlightedIndex >= 0
      ? highlightedIndex
      : currentEnabledIndex >= 0
        ? currentEnabledIndex
        : options.findIndex((option) => !option.disabled)
  const setActiveValue = (option: string | null) => setHighlight({ option, value })
  const visible = open && !disabled

  const place = useCallback(() => {
    const box = triggerRef.current?.getBoundingClientRect()
    if (!box) return
    const below = Math.max(0, window.innerHeight - box.bottom - MENU_GAP - VIEWPORT_MARGIN)
    const above = Math.max(0, box.top - MENU_GAP - VIEWPORT_MARGIN)
    const desiredHeight = Math.min(MENU_MAX_HEIGHT, options.length * 34 + 8)
    const up = below < desiredHeight && above > below
    const width = Math.min(box.width, Math.max(0, window.innerWidth - VIEWPORT_MARGIN * 2))
    setRect({
      left: Math.max(
        VIEWPORT_MARGIN,
        Math.min(box.left, window.innerWidth - width - VIEWPORT_MARGIN),
      ),
      width,
      top: up ? box.top - MENU_GAP : box.bottom + MENU_GAP,
      up,
      maxHeight: Math.min(desiredHeight, up ? above : below),
    })
  }, [options.length])

  const close = useCallback((refocus = true) => {
    setOpen(false)
    if (refocus) triggerRef.current?.focus()
  }, [])

  const openMenu = () => {
    if (disabled) return
    place()
    setActiveValue(options[selectedIndex]?.value ?? null)
    setOpen(true)
  }

  const choose = (index: number) => {
    const option = options[index]
    if (disabled || !option || option.disabled) return
    if (option.value !== value) onChange(option.value)
    close()
  }

  useLayoutEffect(() => {
    if (!visible) return
    listRef.current
      ?.querySelector<HTMLElement>(`[data-index="${activeIndex}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, visible])

  useEffect(() => {
    if (disabled) setOpen(false)
  }, [disabled])

  useEffect(() => {
    if (!open) return
    const onScrollOrResize = () => {
      if (triggerRef.current && isAnchorClipped(triggerRef.current)) {
        close(false)
        return
      }
      place()
    }
    window.addEventListener('resize', onScrollOrResize)
    window.addEventListener('scroll', onScrollOrResize, true)
    return () => {
      window.removeEventListener('resize', onScrollOrResize)
      window.removeEventListener('scroll', onScrollOrResize, true)
    }
  }, [open, place, close])

  const move = (delta: number) => {
    if (options.length === 0) return
    let next = activeIndex
    for (let attempt = 0; attempt < options.length; attempt += 1) {
      next = (next + delta + options.length) % options.length
      if (!options[next].disabled) break
    }
    setActiveValue(options[next]?.value ?? null)
  }

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (disabled || event.nativeEvent.isComposing) return
    if (!open) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
        event.preventDefault()
        openMenu()
      }
      return
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        move(1)
        break
      case 'ArrowUp':
        event.preventDefault()
        move(-1)
        break
      case 'Home':
        event.preventDefault()
        setActiveValue(options.find((option) => !option.disabled)?.value ?? null)
        break
      case 'End':
        event.preventDefault()
        setActiveValue(
          options[
            options.reduce(
              (lastEnabled, option, index) => (option.disabled ? lastEnabled : index),
              -1,
            )
          ]?.value ?? null,
        )
        break
      case 'Enter':
      case ' ':
        event.preventDefault()
        choose(activeIndex)
        break
      case 'Escape':
        event.preventDefault()
        event.stopPropagation()
        close()
        break
      case 'Tab':
        close(false)
        break
    }
  }

  return (
    <div className={`relative ${className}`}>
      <button
        ref={triggerRef}
        type="button"
        role="combobox"
        aria-label={ariaLabel}
        data-value={value}
        aria-haspopup="listbox"
        aria-expanded={visible}
        aria-controls={visible ? listId : undefined}
        aria-activedescendant={visible && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
        disabled={disabled}
        onClick={() => (open ? close() : openMenu())}
        onBlur={() => close(false)}
        onKeyDown={onKeyDown}
        className={`field-control relative flex cursor-pointer items-center justify-between gap-2 text-left hover:border-text-tertiary/40 disabled:cursor-not-allowed disabled:opacity-50 ${
          open ? 'border-border-focus shadow-[0_0_0_2px_rgba(42,187,167,0.15)]' : ''
        }`}
      >
        {selected?.leading && (
          <span
            aria-hidden="true"
            className="flex h-[18px] w-[18px] shrink-0 items-center justify-center"
          >
            {selected.leading}
          </span>
        )}
        <span className="min-w-0 flex-1 truncate">{selected?.label ?? ''}</span>
        <ChevronDown
          size={16}
          aria-hidden="true"
          className={`shrink-0 text-text-tertiary transition-transform duration-150 ${open ? 'rotate-180' : ''}`}
        />
      </button>
      {visible &&
        rect &&
        createPortal(
          <>
            <div className="fixed inset-0 z-[90]" onMouseDown={() => close(false)} />
            <ul
              ref={listRef}
              id={listId}
              role="listbox"
              aria-label={ariaLabel}
              data-select-menu=""
              className="surface-popover fixed z-[91] m-0 list-none overflow-y-auto p-1"
              style={{
                left: rect.left,
                width: rect.width,
                maxHeight: rect.maxHeight,
                ...(rect.up ? { bottom: window.innerHeight - rect.top } : { top: rect.top }),
              }}
            >
              {options.map((option, index) => {
                const isSelected = option.value === value
                return (
                  <li
                    key={option.value}
                    id={`${listId}-${index}`}
                    role="option"
                    data-index={index}
                    data-value={option.value}
                    aria-selected={isSelected}
                    aria-disabled={option.disabled || undefined}
                    onMouseEnter={() => {
                      if (!option.disabled) setActiveValue(option.value)
                    }}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => choose(index)}
                    className={`mb-0.5 flex cursor-pointer items-center gap-2 rounded-[4px] px-2 py-1.5 text-[14px] leading-5 last:mb-0 ${
                      option.disabled
                        ? 'cursor-not-allowed text-text-tertiary opacity-50'
                        : isSelected
                          ? 'bg-fill-selected text-text-primary'
                          : index === activeIndex
                            ? 'bg-fill-hover text-text-primary'
                            : 'text-text-secondary'
                    }`}
                  >
                    {option.leading && (
                      <span
                        aria-hidden="true"
                        className="flex h-[18px] w-[18px] shrink-0 items-center justify-center"
                      >
                        {option.leading}
                      </span>
                    )}
                    <span className="min-w-0 flex-1 truncate">{option.label}</span>
                    {isSelected && <Check size={14} className="shrink-0 text-text-primary" />}
                  </li>
                )
              })}
            </ul>
          </>,
          document.body,
        )}
    </div>
  )
}
