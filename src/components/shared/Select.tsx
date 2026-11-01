import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronDown } from 'lucide-react'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
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
  const [active, setActive] = useState(0)
  const [rect, setRect] = useState<{ left: number; top: number; width: number; up: boolean }>()
  const triggerRef = useRef<HTMLButtonElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const listId = useId()
  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.value === value),
  )
  const selected = options.find((option) => option.value === value)

  const place = useCallback(() => {
    const box = triggerRef.current?.getBoundingClientRect()
    if (!box) return
    const below = window.innerHeight - box.bottom
    const up = below < Math.min(MENU_MAX_HEIGHT, options.length * 34 + 8) && box.top > below
    setRect({
      left: box.left,
      width: box.width,
      top: up ? box.top - MENU_GAP : box.bottom + MENU_GAP,
      up,
    })
  }, [options.length])

  const close = useCallback((refocus = true) => {
    setOpen(false)
    if (refocus) triggerRef.current?.focus()
  }, [])

  const openMenu = () => {
    if (disabled) return
    place()
    setActive(selectedIndex)
    setOpen(true)
  }

  const choose = (index: number) => {
    const option = options[index]
    if (!option || option.disabled) return
    if (option.value !== value) onChange(option.value)
    close()
  }

  useLayoutEffect(() => {
    if (!open) return
    listRef.current
      ?.querySelector<HTMLElement>(`[data-index="${active}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [active, open])

  useEffect(() => {
    if (!open) return
    const onScrollOrResize = () => place()
    window.addEventListener('resize', onScrollOrResize)
    window.addEventListener('scroll', onScrollOrResize, true)
    return () => {
      window.removeEventListener('resize', onScrollOrResize)
      window.removeEventListener('scroll', onScrollOrResize, true)
    }
  }, [open, place])

  const move = (delta: number) => {
    if (options.length === 0) return
    let next = active
    for (let i = 0; i < options.length; i += 1) {
      next = (next + delta + options.length) % options.length
      if (!options[next].disabled) break
    }
    setActive(next)
  }

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (disabled) return
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
        setActive(0)
        break
      case 'End':
        event.preventDefault()
        setActive(options.length - 1)
        break
      case 'Enter':
      case ' ':
        event.preventDefault()
        choose(active)
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
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        disabled={disabled}
        onClick={() => (open ? close() : openMenu())}
        onKeyDown={onKeyDown}
        className={`field-control relative flex cursor-pointer items-center justify-between gap-2 text-left hover:border-text-tertiary/40 disabled:cursor-not-allowed disabled:opacity-50 ${
          open ? 'border-border-focus shadow-[0_0_0_2px_rgba(42,187,167,0.15)]' : ''
        }`}
      >
        <span className="min-w-0 flex-1 truncate">{selected?.label ?? ''}</span>
        <ChevronDown
          size={16}
          aria-hidden="true"
          className={`shrink-0 text-text-tertiary transition-transform duration-150 ${open ? 'rotate-180' : ''}`}
        />
      </button>
      {open &&
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
                maxHeight: MENU_MAX_HEIGHT,
                ...(rect.up ? { bottom: window.innerHeight - rect.top } : { top: rect.top }),
              }}
            >
              {options.map((option, index) => {
                const isSelected = option.value === value
                return (
                  <li
                    key={option.value}
                    role="option"
                    data-index={index}
                    data-value={option.value}
                    aria-selected={isSelected}
                    aria-disabled={option.disabled || undefined}
                    onMouseEnter={() => setActive(index)}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => choose(index)}
                    className={`mb-0.5 flex cursor-pointer items-center gap-2 rounded-[4px] px-2 py-1.5 text-[14px] leading-5 last:mb-0 ${
                      option.disabled
                        ? 'cursor-not-allowed text-text-tertiary opacity-50'
                        : isSelected
                          ? 'bg-fill-selected text-text-primary'
                          : index === active
                            ? 'bg-fill-hover text-text-primary'
                            : 'text-text-secondary'
                    }`}
                  >
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
