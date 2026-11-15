import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../../../stores/appStore'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import { Capsule } from '../index'
import { UPDATE_INSTALL_EVENT, UPDATE_READY_EVENT } from '../../../lib/appUpdaterEvents'

const { listeners, mockEmitTo } = vi.hoisted(() => ({
  listeners: new Map<string, (event: { payload: unknown }) => void>(),
  mockEmitTo: vi.fn(),
}))

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async (name: string, handler: (event: { payload: unknown }) => void) => {
    listeners.set(name, handler)
    return () => listeners.delete(name)
  }),
  emit: vi.fn().mockResolvedValue(undefined),
  emitTo: mockEmitTo,
}))
vi.mock('../../../lib/tauri', () => ({}))
vi.mock('../../../hooks/useCapsuleResize', () => ({ useCapsuleResize: vi.fn() }))
vi.mock('../../../hooks/useCapsuleClickThrough', () => ({ useCapsuleClickThrough: vi.fn() }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({ writeText: vi.fn() }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))

async function announce(version: string) {
  await act(async () => {
    listeners.get(UPDATE_READY_EVENT)?.({ payload: version })
  })
}

beforeEach(() => {
  vi.useFakeTimers()
  mockEmitTo.mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  listeners.clear()
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.getState().dismiss()
})

describe('capsule update alert', () => {
  it('waits for eight idle seconds, then asks the main window to install', async () => {
    render(<Capsule />)
    await act(async () => {})
    await announce('1.2.0')

    await act(async () => {
      vi.advanceTimersByTime(7999)
    })
    expect(screen.queryByText('updates.availableTitle')).toBeNull()
    await act(async () => {
      vi.advanceTimersByTime(1)
    })
    expect(screen.getByText('updates.availableTitle')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'updates.check.restart' }))
    expect(mockEmitTo).toHaveBeenCalledWith('main', UPDATE_INSTALL_EVENT)
    expect(screen.queryByText('updates.availableTitle')).toBeNull()
  })

  it('holds off while dictation is running', async () => {
    useAppStore.setState({ pipelineState: 'recording' })
    render(<Capsule />)
    await act(async () => {})
    await announce('1.2.0')
    await act(async () => {
      vi.advanceTimersByTime(10_000)
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()

    await act(async () => {
      useAppStore.setState({ pipelineState: 'idle' })
    })
    await act(async () => {
      vi.advanceTimersByTime(8000)
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('update_available')
  })

  it('shows once per version', async () => {
    render(<Capsule />)
    await act(async () => {})
    await announce('1.2.0')
    await act(async () => {
      vi.advanceTimersByTime(8000)
    })
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    await announce('1.2.0')
    await act(async () => {
      vi.advanceTimersByTime(8000)
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })
})
