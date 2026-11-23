import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CheckUpdateDialog } from '../CheckUpdateDialog'
import { AboutPane } from '../AboutPane'
import { __resetUpdaterForTests } from '../../../lib/appUpdater'

const { mockCheck, mockRelaunch, mockIsDev, mockOpenUrl } = vi.hoisted(() => ({
  mockCheck: vi.fn(),
  mockRelaunch: vi.fn(),
  mockIsDev: vi.fn(),
  mockOpenUrl: vi.fn(),
}))

vi.mock('@tauri-apps/plugin-updater', () => ({ check: mockCheck }))
vi.mock('../../../lib/secureUpdater', () => ({ checkSecureUpdate: mockCheck }))
vi.mock('@tauri-apps/plugin-process', () => ({ relaunch: mockRelaunch }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mockOpenUrl }))
vi.mock('@tauri-apps/api/event', () => ({ emit: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@tauri-apps/api/app', () => ({ getVersion: vi.fn().mockResolvedValue('1.1.64') }))
vi.mock('../../../lib/tauri', () => ({
  isDevelopmentBuild: mockIsDev,
  setTrayUpdateStatus: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, values?: Record<string, string>) =>
      values ? `${key}:${Object.values(values).join('|')}` : key,
    i18n: { language: 'zh' },
  }),
}))

beforeEach(() => {
  __resetUpdaterForTests()
  mockIsDev.mockResolvedValue(false)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

function phase() {
  return screen.getByRole('dialog').getAttribute('data-update-phase')
}

describe('CheckUpdateDialog', () => {
  it('does not interrupt a handed-off installation when its dialog unmounts', async () => {
    let finish!: () => void
    const install = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    mockRelaunch.mockResolvedValue(undefined)
    mockCheck.mockResolvedValueOnce({
      version: '1.2.0',
      download: vi.fn().mockResolvedValue(undefined),
      install,
    })
    const { unmount } = render(<CheckUpdateDialog onClose={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
    await waitFor(() => expect(install).toHaveBeenCalledOnce())
    unmount()
    await act(async () => finish())
    expect(mockRelaunch).toHaveBeenCalledOnce()
  })

  it('ignores an installation rejection after its dialog unmounts', async () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    let fail!: (error: Error) => void
    const install = vi.fn(
      () =>
        new Promise<void>((_resolve, reject) => {
          fail = reject
        }),
    )
    mockCheck.mockResolvedValueOnce({
      version: '1.2.0',
      download: vi.fn().mockResolvedValue(undefined),
      install,
    })
    const { unmount } = render(<CheckUpdateDialog onClose={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
    await waitFor(() => expect(install).toHaveBeenCalledOnce())
    unmount()
    await act(async () => fail(new Error('late installation failure')))
    expect(errorLog).not.toHaveBeenCalled()
    expect(mockRelaunch).not.toHaveBeenCalled()
  })

  it.each(['install', 'relaunch'] as const)(
    'retries a failed %s without checking or downloading the update again',
    async (failure) => {
      const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
      const install = vi.fn().mockResolvedValue(undefined)
      const download = vi.fn().mockResolvedValue(undefined)
      mockRelaunch.mockResolvedValue(undefined)
      if (failure === 'install') install.mockRejectedValueOnce(new Error('installation failed'))
      else mockRelaunch.mockRejectedValueOnce(new Error('relaunch failed'))
      mockCheck.mockResolvedValueOnce({ version: '1.2.0', download, install })
      render(<CheckUpdateDialog onClose={() => {}} />)

      fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
      expect(await screen.findByText('updates.check.titleError')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
      fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))

      await waitFor(() => expect(mockRelaunch).toHaveBeenCalledTimes(failure === 'install' ? 1 : 2))
      expect(install).toHaveBeenCalledTimes(failure === 'install' ? 2 : 1)
      expect(mockCheck).toHaveBeenCalledOnce()
      expect(download).toHaveBeenCalledOnce()
      expect(errorLog).toHaveBeenCalledExactlyOnceWith('Failed to install update')
    },
  )

  it('keeps installation error details out of logs while showing the existing retry UI', async () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    const install = vi
      .fn()
      .mockRejectedValueOnce(new Error('https://example.test/update?token=private-detail'))
    mockCheck.mockResolvedValueOnce({
      version: '1.2.0',
      download: vi.fn().mockResolvedValue(undefined),
      install,
    })
    render(<CheckUpdateDialog onClose={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: 'updates.check.restart' }))
    expect(await screen.findByText('updates.check.titleError')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'common.retry' })).toBeInTheDocument()
    expect(mockRelaunch).not.toHaveBeenCalled()
    expect(errorLog).toHaveBeenCalledExactlyOnceWith('Failed to install update')
    expect(screen.queryByText(/private-detail/)).toBeNull()
  })

  it('shows a safe fallback instead of private details for an unknown check error', async () => {
    mockCheck.mockRejectedValueOnce(new Error('/Users/synthetic/private-update-file'))
    render(<CheckUpdateDialog onClose={() => {}} />)
    expect(await screen.findByText('updates.check.errors.other')).toBeInTheDocument()
    expect(screen.queryByText(/private-update-file/)).toBeNull()
    expect(screen.getByRole('button', { name: 'common.retry' })).toBeInTheDocument()
  })

  it('clears automatic restart when a downloaded update dialog unmounts', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const install = vi.fn().mockResolvedValue(undefined)
    mockCheck.mockResolvedValueOnce({
      version: '1.2.0',
      download: vi.fn().mockResolvedValue(undefined),
      install,
    })
    const { unmount } = render(<CheckUpdateDialog onClose={() => {}} />)
    expect(await screen.findByText('updates.check.titleDownloaded')).toBeInTheDocument()
    unmount()
    await act(async () => {
      vi.advanceTimersByTime(3000)
    })
    expect(install).not.toHaveBeenCalled()
    expect(mockRelaunch).not.toHaveBeenCalled()
  })

  it('starts in the checking state with an indeterminate bar', () => {
    mockCheck.mockReturnValueOnce(new Promise(() => {}))
    render(<CheckUpdateDialog onClose={() => {}} />)
    expect(phase()).toBe('checking')
    expect(screen.getByText('updates.check.checkingText')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).not.toHaveAttribute('aria-valuenow')
  })

  it('reports up to date with the bundle version', async () => {
    mockCheck.mockResolvedValueOnce(null)
    const onClose = vi.fn()
    render(<CheckUpdateDialog onClose={onClose} />)
    expect(await screen.findByText('updates.check.titleUpToDate')).toBeInTheDocument()
    expect(screen.getByText('updates.check.upToDateText:OpenTypeless|1.1.64')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'updates.check.gotIt' }))
    expect(onClose).toHaveBeenCalled()
  })

  it('downloads, then restarts by itself after three seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const install = vi.fn().mockResolvedValue(undefined)
    mockCheck.mockResolvedValueOnce({
      version: '1.2.0',
      download: vi.fn().mockResolvedValue(undefined),
      install,
    })
    render(<CheckUpdateDialog onClose={() => {}} />)
    expect(await screen.findByText('updates.check.titleDownloaded')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'updates.check.restart' })).toBeInTheDocument()
    expect(mockRelaunch).not.toHaveBeenCalled()

    await act(async () => {
      vi.advanceTimersByTime(3000)
    })
    await waitFor(() => expect(mockRelaunch).toHaveBeenCalled())
    expect(install).toHaveBeenCalled()
  })

  it('explains development builds and retries on demand', async () => {
    mockIsDev.mockResolvedValueOnce(true)
    render(<CheckUpdateDialog onClose={() => {}} />)
    expect(await screen.findByText('updates.check.errors.development')).toBeInTheDocument()
    expect(phase()).toBe('error')

    mockCheck.mockResolvedValueOnce(null)
    fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
    expect(await screen.findByText('updates.check.titleUpToDate')).toBeInTheDocument()
  })

  it('lists the read-only fix steps', async () => {
    mockCheck.mockRejectedValueOnce(new Error('Read-only file system (os error 30)'))
    render(<CheckUpdateDialog onClose={() => {}} />)
    expect(await screen.findByText('updates.check.errors.readOnlyStep3')).toBeInTheDocument()
  })

  it('links to the localized download page when the signature fails', async () => {
    mockCheck.mockRejectedValueOnce(new Error('signature verification failed'))
    render(<CheckUpdateDialog onClose={() => {}} />)
    fireEvent.click(await screen.findByText('https://www.opentypeless.com/zh/download'))
    expect(mockOpenUrl).toHaveBeenCalledWith('https://www.opentypeless.com/zh/download')
  })

  it('closes on Escape without stopping the download', async () => {
    mockCheck.mockReturnValueOnce(new Promise(() => {}))
    const onClose = vi.fn()
    render(<CheckUpdateDialog onClose={onClose} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})

describe('AboutPane', () => {
  it('shows the bundle version and opens localized legal pages', async () => {
    render(<AboutPane />)
    expect(await screen.findByText('v1.1.64')).toBeInTheDocument()
    fireEvent.click(screen.getByText('settings.aboutPage.terms'))
    expect(mockOpenUrl).toHaveBeenCalledWith('https://www.opentypeless.com/zh/terms')
  })

  it('opens the update dialog from the button', async () => {
    mockCheck.mockReturnValueOnce(new Promise(() => {}))
    render(<AboutPane />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.aboutPage.checkUpdates' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('opens the dialog shortly after arriving from the tray', async () => {
    vi.useFakeTimers()
    mockCheck.mockReturnValueOnce(new Promise(() => {}))
    render(<AboutPane autoCheck="123" />)
    expect(screen.queryByRole('dialog')).toBeNull()
    await act(async () => {
      vi.advanceTimersByTime(1100)
    })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
