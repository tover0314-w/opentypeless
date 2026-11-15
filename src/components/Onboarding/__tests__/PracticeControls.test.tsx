import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { QuickTestStep } from '../QuickTestStep'
import i18n from '../../../i18n'
import { UI_LANGUAGES } from '../../../lib/constants'

const mocks = vi.hoisted(() => ({ invoke: vi.fn() }))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))

afterEach(async () => {
  cleanup()
  mocks.invoke.mockReset()
  await i18n.changeLanguage('en')
})

describe('practice controls', () => {
  it.each(UI_LANGUAGES)(
    'shows missing speech credentials as a setup requirement in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'error',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: {
              code: 'stt_not_configured',
              details: 'synthetic-private-credential-detail',
              retry_count: 0,
            },
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      expect(
        await screen.findByText(i18n.t('capsule.errors.stt_not_configured')),
      ).toBeInTheDocument()
      expect(screen.queryByText(/synthetic-private-credential-detail/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeEnabled()
    },
  )

  it.each(UI_LANGUAGES)(
    'shows an expired cloud session as a login requirement in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'error',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: {
              code: 'authentication_required',
              details: 'synthetic-private-session-detail',
              retry_count: 0,
            },
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      expect(
        await screen.findByText(i18n.t('capsule.errors.authentication_required')),
      ).toBeInTheDocument()
      expect(screen.queryByText(/synthetic-private-session-detail/)).not.toBeInTheDocument()
      expect(screen.queryByText(i18n.t('capsule.errors.stt_failed'))).not.toBeInTheDocument()
    },
  )

  it.each(
    UI_LANGUAGES.flatMap((language) =>
      ['microphone_permission_required', 'stt_no_speech_detected'].map((code) => ({
        language: language.value,
        label: language.label,
        code,
      })),
    ),
  )(
    'shows the native $code reason without private details in $label',
    async ({ language, code }) => {
      await i18n.changeLanguage(language)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'error',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: {
              code,
              details: 'synthetic-private-device-detail',
              retry_count: 0,
            },
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      expect(await screen.findByText(i18n.t(`capsule.errors.${code}`))).toBeInTheDocument()
      expect(screen.queryByText(/synthetic-private-device-detail/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeEnabled()
    },
  )

  it('disables stop while the request is pending without disabling cancel', async () => {
    let finishStop!: () => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice') return Promise.resolve('test-session')
      if (command === 'get_practice')
        return Promise.resolve({
          session: 'test-session',
          phase: 'recording',
          rawText: '',
          polishPreview: '',
          result: '',
          level: 0.5,
          error: null,
        })
      if (command === 'stop_practice')
        return new Promise<void>((resolve) => {
          finishStop = resolve
        })
      return Promise.resolve()
    })
    render(<QuickTestStep />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    })
    await waitFor(() =>
      expect(screen.getByRole('button', { name: i18n.t('practice.stop') })).toBeEnabled(),
    )
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.stop') }))
    expect(screen.getByRole('button', { name: i18n.t('practice.stop') })).toBeDisabled()
    expect(screen.getByRole('button', { name: i18n.t('practice.cancel') })).toBeEnabled()
    await act(async () => {
      finishStop()
    })
  })
  it.each(UI_LANGUAGES)(
    'renders native cancellation without a raw key in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'cancelled',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: null,
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      await waitFor(() =>
        expect(screen.getByText(i18n.t('practice.cancel'), { selector: 'p' })).toBeInTheDocument(),
      )
      expect(screen.queryByText('practice.cancelled')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeEnabled()
    },
  )
  it('shows streamed polish as a preview without claiming completion', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'test-session'
      if (command === 'get_practice')
        return {
          session: 'test-session',
          phase: 'polishing',
          rawText: 'raw speech',
          polishPreview: 'Edited preview',
          result: '',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    })
    await waitFor(() => expect(screen.getByText('Edited preview')).toBeInTheDocument())
    expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
    expect(screen.getByText(i18n.t('onboarding.test.polished'))).toBeInTheDocument()
  })
  it.each(['recording', 'transcribing', 'error'])(
    'labels %s text as a preview rather than a completed result',
    async (phase) => {
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase,
            rawText: 'spoken preview',
            result: '',
            level: 0.5,
            error: null,
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      await waitFor(() => expect(screen.getByText('spoken preview')).toBeInTheDocument())
      expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
      expect(screen.getByText(i18n.t('onboarding.test.transcribing'))).toBeInTheDocument()
    },
  )
  it.each(UI_LANGUAGES)(
    'shows consent and controls without recording in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      render(<QuickTestStep />)
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeInTheDocument()
      expect(screen.getByText(i18n.t('practice.notice'))).toBeInTheDocument()
      expect(mocks.invoke).not.toHaveBeenCalled()
    },
  )

  it('shows the current result in the existing area and keeps it after completion', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'test-session'
      if (command === 'get_practice')
        return {
          session: 'test-session',
          phase: 'complete',
          rawText: 'spoken words',
          result: 'Spoken words.',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    })
    await waitFor(() => expect(screen.getByText('Spoken words.')).toBeInTheDocument())
    expect(screen.queryByText(i18n.t('onboarding.test.demoPolished'))).not.toBeInTheDocument()
  })
})
