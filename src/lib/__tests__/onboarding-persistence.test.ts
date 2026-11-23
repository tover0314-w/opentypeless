import { beforeEach, describe, expect, it, vi } from 'vitest'
import { loadOnboardingCompleted, saveOnboardingCompleted } from '../tauri'

const invoke = vi.hoisted(() => vi.fn())
const emit = vi.hoisted(() => vi.fn())

vi.mock('@tauri-apps/api/core', () => ({ invoke }))
vi.mock('@tauri-apps/api/event', () => ({ emit }))

beforeEach(() => {
  vi.clearAllMocks()
  invoke.mockResolvedValue(undefined)
  emit.mockResolvedValue(undefined)
})

describe('onboarding completion persistence', () => {
  it('persists through the narrow command before emitting the ready hint', async () => {
    await saveOnboardingCompleted()
    expect(invoke).toHaveBeenCalledWith('save_onboarding_completed')
    expect(invoke.mock.invocationCallOrder[0]).toBeLessThan(emit.mock.invocationCallOrder[0])
  })

  it('rejects a failed native save and does not emit a completion hint', async () => {
    invoke.mockRejectedValueOnce(new Error('disk full'))
    await expect(saveOnboardingCompleted()).rejects.toThrow('disk full')
    expect(emit).not.toHaveBeenCalled()
  })

  it.each([false, true])('reads the persisted completion state %s', async (completed) => {
    invoke.mockResolvedValueOnce(completed)
    expect(await loadOnboardingCompleted()).toBe(completed)
    expect(invoke).toHaveBeenCalledWith('load_onboarding_completed')
  })
})
