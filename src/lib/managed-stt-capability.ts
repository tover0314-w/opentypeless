import { invoke } from '@tauri-apps/api/core'
import type { AuthenticatedAccountSnapshot } from './api'

export async function syncManagedSttCapability(
  accountSnapshot: AuthenticatedAccountSnapshot | null,
  expectedUserId: string | null,
): Promise<void> {
  if (
    !accountSnapshot ||
    !expectedUserId ||
    accountSnapshot.schemaVersion !== 1 ||
    accountSnapshot.userId !== expectedUserId
  ) {
    await invoke('clear_managed_stt_capability')
    return
  }

  await invoke('cache_managed_stt_capability', {
    accountSnapshot,
    expectedUserId,
  })
}
