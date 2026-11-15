import { expect, it } from 'vitest'
import * as module from '../configSaveRegistry'

it('owns cloned submissions and removes only the matching save', () => {
  const Registry = module.ConfigSaveRegistry
  const registry = new Registry<{ theme: string; targets: string[] }>(2)
  const first = '11111111-1111-4111-8111-111111111111'
  const second = '22222222-2222-4222-8222-222222222222'
  const draft = { theme: 'dark', targets: ['en'] }
  registry.register(first, draft)
  draft.targets.push('fr')
  expect(registry.snapshot(first)).toEqual({ theme: 'dark', targets: ['en'] })
  const snapshot = registry.snapshot(first)!
  snapshot.targets.push('ja')
  expect(registry.snapshot(first)?.targets).toEqual(['en'])
  registry.register(second, { theme: 'light', targets: [] })
  expect(() => registry.register(first, draft)).toThrow()
  expect(() => registry.register('33333333-3333-4333-8333-333333333333', draft)).toThrow()
  expect(registry.retire(first)).toBe(true)
  expect(registry.retire(first)).toBe(false)
  expect(registry.snapshot(first)).toBeNull()
  expect(registry.snapshot(second)?.theme).toBe('light')
})

it('rejects malformed identities and capacities without retaining drafts', () => {
  for (const capacity of [0, -1, 1.5, Number.POSITIVE_INFINITY]) {
    expect(() => new module.ConfigSaveRegistry(capacity)).toThrow()
  }
  const registry = new module.ConfigSaveRegistry(1)
  expect(() => registry.register('invalid', { theme: 'dark' })).toThrow()
  expect(registry.snapshot('invalid')).toBeNull()
})
