import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { clearSessionTokenFromMemory, resetCloudSessionCoordinatorForTests } from '../cloud-session'
import {
  cancelHistorySyncProposal,
  confirmHistorySyncProposal,
  confirmAndReadHistorySyncConsent,
  HistorySyncConfirmationUnknownError,
  HistorySyncCancellationUnknownError,
  getHistorySyncConsentStatus,
  getHistorySyncConsentView,
  getHistorySyncSessionEpoch,
  prepareHistorySyncProposal,
  revokeHistorySyncConsentView,
} from '../historySyncConsent'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

afterEach(() => vi.useRealTimers())

describe('confirmation with native readback', () => {
  const proposal = {
    requestId: '11111111-1111-4111-8111-111111111111',
    description: {
      accountId: 'synthetic-account',
      serviceUrl: 'https://example.invalid/api/history-sync/status',
      sessionEpoch: '22222222-2222-4222-8222-222222222222',
    },
  }
  const view = {
    sessionEpoch: proposal.description.sessionEpoch,
    active: true,
    generation: '33333333-3333-4333-8333-333333333333',
  }

  it('confirms once then returns the current native view', async () => {
    vi.mocked(invoke).mockImplementation(async (command) =>
      command === 'confirm_history_sync_proposal' ? null : view,
    )
    await expect(confirmAndReadHistorySyncConsent(proposal)).resolves.toEqual(view)
    expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
      'confirm_history_sync_proposal',
      'get_history_sync_consent_view',
    ])
  })

  it.each([
    { ...proposal, description: { ...proposal.description, accountId: '' } },
    {
      ...proposal,
      description: {
        ...proposal.description,
        serviceUrl: 'http://example.invalid/api/history-sync/status',
      },
    },
    { ...proposal, token: 'private' },
    ...[
      '',
      'invalid',
      '22222222-2222-1222-8222-222222222222',
      '22222222-2222-4222-7222-222222222222',
    ].map((sessionEpoch) => ({
      ...proposal,
      description: { ...proposal.description, sessionEpoch },
    })),
  ])('does not confirm malformed displayed material: %j', async (invalid) => {
    vi.mocked(invoke).mockResolvedValue(null)
    await expect(confirmAndReadHistorySyncConsent(invalid)).rejects.toThrow(
      'Invalid history sync proposal response',
    )
    expect(invoke).not.toHaveBeenCalled()
  })

  it.each([
    { ...view, active: false, generation: null },
    { ...view, sessionEpoch: '44444444-4444-4444-8444-444444444444' },
  ])('does not report authorization success for mismatched readback %j', async (current) => {
    vi.mocked(invoke).mockImplementation(async (command) =>
      command === 'confirm_history_sync_proposal' ? null : current,
    )
    await expect(confirmAndReadHistorySyncConsent(proposal)).rejects.toBeInstanceOf(
      HistorySyncConfirmationUnknownError,
    )
    expect(
      vi
        .mocked(invoke)
        .mock.calls.filter(([command]) => command === 'confirm_history_sync_proposal'),
    ).toHaveLength(1)
  })

  it('marks readback failure unknown rather than retrying a consumed proposal', async () => {
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'confirm_history_sync_proposal') return null
      throw new Error('Synthetic readback failure')
    })
    await expect(confirmAndReadHistorySyncConsent(proposal)).rejects.toBeInstanceOf(
      HistorySyncConfirmationUnknownError,
    )
    expect(invoke).toHaveBeenCalledTimes(2)
  })

  it('preserves an explicit confirmation rejection and skips readback', async () => {
    vi.mocked(invoke).mockRejectedValue(new Error('Synthetic expired proposal'))
    await expect(confirmAndReadHistorySyncConsent(proposal)).rejects.toThrow(
      'Synthetic expired proposal',
    )
    expect(invoke).toHaveBeenCalledTimes(1)
  })
})

describe('history sync proposal preparation', () => {
  const epoch = '22222222-2222-4222-8222-222222222222'
  const proposal = {
    requestId: '11111111-1111-4111-8111-111111111111',
    description: {
      accountId: 'synthetic-account',
      serviceUrl: 'https://example.invalid/api/history-sync/status',
      sessionEpoch: epoch,
    },
  }

  it('prepares only for the supplied session without confirming or uploading', async () => {
    vi.mocked(invoke).mockResolvedValue(proposal)
    await expect(prepareHistorySyncProposal(epoch)).resolves.toEqual(proposal)
    expect(invoke).toHaveBeenCalledExactlyOnceWith('prepare_history_sync_proposal', {
      expectedSessionEpoch: epoch,
    })
  })

  it('does not invoke preparation when already cancelled', async () => {
    const controller = new AbortController()
    controller.abort()
    await expect(prepareHistorySyncProposal(epoch, controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    })
    expect(invoke).not.toHaveBeenCalled()
  })

  it('stops waiting on cancellation and cleans only the late returned proposal', async () => {
    const controller = new AbortController()
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation((command) => {
      if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
      return new Promise((resolve) => {
        finish = resolve
      })
    })
    const outcome = prepareHistorySyncProposal(epoch, controller.signal).catch(
      (error: unknown) => error,
    )
    controller.abort()
    const result = await Promise.race([
      outcome,
      new Promise((resolve) => setTimeout(() => resolve('still waiting'), 100)),
    ])
    expect(result).toMatchObject({ name: 'AbortError' })
    finish(proposal)
    await vi.waitFor(() =>
      expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
        requestId: proposal.requestId,
      }),
    )
    expect(invoke).not.toHaveBeenCalledWith('confirm_history_sync_proposal', expect.anything())
  })

  it.each([
    null,
    {},
    { ...proposal, token: 'private' },
    { ...proposal, requestId: 'invalid' },
    { ...proposal, description: { ...proposal.description, accountId: ' ' } },
    { ...proposal, description: { ...proposal.description, accountId: 'account\n' } },
    { ...proposal, description: { ...proposal.description, accountId: 'x'.repeat(201) } },
    { ...proposal, description: { ...proposal.description, sessionEpoch: crypto.randomUUID() } },
    { ...proposal, description: { ...proposal.description, token: 'private' } },
    ...[
      'http://example.invalid/api/history-sync/status',
      'https://user:secret@example.invalid/api/history-sync/status',
      'https://example.invalid/api/history-sync/status?unexpected=1',
      'https://example.invalid/api/history-sync/status#unexpected',
      'https://example.invalid/api/history-sync/status?',
      'https://example.invalid/api/history-sync/status#',
      'https://example.invalid/other',
      'not-a-url',
    ].map((serviceUrl) => ({
      ...proposal,
      description: { ...proposal.description, serviceUrl },
    })),
  ])('rejects malformed or mismatched proposal: %j', async (value) => {
    vi.mocked(invoke).mockResolvedValue(value)
    await expect(prepareHistorySyncProposal(epoch)).rejects.toThrow(
      'Invalid history sync proposal response',
    )
    const identity =
      value && typeof value === 'object' && 'requestId' in value ? value.requestId : null
    if (identity === proposal.requestId) {
      expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
        requestId: proposal.requestId,
      })
    } else {
      expect(invoke).not.toHaveBeenCalledWith('cancel_history_sync_proposal', expect.anything())
    }
  })

  it('rejects invalid input before IPC', async () => {
    await expect(prepareHistorySyncProposal('invalid')).rejects.toThrow(
      'Invalid history sync session epoch',
    )
    expect(invoke).not.toHaveBeenCalled()
  })

  it('rejects a delayed proposal after renderer session change', async () => {
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation((command) =>
      command === 'cancel_history_sync_proposal'
        ? Promise.resolve(true)
        : new Promise((resolve) => {
            finish = resolve
          }),
    )
    const outcome = prepareHistorySyncProposal(epoch).catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish(proposal)
    expect(await outcome).toMatchObject({
      message: 'History sync proposal preparation superseded',
    })
    expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
      requestId: proposal.requestId,
    })
    expect(invoke).not.toHaveBeenCalledWith('confirm_history_sync_proposal', expect.anything())
  })

  it('does not hide native discovery failure', async () => {
    vi.mocked(invoke).mockRejectedValue(new Error('Synthetic discovery failure'))
    await expect(prepareHistorySyncProposal(epoch)).rejects.toThrow('Synthetic discovery failure')
  })

  it('preserves superseded failure when stale proposal cleanup is rejected', async () => {
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation((command) => {
      if (command === 'cancel_history_sync_proposal') {
        return Promise.reject(new Error('Synthetic cleanup failure'))
      }
      return new Promise((resolve) => {
        finish = resolve
      })
    })
    const outcome = prepareHistorySyncProposal(epoch).catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish(proposal)
    expect(await outcome).toMatchObject({
      message: 'History sync proposal preparation superseded',
    })
  })

  it('bounds stale cleanup waiting and observes late native rejection', async () => {
    vi.useFakeTimers()
    let finishPreparation!: (value: unknown) => void
    let rejectCleanup!: (error: Error) => void
    vi.mocked(invoke).mockImplementation((command) => {
      if (command === 'cancel_history_sync_proposal') {
        return new Promise((_resolve, reject) => {
          rejectCleanup = reject
        })
      }
      return new Promise((resolve) => {
        finishPreparation = resolve
      })
    })
    let settled = false
    const outcome = prepareHistorySyncProposal(epoch).catch((error: unknown) => {
      settled = true
      return error
    })
    clearSessionTokenFromMemory()
    finishPreparation(proposal)
    await vi.advanceTimersByTimeAsync(1001)
    expect(settled).toBe(true)
    expect(await outcome).toMatchObject({
      message: 'History sync proposal preparation superseded',
    })
    expect(vi.getTimerCount()).toBe(0)
    rejectCleanup(new Error('Synthetic late cancellation failure'))
    await vi.advanceTimersByTimeAsync(0)
  })
})

describe('explicit history sync proposal confirmation', () => {
  const requestId = '11111111-1111-4111-8111-111111111111'
  const epoch = '22222222-2222-4222-8222-222222222222'

  it('bounds confirmation waiting without retrying or treating late success as the result', async () => {
    vi.useFakeTimers()
    let finish!: (value: null) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    let settled = false
    const outcome = confirmHistorySyncProposal(requestId, epoch).catch((error: unknown) => {
      settled = true
      return error
    })
    await vi.advanceTimersByTimeAsync(5001)
    expect(settled).toBe(true)
    expect(await outcome).toMatchObject({
      message: 'History sync proposal confirmation result unknown',
    })
    expect(await outcome).toBeInstanceOf(HistorySyncConfirmationUnknownError)
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
    finish(null)
    await vi.advanceTimersByTimeAsync(0)
  })

  it('sends the displayed proposal identity and epoch without credentials or service fields', async () => {
    vi.mocked(invoke).mockResolvedValue(null)
    await expect(confirmHistorySyncProposal(requestId, epoch)).resolves.toBeUndefined()
    expect(invoke).toHaveBeenCalledExactlyOnceWith('confirm_history_sync_proposal', {
      requestId,
      expectedSessionEpoch: epoch,
    })
  })

  it.each([
    ['', epoch],
    [requestId, 'invalid'],
    ['11111111-1111-1111-8111-111111111111', epoch],
  ])('rejects invalid proposal ownership before IPC: %s %s', async (identity, sessionEpoch) => {
    await expect(confirmHistorySyncProposal(identity, sessionEpoch)).rejects.toThrow(
      'Invalid history sync proposal confirmation identity',
    )
    expect(invoke).not.toHaveBeenCalled()
  })

  it.each([undefined, false, true, {}, 'success'])(
    'rejects ambiguous native success %j',
    async (value) => {
      vi.mocked(invoke).mockResolvedValue(value)
      const result = await confirmHistorySyncProposal(requestId, epoch).catch(
        (error: unknown) => error,
      )
      expect(result).toBeInstanceOf(HistorySyncConfirmationUnknownError)
      expect(result).toMatchObject({
        message: 'Invalid history sync proposal confirmation response',
      })
      expect(invoke).toHaveBeenCalledTimes(1)
    },
  )

  it('preserves native confirmation failure', async () => {
    vi.mocked(invoke).mockRejectedValue(new Error('Synthetic expired proposal'))
    await expect(confirmHistorySyncProposal(requestId, epoch)).rejects.toThrow(
      'Synthetic expired proposal',
    )
  })

  it('does not report old confirmation success after session replacement', async () => {
    let finish!: (value: null) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const outcome = confirmHistorySyncProposal(requestId, epoch).catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish(null)
    expect(await outcome).toMatchObject({
      message: 'History sync proposal confirmation superseded',
    })
  })
})

describe('history sync proposal cancellation', () => {
  const requestId = '11111111-1111-4111-8111-111111111111'

  it('bounds cancellation waiting without reporting success or retrying', async () => {
    vi.useFakeTimers()
    let finish!: (value: boolean) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    let settled = false
    const outcome = cancelHistorySyncProposal(requestId).catch((error: unknown) => {
      settled = true
      return error
    })
    await vi.advanceTimersByTimeAsync(5001)
    expect(settled).toBe(true)
    expect(await outcome).toBeInstanceOf(HistorySyncCancellationUnknownError)
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
    finish(true)
    await vi.advanceTimersByTimeAsync(0)
  })

  it.each([true, false])('returns explicit cancellation result %s', async (cancelled) => {
    vi.mocked(invoke).mockResolvedValue(cancelled)
    await expect(cancelHistorySyncProposal(requestId)).resolves.toBe(cancelled)
    expect(invoke).toHaveBeenCalledExactlyOnceWith('cancel_history_sync_proposal', { requestId })
  })

  it.each(['', 'invalid', '11111111-1111-1111-8111-111111111111'])(
    'rejects invalid request identity %s before invoking',
    async (invalid) => {
      await expect(cancelHistorySyncProposal(invalid)).rejects.toThrow(
        'Invalid history sync proposal identity',
      )
      expect(invoke).not.toHaveBeenCalled()
    },
  )

  it.each([null, undefined, 'false', 0, {}])('rejects malformed result %j', async (result) => {
    vi.mocked(invoke).mockResolvedValue(result)
    await expect(cancelHistorySyncProposal(requestId)).rejects.toThrow(
      'Invalid history sync proposal cancellation response',
    )
  })

  it('rejects delayed cancellation feedback after session change', async () => {
    let finish!: (value: boolean) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const outcome = cancelHistorySyncProposal(requestId).catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish(true)
    expect(await outcome).toMatchObject({
      message: 'History sync proposal cancellation superseded',
    })
  })
})

describe('generation-owned consent revocation', () => {
  const view = {
    sessionEpoch: '11111111-1111-4111-8111-111111111111',
    active: true,
    generation: '22222222-2222-4222-8222-222222222222',
  }

  it('bounds revocation waiting as an unknown result without retrying or accepting late success', async () => {
    vi.useFakeTimers()
    let finish!: (value: null) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    let settled = false
    const outcome = revokeHistorySyncConsentView(view).catch((error: unknown) => {
      settled = true
      return error
    })
    await vi.advanceTimersByTimeAsync(5001)
    expect(settled).toBe(true)
    expect(await outcome).toMatchObject({
      message: 'History sync consent revocation result unknown',
    })
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
    finish(null)
    await vi.advanceTimersByTimeAsync(0)
  })

  it('revokes only the displayed snapshot without reading a newer generation', async () => {
    vi.mocked(invoke).mockResolvedValue(null)
    await expect(revokeHistorySyncConsentView(view)).resolves.toBeUndefined()
    expect(invoke).toHaveBeenCalledExactlyOnceWith('revoke_history_sync_consent_generation', {
      expectedSessionEpoch: view.sessionEpoch,
      expectedGeneration: view.generation,
    })
  })

  it.each([
    { ...view, active: false },
    { ...view, generation: null },
    { ...view, generation: 'invalid' },
    { ...view, sessionEpoch: 'invalid' },
  ])('rejects a view that cannot identify an active grant: %j', async (invalid) => {
    await expect(revokeHistorySyncConsentView(invalid)).rejects.toThrow(
      'Invalid history sync consent revocation view',
    )
    expect(invoke).not.toHaveBeenCalled()
  })

  it('preserves native rejection of a stale generation', async () => {
    vi.mocked(invoke).mockRejectedValue(new Error('History sync consent revocation superseded'))
    await expect(revokeHistorySyncConsentView(view)).rejects.toThrow('superseded')
  })
})

describe('atomic consent view', () => {
  const sessionEpoch = '11111111-1111-4111-8111-111111111111'
  const generation = '22222222-2222-4222-8222-222222222222'

  it('times out an unknown native state without accepting a late result', async () => {
    vi.useFakeTimers()
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    let settled = false
    const outcome = getHistorySyncConsentView().catch((error: unknown) => {
      settled = true
      return error
    })
    await vi.advanceTimersByTimeAsync(5001)
    expect(settled).toBe(true)
    expect(await outcome).toMatchObject({ message: 'History sync consent view timed out' })
    expect(vi.getTimerCount()).toBe(0)
    finish({ sessionEpoch, active: true, generation })
    await vi.advanceTimersByTimeAsync(0)
  })

  it.each([
    { sessionEpoch, active: false, generation: null },
    { sessionEpoch, active: true, generation },
  ])('accepts the native atomic view %j', async (view) => {
    vi.mocked(invoke).mockResolvedValue(view)
    await expect(getHistorySyncConsentView()).resolves.toEqual(view)
    expect(invoke).toHaveBeenCalledExactlyOnceWith('get_history_sync_consent_view')
  })

  it.each([
    null,
    {},
    { sessionEpoch, active: true, generation: null },
    { sessionEpoch, active: false, generation },
    { sessionEpoch, active: true, generation: 'invalid' },
    { sessionEpoch: 'invalid', active: false, generation: null },
    { sessionEpoch, active: false, generation: null, token: 'private' },
  ])('rejects malformed or credential-bearing view %j', async (view) => {
    vi.mocked(invoke).mockResolvedValue(view)
    await expect(getHistorySyncConsentView()).rejects.toThrow('Invalid history sync consent view')
  })

  it('rejects a late atomic view after session replacement', async () => {
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const outcome = getHistorySyncConsentView().catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish({ sessionEpoch, active: false, generation: null })
    expect(await outcome).toMatchObject({ message: 'History sync consent view superseded' })
  })
})

beforeEach(() => {
  vi.resetAllMocks()
  resetCloudSessionCoordinatorForTests()
})

describe('owned history sync consent revocation', () => {
  const epoch = '11111111-1111-4111-8111-111111111111'
  const generation = '22222222-2222-4222-8222-222222222222'
  const revoke = (sessionEpoch: string) =>
    revokeHistorySyncConsentView({ sessionEpoch, active: true, generation })

  it('sends the supplied epoch and generation and accepts the native unit response', async () => {
    vi.mocked(invoke).mockResolvedValue(null)
    await expect(revoke(epoch)).resolves.toBeUndefined()
    expect(invoke).toHaveBeenCalledExactlyOnceWith('revoke_history_sync_consent_generation', {
      expectedSessionEpoch: epoch,
      expectedGeneration: generation,
    })
  })

  it.each(['', 'invalid', '11111111-1111-1111-8111-111111111111'])(
    'rejects invalid epoch before invoking: %s',
    async (invalid) => {
      await expect(revoke(invalid)).rejects.toThrow('Invalid history sync consent revocation view')
      expect(invoke).not.toHaveBeenCalled()
    },
  )

  it.each([false, true, {}, 'success'])('rejects malformed acknowledgement: %j', async (value) => {
    vi.mocked(invoke).mockResolvedValue(value)
    await expect(revoke(epoch)).rejects.toThrow('Invalid history sync consent revocation response')
  })

  it('does not report a delayed revocation as success after a session change', async () => {
    let finish!: (value: null) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const outcome = revoke(epoch).catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish(null)
    expect(await outcome).toMatchObject({ message: 'History sync consent revocation superseded' })
  })

  it('preserves native revocation errors', async () => {
    vi.mocked(invoke).mockRejectedValue(new Error('Synthetic native rejection'))
    await expect(revoke(epoch)).rejects.toThrow('Synthetic native rejection')
  })
})

describe('credential-free history sync session identity', () => {
  it('bounds epoch waiting and ignores a late native success without retrying', async () => {
    vi.useFakeTimers()
    let finish!: (value: string) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    let settled = false
    const outcome = getHistorySyncSessionEpoch().catch((error: unknown) => {
      settled = true
      return error
    })
    await vi.advanceTimersByTimeAsync(5001)
    expect(settled).toBe(true)
    expect(await outcome).toMatchObject({ message: 'History sync session epoch timed out' })
    expect(vi.getTimerCount()).toBe(0)
    expect(invoke).toHaveBeenCalledExactlyOnceWith('get_history_sync_session_epoch')
    finish('11111111-1111-4111-8111-111111111111')
    await vi.advanceTimersByTimeAsync(0)
  })

  it('reads only the native epoch command', async () => {
    const epoch = '11111111-1111-4111-8111-111111111111'
    vi.mocked(invoke).mockResolvedValue(epoch)
    await expect(getHistorySyncSessionEpoch()).resolves.toBe(epoch)
    expect(invoke).toHaveBeenCalledExactlyOnceWith('get_history_sync_session_epoch')
  })

  it.each([null, undefined, {}, '', 'invalid'])('rejects malformed epoch: %j', async (epoch) => {
    vi.mocked(invoke).mockResolvedValue(epoch)
    await expect(getHistorySyncSessionEpoch()).rejects.toThrow('Invalid history sync session epoch')
  })

  it('rejects a delayed epoch after a renderer session change', async () => {
    let finish!: (value: string) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const outcome = getHistorySyncSessionEpoch().catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish('11111111-1111-4111-8111-111111111111')
    expect(await outcome).toMatchObject({ message: 'History sync session epoch superseded' })
  })
})

describe('native history sync consent status', () => {
  it.each([true, false])(
    'returns the explicit native boolean %s without loading credentials',
    async (status) => {
      vi.mocked(invoke).mockResolvedValue(status)
      await expect(getHistorySyncConsentStatus()).resolves.toBe(status)
      expect(invoke).toHaveBeenCalledExactlyOnceWith('get_history_sync_consent_status')
    },
  )

  it.each([null, undefined, 0, 1, 'false', {}, { enabled: true }])(
    'rejects malformed status rather than treating it as disabled: %j',
    async (status) => {
      vi.mocked(invoke).mockResolvedValue(status)
      await expect(getHistorySyncConsentStatus()).rejects.toThrow(
        'Invalid history sync consent status',
      )
    },
  )

  it('rejects a delayed status from an earlier renderer session', async () => {
    let finish!: (value: boolean) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const pending = getHistorySyncConsentStatus()
    const outcome = pending.catch((error: unknown) => error)
    clearSessionTokenFromMemory()
    finish(true)
    expect(await outcome).toMatchObject({ message: 'History sync consent status superseded' })
  })

  it('propagates native failure without inventing disabled status', async () => {
    vi.mocked(invoke).mockRejectedValue(new Error('Synthetic native failure'))
    await expect(getHistorySyncConsentStatus()).rejects.toThrow('Synthetic native failure')
  })
})
