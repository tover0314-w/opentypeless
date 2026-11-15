import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useTheme } from '../useTheme'
import { useAppStore } from '../../stores/appStore'

const listeners = new Set<(event: { matches: boolean }) => void>()
let systemDark = false

beforeEach(() => {
  listeners.clear()
  systemDark = false
  useAppStore.setState(useAppStore.getInitialState())
  document.documentElement.classList.remove('dark')
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      get matches() {
        return systemDark
      },
      addEventListener: (_name: string, listener: (event: { matches: boolean }) => void) =>
        listeners.add(listener),
      removeEventListener: (_name: string, listener: (event: { matches: boolean }) => void) =>
        listeners.delete(listener),
    })),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  listeners.clear()
  document.documentElement.classList.remove('dark')
  useAppStore.setState(useAppStore.getInitialState())
})

function changeSystemTheme(matches: boolean) {
  act(() => {
    systemDark = matches
    for (const listener of listeners) listener({ matches })
  })
}

describe('system theme ownership', () => {
  it('ignores system input after a fixed theme is selected before effect cleanup', () => {
    renderHook(() => useTheme())
    const callback = [...listeners][0]
    act(() => {
      useAppStore.getState().updateConfig({ theme: 'light' })
      callback({ matches: true })
      expect(document.documentElement).not.toHaveClass('dark')
    })
  })

  it('ignores a discarded system callback after switching to a fixed theme', () => {
    renderHook(() => useTheme())
    const discarded = [...listeners][0]
    act(() => useAppStore.getState().updateConfig({ theme: 'light' }))
    act(() => discarded({ matches: true }))
    expect(document.documentElement).not.toHaveClass('dark')
  })

  it('follows system changes and releases its listener on unmount', () => {
    const { unmount } = renderHook(() => useTheme())
    expect(document.documentElement).not.toHaveClass('dark')
    expect(listeners.size).toBe(1)
    changeSystemTheme(true)
    expect(document.documentElement).toHaveClass('dark')
    changeSystemTheme(false)
    expect(document.documentElement).not.toHaveClass('dark')
    unmount()
    expect(listeners.size).toBe(0)
  })

  it('stops following system changes for a fixed theme and resumes with the latest preference', () => {
    renderHook(() => useTheme())
    changeSystemTheme(true)
    act(() => useAppStore.getState().updateConfig({ theme: 'light' }))
    expect(listeners.size).toBe(0)
    expect(document.documentElement).not.toHaveClass('dark')
    changeSystemTheme(true)
    expect(document.documentElement).not.toHaveClass('dark')
    act(() => useAppStore.getState().updateConfig({ theme: 'system' }))
    expect(document.documentElement).toHaveClass('dark')
    expect(listeners.size).toBe(1)
    act(() => useAppStore.getState().updateConfig({ theme: 'dark' }))
    changeSystemTheme(false)
    expect(document.documentElement).toHaveClass('dark')
    expect(listeners.size).toBe(0)
  })
})
