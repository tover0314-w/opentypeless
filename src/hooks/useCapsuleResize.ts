import { useEffect } from 'react'
import { useAppStore, type PipelineState } from '../stores/appStore'

/** Distance (logical px) from the bottom edge of the screen to the capsule. */
const BOTTOM_MARGIN = 80

interface CapsuleSize {
  width: number
  height: number
}

export interface CapsuleVisibilityInput {
  capsuleAutoHide: boolean
  contextMenuOpen: boolean
  capsuleExpanded: boolean
  hasError: boolean
  pipelineState: PipelineState
}

export function getCapsuleVisibility({
  capsuleAutoHide,
  contextMenuOpen,
  capsuleExpanded,
  hasError,
  pipelineState,
}: CapsuleVisibilityInput): boolean {
  return (
    !capsuleAutoHide || contextMenuOpen || capsuleExpanded || hasError || pipelineState !== 'idle'
  )
}

export function getCapsuleFocusable(): boolean {
  return false
}

function getSizeForState(
  state: PipelineState,
  expanded: boolean,
  hasError: boolean,
  contextMenuOpen: boolean,
): CapsuleSize {
  if (contextMenuOpen) return { width: 220, height: 220 }
  if (hasError) return { width: 200, height: 36 }
  if (expanded) return { width: 220, height: 90 }
  switch (state) {
    case 'idle':
      return { width: 36, height: 36 }
    case 'recording':
      return { width: 200, height: 36 }
    case 'transcribing':
    case 'polishing':
      return { width: 220, height: 36 }
    case 'outputting':
      return { width: 120, height: 36 }
    default:
      return { width: 36, height: 36 }
  }
}

export function useCapsuleResize() {
  const pipelineState = useAppStore((s) => s.pipelineState)
  const capsuleExpanded = useAppStore((s) => s.capsuleExpanded)
  const pipelineError = useAppStore((s) => s.pipelineError)
  const contextMenuOpen = useAppStore((s) => s.contextMenuOpen)
  const setContextMenuReady = useAppStore((s) => s.setContextMenuReady)
  const capsuleAutoHide = useAppStore((s) => s.config.capsule_auto_hide)

  const hasError = pipelineError !== null

  useEffect(() => {
    const size = getSizeForState(pipelineState, capsuleExpanded, hasError, contextMenuOpen)
    const windowWidth = size.width + 24
    const windowHeight = size.height + 24
    const shouldShow = getCapsuleVisibility({
      capsuleAutoHide,
      contextMenuOpen,
      capsuleExpanded,
      hasError,
      pipelineState,
    })

    import('@tauri-apps/api/window')
      .then(async ({ getCurrentWindow, LogicalSize, LogicalPosition, currentMonitor }) => {
        const win = getCurrentWindow()
        await win.setFocusable(getCapsuleFocusable()).catch(() => {})
        await win.setSize(new LogicalSize(windowWidth, windowHeight)).catch(() => {})

        // Re-anchor to the bottom-center of the active monitor on EVERY state
        // change. The window is content-sized (+12px padding each side) so a
        // centered window keeps the capsule screen-centered as it grows/shrinks.
        // Recomputing each time (instead of only on first mount, left-edge
        // anchored) prevents both the off-center drift when recording AND the
        // top-left fallback when monitor info isn't ready on the first pass.
        const anchorBottomCenter = async (): Promise<boolean> => {
          try {
            const monitor = await currentMonitor()
            if (!monitor) return false
            const sw = monitor.size.width / monitor.scaleFactor
            const sh = monitor.size.height / monitor.scaleFactor
            const x = Math.round(sw / 2 - windowWidth / 2)
            const y = Math.round(sh - windowHeight - BOTTOM_MARGIN)
            await win.setPosition(new LogicalPosition(x, y)).catch(() => {})
            return true
          } catch {
            return false
          }
        }
        if (!(await anchorBottomCenter())) {
          // Monitor not ready yet — retry once so we never sit at the top-left.
          setTimeout(() => void anchorBottomCenter(), 150)
        }

        // Signal that the window has finished resizing for the context menu.
        if (contextMenuOpen) {
          setContextMenuReady(true)
        }

        if (shouldShow) {
          await win.show().catch(() => {})
        } else {
          await win.hide().catch(() => {})
        }
      })
      .catch(() => {})
  }, [
    pipelineState,
    capsuleExpanded,
    hasError,
    contextMenuOpen,
    capsuleAutoHide,
    setContextMenuReady,
  ])

  return getSizeForState(pipelineState, capsuleExpanded, hasError, contextMenuOpen)
}
