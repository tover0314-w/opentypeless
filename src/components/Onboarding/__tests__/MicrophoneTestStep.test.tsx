import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MicrophoneTestStep } from '../MicrophoneTestStep'
import i18n from '../../../i18n'
import { useAppStore } from '../../../stores/appStore'

const mocks = vi.hoisted(() => ({
  start: vi.fn(),
  level: vi.fn(),
  stop: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../../lib/tauri', () => ({
  startMicrophonePreview: mocks.start,
  getMicrophonePreviewLevel: mocks.level,
  stopMicrophonePreview: mocks.stop,
}))

afterEach(() => {
  cleanup()
  mocks.start.mockReset()
  mocks.level.mockReset()
  mocks.stop.mockReset().mockResolvedValue(undefined)
})

it('joins a pending stop before continuing instead of issuing a second native stop', async () => {
  mocks.start.mockResolvedValue(77)
  mocks.level.mockResolvedValue(0.1)
  let finishStop!: () => void
  mocks.stop.mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        finishStop = resolve
      }),
  )
  const onContinue = vi.fn()
  render(<MicrophoneTestStep onContinue={onContinue} />)
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
  const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.stop') }))
  fireEvent.click(next)
  expect(mocks.stop).toHaveBeenCalledTimes(1)
  expect(onContinue).not.toHaveBeenCalled()
  await act(async () => finishStop())
  expect(onContinue).toHaveBeenCalledOnce()
})

it('retries the owned microphone stop instead of forgetting its session after failure', async () => {
  mocks.start.mockResolvedValue(76)
  mocks.level.mockResolvedValue(0.1)
  mocks.stop.mockRejectedValueOnce(new Error('Synthetic stop failure'))
  const onContinue = vi.fn()
  render(<MicrophoneTestStep onContinue={onContinue} />)
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
  const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.stop') }))
  await screen.findByRole('alert')
  fireEvent.click(next)
  await waitFor(() => expect(onContinue).toHaveBeenCalledOnce())
  expect(mocks.stop.mock.calls).toEqual([[76], [76]])
})

it('does not continue after leaving while the microphone stop is pending', async () => {
  let finishStop!: () => void
  mocks.start.mockResolvedValue(74)
  mocks.level.mockResolvedValue(0.1)
  mocks.stop.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finishStop = resolve
      }),
  )
  const onContinue = vi.fn()
  const { unmount } = render(<MicrophoneTestStep onContinue={onContinue} />)
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
  const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(next)
  expect(next).toBeDisabled()
  unmount()
  await act(async () => finishStop())
  expect(onContinue).not.toHaveBeenCalled()
})

it('does not access the microphone on mount and requires real level observations before continuing', async () => {
  const onContinue = vi.fn()
  mocks.start.mockResolvedValue(71)
  mocks.level.mockResolvedValue(0.1)
  render(<MicrophoneTestStep onContinue={onContinue} />)
  expect(mocks.start).not.toHaveBeenCalled()
  const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
  expect(next).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(next)
  await waitFor(() => expect(onContinue).toHaveBeenCalledOnce())
  expect(mocks.stop).toHaveBeenCalledWith(71)
})

it('revokes the old device result and stops its preview when the configured device changes', async () => {
  const previous = useAppStore.getState().config
  mocks.start.mockResolvedValue(75)
  mocks.level.mockResolvedValue(0.1)
  try {
    render(<MicrophoneTestStep onContinue={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
    await waitFor(() => expect(next).toBeEnabled())
    act(() =>
      useAppStore.getState().updateConfig({ microphone_device: 'Synthetic USB microphone' }),
    )
    expect(next).toBeDisabled()
    await waitFor(() => expect(mocks.stop).toHaveBeenCalledWith(75))
    expect(mocks.start).toHaveBeenCalledTimes(1)
  } finally {
    cleanup()
    useAppStore.getState().setConfig(previous)
  }
})

it('stops a late preview start after unmount without polling it', async () => {
  let resolveStart!: (session: number) => void
  mocks.start.mockImplementationOnce(
    () =>
      new Promise<number>((resolve) => {
        resolveStart = resolve
      }),
  )
  const { unmount } = render(<MicrophoneTestStep onContinue={() => {}} />)
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
  unmount()
  await act(async () => resolveStart(72))
  expect(mocks.stop).toHaveBeenCalledWith(72)
  expect(mocks.level).not.toHaveBeenCalled()
})

it('does not complete on silence or malformed microphone levels', async () => {
  mocks.start.mockResolvedValue(73)
  mocks.level.mockResolvedValue(Number.NaN)
  const onContinue = vi.fn()
  render(<MicrophoneTestStep onContinue={onContinue} />)
  fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
  await waitFor(() => expect(mocks.stop).toHaveBeenCalledWith(73))
  expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeDisabled()
  expect(onContinue).not.toHaveBeenCalled()
})
