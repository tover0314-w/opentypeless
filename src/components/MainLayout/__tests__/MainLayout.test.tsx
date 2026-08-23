import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MainLayout } from '../index'

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
    t: (key: string) =>
      ({
        'app.name': 'OpenTypeless',
        'app.tagline': 'AI Voice Input',
        'nav.home': 'Home',
        'nav.ask': 'Ask',
        'nav.settings': 'Settings',
        'nav.history': 'History',
        'nav.mainNavigation': 'Main navigation',
      })[key] ?? key,
  }),
}))

afterEach(() => {
  cleanup()
  window.location.hash = ''
})

describe('MainLayout', () => {
  it('does not show Ask as a first-class navigation item', () => {
    render(
      <MainLayout>
        <div>content</div>
      </MainLayout>,
    )

    expect(screen.queryByRole('button', { name: 'Ask' })).not.toBeInTheDocument()
  })
})
