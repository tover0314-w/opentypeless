import { cleanup, render, screen } from '@testing-library/react'
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DoneStep } from '../DoneStep'
import * as tauri from '../../../lib/tauri'

const mockConfig = {
  hotkey: 'Fn',
  ask_hotkey: 'Fn+Space',
  hotkey_mode: 'toggle',
  output_mode: 'clipboard',
}

vi.mock('framer-motion', () => ({
  motion: new Proxy(
    {},
    {
      get:
        (_target, tag: string) =>
        ({ children, ...props }: any) =>
          React.createElement(tag, props, children),
    },
  ),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'onboarding.done.title': "You're all set!",
        'onboarding.done.capsuleAppearsWhenRecording':
          'The capsule appears while recording and processing.',
        'onboarding.done.holdPress': 'Hold / Press',
        'onboarding.done.holdPressSub': 'Use your hotkey to start and stop recording',
        'onboarding.test.hold': 'Hold',
        'onboarding.test.press': 'Press',
        'onboarding.done.askAnything': 'Ask Anything',
        'onboarding.done.askAnythingSub':
          'Use the Ask hotkey to record a question; stop to get one answer.',
        'onboarding.done.dragToReposition': 'Drag to Reposition',
        'onboarding.done.dragToRepositionSub': 'When visible, drag the capsule anywhere on screen.',
        'onboarding.done.rightClickMenu': 'Right-click Menu',
        'onboarding.done.rightClickMenuSub': 'Right-click the capsule for more options',
        'onboarding.done.restoreCapsuleSub':
          'Use the tray or Settings to keep the capsule visible while idle.',
      })[key] ?? key,
  }),
}))

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: any) =>
    selector({
      config: mockConfig,
    }),
}))

vi.mock('../../../lib/tauri', () => ({
  checkAccessibilityPermission: vi.fn().mockResolvedValue(true),
  requestAccessibilityPermission: vi.fn().mockResolvedValue(true),
  waitForAccessibilityPermission: vi.fn().mockResolvedValue(true),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(mockConfig, {
    hotkey: 'Fn',
    ask_hotkey: 'Fn+Space',
    hotkey_mode: 'toggle',
    output_mode: 'clipboard',
  })
})

afterEach(() => cleanup())

describe('DoneStep', () => {
  it('uses a centered full-page summary while preserving usage tips', () => {
    const { container } = render(<DoneStep />)
    expect(screen.getByRole('heading', { level: 1 }).className).toContain('text-[36px]')
    expect(container.querySelector('[data-shortcut-summary]')?.className).toContain('grid')
    expect(container.querySelector('[data-shortcut-summary]')?.className).toContain(
      'sm:grid-cols-2',
    )
    expect(container.querySelector('[data-shortcut-summary] > :first-child')?.className).toContain(
      'sm:col-span-2',
    )
    expect(screen.getByText('Drag to Reposition')).toBeInTheDocument()
  })

  it('uses shared keycaps for both configured shortcut hints', () => {
    Object.assign(mockConfig, { hotkey: 'Fn+LeftShift', ask_hotkey: 'Ctrl+K' })
    const { container } = render(<DoneStep />)
    expect(
      [...container.querySelectorAll('kbd.keycap')].map((keycap) => keycap.textContent),
    ).toEqual(['Fn', 'Left Shift', 'Ctrl', 'K'])
  })

  it.each([
    ['macOS', 'Fn', 'Fn+Space', 'toggle', 'Press Fn', 'Ask Anything Fn+Space'],
    ['Windows', 'Ctrl+/', 'Ctrl+.', 'hold', 'Hold Ctrl+/', 'Ask Anything Ctrl+.'],
    ['Linux', 'Ctrl+/', 'Ctrl+.', 'hold', 'Hold Ctrl+/', 'Ask Anything Ctrl+.'],
  ])(
    'teaches current %s shortcuts and capsule controls during onboarding',
    (_platform, hotkey, askHotkey, mode, dictationTitle, askTitle) => {
      Object.assign(mockConfig, {
        hotkey,
        ask_hotkey: askHotkey,
        hotkey_mode: mode,
      })

      render(<DoneStep />)

      expect(
        screen.getByText(
          (_text, element) =>
            element?.tagName === 'P' &&
            element.textContent?.replace(/[+ ]/g, '') === dictationTitle.replace(/[+ ]/g, ''),
        ),
      ).toBeInTheDocument()
      expect(
        screen.getByText(
          (_text, element) =>
            element?.tagName === 'P' &&
            element.textContent?.replace(/[+ ]/g, '') === askTitle.replace(/[+ ]/g, ''),
        ),
      ).toBeInTheDocument()
      expect(
        screen.getByText('Use the Ask hotkey to record a question; stop to get one answer.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Drag to Reposition')).toBeInTheDocument()
      expect(screen.queryByText('Right-click the capsule for more options')).not.toBeInTheDocument()
      expect(screen.queryByText('Click Capsule')).not.toBeInTheDocument()
    },
  )

  it('does not show Ask Anything guidance when the shortcut is disabled', () => {
    Object.assign(mockConfig, {
      ask_hotkey: '',
    })

    render(<DoneStep />)

    expect(screen.getByText('Press', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('Fn', { selector: 'kbd' })).toBeInTheDocument()
    expect(screen.queryByText(/Ask Anything/)).not.toBeInTheDocument()
  })

  it('does not repeat the Accessibility grant already handled by Permissions', () => {
    const originalPlatform = window.navigator.platform
    Object.defineProperty(window.navigator, 'platform', {
      value: 'MacIntel',
      configurable: true,
    })
    Object.assign(mockConfig, { output_mode: 'keyboard' })

    try {
      render(<DoneStep />)

      expect(tauri.checkAccessibilityPermission).not.toHaveBeenCalled()
      expect(screen.queryByText('onboarding.done.accessibilityRequired')).not.toBeInTheDocument()
    } finally {
      Object.defineProperty(window.navigator, 'platform', {
        value: originalPlatform,
        configurable: true,
      })
    }
  })
})
