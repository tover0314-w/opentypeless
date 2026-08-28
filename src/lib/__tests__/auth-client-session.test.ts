import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { requestOpenTypelessPasswordReset } from '../auth-client'
import { resetCloudSessionCoordinatorForTests } from '../cloud-session'
import { APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from '../constants'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('auth client cloud session transport', () => {
  beforeEach(() => {
    localStorage.clear()
    resetCloudSessionCoordinatorForTests()
    vi.clearAllMocks()
    vi.mocked(invoke).mockImplementation((command) =>
      Promise.resolve(command === 'get_session_token' ? 'vault-token' : undefined),
    )
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
      }),
    )
  })

  it('adds the system-vault-backed bearer without persisting it in localStorage', async () => {
    await requestOpenTypelessPasswordReset('person@example.com', 'en')

    const [, init] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer vault-token')
    expect(headers.get(CLIENT_VERSION_HEADER)).toBe(APP_VERSION_HEADER_VALUE)
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('reuses one restored token without reading the vault on every request', async () => {
    await requestOpenTypelessPasswordReset('person@example.com', 'en')
    await requestOpenTypelessPasswordReset('person@example.com', 'en')

    expect(invoke).toHaveBeenCalledTimes(1)
    expect(invoke).toHaveBeenCalledWith('get_session_token')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
})
