import { describe, expect, it } from 'vitest'

const capabilities = import.meta.glob<{
  windows?: string[]
  webviews?: string[]
  remote?: unknown
  permissions: Array<string | { identifier: string }>
}>('../../../src-tauri/capabilities/*.json', { import: 'default', eager: true })

describe('updater capability boundary', () => {
  it('grants updater and process permissions only to the local main window', () => {
    const privileged = Object.entries(capabilities).filter(([, capability]) =>
      capability.permissions.some((permission) => {
        const identifier = typeof permission === 'string' ? permission : permission.identifier
        return identifier.startsWith('updater:') || identifier.startsWith('process:')
      }),
    )
    expect(privileged.length).toBeGreaterThan(0)
    for (const [, capability] of privileged) {
      expect(capability.windows).toEqual(['main'])
      expect(capability.webviews ?? []).toEqual([])
      expect(capability.remote).toBeUndefined()
    }
    const permissions = privileged.flatMap(([, capability]) =>
      capability.permissions.map((permission) =>
        typeof permission === 'string' ? permission : permission.identifier,
      ),
    )
    expect(permissions).not.toContain('updater:default')
    expect(permissions).not.toContain('updater:allow-check')
    expect(permissions).not.toContain('updater:allow-download-and-install')
    expect(permissions).toContain('updater:allow-download')
    expect(permissions).toContain('updater:allow-install')
    expect(permissions).toContain('process:default')
  })
})
