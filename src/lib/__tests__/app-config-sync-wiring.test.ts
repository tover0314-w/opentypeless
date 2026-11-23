import { expect, it } from 'vitest'
import appSource from '../../App.tsx?raw'
import eventsSource from '../../hooks/useTauriEvents.ts?raw'

it('uses the shared configuration handshake in each window without duplicate unversioned reads', () => {
  expect(appSource.match(/useConfigSync\(\)/g)).toHaveLength(3)
  expect(appSource).not.toContain('getConfig(')
  expect(appSource).not.toContain("'config:patch'")
  expect(appSource).not.toContain('setSavedConfig')
  expect(appSource.match(/useTauriEvents\(\{ legacyConfigEvents: false \}\)/g)).toHaveLength(2)
  expect(appSource).toContain("configSyncStatus === 'loading'")
  expect(appSource).toContain("configSyncStatus === 'error'")
})

it('does not let the legacy event listener apply language side effects in a versioned window', () => {
  const handler = eventsSource.split("'config:patch', (patch) => {")[1]
  expect(handler).toBeDefined()
  expect(handler.split('addListener')[0]).toContain('configCommit !== null')
})
