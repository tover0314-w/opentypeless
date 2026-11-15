import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { UsageStats } from '../../../lib/tauri'

const mocks = vi.hoisted(() => ({
  copyPngToClipboard: vi.fn(),
  savePngWithDialog: vi.fn(),
  openUrl: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mocks.openUrl }))
vi.mock('../../../lib/tauri', () => ({
  copyPngToClipboard: mocks.copyPngToClipboard,
  savePngWithDialog: mocks.savePngWithDialog,
}))
vi.mock('../../toast-service', () => ({
  toast: { success: mocks.toastSuccess, error: mocks.toastError },
}))
vi.mock('../shareCardCanvas', () => ({
  shareCardSize: (kind: string) => ({ width: 512, height: kind === 'insights' ? 460.8 : 288 }),
  narrowWeekdays: () => ['S', 'M', 'T', 'W', 'T', 'F', 'S'],
  renderShareCard: (kind: string) => ({ toDataURL: () => `data:image/png;base64,${kind}` }),
  canvasToPng: vi.fn(async () => new Uint8Array([0x89, 0x50, 0x4e, 0x47])),
}))

import { ShareInsightsDialog } from '../ShareInsightsDialog'

const stats: UsageStats = {
  totalWords: 500,
  totalSessions: 5,
  totalAudioSeconds: 300,
  averageWpm: 120,
  minutesSaved: 6,
  activeDays: 2,
  currentStreak: 1,
  longestStreak: 2,
  daily: [{ date: '2026-11-02', words: 500, sessions: 5 }],
}

async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
}

describe('ShareInsightsDialog', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.copyPngToClipboard.mockResolvedValue(undefined)
    mocks.openUrl.mockResolvedValue(undefined)
  })

  afterEach(() => cleanup())

  it('shows three cards and switches the preview between them', () => {
    render(<ShareInsightsDialog stats={stats} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'home.share.dialogLabel' })).toBeInTheDocument()
    expect(screen.getByTestId('share-card-preview')).toHaveAttribute('data-card-kind', 'insights')
    expect(screen.getByTestId('share-thumbnail-insights')).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(screen.getByTestId('share-thumbnail-activity'))
    expect(screen.getByTestId('share-card-preview')).toHaveAttribute('data-card-kind', 'activity')
    expect(screen.getByTestId('share-card-preview')).toHaveStyle({ height: '288px' })
    expect(screen.getByTestId('share-thumbnail-insights')).toHaveAttribute('aria-pressed', 'false')
  })

  it('copies the selected card as a PNG and confirms it', async () => {
    render(<ShareInsightsDialog stats={stats} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'home.share.copyImage' }))
    await flush()
    expect(mocks.copyPngToClipboard).toHaveBeenCalledWith(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))
    expect(mocks.toastSuccess).toHaveBeenCalledWith('home.share.feedback.copySuccess')
  })

  it('reports a copy failure', async () => {
    mocks.copyPngToClipboard.mockRejectedValue(new Error('clipboard busy'))
    render(<ShareInsightsDialog stats={stats} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'home.share.copyImage' }))
    await flush()
    expect(mocks.toastError).toHaveBeenCalledWith('home.share.feedback.copyFailed')
  })

  it('downloads with a dated file name and stays quiet when the save panel is cancelled', async () => {
    mocks.savePngWithDialog.mockRejectedValueOnce('cancelled')
    render(<ShareInsightsDialog stats={stats} onClose={vi.fn()} />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'home.share.more' }).parentElement!)
    fireEvent.click(screen.getByRole('menuitem', { name: 'home.share.downloadImage' }))
    await flush()
    expect(mocks.savePngWithDialog).toHaveBeenCalledWith(
      expect.any(Uint8Array),
      expect.stringMatching(/^OpenTypeless-insights-\d{4}-\d{2}-\d{2}\.png$/),
    )
    expect(mocks.toastSuccess).not.toHaveBeenCalled()
    expect(mocks.toastError).not.toHaveBeenCalled()

    mocks.savePngWithDialog.mockResolvedValueOnce('/Users/me/Downloads/x.png')
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'home.share.more' }).parentElement!)
    fireEvent.click(screen.getByRole('menuitem', { name: 'home.share.downloadImage' }))
    await flush()
    expect(mocks.toastSuccess).toHaveBeenCalledWith('home.share.feedback.downloadSuccess')
  })

  it('copies the image before opening the X composer', async () => {
    render(<ShareInsightsDialog stats={stats} onClose={vi.fn()} />)
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'home.share.more' }).parentElement!)
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('menuitem', { name: /home\.share\.shareX/ }))
    await flush()
    expect(mocks.copyPngToClipboard).toHaveBeenCalled()
    expect(mocks.openUrl).toHaveBeenCalledWith(
      'https://x.com/intent/post?text=home.share.socialText',
    )
    expect(mocks.toastSuccess).toHaveBeenCalledWith('home.share.feedback.xSuccess')
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('closes the hover menu shortly after the pointer leaves', () => {
    vi.useFakeTimers()
    try {
      render(<ShareInsightsDialog stats={stats} onClose={vi.fn()} />)
      const wrapper = screen.getByRole('button', { name: 'home.share.more' }).parentElement!
      fireEvent.mouseEnter(wrapper)
      fireEvent.mouseLeave(wrapper)
      expect(screen.getByRole('menu')).toBeInTheDocument()
      act(() => {
        vi.advanceTimersByTime(120)
      })
      expect(screen.queryByRole('menu')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('closes from the corner button', () => {
    const onClose = vi.fn()
    render(<ShareInsightsDialog stats={stats} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    expect(onClose).toHaveBeenCalled()
  })
})
