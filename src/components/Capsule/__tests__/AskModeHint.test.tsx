import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CapsuleAlertBubble } from '../CapsuleAlertBubble'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import { useAppStore } from '../../../stores/appStore'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('framer-motion', () => ({ useReducedMotion: () => false }))

afterEach(() => {
  cleanup()
  useCapsuleAlertStore.getState().dismiss()
  useAppStore.setState({ audioVolume: 0 })
})

describe('Ask recording hint', () => {
  it('renders translation onboarding using the configured shortcut', () => {
    const config = useAppStore.getState().config
    useAppStore.setState({
      config: {
        ...config,
        hotkeys: {
          ...config.hotkeys,
          translateBindings: [{ primary: 'LeftShift', modifiers: ['Fn'] }],
        },
      },
    })
    useCapsuleAlertStore.getState().show('switch_translation_mode')
    render(<CapsuleAlertBubble />)
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.switchTranslationTitle')
    expect(screen.getByRole('status')).toHaveTextContent('Fn')
    expect(screen.getByRole('status')).toHaveTextContent('Left Shift')
  })

  it('renders the use-voice reminder with the configured shortcut', () => {
    useCapsuleAlertStore.getState().show('use_voice')
    render(<CapsuleAlertBubble />)
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.useVoicePrefix')
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.useVoiceSuffix')
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows a compact translated label without a close button or icon', () => {
    useCapsuleAlertStore.getState().show('ask_anything')
    render(<CapsuleAlertBubble />)
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.askAnything')
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.getByRole('status').querySelector('[data-mode-hint-glow="ask"]')).not.toBeNull()
  })

  it('responds to volume and clamps invalid levels', () => {
    useCapsuleAlertStore.getState().show('ask_anything')
    const { container } = render(<CapsuleAlertBubble />)
    const glow = container.querySelector<HTMLElement>('[data-mode-hint-glow="ask"]')!
    const quietStyle = glow.style.transform
    act(() => useAppStore.setState({ audioVolume: 0.8 }))
    expect(glow.style.transform).not.toBe(quietStyle)
    act(() => useAppStore.setState({ audioVolume: Number.NaN }))
    expect(glow.style.transform).toBe(quietStyle)
  })
})
