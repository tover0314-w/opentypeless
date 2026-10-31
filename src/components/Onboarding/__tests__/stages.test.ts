import { describe, expect, it } from 'vitest'
import { stageForStep } from '../stages'

describe('stageForStep', () => {
  it('groups the eight onboarding steps into Typeless-style stages', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 7].map(stageForStep)).toEqual([
      'signUp',
      'signUp',
      'setUp',
      'setUp',
      'setUp',
      'setUp',
      'tryIt',
      'tryIt',
    ])
  })
})
