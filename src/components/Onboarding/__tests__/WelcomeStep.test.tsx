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
  cleanup()
  useAppStore.setState(useAppStore.getInitialState())
  localStorage.removeItem('ui_language')
  await i18n.changeLanguage('en')
})

describe('welcome language selection', () => {
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
