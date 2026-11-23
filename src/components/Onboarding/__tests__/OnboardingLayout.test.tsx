import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { OnboardingLayout } from '../OnboardingLayout'
import i18n from '../../../i18n'

afterEach(cleanup)

describe('onboarding source-backed layout', () => {
  it('scopes the reference heading font to onboarding', () => {
    const { container } = render(
      <OnboardingLayout
        step={0}
        totalSteps={8}
        title="Welcome"
        canNext
        canBack={false}
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Existing content</p>
      </OnboardingLayout>,
    )
    expect(container.firstElementChild).toHaveClass('onboarding-fonts')
  })

  it('keeps a split-layout fallback for WebKit without container-query support', () => {
    const { container } = render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Fallback"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Lesson</p>
      </OnboardingLayout>,
    )
    expect(container.querySelector('section')).toHaveClass('onboarding-split-panel')
    expect(container.querySelector('aside')).toHaveClass('onboarding-split-surface')
  })

  it('uses a branded radial background for the teaching surface', () => {
    const { container } = render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Lesson"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Lesson content</p>
      </OnboardingLayout>,
    )
    expect(container.querySelector('aside')?.getAttribute('style')).toContain('radial-gradient')
    expect(container.querySelector('aside')?.getAttribute('style')).toContain('--color-accent')
    expect(container.querySelector('aside')?.getAttribute('style')).not.toContain('color-mix')
  })

  it('centers the account page in a bounded card without a side illustration', () => {
    const { container } = render(
      <OnboardingLayout
        step={1}
        totalSteps={8}
        title="Sign in"
        layout="accountCard"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Existing account controls</p>
      </OnboardingLayout>,
    )
    expect(container.querySelector('aside')).toBeNull()
    expect(container.querySelector('[data-onboarding-text]')?.className).toContain('max-w-[440px]')
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('lets a lesson own its heading without an extra outer title', () => {
    render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Outer title"
        subtitle="Outer description"
        showHeading={false}
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <h1>Lesson title</h1>
      </OnboardingLayout>,
    )
    expect(screen.getAllByRole('heading')).toHaveLength(1)
    expect(screen.queryByText('Outer description')).toBeNull()
  })

  it('uses the source-backed gradient progress strip with bounded accessible values', () => {
    const { rerender } = render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Progress"
        progressValue={77}
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Translation language settings</p>
      </OnboardingLayout>,
    )
    const progress = screen.getByRole('progressbar')
    expect(progress).toHaveAttribute('aria-valuenow', '77')
    expect(progress.firstElementChild?.getAttribute('class')).toContain('bg-gradient-to-r')
    rerender(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Progress"
        progressValue={150}
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Translation language settings</p>
      </OnboardingLayout>,
    )
    expect(progress).toHaveAttribute('aria-valuenow', '100')
  })

  it('highlights setup for microphone testing even inside the practice step', () => {
    const { container } = render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Microphone"
        activeStage="setUp"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Local microphone test</p>
      </OnboardingLayout>,
    )
    expect(container.querySelector('nav [aria-current="step"]')).toHaveTextContent(
      i18n.t('onboarding.stages.setUp'),
    )
  })

  it('does not render a duplicate footer action when a lesson owns its progression', () => {
    render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Lesson"
        showNextButton={false}
        nextLabel="Outer next"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <button>Lesson next</button>
      </OnboardingLayout>,
    )
    expect(screen.queryByRole('button', { name: 'Outer next' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Lesson next' })).toBeInTheDocument()
  })

  it('renders the supplied practice surface instead of the generic illustration', () => {
    render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Practice"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
        rightContent={<textarea aria-label="Owned practice result" readOnly value="" />}
      >
        <p>Practice controls</p>
      </OnboardingLayout>,
    )
    expect(screen.getByRole('textbox', { name: 'Owned practice result' })).toBeVisible()
  })

  it('supports full-width introduction pages without the shared illustration or duplicate heading', () => {
    const { container } = render(
      <OnboardingLayout
        step={6}
        totalSteps={8}
        title="Introduction"
        layout="fullWidth"
        canNext={false}
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <h1>Centered introduction</h1>
      </OnboardingLayout>,
    )
    expect(container.querySelector('aside')).toBeNull()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(container.querySelector('[data-onboarding-text]')?.className).toContain('max-w-[1280px]')
    expect(container.querySelector('section')?.className).not.toContain('lg:w-[55%]')
  })

  it('keeps the back action above the bounded text content and the next action below it', () => {
    const { container } = render(
      <OnboardingLayout
        step={3}
        totalSteps={8}
        title="Synthetic provider setup"
        canNext
        canBack
        onNext={() => {}}
        onBack={() => {}}
        onClose={() => {}}
      >
        <p>Source-backed content</p>
      </OnboardingLayout>,
    )
    const back = screen.getByRole('button', { name: i18n.t('onboarding.layout.back') })
    const content = screen.getByText('Source-backed content')
    expect(back.compareDocumentPosition(content) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const section = container.querySelector('section')!
    expect(section.className).toContain('w-[55%]')
    expect(section.className).toContain('@min-[988px]:w-[55%]')
    expect(container.querySelector('aside')?.className).toContain('@min-[988px]:flex')
    expect(section.className).toContain('px-16')
    expect(section.className).toContain('py-8')
    expect(content.closest('[data-onboarding-text]')?.className).toContain('max-w-[600px]')
    expect(content.closest('[data-onboarding-text]')?.className).toContain('max-h-[630px]')
  })
})
