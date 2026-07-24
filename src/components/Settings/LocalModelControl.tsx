import { useCallback, useEffect, useState } from 'react'
import { Cpu, Loader2, RefreshCw, Zap, ZapOff } from 'lucide-react'
import { localSttStatus, localSttLoad, localSttUnload, type LocalSttStatus } from '../../lib/tauri'

/**
 * GPU model management for the local custom-whisper server. Lets the user see
 * whether the Whisper model is resident in VRAM and load/unload it on demand,
 * so the GPU can be freed for other work without stopping the STT service.
 *
 * Only rendered for local base URLs (localhost / 127.0.0.1); remote OpenAI-
 * compatible endpoints don't expose these controls.
 */
export function LocalModelControl({ baseUrl }: { baseUrl: string }) {
  const [status, setStatus] = useState<LocalSttStatus | null>(null)
  const [busy, setBusy] = useState<null | 'load' | 'unload' | 'refresh'>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setBusy('refresh')
    setError(null)
    try {
      setStatus(await localSttStatus(baseUrl))
    } catch {
      setError('Server not reachable')
      setStatus(null)
    } finally {
      setBusy(null)
    }
  }, [baseUrl])

  useEffect(() => {
    refresh()
  }, [refresh])

  const doLoad = async () => {
    setBusy('load')
    setError(null)
    try {
      setStatus(await localSttLoad(baseUrl))
    } catch {
      setError('Load failed')
    } finally {
      setBusy(null)
    }
  }

  const doUnload = async () => {
    setBusy('unload')
    setError(null)
    try {
      setStatus(await localSttUnload(baseUrl))
    } catch {
      setError('Unload failed')
    } finally {
      setBusy(null)
    }
  }

  const loaded = status?.loaded ?? false
  const gpu = status?.gpu ?? null

  return (
    <div className="mt-2 p-3 bg-bg-secondary border border-border rounded-[10px]">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Cpu size={15} className="text-text-secondary" />
          <span className="text-[13px] font-medium text-text-primary">Local GPU model</span>
        </div>
        <button
          type="button"
          onClick={refresh}
          disabled={busy !== null}
          className="p-1 rounded-md hover:bg-bg-tertiary text-text-tertiary disabled:opacity-50 transition-colors"
          title="Refresh status"
        >
          {busy === 'refresh' ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <RefreshCw size={14} />
          )}
        </button>
      </div>

      <div className="mt-2 flex items-center gap-2 text-[12px]">
        <span
          className={`inline-block w-2 h-2 rounded-full ${
            loaded ? 'bg-emerald-500' : 'bg-text-tertiary'
          }`}
        />
        <span className="text-text-secondary">
          {error
            ? error
            : loaded
              ? 'Loaded in VRAM'
              : status
                ? 'Offloaded (not in VRAM)'
                : 'Checking…'}
        </span>
        {gpu && (
          <span className="text-text-tertiary ml-auto">
            GPU {gpu.used_mib} / {gpu.total_mib} MiB
          </span>
        )}
      </div>

      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={doLoad}
          disabled={busy !== null || loaded}
          className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-bg-tertiary border border-border rounded-[8px] text-[12px] text-text-primary hover:border-border-focus disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          {busy === 'load' ? <Loader2 size={13} className="animate-spin" /> : <Zap size={13} />}
          Load
        </button>
        <button
          type="button"
          onClick={doUnload}
          disabled={busy !== null || !loaded}
          className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-bg-tertiary border border-border rounded-[8px] text-[12px] text-text-primary hover:border-border-focus disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          {busy === 'unload' ? (
            <Loader2 size={13} className="animate-spin" />
          ) : (
            <ZapOff size={13} />
          )}
          Offload
        </button>
      </div>

      <p className="text-[11px] text-text-tertiary mt-2">
        Offload frees GPU memory. The model reloads automatically on your next dictation (first one
        is slower).
      </p>
    </div>
  )
}
