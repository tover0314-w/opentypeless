import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QuickTestStep } from '../QuickTestStep'
import { useAppStore } from '../../../stores/appStore'
import i18n from '../../../i18n'
import { UI_LANGUAGES } from '../../../lib/constants'

vi.mock('framer-motion', async (importOriginal) => ({
  ...(await importOriginal<typeof import('framer-motion')>()),
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

beforeEach(() => {
  vi.useFakeTimers()
  useAppStore.setState(useAppStore.getInitialState())
})

afterEach(async () => {
  cleanup()
  vi.useRealTimers()
  useAppStore.setState(useAppStore.getInitialState())
  await i18n.changeLanguage('en')
})

describe('onboarding demo shortcut hint', () => {
  it.each(UI_LANGUAGES)('uses shared keycaps in $label', async (language) => {
    await i18n.changeLanguage(language.value)
    const config = useAppStore.getState().config
    useAppStore.getState().updateConfig({
      hotkeys: {
        ...config.hotkeys,
        dictation: { primary: 'LeftShift', modifiers: ['Fn'] },
        dictationBindings: [{ primary: 'LeftShift', modifiers: ['Fn'] }],
      },
    })
    const { container } = render(<QuickTestStep />)
    expect(
      [...container.querySelectorAll('kbd.keycap')].map((keycap) => keycap.textContent),
    ).toEqual(['Fn', 'Left Shift'])
  })

  it('updates the hint when the configured dictation shortcut changes', () => {
    const { container } = render(<QuickTestStep />)
    act(() => {
      const config = useAppStore.getState().config
      useAppStore.getState().updateConfig({
        hotkeys: {
          ...config.hotkeys,
          dictation: { primary: 'K', modifiers: ['Ctrl'] },
          dictationBindings: [{ primary: 'K', modifiers: ['Ctrl'] }],
          dictationMode: 'toggle',
        },
      })
    })
    expect(
      [...container.querySelectorAll('kbd.keycap')].map((keycap) => keycap.textContent),
    ).toEqual(['Ctrl', 'K'])
    expect(
      screen.getByText(i18n.t('onboarding.test.press'), { exact: false, selector: 'span' }),
    ).toBeInTheDocument()
  })
})

describe('onboarding demo optional polishing', () => {
  it('omits polishing from the stage indicators when it is disabled', () => {
    useAppStore.getState().updateConfig({ polish_enabled: false })
    render(<QuickTestStep />)
    expect(screen.queryByText(i18n.t('onboarding.test.stageLlm'))).not.toBeInTheDocument()
    expect(screen.getByText(i18n.t('onboarding.test.stageStt'))).toBeInTheDocument()
    expect(screen.getByText(i18n.t('onboarding.test.stageOutput'))).toBeInTheDocument()
  })

  it('does not invent a result before the user starts practice', async () => {
    useAppStore.getState().updateConfig({ polish_enabled: false })
    const { container } = render(<QuickTestStep />)
    for (const duration of [2000, 3000, 2200]) {
      await act(async () => vi.advanceTimersByTime(duration))
    }
    await act(async () => vi.advanceTimersByTime(250))
    expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
    expect(screen.queryByText(i18n.t('onboarding.test.demoRaw'))).not.toBeInTheDocument()
    expect(screen.queryByText(i18n.t('onboarding.test.demoPolished'))).not.toBeInTheDocument()
    expect(container.querySelector('.line-through')).toBeNull()
  })

  it('does not invent a polished result before practice starts', async () => {
    useAppStore.getState().updateConfig({ polish_enabled: true })
    render(<QuickTestStep />)
    for (const duration of [2000, 3000, 2200, 2200]) {
      await act(async () => vi.advanceTimersByTime(duration))
    }
    await act(async () => vi.advanceTimersByTime(250))
    expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
    expect(screen.queryByText(i18n.t('onboarding.test.demoPolished'))).not.toBeInTheDocument()
  })

  it('updates available stages without synthesizing results', async () => {
    useAppStore.getState().updateConfig({ polish_enabled: true })
    render(<QuickTestStep />)
    for (const duration of [2000, 3000, 2200]) {
      await act(async () => vi.advanceTimersByTime(duration))
    }
    expect(screen.queryByText(i18n.t('onboarding.test.polished'))).not.toBeInTheDocument()
    act(() => useAppStore.getState().updateConfig({ polish_enabled: false }))
    expect(screen.queryByText(i18n.t('onboarding.test.polished'))).not.toBeInTheDocument()
    expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
    expect(screen.queryByText(i18n.t('onboarding.test.demoRaw'))).not.toBeInTheDocument()
    await act(async () => vi.advanceTimersByTime(2000))
    expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
  })
})
