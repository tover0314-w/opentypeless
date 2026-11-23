import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { GuidedPracticeFlow } from '../GuidedPracticeFlow'
import '../../../i18n'
import i18n from '../../../i18n'

vi.mock('../ModeIntroduction', () => ({
  ModeIntroduction: ({ mode, onContinue }: { mode: string; onContinue: () => void }) => (
    <button onClick={onContinue}>Introduce {mode}</button>
  ),
}))
vi.mock('../ShortcutLesson', () => ({
  ShortcutLesson: ({
    mode,
    onContinue,
    onConfigure,
  }: {
    mode: string
    onContinue: () => void
    onConfigure: () => void
  }) => (
    <>
      <button onClick={onContinue}>Confirm shortcut {mode}</button>
      <button onClick={onConfigure}>Configure shortcut {mode}</button>
    </>
  ),
}))
vi.mock('../QuickTestStep', () => ({
  PracticeExercise: ({
    mode,
    onCompleted,
    onReset,
  }: {
    mode: string
    onCompleted: () => void
    onReset: () => void
  }) => (
    <>
      <button onClick={onCompleted}>Complete exercise {mode}</button>
      <button onClick={onReset}>Reset exercise {mode}</button>
    </>
  ),
}))
vi.mock('../../Settings', () => ({ SettingsModal: () => null }))
vi.mock('../MicrophoneTestStep', () => ({
  MicrophoneTestStep: ({ onContinue }: { onContinue: () => void }) => (
    <button onClick={onContinue}>Confirm microphone</button>
  ),
}))

afterEach(cleanup)

it('finishes single-mode translation without proceeding to Ask', () => {
  const onComplete = vi.fn()
  render(
    <GuidedPracticeFlow
      singleMode
      initialPage={{ mode: 'translate', stage: 'exercise' }}
      onComplete={onComplete}
      onLayoutChange={() => {}}
    />,
  )
  fireEvent.click(screen.getByText('Complete exercise translate'))
  fireEvent.click(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') }))
  expect(onComplete).toHaveBeenCalledOnce()
  expect(screen.queryByText('Introduce ask')).toBeNull()
})

it('leaves single-mode introduction instead of entering a previous mode', () => {
  let back: (() => void) | null = null
  const onExitBack = vi.fn()
  render(
    <GuidedPracticeFlow
      singleMode
      initialPage={{ mode: 'ask', stage: 'introduction' }}
      onComplete={() => {}}
      onLayoutChange={() => {}}
      onBackChange={(handler) => {
        back = handler
      }}
      onExitBack={onExitBack}
    />,
  )
  act(() => back?.())
  expect(onExitBack).toHaveBeenCalledOnce()
  expect(screen.queryByText('Complete exercise translate')).toBeNull()
})

it('replaces the animated page container when leaving microphone testing', () => {
  const { container } = render(
    <GuidedPracticeFlow onComplete={() => {}} onLayoutChange={() => {}} />,
  )
  const microphonePage = container.querySelector('[data-practice-page]')!
  fireEvent.click(screen.getByText('Confirm microphone'))
  const introductionPage = container.querySelector('[data-practice-page]')!
  expect(introductionPage).not.toBe(microphonePage)
  expect(microphonePage.isConnected).toBe(false)
  expect(introductionPage).toHaveAttribute('data-practice-page', 'dictate-introduction-0')
})

it('uses the preview configuration handler instead of opening live settings', () => {
  const onConfigureShortcut = vi.fn()
  render(
    <GuidedPracticeFlow
      initialPage={{ mode: 'translate', stage: 'shortcut' }}
      onComplete={() => {}}
      onLayoutChange={() => {}}
      onConfigureShortcut={onConfigureShortcut}
    />,
  )
  fireEvent.click(screen.getByText('Configure shortcut translate'))
  expect(onConfigureShortcut).toHaveBeenCalledOnce()
  expect(screen.getByText('Confirm shortcut translate')).toBeInTheDocument()
})

it('shows a selected preview exercise without granting completion', () => {
  const onComplete = vi.fn()
  render(
    <GuidedPracticeFlow
      initialPage={{ mode: 'ask', stage: 'exercise', exampleIndex: 2 }}
      onComplete={onComplete}
      onLayoutChange={() => {}}
    />,
  )
  expect(screen.getByText('Complete exercise ask')).toBeInTheDocument()
  expect(screen.getAllByRole('button').some((button) => button.hasAttribute('disabled'))).toBe(true)
  expect(onComplete).not.toHaveBeenCalled()
})

it('returns from the exercise to shortcut teaching and then introduction before leaving the flow', () => {
  let back: (() => void) | null = null
  const onExitBack = vi.fn()
  const registration = (handler: (() => void) | null) => {
    back = handler
  }
  render(
    <GuidedPracticeFlow
      onComplete={() => {}}
      onLayoutChange={() => {}}
      onBackChange={registration}
      onExitBack={onExitBack}
    />,
  )
  fireEvent.click(screen.getByText('Confirm microphone'))
  fireEvent.click(screen.getByText('Introduce dictate'))
  fireEvent.click(screen.getByText('Confirm shortcut dictate'))
  fireEvent.click(screen.getByText('Complete exercise dictate'))
  act(() => back?.())
  expect(screen.getByText('Confirm shortcut dictate')).toBeInTheDocument()
  expect(onExitBack).not.toHaveBeenCalled()
  act(() => back?.())
  expect(screen.getByText('Introduce dictate')).toBeInTheDocument()
  act(() => back?.())
  expect(screen.getByText('Confirm microphone')).toBeInTheDocument()
  act(() => back?.())
  expect(onExitBack).toHaveBeenCalledOnce()
})

it('requires introduction, shortcut confirmation and completed exercise for each role', () => {
  const onComplete = vi.fn()
  const onReset = vi.fn()
  const onLayoutChange = vi.fn()
  render(
    <GuidedPracticeFlow
      onComplete={onComplete}
      onReset={onReset}
      onLayoutChange={onLayoutChange}
    />,
  )
  fireEvent.click(screen.getByText('Confirm microphone'))
  for (const mode of ['dictate', 'translate', 'ask']) {
    expect(screen.queryByText(`Complete exercise ${mode}`)).toBeNull()
    fireEvent.click(screen.getByText(`Introduce ${mode}`))
    expect(screen.queryByText(`Complete exercise ${mode}`)).toBeNull()
    fireEvent.click(screen.getByText(`Confirm shortcut ${mode}`))
    if (mode === 'translate') {
      expect(screen.queryByText('Complete exercise translate')).toBeNull()
      expect(screen.getByRole('combobox')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') }))
    }
    for (let example = 0; example < (mode === 'translate' ? 1 : 4); example += 1) {
      const next = screen.getAllByRole('button').find((button) => button.hasAttribute('disabled'))!
      expect(next).toBeDisabled()
      fireEvent.click(screen.getByText(`Complete exercise ${mode}`))
      expect(next).toBeEnabled()
      fireEvent.click(next)
    }
  }
  expect(onComplete).toHaveBeenCalledOnce()
  onReset.mockClear()
  fireEvent.click(screen.getByText('Reset exercise ask'))
  expect(onReset).toHaveBeenCalledOnce()
  expect(screen.getAllByRole('button').some((button) => button.hasAttribute('disabled'))).toBe(true)
  expect(onLayoutChange).toHaveBeenCalledWith('fullWidth')
  expect(onLayoutChange).toHaveBeenCalledWith('split')
})
