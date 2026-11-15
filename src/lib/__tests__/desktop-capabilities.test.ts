import { describe, expect, it } from 'vitest'
import capabilities from '../../../src-tauri/capabilities/default.json'
import mainUpdater from '../../../src-tauri/capabilities/main-updater.json'

const capabilityFiles = import.meta.glob('../../../src-tauri/capabilities/*.json', {
  eager: true,
  import: 'default',
}) as Record<string, { windows: string[]; permissions: string[] }>

describe('native capsule permissions', () => {
  it('does not expose direct global shortcut registration to renderer windows', () => {
    expect(
      Object.values(capabilityFiles)
        .flatMap((capability) => capability.permissions)
        .filter((permission) => permission.startsWith('global-shortcut:')),
    ).toEqual([])
  })

  it('does not expose direct autostart plugin control to renderer windows', () => {
    expect(
      Object.values(capabilityFiles)
        .flatMap((capability) => capability.permissions)
        .filter((permission) => permission.startsWith('autostart:')),
    ).toEqual([])
  })

  it.each(['capsule', 'ask'])('does not regrant updater or process permissions to %s', (window) => {
    const permissions = Object.values(capabilityFiles)
      .filter(
        (capability) => capability.windows.includes(window) || capability.windows.includes('*'),
      )
      .flatMap((capability) => capability.permissions)
    expect(
      permissions.filter(
        (permission) => permission.startsWith('updater:') || permission.startsWith('process:'),
      ),
    ).toEqual([])
  })

  it('does not grant auxiliary windows updater or process control', () => {
    expect(
      capabilities.permissions.filter(
        (permission) => permission.startsWith('updater:') || permission.startsWith('process:'),
      ),
    ).toEqual([])
  })

  it('preserves updater and process control for the main window only', () => {
    expect(mainUpdater.windows).toEqual(['main'])
    expect(mainUpdater.permissions).toContain('updater:default')
    expect(mainUpdater.permissions).toContain('process:default')
  })

  it('does not grant renderer access to arbitrary settings stores', () => {
    expect(capabilities.permissions.some((permission) => permission.startsWith('store:'))).toBe(
      false,
    )
  })
  it('allows the focusability change used by capsule placement', () => {
    expect(capabilities.windows).toContain('capsule')
    expect(capabilities.permissions).toContain('core:window:allow-set-focusable')
  })

  it('preserves cursor pass-through and bottom-anchored placement permissions', () => {
    for (const permission of [
      'core:window:allow-set-ignore-cursor-events',
      'core:window:allow-set-size',
      'core:window:allow-set-position',
    ]) {
      expect(capabilities.permissions).toContain(permission)
    }
    expect(capabilities.permissions).toContain('core:window:default')
  })
})
