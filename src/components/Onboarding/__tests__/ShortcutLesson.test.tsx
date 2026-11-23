import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ShortcutLesson } from '../ShortcutLesson'
import i18n from '../../../i18n'
import { useAppStore } from '../../../stores/appStore'

const mocks = vi.hoisted(() => ({
  invoke: vi.fn().mockResolvedValue(undefined),
  channels: [] as Array<{ onmessage?: (payload: unknown) => void }>,
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: mocks.invoke,
  Channel: class {
    onmessage?: (payload: unknown) => void
    constructor() {
      mocks.channels.push(this)
    }
  },
}))

afterEach(() => {
  cleanup()
  mocks.invoke.mockClear()
  mocks.channels.length = 0
})

describe('shortcut lesson', () => {
  it('publishes owned press feedback for the right-hand surface and clears it on unmount', async () => {
    const onPressedChange = vi.fn()
    const { container, unmount } = render(
      <ShortcutLesson
        mode="dictate"
        onContinue={() => {}}
        onConfigure={() => {}}
        onPressedChange={onPressedChange}
      />,
    )
    await act(async () => {})
    const registration = mocks.invoke.mock.calls.find(
      ([command]) => command === 'register_practice_shortcut',
    )![1]
    expect(container.querySelector('kbd.text-\\[36px\\]')).toBeNull()
    act(() => mocks.channels[0].onmessage?.({ leaseId: registration.leaseId, pressed: true }))
    expect(onPressedChange).toHaveBeenLastCalledWith(true)
    unmount()
    expect(onPressedChange).toHaveBeenLastCalledWith(false)
  })

  it('requires a fresh hit after the configured shortcut changes', async () => {
    const previous = useAppStore.getState().config
    try {
      render(<ShortcutLesson mode="translate" onContinue={() => {}} onConfigure={() => {}} />)
      await act(async () => {})
      const registration = mocks.invoke.mock.calls.find(
        ([command]) => command === 'register_practice_shortcut',
      )![1]
      act(() => mocks.channels[0].onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
      expect(next).toBeEnabled()
      act(() => {
        const config = useAppStore.getState().config
        useAppStore.getState().updateConfig({
          hotkeys: {
            ...config.hotkeys,
            translate: { primary: 'K', modifiers: ['Ctrl'] },
            translateBindings: [{ primary: 'K', modifiers: ['Ctrl'] }],
          },
        })
      })
      expect(next).toBeDisabled()
    } finally {
      cleanup()
      useAppStore.getState().setConfig(previous)
    }
  })

  it('unlocks only after an owned native shortcut press without recording', async () => {
    const onContinue = vi.fn()
    render(<ShortcutLesson mode="translate" onContinue={onContinue} onConfigure={() => {}} />)
    await act(async () => {})
    const next = screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })
    expect(next).toBeDisabled()
    const registration = mocks.invoke.mock.calls.find(
      ([command]) => command === 'register_practice_shortcut',
    )![1]
    const channel = mocks.channels[0]
    act(() => channel.onmessage?.({ leaseId: 'foreign', pressed: true }))
    expect(next).toBeDisabled()
    act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
    expect(next).toBeDisabled()
    act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
    expect(next).toBeEnabled()
    fireEvent.click(next)
    expect(onContinue).toHaveBeenCalledOnce()
    expect(mocks.invoke.mock.calls.some(([command]) => command === 'start_practice')).toBe(false)
  })

  it('opens the existing shortcut configuration path without claiming a shortcut hit', () => {
    const onConfigure = vi.fn()
    render(<ShortcutLesson mode="dictate" onContinue={() => {}} onConfigure={onConfigure} />)
    fireEvent.click(screen.getByRole('button', { name: i18n.t('settings.hotkey') }))
    expect(onConfigure).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeDisabled()
  })
})
