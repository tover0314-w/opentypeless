import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Pencil } from 'lucide-react'
import { hotkeyFromBinding, useAppStore } from '../../stores/appStore'
import { useVoiceShortcuts, type VoiceShortcutRole } from '../../hooks/useVoiceShortcuts'
import { ShortcutRecorderBox } from '../Settings/ShortcutKeycapInput'
import { Keycaps } from '../shared/Keycaps'
import { queueShortcutCaptureSave } from '../../hooks/useHotkeyCapture'
import { enqueueConfigSave } from '../Settings/shared/useAutoSaveConfig'
import { toast } from '../toast-service'
import { valuesEqual } from '../../lib/configValuesEqual'

const SHORTCUT_ROWS: Array<{ key: string; role: VoiceShortcutRole; accent: string }> = [
  { key: 'dictate', role: 'dictation', accent: '' },
  { key: 'translate', role: 'translate', accent: 'bg-accent/10' },
  { key: 'ask', role: 'ask', accent: 'bg-[rgba(120,214,255,0.14)]' },
]

export function ShortcutsCard() {
  const { t } = useTranslation()
  const { bindings, otherBindings, specialOptions, setPrimaryBinding } = useVoiceShortcuts()
  const [editing, setEditing] = useState<VoiceShortcutRole | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const editAttempt = useRef(0)

  return (
    <section
      aria-label={t('home.shortcutHint')}
      className="home-shortcuts surface-card divide-y divide-border-thin px-5"
    >
      {SHORTCUT_ROWS.map((row) => {
        const current = bindings[row.role][0]
        const hotkey = current ? hotkeyFromBinding(current) : null
        return (
          <div key={row.key} className="flex min-h-[108px] items-center justify-between gap-6 py-6">
            <div>
              <div className="text-[16px] font-semibold text-text-primary">
                {t(`home.modes.${row.key}.title`)}
              </div>
              <div className="mt-0.5 text-[14px] text-text-tertiary">
                {t(`home.modes.${row.key}.description`)}
              </div>
            </div>
            {editing === row.role ? (
              <div className="w-[260px]" data-shortcut-editor={row.role}>
                <ShortcutRecorderBox
                  binding={current ?? null}
                  presets={specialOptions[row.role]}
                  otherBindings={[...otherBindings(row.role), ...bindings[row.role].slice(1)]}
                  saveError={saveError}
                  onCancel={() => {
                    editAttempt.current += 1
                    setSaveError(null)
                    setEditing(null)
                  }}
                  onSave={(binding) => {
                    const attempt = ++editAttempt.current
                    setSaveError(null)
                    const previousConfig = useAppStore.getState().config
                    setPrimaryBinding(row.role, binding)
                    const draftConfig = useAppStore.getState().config
                    const bindingKey = row.role === 'dictation' ? 'dictation' : row.role
                    const listKey =
                      row.role === 'dictation'
                        ? 'dictationBindings'
                        : row.role === 'ask'
                          ? 'askBindings'
                          : 'translateBindings'
                    const bindingPersisted = () => {
                      const persisted = useAppStore.getState().savedConfig?.hotkeys
                      return (
                        persisted !== undefined &&
                        valuesEqual(persisted[bindingKey], draftConfig.hotkeys[bindingKey]) &&
                        valuesEqual(persisted[listKey], draftConfig.hotkeys[listKey])
                      )
                    }
                    const restoreDraft = () => {
                      const state = useAppStore.getState()
                      if (
                        valuesEqual(
                          state.config.hotkeys[bindingKey],
                          draftConfig.hotkeys[bindingKey],
                        ) &&
                        valuesEqual(state.config.hotkeys[listKey], draftConfig.hotkeys[listKey])
                      ) {
                        state.updateConfig({
                          hotkeys: {
                            ...state.config.hotkeys,
                            [bindingKey]: previousConfig.hotkeys[bindingKey],
                            [listKey]: previousConfig.hotkeys[listKey],
                          },
                        })
                      }
                    }
                    const reportFailure = () => {
                      if (bindingPersisted()) return
                      restoreDraft()
                      if (editAttempt.current !== attempt) return
                      setSaveError(t('settings.hotkeyRegistrationFailed'))
                      setEditing(row.role)
                    }
                    void queueShortcutCaptureSave(enqueueConfigSave)
                      .then((saved) => {
                        if (!saved) reportFailure()
                      })
                      .catch(() => {
                        reportFailure()
                        toast.error(t('settings.hotkeyRegistrationFailed'))
                      })
                    setEditing(null)
                  }}
                />
              </div>
            ) : (
              <button
                type="button"
                aria-label={`${t('home.editShortcut')}: ${t(`home.modes.${row.key}.title`)}`}
                title={t('home.editShortcut')}
                onClick={() => {
                  editAttempt.current += 1
                  setSaveError(null)
                  setEditing(row.role)
                }}
                className={`group flex cursor-pointer items-center rounded-[10px] border border-transparent bg-transparent p-1 transition-colors hover:border-border hover:bg-fill-hover ${row.accent}`}
              >
                {hotkey ? (
                  <Keycaps hotkey={hotkey} />
                ) : (
                  <span className="px-2 text-[13px] text-text-tertiary">
                    {t('home.shortcutOff')}
                  </span>
                )}
                <Pencil
                  size={13}
                  className="ml-0 w-0 text-text-tertiary opacity-0 transition-all group-hover:ml-2 group-hover:mr-1 group-hover:w-[13px] group-hover:opacity-100"
                />
              </button>
            )}
          </div>
        )
      })}
    </section>
  )
}
