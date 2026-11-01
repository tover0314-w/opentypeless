import { useEffect, type RefObject } from 'react'
import { useAppStore } from '../stores/appStore'

const POLL_MS = 100

/**
 * Typeless lets clicks fall through its floating-bar window everywhere except over the bar and its
 * alerts, so the transparent panel never blocks the app underneath. The cursor is polled because a
 * window that ignores cursor events receives no pointer events to tell it when to stop ignoring.
 */
export function useCapsuleClickThrough(
  rootRef: RefObject<HTMLElement | null>,
  onBarHoverChange: (over: boolean) => void,
) {
  const menuOpen = useAppStore((s) => s.contextMenuOpen || s.translationTargetMenuOpen)

  useEffect(() => {
    let cancelled = false
    let timer: number | undefined
    let ignoring: boolean | null = null
    let overBar = false

    import('@tauri-apps/api/window')
      .then(({ getCurrentWindow, cursorPosition }) => {
        if (cancelled) return
        const win = getCurrentWindow()
        const setIgnore = (ignore: boolean) => {
          if (ignoring === ignore) return
          ignoring = ignore
          win.setIgnoreCursorEvents(ignore).catch(() => {
            // Unsupported here: stop polling and leave the window interactive.
            cancelled = true
          })
        }
        // Menus close on an outside click, so the whole panel must stay interactive.
        if (menuOpen) {
          setIgnore(false)
          return
        }

        const tick = async () => {
          try {
            const [cursor, position, scale] = await Promise.all([
              cursorPosition(),
              win.outerPosition(),
              win.scaleFactor(),
            ])
            if (cancelled) return
            const x = (cursor.x - position.x) / scale
            const y = (cursor.y - position.y) / scale
            const inside = x >= 0 && y >= 0 && x < window.innerWidth && y < window.innerHeight
            const target = inside ? document.elementFromPoint(x, y) : null
            const root = rootRef.current
            // Hit-test the bar's box rather than the element under the cursor: an idle-hidden bar is
            // transparent and only 6px tall, but its hover zone is the full pill it grows into.
            const bar = root?.querySelector<HTMLElement>('[data-capsule-view]')
            const nowOverBar = Boolean(inside && bar && isWithinHoverZone(bar, x, y))
            const overContent = Boolean(target && root && target !== root && root.contains(target))
            setIgnore(!(overContent || nowOverBar))
            if (nowOverBar !== overBar) {
              overBar = nowOverBar
              onBarHoverChange(nowOverBar)
            }
          } catch {
            /* cursor APIs are unavailable on this platform; keep the window interactive */
            setIgnore(false)
            return
          }
          if (!cancelled) timer = window.setTimeout(tick, POLL_MS)
        }
        void tick()
      })
      .catch(() => {})

    return () => {
      cancelled = true
      if (timer) window.clearTimeout(timer)
    }
  }, [menuOpen, onBarHoverChange, rootRef])
}

const HOVER_ZONE = { width: 70, height: 34 }

function isWithinHoverZone(bar: HTMLElement, x: number, y: number): boolean {
  const rect = bar.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const width = Math.max(rect.width, HOVER_ZONE.width)
  const height = Math.max(rect.height, HOVER_ZONE.height)
  return Math.abs(x - centerX) <= width / 2 && y <= rect.bottom && y >= rect.bottom - height
}
