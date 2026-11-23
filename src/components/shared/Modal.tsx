import { createContext, useContext, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { ModalDepthContext } from './modalLayer'

const FOCUSABLE =
  'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [href], [tabindex]'

const ModalParentContext = createContext<symbol | null>(null)
const modalLayers = new Map<symbol, { parent: symbol | null; panel: HTMLElement; order: number }>()
let modalOpenOrder = 0

function topModalLayer(unmountingIdentifier?: symbol) {
  const visible = Array.from(modalLayers.entries()).filter(([, layer]) =>
    isVisibleModalFocusTarget(layer.panel),
  )
  if (
    unmountingIdentifier &&
    !visible.some(([identifier]) => identifier === unmountingIdentifier)
  ) {
    const unmounting = modalLayers.get(unmountingIdentifier)
    if (unmounting) visible.push([unmountingIdentifier, unmounting])
  }
  const leaves = visible.filter(([identifier]) =>
    visible.every(([otherIdentifier, layer]) => {
      if (identifier === otherIdentifier) return true
      let parent = layer.parent
      const visited = new Set<symbol>()
      while (parent && !visited.has(parent)) {
        if (parent === identifier) return false
        visited.add(parent)
        parent = modalLayers.get(parent)?.parent ?? null
      }
      return true
    }),
  )
  return leaves.sort((left, right) => right[1].order - left[1].order)[0]
}

function isVisibleModalFocusTarget(element: HTMLElement) {
  if (!element.isConnected) return false
  if (element.matches(':disabled, input[type="hidden"]') || element.closest('[hidden], [inert]'))
    return false
  let ancestor: HTMLElement | null = element
  while (ancestor) {
    const style = window.getComputedStyle(ancestor)
    if (style.display === 'none' || style.visibility === 'hidden') return false
    ancestor = ancestor.parentElement
  }
  return true
}

function isAvailableModalControl(element: HTMLElement) {
  return element.tabIndex >= 0 && isVisibleModalFocusTarget(element)
}

export function Modal({
  title,
  onClose,
  dismissible = true,
  closeOnBackdrop = true,
  role = 'dialog',
  width = 448,
  bare = false,
  children,
}: {
  title: string
  onClose: () => void
  dismissible?: boolean
  /** Escape still closes; set false where a stray click would discard typed input. */
  closeOnBackdrop?: boolean
  role?: 'dialog' | 'alertdialog'
  width?: number
  /** Drops the title row and padding so the content owns the whole panel. */
  bare?: boolean
  children: React.ReactNode
}) {
  const parentIdentifier = useContext(ModalParentContext)
  const depth = useContext(ModalDepthContext)
  const identifier = useRef(Symbol('modal')).current
  const panel = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  const dismissibleRef = useRef(dismissible)
  closeRef.current = onClose
  dismissibleRef.current = dismissible

  useEffect(() => {
    const currentPanel = panel.current
    if (!currentPanel) return
    modalLayers.set(identifier, {
      parent: parentIdentifier,
      panel: currentPanel,
      order: ++modalOpenOrder,
    })
    const previousFocus = document.activeElement as HTMLElement | null
    const autoFocus = Array.from(
      panel.current?.querySelectorAll<HTMLElement>('[data-autofocus]') ?? [],
    ).find(isVisibleModalFocusTarget)
    if (topModalLayer()?.[0] === identifier) (autoFocus ?? currentPanel).focus()
    const keydown = (event: KeyboardEvent) => {
      if (topModalLayer()?.[0] !== identifier) return
      if (event.isComposing || event.keyCode === 229) return
      if (event.key === 'Escape') {
        const selector =
          event.target instanceof Element
            ? event.target.closest('[role="combobox"][aria-expanded="true"]')
            : null
        const menuId = selector?.getAttribute('aria-controls')
        if (menuId && document.getElementById(menuId)?.getAttribute('role') === 'listbox') {
          return
        }
        const managedMenu =
          event.target instanceof Element
            ? event.target.closest('[data-modal-escape-menu="open"]')
            : null
        if (managedMenu && currentPanel.contains(managedMenu)) {
          return
        }
        event.preventDefault()
        event.stopImmediatePropagation()
        if (dismissibleRef.current) closeRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = Array.from(
        panel.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [],
      ).filter(isAvailableModalControl)
      if (!items.length) {
        event.preventDefault()
        panel.current?.focus()
        return
      }
      const first = items[0]
      const last = items[items.length - 1]
      if (!items.some((element) => element === document.activeElement)) {
        event.preventDefault()
        ;(event.shiftKey ? last : first).focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    const containFocus = (event: FocusEvent) => {
      if (topModalLayer()?.[0] !== identifier) return
      if (event.target instanceof Node && currentPanel.contains(event.target)) return
      currentPanel.focus()
    }
    window.addEventListener('focusin', containFocus, true)
    window.addEventListener('keydown', keydown, true)
    return () => {
      window.removeEventListener('focusin', containFocus, true)
      window.removeEventListener('keydown', keydown, true)
      const wasTop = topModalLayer(identifier)?.[0] === identifier
      modalLayers.delete(identifier)
      const nextLayer = topModalLayer()?.[1]
      if (
        nextLayer &&
        document.activeElement instanceof HTMLElement &&
        nextLayer.panel.contains(document.activeElement) &&
        isVisibleModalFocusTarget(document.activeElement)
      )
        return
      if (
        wasTop &&
        previousFocus?.isConnected &&
        isVisibleModalFocusTarget(previousFocus) &&
        (!nextLayer || nextLayer.panel.contains(previousFocus))
      ) {
        previousFocus.focus()
      } else if (wasTop && nextLayer) {
        nextLayer.panel.focus()
      }
    }
  }, [identifier, parentIdentifier])

  return createPortal(
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-6"
      style={{ zIndex: 100 + depth * 10 }}
    >
      <div
        className="fixed inset-0"
        onClick={() => {
          if (dismissible && closeOnBackdrop && topModalLayer()?.[0] === identifier) onClose()
        }}
      />
      <div
        ref={panel}
        role={role}
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        style={{
          maxWidth: width,
          maxHeight: 'calc(100dvh - 48px)',
          overflowY: 'auto',
        }}
        className={`relative w-full rounded-[16px] bg-bg-elevated text-text-primary shadow-float outline-none ${
          bare ? 'overflow-hidden border border-border' : 'p-6'
        }`}
      >
        {bare ? null : <h2 className="m-0 mb-4 text-[18px] font-semibold leading-6">{title}</h2>}
        <ModalParentContext.Provider value={identifier}>
          <ModalDepthContext.Provider value={depth + 1}>{children}</ModalDepthContext.Provider>
        </ModalParentContext.Provider>
      </div>
    </div>,
    document.body,
  )
}
