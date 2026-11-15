import { beforeEach, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import * as api from '../tauri'
import { useAppStore } from '../../stores/appStore'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

beforeEach(() => vi.mocked(invoke).mockReset())

it('passes an optional save identity without changing legacy update arguments', async () => {
  const config = useAppStore.getInitialState().config
  const saveId = '22222222-2222-4222-8222-222222222222'
  const update = api.updateConfig as (
    incomingConfig: typeof config,
    saveId?: string,
  ) => Promise<unknown>
  await update(config, saveId)
  expect(invoke).toHaveBeenLastCalledWith('update_config', {
    config: api.configWithoutProviderSecrets(config),
    saveId,
  })
  await api.updateConfig(config)
  expect(invoke).toHaveBeenLastCalledWith('update_config', {
    config: api.configWithoutProviderSecrets(config),
  })
})

it('exposes the versioned snapshot without changing the legacy config read', async () => {
  const commit = {
    configEpoch: '11111111-1111-4111-8111-111111111111',
    revision: 0,
    saveId: null,
    config: { theme: 'dark' },
  }
  vi.mocked(invoke).mockResolvedValueOnce(commit)
  expect('getConfigCommit' in api).toBe(true)
  const getCommit = (api as unknown as { getConfigCommit: () => Promise<unknown> }).getConfigCommit
  expect(await getCommit()).toEqual(commit)
  expect(invoke).toHaveBeenCalledWith('get_config_commit')
  vi.mocked(invoke).mockResolvedValueOnce(commit.config)
  expect(await api.getConfig()).toEqual(commit.config)
  expect(invoke).toHaveBeenLastCalledWith('get_config')
})
