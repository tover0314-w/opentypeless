import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { FeatureLesson } from '../FeatureLesson'
import { useAppStore } from '../../../stores/appStore'
import '../../../i18n'

vi.mock('../GuidedPracticeFlow', () => ({
  GuidedPracticeFlow: ({
    singleMode,
    initialPage,
    onComplete,
    onExitBack,
  }: {
    singleMode: boolean
    initialPage: { mode: string; stage: string }
    onComplete: () => void
    onExitBack: () => void
  }) => (
    <div>
      <p>{`${singleMode}-${initialPage.mode}-${initialPage.stage}`}</p>
      <button onClick={onComplete}>Finish lesson</button>
      <button onClick={onExitBack}>Leave lesson</button>
    </div>
  ),
}))

afterEach(cleanup)

it.each(['Finish lesson', 'Leave lesson'])(
  'closes without resetting onboarding or configuration: %s',
  (action) => {
    const before = useAppStore.getState()
    const onClose = vi.fn()
    render(<FeatureLesson mode="ask" onClose={onClose} />)
    expect(screen.getByText('true-ask-introduction')).toBeInTheDocument()
    fireEvent.click(screen.getByText(action))
    expect(onClose).toHaveBeenCalledOnce()
    const after = useAppStore.getState()
    expect(after.config).toBe(before.config)
    expect(after.onboardingStep).toBe(before.onboardingStep)
    expect(after.onboardingCompleted).toBe(before.onboardingCompleted)
  },
)
