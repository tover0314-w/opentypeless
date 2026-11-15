import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { UpdatePrompt } from '../UpdatePrompt'
import { __resetUpdaterForTests, useUpdaterStore } from '../../lib/appUpdater'

const { mockCheck, mockRelaunch } = vi.hoisted(() => ({
  mockCheck: vi.fn(),
  mockRelaunch: vi.fn(),
}))

vi.mock('@tauri-apps/plugin-updater', () => ({ check: mockCheck }))
vi.mock('@tauri-apps/plugin-process', () => ({ relaunch: mockRelaunch }))
vi.mock('@tauri-apps/api/event', () => ({ emit: vi.fn().mockResolvedValue(undefined) }))
vi.mock('../../lib/tauri', () => ({
  isDevelopmentBuild: vi.fn().mockResolvedValue(false),
  setTrayUpdateStatus: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

function downloadedUpdate(install = vi.fn().mockResolvedValue(undefined)) {
  return { version: '1.2.0', download: vi.fn().mockResolvedValue(undefined), install }
}

beforeEach(() => {
  __resetUpdaterForTests()
  vi.spyOn(document, 'hasFocus').mockReturnValue(true)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

describe('UpdatePrompt', () => {
  it.each(['success', 'failure'] as const)(
    'leaves a handed-off installation running but ignores late %s after unmount',
    async (outcome) => {
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      let finish!: () => void
      let fail!: (error: Error) => void
      const install = vi.fn(
        () =>
          new Promise<void>((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      mockRelaunch.mockResolvedValue(undefined)
      mockCheck.mockResolvedValueOnce(downloadedUpdate(install))
      const { unmount } = render(<UpdatePrompt />)
      fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
      await waitFor(() => expect(install).toHaveBeenCalledOnce())
      unmount()
      await act(async () => {
        if (outcome === 'success') finish()
        else fail(new Error('late installation failure'))
      })
      expect(warning).not.toHaveBeenCalled()
      expect(mockRelaunch).toHaveBeenCalledTimes(outcome === 'success' ? 1 : 0)
    },
  )

  it('does not carry an installation error into a different version notice', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    mockCheck.mockResolvedValueOnce(
      downloadedUpdate(vi.fn().mockRejectedValueOnce(new Error('installation failed'))),
    )
    render(<UpdatePrompt />)
    fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
    expect(await screen.findByText('updates.error')).toBeInTheDocument()

    act(() => useUpdaterStore.setState({ notifyVersion: '1.3.0' }))

    expect(screen.queryByText('updates.error')).not.toBeInTheDocument()
    expect(screen.getByText('updates.check.downloadedText')).toBeInTheDocument()
  })

  it('does not mark a new version notice failed when an older installation rejects late', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    let fail!: (error: Error) => void
    const install = vi.fn(
      () =>
        new Promise<void>((_resolve, reject) => {
          fail = reject
        }),
    )
    mockCheck.mockResolvedValueOnce(downloadedUpdate(install))
    render(<UpdatePrompt />)
    fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
    await waitFor(() => expect(install).toHaveBeenCalledOnce())
    act(() => useUpdaterStore.setState({ notifyVersion: '1.3.0' }))
    await act(async () => fail(new Error('older installation failed')))

    expect(screen.queryByText('updates.error')).not.toBeInTheDocument()
    expect(screen.getByText('updates.check.downloadedText')).toBeInTheDocument()
  })

  it('stays hidden when no update is available', async () => {
    mockCheck.mockResolvedValueOnce(null)
    render(<UpdatePrompt />)
    await waitFor(() => expect(useUpdaterStore.getState().phase).toBe('up-to-date'))
    expect(screen.queryByText('updates.availableTitle')).not.toBeInTheDocument()
  })

  it('shows the downloaded notice and restarts into the new version', async () => {
    const update = downloadedUpdate()
    mockCheck.mockResolvedValueOnce(update)
    render(<UpdatePrompt />)

    expect(await screen.findByText('updates.availableTitle')).toBeInTheDocument()
    expect(screen.getByText('updates.check.downloadedText')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'updates.check.restart' }))

    await waitFor(() => expect(mockRelaunch).toHaveBeenCalled())
    expect(update.install).toHaveBeenCalled()
  })

  it('shows the website fallback when install fails', async () => {
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    mockCheck.mockResolvedValueOnce(
      downloadedUpdate(
        vi.fn().mockRejectedValue(new Error('https://example.test/update?token=private-detail')),
      ),
    )
    render(<UpdatePrompt />)

    fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
    expect(await screen.findByText('updates.error')).toBeInTheDocument()
    expect(mockRelaunch).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledExactlyOnceWith('Failed to install update')
  })

  it('dismisses until a different version arrives', async () => {
    mockCheck.mockResolvedValueOnce(downloadedUpdate())
    render(<UpdatePrompt />)
    fireEvent.click(await screen.findByRole('button', { name: 'updates.dismiss' }))
    expect(screen.queryByText('updates.availableTitle')).not.toBeInTheDocument()
    expect(useUpdaterStore.getState().dismissedVersion).toBe('1.2.0')
  })

  it('checks again when the window regains focus', async () => {
    mockCheck.mockResolvedValue(null)
    render(<UpdatePrompt />)
    await waitFor(() => expect(mockCheck).toHaveBeenCalledTimes(1))
    await act(async () => {
      window.dispatchEvent(new Event('focus'))
    })
    await waitFor(() => expect(mockCheck).toHaveBeenCalledTimes(2))
  })
})
