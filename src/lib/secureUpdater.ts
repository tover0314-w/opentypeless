import { invoke } from '@tauri-apps/api/core'
import { Update } from '@tauri-apps/plugin-updater'

export async function checkSecureUpdate(): Promise<Update | null> {
  const metadata = await invoke<ConstructorParameters<typeof Update>[0] | null>(
    'check_secure_update',
  )
  return metadata ? new Update(metadata) : null
}
