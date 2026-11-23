import { describe, expect, it } from 'vitest'
import { capsuleSystemSettingsUrl } from '../capsuleNavigation'

describe('capsuleSystemSettingsUrl', () => {
  it('targets the system page corresponding to the actual error', () => {
    expect(capsuleSystemSettingsUrl('accessibility_required')).toContain('Privacy_Accessibility')
    expect(capsuleSystemSettingsUrl('microphone_permission_required')).toContain(
      'Privacy_Microphone',
    )
    expect(capsuleSystemSettingsUrl('no_network')).toContain('preference.network')
    expect(capsuleSystemSettingsUrl('speech_permission_required')).toContain(
      'Privacy_SpeechRecognition',
    )
    expect(capsuleSystemSettingsUrl('speech_permission_restricted')).toContain(
      'Privacy_SpeechRecognition',
    )
    expect(capsuleSystemSettingsUrl('stt_invalid_key')).toBeNull()
    expect(capsuleSystemSettingsUrl(null)).toBeNull()
  })
})
