import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../../components/toast-service', () => ({ toast: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { API_BASE_URL, APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from '../../lib/constants'
import { resetCloudSessionCoordinatorForTests } from '../../lib/cloud-session'
import { useAuthStore } from '../authStore'

describe('authStore restart recovery', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    resetCloudSessionCoordinatorForTests()
    useAuthStore.setState({
      user: null,
      plan: 'free',
      source: 'free',
      displayName: 'Free',
      licenseStatus: null,
      subscriptionRefreshState: 'unknown',
      subscriptionLastVerifiedAt: null,
      loading: false,
      error: null,
    })

    vi.mocked(invoke).mockImplementation((command) =>
      Promise.resolve(command === 'get_session_token' ? 'vault-token' : undefined),
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string | URL | Request) => {
        const url = String(input)
        if (url === `${API_BASE_URL}/api/auth/get-session`) {
          return new Response(
            JSON.stringify({
              user: {
                id: 'user-1',
                email: 'person@example.com',
                name: 'Person',
                emailVerified: true,
              },
              session: { id: 'session-1' },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (url === `${API_BASE_URL}/api/auth/list-accounts`) {
          return new Response(JSON.stringify([{ providerId: 'credential' }]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (url === `${API_BASE_URL}/api/subscription/status`) {
          return new Response(
            JSON.stringify({
              plan: 'lifetime_starter',
              source: 'lifetime',
              displayName: 'Lifetime Starter',
              subscriptionEnd: null,
              subscriptionStatus: 'active',
              licenseStatus: 'active',
              quotaModel: 'legacy_dual_meter',
              displayWordsUsedEstimate: 0,
              displayWordsLimit: 0,
              displayWordsResetAt: null,
              sttSecondsUsed: 0,
              sttSecondsLimit: 0,
              llmTokensUsed: 0,
              llmTokensLimit: 0,
              cloudWordsUsed: 0,
              cloudWordsLimit: 0,
              cloudWordsResetAt: null,
              byokUnlimited: true,
              accountSnapshot: {
                schemaVersion: 1,
                userId: 'user-1',
                managedSttCapabilities: {
                  version: 1,
                  maxRecordingSeconds: 600,
                  maxMultipartBytes: 25_000_000,
                  formats: [{ mimeType: 'audio/wav', maxAudioBytes: 25_000_000 }],
                },
                generatedAt: '2026-09-08T00:00:00.000Z',
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        throw new Error(`Unexpected request: ${url}`)
      }),
    )
  })

  it('restores a paid account and managed recording limit from the vault token', async () => {
    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.user?.id).toBe('user-1')
    expect(state.plan).toBe('lifetime_starter')
    expect(state.licenseStatus).toBe('active')
    expect(state.subscriptionRefreshState).toBe('fresh')
    expect(invoke).toHaveBeenCalledWith('get_session_token')
    expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', {
      accountSnapshot: expect.objectContaining({
        userId: 'user-1',
        managedSttCapabilities: expect.objectContaining({ maxRecordingSeconds: 600 }),
      }),
      expectedUserId: 'user-1',
    })
    expect(invoke).not.toHaveBeenCalledWith('clear_managed_stt_capability')

    for (const [, init] of vi.mocked(fetch).mock.calls) {
      const headers = new Headers(init?.headers)
      expect(headers.get('Authorization')).toBe('Bearer vault-token')
      expect(headers.get(CLIENT_VERSION_HEADER)).toBe(APP_VERSION_HEADER_VALUE)
    }
    expect(localStorage.getItem('session_token')).toBeNull()
  })
})
