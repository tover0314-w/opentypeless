import { expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { setActiveTranslationTarget } from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

it('passes optional recording ownership without changing legacy arguments', async () => {
  const switchTarget = setActiveTranslationTarget as (
    code: string,
    expectedSessionId?: number,
  ) => ReturnType<typeof setActiveTranslationTarget>
  await switchTarget('ja', 7)
  expect(invoke).toHaveBeenLastCalledWith('set_active_translation_target', {
    code: 'ja',
    expectedSessionId: 7,
  })
  await switchTarget('en')
  expect(invoke).toHaveBeenLastCalledWith('set_active_translation_target', { code: 'en' })
})

it('passes the process epoch with recording ownership', async () => {
  const epoch = '11111111-1111-4111-8111-111111111111'
  const switchTarget = setActiveTranslationTarget as (
    code: string,
    expectedSessionId?: number,
    expectedConfigEpoch?: string,
  ) => ReturnType<typeof setActiveTranslationTarget>
  await switchTarget('ja', 7, epoch)
  expect(invoke).toHaveBeenLastCalledWith('set_active_translation_target', {
    code: 'ja',
    expectedSessionId: 7,
    expectedConfigEpoch: epoch,
  })
})
