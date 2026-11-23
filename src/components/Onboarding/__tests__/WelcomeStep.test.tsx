import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import i18n from '../../../i18n'
import { UI_LANGUAGES } from '../../../lib/constants'
import { useAppStore } from '../../../stores/appStore'
import { WelcomeStep } from '../WelcomeStep'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue(undefined) }))

beforeEach(async () => {
  vi.clearAllMocks()
  localStorage.clear()
  useAppStore.setState(useAppStore.getInitialState())
  await i18n.changeLanguage('en')
})

afterEach(async () => {
  vi.restoreAllMocks()
  cleanup()
  useAppStore.setState(useAppStore.getInitialState())
  localStorage.removeItem('ui_language')
  await i18n.changeLanguage('en')
})

describe('welcome language selection', () => {
  it('keeps language selection usable when the optional browser cache cannot be written', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Synthetic cache restriction', 'SecurityError')
    })
    render(<WelcomeStep />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: '日本語' })))
    expect(i18n.resolvedLanguage).toBe('ja')
    expect(useAppStore.getState().config.ui_language).toBe('ja')
    expect(screen.getByRole('button', { name: '日本語' })).toHaveAttribute('aria-pressed', 'true')
    expect(invoke).toHaveBeenCalledWith('refresh_tray_labels')
  })

  it('uses a centered welcome heading and compact language choices without removing modes', () => {
    const { container } = render(<WelcomeStep />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      i18n.t('onboarding.steps.welcome'),
    )
    expect(container.firstElementChild?.className).toContain('max-w-[760px]')
    expect(screen.getByRole('button', { name: 'English' }).parentElement?.className).toContain(
      'grid-cols-5',
    )
    for (const mode of ['dictate', 'translate', 'ask']) {
      expect(screen.getByText(i18n.t(`home.modes.${mode}.title`))).toBeInTheDocument()
    }
  })

  it.each(UI_LANGUAGES)(
    'selects $label without dropping any supported language',
    async (language) => {
      render(<WelcomeStep />)
      for (const option of UI_LANGUAGES) {
        expect(screen.getByRole('button', { name: option.label })).toBeInTheDocument()
      }
      await act(async () => fireEvent.click(screen.getByRole('button', { name: language.label })))
      expect(i18n.language).toBe(language.value)
      expect(localStorage.getItem('ui_language')).toBe(language.value)
      expect(useAppStore.getState().config.ui_language).toBe(language.value)
      expect(screen.getByRole('button', { name: language.label })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
      expect(invoke).toHaveBeenCalledWith('refresh_tray_labels')
    },
  )
})
