import { describe, expect, it } from 'vitest'
import {
  hasUnsupportedNativeCombination,
  nativeShortcutErrorKey,
  validateShortcut,
} from '../shortcutRules'
import { bindingFromHotkey } from '../../stores/appStore'

const b = (...keys: string[]) => ({ primary: keys[keys.length - 1], modifiers: keys.slice(0, -1) })

describe('native shortcut availability feedback', () => {
  it('distinguishes a wrong platform from an unimplemented native combination', () => {
    expect(nativeShortcutErrorKey(b('Fn'), 'windows')).toBe('shortcuts.errorPlatform')
    expect(nativeShortcutErrorKey(b('RightAlt'), 'macos')).toBe('shortcuts.errorPlatform')
    expect(nativeShortcutErrorKey(b('Fn', 'K'), 'macos')).toBe('shortcuts.errorNativeCombination')
    expect(nativeShortcutErrorKey(b('Ctrl', 'Fn'), 'macos')).toBe(
      'shortcuts.errorNativeCombination',
    )
    expect(nativeShortcutErrorKey(b('Fn', 'Space'), 'macos')).toBeNull()
    expect(nativeShortcutErrorKey(b('Ctrl', 'K'), 'macos')).toBeNull()
  })
})

describe('validateShortcut (Typeless rules)', () => {
  it('records the current representation gap for two ordinary keys with a modifier', () => {
    expect(validateShortcut(b('Ctrl', 'A', 'C'), [])).toBeNull()
    expect(bindingFromHotkey('Ctrl+A+C')).toBeNull()
  })

  it('rejects modified native primary keys and unsupported RightAlt combinations', () => {
    expect(hasUnsupportedNativeCombination(b('Ctrl', 'Fn'))).toBe(true)
    expect(hasUnsupportedNativeCombination(b('Ctrl', 'RightAlt'))).toBe(true)
    expect(hasUnsupportedNativeCombination(b('RightAlt', 'K'))).toBe(true)
    expect(hasUnsupportedNativeCombination(b('RightAlt'))).toBe(false)
    expect(hasUnsupportedNativeCombination(b('RightAlt', 'Space'))).toBe(false)
    expect(hasUnsupportedNativeCombination(b('RightAlt', 'LeftShift'))).toBe(false)
    expect(hasUnsupportedNativeCombination(b('Alt', 'K'))).toBe(false)
  })

  it('accepts the Typeless defaults', () => {
    expect(validateShortcut(b('Fn'), [])).toBeNull()
    expect(validateShortcut(b('Fn', 'Space'), [])).toBeNull()
    expect(validateShortcut(b('Fn', 'LeftShift'), [])).toBeNull()
    expect(validateShortcut(b('Ctrl', 'Command', 'V'), [])).toBeNull()
  })

  it('rejects letters or digits without a modifier', () => {
    expect(validateShortcut(b('A'), [])).toBe('alphanumericOnly')
    expect(validateShortcut(b('7'), [])).toBe('alphanumericOnly')
  })

  it('rejects more than three keys', () => {
    expect(validateShortcut(b('Ctrl', 'Option', 'Shift', 'K'), [])).toBe('tooManyKeys')
  })

  it('preserves the first failing rule when multiple restrictions apply', () => {
    expect(validateShortcut(b('A', 'B', 'C', 'D'), [])).toBe('alphanumericOnly')
    expect(validateShortcut(b('Ctrl', 'A', 'B', 'C'), [])).toBe('tooManyKeys')
    expect(validateShortcut(b('Command', 'C'), [b('Command', 'C')])).toBe('systemReserved')
    expect(validateShortcut(b('Ctrl', 'A', 'B'), [b('Ctrl', 'A', 'B')])).toBe('consecutiveLetters')
  })

  it('rejects shortcuts the system already owns, in any modifier spelling', () => {
    expect(validateShortcut(b('Command', 'C'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Ctrl', 'Z'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Space'), [])).toBe('systemReserved')
    expect(validateShortcut(b('F5'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Ctrl', 'Shift', 'Tab'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Command', 'Shift', 'Tab'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Command', 'LeftShift', 'Tab'), [])).toBe('systemReserved')
  })

  it('rejects neighbouring letters or digits', () => {
    expect(validateShortcut(b('Ctrl', 'A', 'B'), [])).toBe('consecutiveLetters')
    expect(validateShortcut(b('Option', '3', '4'), [])).toBe('consecutiveNumbers')
    expect(validateShortcut(b('Ctrl', 'A', 'C'), [])).toBeNull()
  })

  it('rejects a shortcut another feature already uses', () => {
    expect(validateShortcut(b('Option', 'Ctrl', '9'), [b('Ctrl', 'Option', '9')])).toBe(
      'alreadyInUse',
    )
    expect(validateShortcut(b('Alt', 'K'), [b('Option', 'K')])).toBe('alreadyInUse')
  })
})
