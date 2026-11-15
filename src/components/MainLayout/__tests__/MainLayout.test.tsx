import React from 'react'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MainLayout } from '../index'
import { useCloudServiceStore } from '../../../stores/cloudServiceStore'

const MOTION_PROPS = new Set([
  'initial',
  'animate',
  'exit',
  'transition',
  'variants',
  'whileHover',
  'whileTap',
  'layoutId',
  'layout',
])

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: new Proxy(
    {},
    {
      get:
        (_target, tag: string) =>
        ({ children, ...props }: React.HTMLAttributes<HTMLElement>) => {
          const domProps: Record<string, unknown> = {}
          for (const [key, value] of Object.entries(props)) {
            if (!MOTION_PROPS.has(key)) domProps[key] = value
          }
          return React.createElement(tag, domProps, children)
        },
    },
  ),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    i18n: { language: 'en' },
    t: (key: string) =>
      ({
        'app.name': 'OpenTypeless',
        'app.tagline': 'AI Voice Input',
        'nav.home': 'Home',
        'nav.ask': 'Ask',
        'nav.settings': 'Settings',
        'nav.history': 'History',
        'nav.upgrade': 'Upgrade',
        'nav.account': 'Account',
        'nav.mainNavigation': 'Main navigation',
        'nav.dictionary': 'Dictionary',
        'nav.models': 'Models',
        'settingsModal.tabs.help': 'Help',
        'cloudRecovery.sttBody': 'Cloud speech unavailable · audio was not resent',
        'cloudRecovery.tryAgain': 'Try again',
        'cloudRecovery.openSttSettings': 'Settings',
      })[key] ?? key,
  }),
}))

vi.mock('../../../stores/authStore', () => ({
  hasManagedCloudAccess: () => false,
  useAuthStore: Object.assign(
    (selector: any) => (typeof selector === 'function' ? selector({}) : {}),
    { getState: () => ({}) },
  ),
}))

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))

afterEach(() => {
  cleanup()
  useCloudServiceStore.setState({ incident: null })
  window.location.hash = ''
})

describe('MainLayout', () => {
  it('shows Home, History, Dictionary and Models as the primary pages', () => {
    render(
      <MainLayout>
        <div>content</div>
      </MainLayout>,
    )

    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(
      within(nav)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['Home', 'Models', 'History', 'Dictionary'])
  })

  it('opens Settings, Account and Help as overlays from the sidebar footer', () => {
    render(
      <MainLayout>
        <div>content</div>
      </MainLayout>,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(window.location.hash).toBe('#/settings')
    fireEvent.click(screen.getByRole('button', { name: 'Account' }))
    expect(window.location.hash).toBe('#/settings?pane=account')
    fireEvent.click(screen.getByRole('button', { name: 'Help' }))
    expect(window.location.hash).toBe('#/settings?pane=help')
  })

  it('does not show Ask as a first-class navigation item', () => {
    render(
      <MainLayout>
        <div>content</div>
      </MainLayout>,
    )

    expect(screen.queryByRole('button', { name: 'Ask' })).not.toBeInTheDocument()
  })

  it('keeps a managed-cloud failure visible with manual recovery actions', () => {
    useCloudServiceStore.setState({
      incident: { kind: 'stt', code: 'stt_failed', occurredAt: '2026-08-26T10:00:00.000Z' },
    })
    render(
      <MainLayout>
        <div>content</div>
      </MainLayout>,
    )

    const banner = screen.getByTestId('cloud-service-banner')
    expect(
      within(banner).getByText('Cloud speech unavailable · audio was not resent'),
    ).toBeInTheDocument()
    fireEvent.click(within(banner).getByRole('button', { name: /Settings/ }))
    expect(window.location.hash).toBe('#/settings?pane=stt')

    fireEvent.click(within(banner).getByRole('button', { name: /Try again/ }))
    expect(screen.queryByTestId('cloud-service-banner')).not.toBeInTheDocument()
  })
})
