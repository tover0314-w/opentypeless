import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Info, X } from 'lucide-react'
import { writeText } from '@tauri-apps/plugin-clipboard-manager'
import { safeAlertWord, useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import {
  abortAskDictation,
  abortRecording,
  recoverUnfinishedTake,
  removeDictionaryEntry,
} from '../../lib/tauri'
import { capsuleErrorKeyFromPayload } from '../../lib/capsuleError'
import { historyRetryErrorKey } from '../../lib/historyRetryError'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'
import { Keycaps } from '../shared/Keycaps'
import { BrandMark } from '../MainLayout/BrandMark'
import { useAppStore } from '../../stores/appStore'
import { emit } from '@tauri-apps/api/event'
import { invoke } from '@tauri-apps/api/core'
import { AskModeHint } from './AskModeHint'
import { TranslateTargetChip } from './TranslateTargetChip'
import { openCapsuleSettings } from '../../lib/capsuleNavigation'
import { textPreview } from '../../lib/textPreview'

export function CapsuleAlertBubble() {
  const { t } = useTranslation()
  const alert = useCapsuleAlertStore((s) => s.alert)
  const dismiss = useCapsuleAlertStore((s) => s.dismiss)
  const keys = useVoiceHotkeys()

  if (!alert || (alert.kind === 'shortcut_guide' && !keys.dictate?.trim())) return null

  return (
    <div
      role="status"
      aria-live="polite"
      data-capsule-alert={alert.kind}
      onPointerDown={(event) => event.stopPropagation()}
      onPointerUp={(event) => event.stopPropagation()}
      className="pointer-events-auto mb-2 max-w-[440px]"
    >
      {alert.kind === 'ask_anything' && <AskModeHint />}
      {alert.kind === 'scene_changed' && (
        <div className="capsule-alert max-w-full break-words px-3 py-2 text-[14px] leading-5">
          {alert.text
            ? t('scenes.activeScene', { name: textPreview(alert.text, 80) })
            : t('scenes.noManualScene')}
        </div>
      )}
      {alert.kind === 'better_microphone' && <BetterMicrophone name={alert.text ?? ''} />}
      {alert.kind === 'microphone_in_use' && (
        <div className="capsule-alert max-w-full break-words px-3 py-2 text-[14px] leading-5">
          {t('capsule.alerts.microphoneUsing')} {alert.text}
        </div>
      )}
      {alert.kind === 'use_voice' && <UseVoice />}
      {alert.kind === 'switch_translation_mode' && <SwitchTranslationMode />}
      {alert.kind === 'shortcut_guide' && <ShortcutGuide />}
      {alert.kind === 'press_to_finish' && <PressToFinish />}
      {alert.kind === 'previous_pending' && (
        <PreviousPending
          key={alert.id}
          alertId={alert.id}
          onClose={() => useCapsuleAlertStore.getState().dismissById(alert.id)}
        />
      )}
      {alert.kind === 'weak_network' && <WeakNetwork />}
      {alert.kind === 'copy_last_transcript' && (
        <CopyLastTranscript
          key={alert.id}
          alertId={alert.id}
          text={alert.text ?? ''}
          onClose={() => useCapsuleAlertStore.getState().dismissById(alert.id)}
        />
      )}
      {alert.kind === 'transcript_cancelled' && (
        <TranscriptCancelled alertId={alert.id} historyId={alert.entryId} />
      )}
      {alert.kind === 'ready' && <ReadyAlert onClose={() => dismiss('ready')} />}
      {alert.kind === 'last_minute' && <LastMinute />}
      {alert.kind === 'input_duration_exceeded' && (
        <InputDurationExceeded duration={alert.text ?? ''} seconds={alert.durationSeconds} />
      )}
      {alert.kind === 'selected_text_tip' && <SelectedTextTip />}
      {alert.kind === 'translating_to' && <TranslateTargetChip />}
      {alert.kind === 'retry_copied' && <SimpleAlert textKey="capsule.alerts.retryCopied" />}
      {alert.kind === 'update_available' && (
        <UpdateAvailable onClose={() => dismiss('update_available')} />
      )}
      {alert.kind === 'dictionary_auto_added' && (
        <DictionaryAutoAdded
          key={alert.id}
          alertId={alert.id}
          term={alert.text ?? ''}
          entryId={alert.entryId}
        />
      )}
      {alert.kind === 'dictionary_word_removed' && (
        <div className="capsule-alert px-3 py-2 text-[14px] leading-5">
          {t('dictionary.deleteSuccess', { term: alert.text ?? '' })}
        </div>
      )}
    </div>
  )
}

function UseVoice() {
  const { t } = useTranslation()
  const { dictate } = useVoiceHotkeys()
  if (!dictate) return null
  return (
    <div className="capsule-alert flex max-w-full flex-wrap items-center justify-center gap-1.5 px-3 py-2 text-[14px] leading-4">
      <BrandMark size={18} className="shrink-0 text-white" />
      <span>{t('capsule.alerts.useVoicePrefix')}</span>
      <Keycaps hotkey={dictate} />
      <span>{t('capsule.alerts.useVoiceSuffix')}</span>
    </div>
  )
}

function BetterMicrophone({ name }: { name: string }) {
  const { t } = useTranslation()
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <Info size={16} className="shrink-0 text-[#658aff]" />
        {t('capsule.alerts.betterMicTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('capsule.alerts.betterMicDescription', { device: name })}
      </p>
      <button
        type="button"
        onClick={() =>
          void openCapsuleSettings(
            `#/settings?pane=general&dialog=microphone&open=${Date.now()}`,
          ).catch((error) => console.error('Failed to open microphone settings:', error))
        }
        className="min-h-8 cursor-pointer rounded-full border-none bg-white/15 px-3 text-[12px] text-white hover:bg-white/20"
      >
        {t('settings.microphone')}
      </button>
    </div>
  )
}

function SwitchTranslationMode() {
  const { t } = useTranslation()
  const { translate } = useVoiceHotkeys()
  if (!translate) return null
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <Info size={16} className="shrink-0 text-[#658aff]" />
        {t('capsule.alerts.switchTranslationTitle')}
      </div>
      <p className="m-0 flex flex-wrap items-center justify-center gap-1.5 text-[13px] leading-5 text-[#c9c9c9]">
        <span>{t('capsule.alerts.switchTranslationPrefix')}</span>
        <Keycaps hotkey={translate} />
        <span>{t('capsule.alerts.switchTranslationSuffix')}</span>
      </p>
    </div>
  )
}

/** Typeless "Transcript cancelled": Undo re-runs the stored take into the focused app. */
function TranscriptCancelled({ alertId, historyId }: { alertId: number; historyId?: number }) {
  const { t } = useTranslation()
  const idle = useAppStore((s) => s.pipelineState === 'idle')
  const undo = () => {
    if (historyId === undefined || useCapsuleAlertStore.getState().alert?.id !== alertId) return
    const app = useAppStore.getState()
    if (app.pipelineState !== 'idle') return
    app.resetRecording()
    app.setPipelineError(null)
    useCapsuleAlertStore.getState().dismissById(alertId)
    let superseded = false
    let listening = true
    const releaseSubscription = () => {
      if (!listening) return
      listening = false
      unsubscribe()
    }
    const unsubscribe = useAppStore.subscribe((state, previous) => {
      if (
        state.pipelineState !== previous.pipelineState &&
        (state.pipelineState === 'preparing' ||
          state.pipelineState === 'recording' ||
          state.pipelineState === 'ask_recording')
      ) {
        superseded = true
        releaseSubscription()
      }
    })
    void recoverUnfinishedTake(historyId)
      .catch((error) => {
        console.error('Failed to restore cancelled take:', error)
        const state = useAppStore.getState()
        if (superseded || state.pipelineState !== 'idle' || state.pipelineError !== null) return
        state.setPipelineErrorKey(capsuleErrorKeyFromPayload(String(error)))
        const messageKey = historyRetryErrorKey(error)
        state.setPipelineError(t(messageKey), messageKey)
      })
      .finally(releaseSubscription)
  }
  return (
    <div className="capsule-alert flex max-w-[360px] items-center gap-2 p-2">
      <span className="flex min-w-0 flex-1 items-center justify-center gap-2 px-1 text-[14px] font-medium leading-5 text-white">
        <Info size={16} className="shrink-0 text-[#658aff]" />
        <span className="min-w-0 break-words">{t('capsule.alerts.cancelled')}</span>
      </span>
      {historyId !== undefined && (
        <button
          type="button"
          onClick={undo}
          disabled={!idle}
          className="min-h-8 shrink-0 whitespace-nowrap rounded-full border-none bg-[rgba(119,119,119,0.4)] px-3 text-[12px] text-white cursor-pointer hover:bg-[rgba(119,119,119,0.5)] active:bg-[rgba(119,119,119,0.3)] disabled:opacity-40"
        >
          {t('capsule.alerts.undo')}
        </button>
      )}
    </div>
  )
}

/** Typeless learned a word from the user's edit; Undo removes it again. */
function DictionaryAutoAdded({
  alertId,
  term,
  entryId,
}: {
  alertId: number
  term: string
  entryId?: number
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const undoPending = useRef(false)
  const undo = async () => {
    if (
      entryId === undefined ||
      undoPending.current ||
      useCapsuleAlertStore.getState().alert?.id !== alertId
    )
      return
    undoPending.current = true
    setBusy(true)
    try {
      await removeDictionaryEntry(entryId)
      await emit('dictionary:changed').catch(() => {})
      const alerts = useCapsuleAlertStore.getState()
      if (alerts.alert?.id !== alertId) return
      alerts.dismissById(alertId)
      alerts.show('dictionary_word_removed', term)
    } catch (error) {
      console.error('Failed to undo auto-added word:', error)
    } finally {
      undoPending.current = false
      setBusy(false)
    }
  }
  return (
    <div className="capsule-alert flex max-w-[360px] items-center gap-2 p-2">
      <span className="min-w-0 flex-1 break-words px-1 text-center text-[14px] font-medium leading-5 text-white">
        {t('capsule.alerts.dictionaryAutoAdded', { word: safeAlertWord(term) })}
      </span>
      {entryId !== undefined && (
        <button
          type="button"
          onClick={undo}
          disabled={busy}
          className="min-h-8 shrink-0 whitespace-nowrap rounded-full border-none bg-[rgba(119,119,119,0.4)] px-3 text-[12px] text-white cursor-pointer hover:bg-[rgba(119,119,119,0.5)] active:bg-[rgba(119,119,119,0.3)] disabled:opacity-40"
        >
          {t('capsule.alerts.undo')}
        </button>
      )}
    </div>
  )
}

/** A background download finished; the main window owns the package, so ask it to install. */
function UpdateAvailable({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const restart = () => {
    invoke('request_update_install').catch((error) =>
      console.error('Failed to request update install:', error),
    )
    onClose()
  }
  return (
    <div className="capsule-alert relative flex min-w-[320px] max-w-[360px] flex-col items-center gap-2 p-4 text-center">
      <button
        type="button"
        aria-label={t('common.close')}
        onClick={onClose}
        className="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-full border-none bg-transparent text-white/60 cursor-pointer hover:bg-white/10 hover:text-white"
      >
        <X size={14} />
      </button>
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <Info size={16} className="shrink-0 text-[#658aff]" />
        {t('updates.availableTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('updates.check.downloadedText')}
      </p>
      <button
        type="button"
        onClick={restart}
        className="mt-1 min-h-8 rounded-full border-none bg-[rgba(119,119,119,0.4)] px-3 text-[12px] text-white cursor-pointer hover:bg-[rgba(119,119,119,0.5)]"
      >
        {t('updates.check.restart')}
      </button>
    </div>
  )
}

/** First run after onboarding: where the bar lives and which key starts dictation. */
function ReadyAlert({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  return (
    <div className="capsule-alert flex min-w-[360px] max-w-[440px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[15px] font-semibold">
        <BrandMark size={18} className="text-white" />
        {t('capsule.alerts.readyTitle', { app: t('app.name') })}
      </div>
      <p className="m-0 flex flex-wrap items-center justify-center gap-1 text-[13px] leading-5 text-[#c9c9c9]">
        {t('capsule.alerts.readyPrefix')}
        {keys.dictate && <Keycaps hotkey={keys.dictate} />}
        {t('capsule.alerts.readySuffix')}
      </p>
      <button
        type="button"
        onClick={onClose}
        className="mt-1 h-7 rounded-full border-none bg-accent px-3 text-[12px] font-medium text-white cursor-pointer hover:bg-accent-hover"
      >
        {t('capsule.alerts.gotIt')}
      </button>
    </div>
  )
}

/** Recording is about to hit its provider limit; the bar also shows an mm:ss countdown. */
function LastMinute() {
  const { t } = useTranslation()
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-1 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <AlertTriangle size={16} className="shrink-0 text-[#ff9e6c]" />
        {t('capsule.alerts.lastMinuteTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('capsule.alerts.lastMinuteDesc')}
      </p>
    </div>
  )
}

/** Typeless "Transcription session ended": the take hit its limit and was submitted as is. */
function InputDurationExceeded({ duration, seconds }: { duration: string; seconds?: number }) {
  const { t } = useTranslation()
  const localizedDuration =
    seconds === undefined
      ? duration
      : seconds === 60
        ? t('recordingLimits.durationMinute', { count: 1 })
        : seconds > 0 && seconds % 60 === 0
          ? t('recordingLimits.durationMinutes', { count: seconds / 60 })
          : t('recordingLimits.durationSeconds', { count: seconds })
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <AlertTriangle size={16} className="shrink-0 text-[#ff9e6c]" />
        {t('capsule.alerts.sessionEndedTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('capsule.alerts.sessionEndedDesc', { duration: localizedDuration })}
      </p>
    </div>
  )
}

/** Dictating over a selection: point at the Ask key, which answers about the selected text. */
function SelectedTextTip() {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  if (!keys.ask) return null
  return (
    <div className="capsule-alert flex items-center gap-1.5 px-3 py-2 text-[14px] leading-4">
      <BrandMark size={18} className="shrink-0 text-white" />
      <span>{t('capsule.alerts.selectedTipPrefix')}</span>
      <Keycaps hotkey={keys.ask} />
      <span>{t('capsule.alerts.selectedTipSuffix')}</span>
    </div>
  )
}

/** Shown while a voice key is held past the long-press threshold; hidden on release. */
function ShortcutGuide() {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  const rows = [
    { id: 'dictate', label: t('capsule.alerts.guideDictate'), hotkey: keys.dictate },
    { id: 'translate', label: t('capsule.alerts.guideTranslate'), hotkey: keys.translate },
    { id: 'ask', label: t('capsule.alerts.guideAsk'), hotkey: keys.ask },
  ].filter((row): row is { id: string; label: string; hotkey: string } => Boolean(row.hotkey))

  return (
    <div className="capsule-alert min-w-[360px] max-w-[440px] overflow-hidden p-2">
      <div className="flex items-center justify-center gap-2 px-2 pt-2">
        <BrandMark size={16} className="text-white" />
        <span className="text-[14px] font-medium leading-4 text-[#f2f1f0]">
          {t('capsule.alerts.guideTitle', { app: t('app.name') })}
        </span>
      </div>
      <div className="flex flex-col items-center gap-2 p-2">
        <p className="m-0 flex flex-wrap items-center justify-center text-center text-[14px] leading-[22px] text-[rgba(242,241,240,0.7)]">
          {t('capsule.alerts.guidePrefix')}
          {keys.dictate && (
            <span className="mx-1 inline-flex">
              <Keycaps hotkey={keys.dictate} />
            </span>
          )}
          {t('capsule.alerts.guideSuffix')}
        </p>
        <div className="capsule-shortcut-table w-full" role="table">
          {rows.map((row) => (
            <div key={row.id} role="row" className="flex" data-guide-row={row.id}>
              <div
                role="rowheader"
                className="flex w-[138px] shrink-0 items-center px-3 py-2 text-[14px] font-medium text-[#c9c9c9]"
              >
                {row.label}
              </div>
              <div
                role="cell"
                className="flex flex-1 flex-wrap items-center gap-1 border-l border-[rgba(242,241,240,0.12)] px-3 py-2"
              >
                <Keycaps hotkey={row.hotkey} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/** Nudge after finishing with the ✓ button: the dictation key finishes faster. */
function PressToFinish() {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  return (
    <div className="capsule-alert flex items-center gap-1.5 px-3 py-2 text-[14px] leading-4">
      <BrandMark size={18} className="shrink-0 text-white" />
      <span>{t('capsule.alerts.pressToFinishPrefix')}</span>
      {keys.dictate && <Keycaps hotkey={keys.dictate} />}
      <span>{t('capsule.alerts.pressToFinishSuffix')}</span>
    </div>
  )
}

/** The voice key was pressed while the previous take is still being processed. */
function PreviousPending({ alertId, onClose }: { alertId: number; onClose: () => void }) {
  const { t } = useTranslation()
  const pending = useRef(false)
  const [busy, setBusy] = useState(false)
  const discard = async () => {
    if (pending.current || useCapsuleAlertStore.getState().alert?.id !== alertId) return
    const state = useAppStore.getState().pipelineState
    if (
      state !== 'transcribing' &&
      state !== 'polishing' &&
      state !== 'outputting' &&
      state !== 'ask_thinking'
    ) {
      onClose()
      return
    }
    pending.current = true
    setBusy(true)
    try {
      if (state === 'ask_thinking') await abortAskDictation()
      else await abortRecording()
      onClose()
    } catch (error) {
      console.error('Failed to discard pending take:', error)
    } finally {
      pending.current = false
      setBusy(false)
    }
  }
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <AlertTriangle size={16} className="shrink-0 text-[#ff9e6c]" />
        {t('capsule.alerts.previousPendingTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('capsule.alerts.previousPendingDesc')}
      </p>
      <button
        type="button"
        disabled={busy}
        onClick={() => void discard()}
        className="mt-1 min-h-8 rounded-full border-none bg-[rgba(119,119,119,0.4)] px-3 text-[12px] text-white cursor-pointer hover:bg-[rgba(119,119,119,0.5)]"
      >
        {t('capsule.cancel')}
      </button>
    </div>
  )
}

function WeakNetwork() {
  const { t } = useTranslation()
  return (
    <div className="capsule-alert px-3 py-2 text-[12px] leading-[18px]">
      {t('capsule.alerts.weakNetwork')}
    </div>
  )
}

function SimpleAlert({ textKey }: { textKey: string }) {
  const { t } = useTranslation()
  return <div className="capsule-alert px-3 py-2 text-[13px]">{t(textKey)}</div>
}

function CopyLastTranscript({
  alertId,
  text,
  onClose,
}: {
  alertId: number
  text: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const copying = useRef(false)
  const [busy, setBusy] = useState(false)
  const preview = textPreview(text, 32)

  const copy = async () => {
    if (copying.current || useCapsuleAlertStore.getState().alert?.id !== alertId) return
    copying.current = true
    setBusy(true)
    try {
      await writeText(text)
      setCopied(true)
    } catch (error) {
      console.error('Failed to copy last transcript:', error)
    } finally {
      copying.current = false
      setBusy(false)
    }
  }

  return (
    <div className="capsule-alert relative min-w-[320px] px-4 pb-3 pt-3 text-center">
      <button
        type="button"
        aria-label={t('common.close')}
        onClick={onClose}
        className="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-full border-none bg-transparent text-white/60 cursor-pointer hover:bg-white/10 hover:text-white"
      >
        <X size={14} />
      </button>
      <div className="flex items-center justify-center gap-2 text-[14px] font-semibold">
        <Info size={16} className="text-accent" />
        {t('capsule.alerts.copyLastTitle')}
      </div>
      <p className="mt-1 text-[13px] text-white/80">“{preview}”</p>
      <button
        type="button"
        disabled={busy}
        onClick={() => void copy()}
        className="mt-2 rounded-[8px] border-none bg-white/12 px-3 py-1.5 text-[13px] font-medium text-white cursor-pointer hover:bg-white/20"
      >
        {copied ? t('capsule.alerts.copied') : t('capsule.alerts.copy')}
      </button>
    </div>
  )
}
