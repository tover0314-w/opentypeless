import { describe, expect, it } from 'vitest'
import { bindingFromHotkey, hotkeyFromBinding } from '../../stores/appStore'
import nativeHotkeys from '../../../src-tauri/src/hotkey.rs?raw'

function nativePrimaryNames(source: string): string[] {
  const start = source.indexOf('let code = match key_str.to_lowercase().as_str() {')
  const end = source.indexOf('_ => return None,', start)
  if (start < 0 || end < start) throw new Error('Native primary key match is unavailable')
  return [
    ...source
      .slice(start, end)
      .matchAll(/((?:"(?:\\.|[^"\\])*"\s*\|\s*)*"(?:\\.|[^"\\])*")\s*=>\s*Code::/g),
  ]
    .flatMap((match) => [...match[1].matchAll(/"(?:\\.|[^"\\])*"/g)])
    .map((match) => JSON.parse(match[0]) as string)
}

describe('existing native primary key name coverage', () => {
  it.each(['Ctrl++K', '+Ctrl+K', 'Ctrl+K+', 'Ctrl+ +K'])(
    'rejects malformed empty key segments in %s',
    (value) => {
      expect(bindingFromHotkey(value)).toBeNull()
    },
  )

  it('keeps whitespace around valid key segments', () => {
    expect(bindingFromHotkey(' Ctrl + Shift + K ')).toEqual({
      primary: 'K',
      modifiers: ['Ctrl', 'Shift'],
    })
  })

  it.each(['constructor', '__proto__'])('rejects inherited object primary name %s', (name) => {
    expect(bindingFromHotkey(`Ctrl+${name}`)).toBeNull()
  })

  it('detects a synthetic native key name with no frontend mapping', () => {
    const synthetic = nativePrimaryNames(
      'let code = match key_str.to_lowercase().as_str() { "synthetic-unmapped-key" => Code::KeyA, _ => return None, };',
    )
    expect(synthetic).toEqual(['synthetic-unmapped-key'])
    expect(synthetic.filter((name) => bindingFromHotkey(`Ctrl+${name}`) === null)).toEqual([
      'synthetic-unmapped-key',
    ])
  })

  it('fails closed when the native parser layout no longer matches', () => {
    expect(() => nativePrimaryNames('fn changed_parser() {}')).toThrow(
      'Native primary key match is unavailable',
    )
  })

  it('extracts aliases and escaped punctuation without including modifiers', () => {
    expect(
      nativePrimaryNames(
        'let code = match key_str.to_lowercase().as_str() { "up" | "arrowup" => Code::ArrowUp, "\\\\" | "backslash" => Code::Backslash, _ => return None, };',
      ),
    ).toEqual(['up', 'arrowup', '\\', 'backslash'])
  })

  it('can express and reload every primary name already parsed by the native global shortcut path', () => {
    const names = nativePrimaryNames(nativeHotkeys)
    expect(names.length).toBeGreaterThan(70)
    for (const name of names) {
      const binding = bindingFromHotkey(`Ctrl+${name}`)
      expect(binding, `Missing frontend name: ${name}`).not.toBeNull()
      expect(bindingFromHotkey(hotkeyFromBinding(binding!))).toEqual(binding)
    }
  })
})
