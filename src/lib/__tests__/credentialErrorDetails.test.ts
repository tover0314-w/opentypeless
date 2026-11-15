import { describe, expect, it } from 'vitest'
import { credentialErrorDetails } from '../credentialErrorDetails'

describe('credential error details', () => {
  it.each([
    'Could not save credential in the system vault',
    'Could not delete credential from the system vault',
    'Credential save could not be verified',
    'Credential deletion could not be verified',
  ])('uses the existing localized failure without repeating %s', (message) => {
    expect(credentialErrorDetails(message)).toBe('')
  })

  it('preserves unknown diagnostic messages', () => {
    expect(credentialErrorDetails('Synthetic permission unavailable')).toBe(
      'Synthetic permission unavailable',
    )
  })
})
