import { expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import * as api from '../tauri'
import { useAppStore, type AppConfig } from '../../stores/appStore'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

it('passes an optional activation identifier in the same scene save request', async () => {
  const config = structuredClone(useAppStore.getInitialState().config)
  const identity = '22222222-2222-4222-8222-222222222222'
  const update = api.updateSceneConfiguration as (
    expected: AppConfig,
    desired: AppConfig,
    saveId: string,
    activateSceneId?: string,
  ) => Promise<unknown>
  await update(config, config, identity, 'builtin_meeting_notes')
  expect(invoke).toHaveBeenLastCalledWith('update_scene_configuration', {
    expected: {
      custom_scenes: [],
      system_scene_overrides: [],
      active_scene: null,
      family_scene_assignments: [],
    },
    desired: {
      custom_scenes: [],
      system_scene_overrides: [],
      active_scene: null,
      family_scene_assignments: [],
    },
    saveId: identity,
    activateSceneId: 'builtin_meeting_notes',
  })
})

it('clears activation with an ID-only request instead of a renderer scene snapshot', async () => {
  const identity = '22222222-2222-4222-8222-222222222222'
  expect('setActiveScene' in api).toBe(true)
  const setActive = (
    api as unknown as {
      setActiveScene: (sceneId: string | null, saveId: string) => Promise<unknown>
    }
  ).setActiveScene
  await setActive(null, identity)
  expect(invoke).toHaveBeenLastCalledWith('set_active_scene', {
    sceneId: null,
    saveId: identity,
  })
})

it('sends only the expected and desired scene group with its save identity', async () => {
  const expected = structuredClone(useAppStore.getInitialState().config)
  const desired = { ...expected, microphone_device: 'never-send', theme: 'dark' as const }
  const saveId = '22222222-2222-4222-8222-222222222222'
  expect('updateSceneConfiguration' in api).toBe(true)
  const update = (
    api as unknown as {
      updateSceneConfiguration: (
        expected: AppConfig,
        desired: AppConfig,
        saveId: string,
      ) => Promise<unknown>
    }
  ).updateSceneConfiguration
  await update(expected, desired, saveId)
  const scenes = {
    custom_scenes: [],
    system_scene_overrides: [],
    active_scene: null,
    family_scene_assignments: [],
  }
  expect(invoke).toHaveBeenCalledWith('update_scene_configuration', {
    expected: scenes,
    desired: scenes,
    saveId,
  })
})
