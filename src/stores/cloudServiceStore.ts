import { create } from 'zustand'
import { capsuleErrorKeyFromPayload, type PipelineErrorPayload } from '../lib/capsuleError'
import type { AppConfig } from './appStore'

export type CloudServiceKind = 'stt' | 'llm'

export interface CloudServiceIncident {
  kind: CloudServiceKind
  code: string
  occurredAt: string
}

interface CloudServiceState {
  incident: CloudServiceIncident | null
  setIncident: (incident: CloudServiceIncident) => void
  clearIncident: () => void
}

const managedSttFailureCodes = new Set(['stt_timeout', 'stt_failed', 'stt_connection_failed'])
const managedLlmFailureCodes = new Set(['llm_failed'])

export function managedCloudIncidentFromPipelineError(
  payload: PipelineErrorPayload,
  config: Pick<AppConfig, 'stt_provider' | 'llm_provider'>,
  occurredAt = new Date().toISOString(),
): CloudServiceIncident | null {
  const code = capsuleErrorKeyFromPayload(payload)
  if (config.stt_provider === 'cloud' && managedSttFailureCodes.has(code)) {
    return { kind: 'stt', code, occurredAt }
  }
  if (config.llm_provider === 'cloud' && managedLlmFailureCodes.has(code)) {
    return { kind: 'llm', code, occurredAt }
  }
  return null
}

export const useCloudServiceStore = create<CloudServiceState>((set) => ({
  incident: null,
  setIncident: (incident) => set({ incident }),
  clearIncident: () => set({ incident: null }),
}))
