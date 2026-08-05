import { useCallback, useEffect, useState } from 'react'
import { Cpu, Loader2, RefreshCw, Zap, ZapOff } from 'lucide-react'
import {
  localSttStatus,
  localSttLoad,
  localSttUnload,
  localSttSetDevice,
  type LocalSttStatus,
  type SttDeviceMode,
} from '../../lib/tauri'

const DEVICE_MODES: { value: SttDeviceMode; label: string; hint: string }[] = [
  { value: 'auto', label: 'Auto', hint: 'Use the GPU, fall back to the CPU if VRAM runs out.' },
  { value: 'gpu', label: 'GPU', hint: 'GPU only. Dictation fails if the GPU is out of memory.' },
  { value: 'cpu', label: 'CPU', hint: 'CPU only — slower, but leaves the GPU free for games.' },
]

/**
 * Model management for the local custom-whisper server. Lets the user see
 * whether the Whisper model is resident in VRAM, load/unload it on demand, and
 * choose which device transcription runs on.
 *
 * The device choice matters on small cards: a game can occupy enough VRAM that
 * there's no room for the transient encode workspace, so "Auto" reroutes to the
 * CPU rather than failing, and "CPU" pins it there deliberately.
 *
 * Only rendered for local base URLs (localhost / 127.0.0.1); remote OpenAI-
 * compatible endpoints don't expose these controls.
 */
export function LocalModelControl({ baseUrl }: { baseUrl: string }) {
  const [status, setStatus] = useState<LocalSttStatus | null>(null)
  const [busy, setBusy] = useState<null | 'load' | 'unload' | 'refresh' | 'device'>(null)
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

  const setMode = async (mode: SttDeviceMode) => {
    setBusy('device')
    setError(null)
    try {
      setStatus(await localSttSetDevice(baseUrl, mode))
      // The set-device response omits the health-only fields, so re-read to keep
      // the CPU-fallback readout accurate.
      setStatus(await localSttStatus(baseUrl))
    } catch {
      setError('Could not change device')
    } finally {
      setBusy(null)
    }
  }

  const loaded = status?.loaded ?? false
  const gpu = status?.gpu ?? null
  const mode = status?.device_mode ?? 'auto'
  const fallback = status?.cpu_fallback ?? null
  const onCpu = mode === 'cpu' || (fallback?.gpu_cooldown_seconds ?? 0) > 0

  return (
    <div className="mt-2 p-3 bg-bg-secondary border border-border rounded-[10px]">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Cpu size={15} className="text-text-secondary" />
          <span className="text-[13px] font-medium text-text-primary">Local model</span>
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
            onCpu ? 'bg-amber-500' : loaded ? 'bg-emerald-500' : 'bg-text-tertiary'
          }`}
        />
        <span className="text-text-secondary">
          {error
            ? error
            : !status
              ? 'Checking…'
              : mode === 'cpu'
                ? 'Running on CPU'
                : loaded
                  ? 'Loaded in VRAM'
                  : 'Offloaded (not in VRAM)'}
        </span>
        {gpu && (
          <span className="text-text-tertiary ml-auto">
            GPU {gpu.used_mib} / {gpu.total_mib} MiB
          </span>
        )}
      </div>

      <div
        role="radiogroup"
        aria-label="Transcription device"
        className="mt-3 flex gap-1 p-1 bg-bg-tertiary border border-border rounded-[8px]"
      >
        {DEVICE_MODES.map((m) => (
          <button
            key={m.value}
            type="button"
            role="radio"
            aria-checked={mode === m.value}
            title={m.hint}
            onClick={() => setMode(m.value)}
            disabled={busy !== null || mode === m.value}
            className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-[6px] text-[12px] transition-colors disabled:cursor-default ${
              mode === m.value
                ? 'bg-bg-secondary text-text-primary border border-border-focus'
                : 'text-text-secondary hover:text-text-primary hover:bg-bg-secondary/60 border border-transparent disabled:opacity-40'
            }`}
          >
            {busy === 'device' && mode === m.value && (
              <Loader2 size={12} className="animate-spin" />
            )}
            {m.label}
          </button>
        ))}
      </div>

      <p className="text-[11px] text-text-tertiary mt-2">
        {DEVICE_MODES.find((m) => m.value === mode)?.hint}
      </p>

      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={doLoad}
          disabled={busy !== null || loaded || mode === 'cpu'}
          className="flex-1 flex items-center justify-center gap-1.5 px-3 py-2 bg-bg-tertiary border border-border rounded-[8px] text-[12px] text-text-primary hover:border-border-focus disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          {busy === 'load' ? <Loader2 size={13} className="animate-spin" /> : <Zap size={13} />}
          Load
        </button>
        <button
          type="button"
          onClick={doUnload}
          disabled={busy !== null || !loaded || mode === 'cpu'}
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
        {mode === 'cpu'
          ? 'The GPU is free for other apps. Transcription runs at roughly 2x realtime instead of 10x.'
          : 'Offload frees GPU memory. The model reloads automatically on your next dictation (first one is slower).'}
        {fallback && fallback.gpu_cooldown_seconds > 0 && mode !== 'cpu' && (
          <span className="block mt-1 text-amber-500">
            GPU ran out of memory — using the CPU for the next{' '}
            {Math.ceil(fallback.gpu_cooldown_seconds)}s.
          </span>
        )}
      </p>
    </div>
  )
}
