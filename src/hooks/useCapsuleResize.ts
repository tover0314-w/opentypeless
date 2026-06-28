import { useEffect } from 'react'
import { useAppStore, type PipelineState } from '../stores/appStore'
import { shouldShowSavedRecordingHint } from '../lib/capsuleError'

/** Distance (logical px) from the top edge of the screen to the capsule. */
const TOP_MARGIN = 14

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
  justCompleted: boolean
}

export function getCapsuleVisibility({
  capsuleAutoHide,
  contextMenuOpen,
  capsuleExpanded,
  hasError,
  pipelineState,
  justCompleted,
}: CapsuleVisibilityInput): boolean {
  return (
    !capsuleAutoHide ||
    contextMenuOpen ||
    capsuleExpanded ||
    hasError ||
    justCompleted ||
    pipelineState !== 'idle'
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
  showSavedHint: boolean,
  justCompleted: boolean,
): CapsuleSize {
  if (contextMenuOpen) return { width: 220, height: 220 }
  if (hasError) return showSavedHint ? { width: 230, height: 54 } : { width: 200, height: 36 }
  if (expanded) return { width: 220, height: 90 }
  // Completion confirmation ("✓ Transcribed") — also covers the brief
  // `outputting` state, which renders the same view.
  if (justCompleted) return { width: 140, height: 36 }
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
  const pipelineErrorKey = useAppStore((s) => s.pipelineErrorKey)
  const saveRecordings = useAppStore((s) => s.config.save_recordings)
  const justCompleted = useAppStore((s) => s.justCompleted)

  const hasError = pipelineError !== null
  const showSavedHint = shouldShowSavedRecordingHint(pipelineErrorKey, saveRecordings)

  useEffect(() => {
    const size = getSizeForState(
      pipelineState,
      capsuleExpanded,
      hasError,
      contextMenuOpen,
      showSavedHint,
      justCompleted,
    )
    const windowWidth = size.width + 24
    const windowHeight = size.height + 24
    const shouldShow = getCapsuleVisibility({
      capsuleAutoHide,
      contextMenuOpen,
      capsuleExpanded,
      hasError,
      pipelineState,
      justCompleted,
    })

    import('@tauri-apps/api/window')
      .then(async ({ getCurrentWindow, LogicalSize, LogicalPosition, currentMonitor }) => {
        const win = getCurrentWindow()
        await win.setFocusable(getCapsuleFocusable()).catch(() => {})
        await win.setSize(new LogicalSize(windowWidth, windowHeight)).catch(() => {})

        // Re-anchor to the top-center of the active monitor on EVERY state
        // change. The window is content-sized so a horizontally-centered window
        // keeps the capsule screen-centered as it grows/shrinks. Recomputing
        // each time (instead of only on first mount, left-edge anchored)
        // prevents both the off-center drift as it grows AND the top-left
        // fallback when monitor info isn't ready on the first pass.
        const anchorTopCenter = async (): Promise<boolean> => {
          try {
            const monitor = await currentMonitor()
            if (!monitor) return false
            const sw = monitor.size.width / monitor.scaleFactor
            const x = Math.round(sw / 2 - windowWidth / 2)
            const y = TOP_MARGIN
            await win.setPosition(new LogicalPosition(x, y)).catch(() => {})
            return true
          } catch {
            return false
          }
        }
        if (!(await anchorTopCenter())) {
          // Monitor not ready yet — retry once so we never sit at the top-left.
          setTimeout(() => void anchorTopCenter(), 150)
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
    showSavedHint,
    justCompleted,
  ])

  return getSizeForState(
    pipelineState,
    capsuleExpanded,
    hasError,
    contextMenuOpen,
    showSavedHint,
    justCompleted,
  )
}
