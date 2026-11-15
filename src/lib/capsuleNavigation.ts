import type { CapsuleErrorKey } from './capsuleError'

export function capsuleSystemSettingsUrl(error: CapsuleErrorKey | null): string | null {
  switch (error) {
    case 'accessibility_required':
      return 'x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility'
    case 'microphone_permission_required':
      return 'x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone'
    case 'speech_permission_required':
    case 'speech_permission_restricted':
      return 'x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition'
    case 'no_network':
      return 'x-apple.systempreferences:com.apple.preference.network'
    default:
      return null
  }
}

export async function openCapsuleSettings(route: string): Promise<void> {
  const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow')
  const { emitTo } = await import('@tauri-apps/api/event')
  const main = await WebviewWindow.getByLabel('main')
  if (!main) throw new Error('Main window unavailable')
  await emitTo('main', 'navigate', route)
  await main.show()
  await main.setFocus()
}
