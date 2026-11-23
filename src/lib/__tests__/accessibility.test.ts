import { afterEach, beforeEach, expect, it } from 'vitest'
import { needsMacAccessibility } from '../accessibility'
import { useAppStore } from '../../stores/appStore'

const originalPlatform = navigator.platform

beforeEach(() => {
  Object.defineProperty(navigator, 'platform', { value: 'MacIntel', configurable: true })
})

afterEach(() => {
  Object.defineProperty(navigator, 'platform', { value: originalPlatform, configurable: true })
})

it.each(['dictation', 'translate', 'ask'] as const)(
  'requires Accessibility for a configured Fn combination in %s with copy-only output',
  (role) => {
    const base = useAppStore.getInitialState().config
    const ordinary = { primary: 'K', modifiers: ['Ctrl'] }
    const combo = { primary: 'Space', modifiers: ['Fn'] }
    const config = {
      ...base,
      hotkey: 'Ctrl+K',
      ask_hotkey: '',
      insertion_strategy: 'clipboardCopyOnly' as const,
      hotkeys: {
        ...base.hotkeys,
        dictation: ordinary,
        dictationBindings: [ordinary],
        translate: null,
        translateBindings: [],
        ask: null,
        askBindings: [],
        [role]: combo,
        [`${role}Bindings`]: [combo],
      },
    }
    expect(needsMacAccessibility(config)).toBe(true)
  },
)

it('does not require Accessibility for ordinary copy-only shortcuts or another platform', () => {
  const base = useAppStore.getInitialState().config
  const ordinary = { primary: 'K', modifiers: ['Ctrl'] }
  const config = {
    ...base,
    hotkey: 'Ctrl+K',
    ask_hotkey: '',
    insertion_strategy: 'clipboardCopyOnly' as const,
    hotkeys: {
      ...base.hotkeys,
      dictation: ordinary,
      dictationBindings: [ordinary],
      translate: null,
      translateBindings: [],
      ask: null,
      askBindings: [],
    },
  }
  expect(needsMacAccessibility(config)).toBe(false)
  Object.defineProperty(navigator, 'platform', { value: 'Win32', configurable: true })
  expect(needsMacAccessibility({ ...config, hotkey: 'Fn' })).toBe(false)
})
