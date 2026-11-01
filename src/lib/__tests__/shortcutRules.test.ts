import { describe, expect, it } from 'vitest'
import { validateShortcut } from '../shortcutRules'

const b = (...keys: string[]) => ({ primary: keys[keys.length - 1], modifiers: keys.slice(0, -1) })

describe('validateShortcut (Typeless rules)', () => {
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

  it('rejects shortcuts the system already owns, in any modifier spelling', () => {
    expect(validateShortcut(b('Command', 'C'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Ctrl', 'Z'), [])).toBe('systemReserved')
    expect(validateShortcut(b('Space'), [])).toBe('systemReserved')
    expect(validateShortcut(b('F5'), [])).toBe('systemReserved')
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
