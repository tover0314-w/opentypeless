import { describe, expect, it } from 'vitest'
import { providerErrorMessage } from '../providerErrorMessage'

describe('provider error feedback', () => {
  it.each([null, {}, 42, [], '', ' \n', new Error('')])(
    'uses the selected fallback for unsupported or blank error %j',
    (error) => {
      expect(providerErrorMessage(error, 'synthetic-key', null)).toBeNull()
      expect(providerErrorMessage(error, 'synthetic-key', 'Save failed')).toBe('Save failed')
    },
  )

  it('redacts every literal occurrence without interpreting regular expression characters', () => {
    const secret = 'synthetic+$密钥'
    expect(providerErrorMessage(new Error(`${secret} rejected ${secret}`), secret, null)).toBe(
      '[redacted] rejected [redacted]',
    )
  })

  it('preserves native string diagnostics and surrounding whitespace', () => {
    expect(providerErrorMessage('  Synthetic diagnostic\n', '', null)).toBe(
      '  Synthetic diagnostic\n',
    )
  })
})
