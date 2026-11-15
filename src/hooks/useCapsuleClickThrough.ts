import { useEffect, type RefObject } from 'react'
import { useAppStore } from '../stores/appStore'

const POLL_MS = 100

/**
 * Typeless lets clicks fall through its floating-bar window everywhere except over visible content
 * (the bar while it is active, alerts, menus), so the transparent panel never blocks the app below.
 * The cursor is polled because a window ignoring cursor events receives no pointer events.
 */
export function useCapsuleClickThrough(rootRef: RefObject<HTMLElement | null>) {
  const idle = useAppStore((s) => s.pipelineState === 'idle' && s.pipelineError === null)
  const menuOpen = useAppStore((s) => s.translationTargetMenuOpen)

  useEffect(() => {
    let cancelled = false
    let timer: number | undefined
    let ignoring: boolean | null = null

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
            const overContent = Boolean(target && root && target !== root && root.contains(target))
            // The idle bar is decoration only: never let it catch a click.
            const overIdleBar = Boolean(idle && target?.closest('[data-capsule-view="idle"]'))
            setIgnore(!overContent || overIdleBar)
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
  }, [idle, menuOpen, rootRef])
}
