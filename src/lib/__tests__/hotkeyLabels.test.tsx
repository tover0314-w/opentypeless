import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { hotkeyParts, keyLabel, useVoiceHotkeys } from '../hotkeyLabels'
import { useAppStore } from '../../stores/appStore'

afterEach(() => {
  useAppStore.setState(useAppStore.getInitialState())
})

describe('voice shortcut labels', () => {
  it.each(['constructor', 'toString', '__proto__'])(
    'keeps unknown key label %s as text instead of inherited object values',
    (key) => {
      expect(keyLabel(key)).toBe(key)
      expect(hotkeyParts(`Ctrl+${key}`)).toEqual(['Ctrl', key])
    },
  )

  it.each([
    ['MouseButton4', 'Mouse 4'],
    ['MouseButton12', 'Mouse 12'],
    ['LeftShift', 'Left Shift'],
    ['RightAlt', 'Right Alt'],
    ['LeftCtrl', 'Left Ctrl'],
    ['KeypadAdd', 'KeypadAdd'],
    ['NumpadAdd', 'NumpadAdd'],
  ])('formats %s using the original key label rule', (key, label) => {
    expect(keyLabel(key)).toBe(label)
  })

  it('keeps numpad add and mouse buttons intact in a shortcut combination', () => {
    expect(hotkeyParts('Ctrl+NumpadAdd+MouseButton4')).toEqual(['Ctrl', 'NumpadAdd', 'Mouse 4'])
  })

  it.each(['Up', 'Down', 'Left', 'Right', 'Home', 'End', 'PageUp', 'PageDown', 'Insert'])(
    'keeps the newly accepted navigation key %s visible in shortcut hints',
    (primary) => {
      expect(hotkeyParts(`Ctrl+${primary}`)).toEqual(['Ctrl', primary])
      const config = useAppStore.getState().config
      useAppStore.setState({
        config: {
          ...config,
          hotkeys: {
            ...config.hotkeys,
            dictationBindings: [{ primary, modifiers: ['Ctrl'] }],
          },
        },
      })
      const { result, unmount } = renderHook(() => useVoiceHotkeys())
      expect(result.current.dictate).toBe(`Ctrl+${primary}`)
      unmount()
    },
  )

  it('does not present modifiers as a usable shortcut when its primary is empty', () => {
    const { result } = renderHook(() => useVoiceHotkeys())
    act(() => {
      const config = useAppStore.getState().config
      useAppStore.setState({
        config: {
          ...config,
          hotkeys: {
            ...config.hotkeys,
            dictation: { primary: ' ', modifiers: ['Ctrl'] },
            dictationBindings: [{ primary: ' ', modifiers: ['Ctrl'] }],
          },
        },
      })
    })
    expect(result.current.dictate).toBeNull()
  })

  it('preserves modifier keys used as the actual primary trigger', () => {
    const { result } = renderHook(() => useVoiceHotkeys())
    act(() => {
      const config = useAppStore.getState().config
      useAppStore.setState({
        config: {
          ...config,
          hotkeys: {
            ...config.hotkeys,
            dictationBindings: [{ primary: 'LeftShift', modifiers: [] }],
            translateBindings: [{ primary: 'LeftShift', modifiers: ['Fn'] }],
          },
        },
      })
    })
    expect(result.current.dictate).toBe('LeftShift')
    expect(result.current.translate).toBe('Fn+LeftShift')
  })
})
